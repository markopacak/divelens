package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionMap
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.V1Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewStoreTest {

    /** Logs calls; a photo in [gates] blocks until its gate is completed; one in [failing] throws. */
    private class FakePreparer : PhotoPreparer<String> {
        val log = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun prepare(source: String): Prepared<String> {
            log += "prepare $source"
            run(source)
            val renderer = ThumbnailRenderer { s -> log += "render $source $s"; "thumb $source at $s" }
            return Prepared(correction(), "thumb $source", rotation = 90, renderer = renderer)
        }

        override suspend fun estimate(source: String): Correction {
            log += "estimate $source"
            run(source)
            return correction()
        }

        private suspend fun run(source: String) {
            try {
                gates[source]?.await()
            } catch (e: CancellationException) {
                cancelled += source
                throw e
            }
            if (source in failing) throw IllegalStateException("cannot decode $source")
        }

        private fun correction() =
            Correction(CorrectionParams.IDENTITY.withStrength(V1Model.DEFAULT_STRENGTH), CorrectionMap(1, 1, FloatArray(4), FloatArray(4)), 400, 300)
    }

    private val preparer = FakePreparer()

    private fun TestScope.store() = PhotoPreviewStore(preparer, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun photos(vararg n: Int) = n.map { "p$it" }

    @Test fun preparesInSelectionOrder() = runTest {
        val store = store()
        store.setPhotos(photos(3, 1, 2))
        runCurrent()
        assertEquals(listOf("prepare p3", "prepare p1", "prepare p2"), preparer.log)
        val ready = store.entries.value["p1"] as PreviewEntry.Ready
        assertEquals("thumb p1", ready.thumbnail)
        assertEquals(90, ready.rotation)
        assertEquals(400 to 300, ready.width to ready.height)
    }

    @Test fun priorityJumpsTheQueue() = runTest {
        val store = store()
        preparer.gates["p1"] = CompletableDeferred()
        store.setPhotos(photos(1, 2, 3, 4, 5))
        runCurrent()
        store.prioritize(photos(4))
        preparer.gates.getValue("p1").complete(Unit)
        runCurrent()
        assertEquals(photos(1, 4, 2, 3, 5).map { "prepare $it" }, preparer.log)
    }

    @Test fun correctionRequestJumpsTheQueue() = runTest {
        val store = store()
        preparer.gates["p1"] = CompletableDeferred()
        store.setPhotos(photos(1, 2, 3))
        runCurrent()
        val request = async { store.correction("p3") }
        runCurrent()
        preparer.gates.getValue("p1").complete(Unit)
        runCurrent()
        assertEquals(400, request.await().width)
        assertEquals(photos(1, 3, 2).map { "prepare $it" }, preparer.log)
    }

    @Test fun removeCancelsAndFrees() = runTest {
        val store = store()
        preparer.gates["p1"] = CompletableDeferred()
        store.setPhotos(photos(1, 2))
        runCurrent()
        store.setPhotos(photos(2))
        runCurrent()
        assertEquals(listOf("p1"), preparer.cancelled)
        assertEquals(setOf("p2"), store.entries.value.keys)

        // A cached correction is freed on removal: after adding the photo again, asking for it waits for new work.
        store.correction("p2")
        preparer.gates["p2"] = CompletableDeferred()
        store.setPhotos(emptyList())
        store.setPhotos(photos(2))
        val again = async { store.correction("p2") }
        runCurrent()
        assertTrue(again.isActive)
        preparer.gates.getValue("p2").complete(Unit)
        runCurrent()
        assertEquals(400, again.await().width)
        assertEquals(listOf("prepare p1", "prepare p2", "estimate p2", "prepare p2"), preparer.log)
    }

    @Test fun cacheEvictsTheLeastRecentlyUsed() = runTest {
        val store = store()
        store.setPhotos(photos(1, 2, 3, 4))
        runCurrent() // all prepared, no corrections kept: nothing asked for them
        preparer.log.clear()

        listOf("p1", "p2", "p3", "p1", "p4").forEach { store.correction(it) } // p1 used again, so p2 is the oldest
        assertEquals(photos(1, 2, 3, 4).map { "estimate $it" }, preparer.log)
        preparer.log.clear()

        store.correction("p1"); store.correction("p3"); store.correction("p4")
        assertEquals(emptyList<String>(), preparer.log)
        store.correction("p2")
        assertEquals(listOf("estimate p2"), preparer.log)
    }

    @Test fun failureIsKeptAndDoesNotStopTheQueue() = runTest {
        val store = store()
        preparer.failing += "p2"
        store.setPhotos(photos(1, 2, 3))
        runCurrent()
        val failed = store.entries.value["p2"]
        assertTrue(failed is PreviewEntry.Failed)
        assertTrue(store.entries.value["p1"] is PreviewEntry.Ready)
        assertTrue(store.entries.value["p3"] is PreviewEntry.Ready)
        assertEquals(1, preparer.log.count { it == "prepare p2" })
    }

    @Test fun failedCorrectionRequestThrowsAndTheQueueGoesOn() = runTest {
        val store = store()
        preparer.failing += "p1"
        store.setPhotos(photos(1, 2))
        val result = runCatching { store.correction("p1") }
        runCurrent()
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(store.entries.value["p2"] is PreviewEntry.Ready)
    }

    @Test fun requestForAPhotoNotInTheSetIsAnswered() = runTest {
        val store = store()
        val request = async { store.correction("p9") }
        runCurrent()
        assertEquals(400, request.await().width)
        assertTrue(store.entries.value.isEmpty())
    }

    private fun PhotoPreviewStore<String>.thumbnail(source: String) = (entries.value[source] as PreviewEntry.Ready).thumbnail

    @Test fun strengthIsTheDefaultUntilSet() = runTest {
        val store = store()
        store.setPhotos(photos(1))
        runCurrent()
        assertEquals(V1Model.DEFAULT_STRENGTH, store.strengthOf("p1"))
        assertEquals(V1Model.DEFAULT_STRENGTH, store.correction("p1").params.strength)
        assertEquals(emptyMap<String, Float>(), store.strengths.value)
        assertEquals("thumb p1", store.thumbnail("p1"))
    }

    @Test fun strengthIsRoundedClampedAndCarriedByTheCorrection() = runTest {
        val store = store()
        store.setPhotos(photos(1, 2))
        runCurrent()
        store.correction("p1")
        preparer.log.clear()

        store.setStrength("p1", 0.404f)
        assertEquals(mapOf("p1" to 0.4f), store.strengths.value)
        assertEquals(0.4f, store.correction("p1").params.strength)
        assertEquals(V1Model.DEFAULT_STRENGTH, store.correction("p2").params.strength)
        store.setStrength("p2", 1.5f); assertEquals(1f, store.strengthOf("p2"))
        store.setStrength("p2", -0.2f); assertEquals(0f, store.strengthOf("p2"))
        store.setStrength("p2", V1Model.DEFAULT_STRENGTH) // back to the default: no entry
        assertEquals(setOf("p1"), store.strengths.value.keys)
        runCurrent()
        // No new estimate for the cached p1; p2 was estimated once when first asked for.
        assertEquals(listOf("estimate p2"), preparer.log.filter { it.startsWith("estimate") })
    }

    @Test fun changedStrengthReRendersTheThumbnailOnly() = runTest {
        val store = store()
        store.setPhotos(photos(1, 2, 3))
        runCurrent()
        preparer.log.clear()
        store.setStrength("p2", 0.3f)
        runCurrent()
        assertEquals(listOf("render p2 0.3"), preparer.log)
        assertEquals("thumb p2 at 0.3", store.thumbnail("p2"))
        assertEquals("thumb p1", store.thumbnail("p1"))
    }

    @Test fun applyToAllSetsAndReRendersEveryPhoto() = runTest {
        val store = store()
        store.setPhotos(photos(1, 2, 3))
        runCurrent()
        store.setStrength("p1", 0.3f)
        runCurrent()
        preparer.log.clear()
        store.setAllStrengths(0.6f)
        assertEquals(photos(1, 2, 3).associateWith { 0.6f }, store.strengths.value)
        runCurrent()
        assertEquals(photos(1, 2, 3).map { "render $it 0.6" }, preparer.log)
        assertTrue(preparer.log.none { it.startsWith("estimate") })
        store.setAllStrengths(V1Model.DEFAULT_STRENGTH)
        assertEquals(emptyMap<String, Float>(), store.strengths.value)
    }

    @Test fun strengthSetBeforePreparingIsRenderedOncePrepared() = runTest {
        val store = store()
        preparer.gates["p1"] = CompletableDeferred()
        store.setPhotos(photos(1, 2))
        runCurrent()
        store.setAllStrengths(0.5f)
        preparer.gates.getValue("p1").complete(Unit)
        runCurrent()
        // The re-render of p1 goes before preparing p2.
        assertEquals(listOf("prepare p1", "render p1 0.5", "prepare p2", "render p2 0.5"), preparer.log)
        assertEquals("thumb p2 at 0.5", store.thumbnail("p2"))
    }

    @Test fun removedPhotoComesBackAtTheDefault() = runTest {
        val store = store()
        store.setPhotos(photos(1, 2))
        runCurrent()
        store.setStrength("p1", 0.2f)
        store.setPhotos(photos(2))
        assertEquals(emptyMap<String, Float>(), store.strengths.value)
        store.setPhotos(photos(1, 2))
        runCurrent()
        assertEquals(V1Model.DEFAULT_STRENGTH, store.strengthOf("p1"))
        assertEquals("thumb p1", store.thumbnail("p1"))
    }
}
