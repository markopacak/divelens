package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionMap
import com.markopacak.divelens.analysis.CorrectionParams
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
            return Prepared(correction(), "thumb $source", rotation = 90)
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

        private fun correction() = Correction(CorrectionParams.IDENTITY, CorrectionMap(1, 1, FloatArray(4), FloatArray(4)), 400, 300)
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
}
