package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionMap
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.V1Model
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeline analysis in [PhotoPreviewStore] (add-video-processing D6, task 5.2). */
class PreviewStoreTimelineTest {
    private val log = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val preparer = object : PhotoPreparer<String> {
        override suspend fun prepare(source: String): Prepared<String> {
            log += "prepare $source"
            gates[source]?.await()
            val renderer = ThumbnailRenderer { s -> log += "render $source"; "thumb $source at $s" }
            return Prepared(correction(), "thumb $source", 0, renderer)
        }

        override suspend fun estimate(source: String): Correction = correction().also { log += "estimate $source" }

        private fun correction() =
            Correction(CorrectionParams.IDENTITY.withStrength(V1Model.DEFAULT_STRENGTH), CorrectionMap(1, 1, FloatArray(4), FloatArray(4)), 400, 300)
    }

    /** Samples the exact target times; the green gain encodes the time in seconds. Logs "sample v1 0-9" (seconds). */
    private val sampler = TimelineSampler { source, targets ->
        log += "sample $source ${targets.first() / 1_000_000}-${targets.last() / 1_000_000}"
        gates["sample $source"]?.await()
        targets.map { t -> t to params(t / 1_000_000f) }
    }

    private fun params(gain: Float) = CorrectionParams(floatArrayOf(1f, 0f, 0f, 0f, 0f, gain, 0f, 0f, 0f, 0f, 1f, 0f), 0.78f)

    private fun TestScope.store() = PhotoPreviewStore(preparer, backgroundScope, StandardTestDispatcher(testScheduler), sampler = sampler)

    private fun video(n: Int, seconds: Int) = StoreItem("v$n", seconds * 1_000_000L)
    private fun photo(n: Int) = StoreItem("p$n")

    @Test fun itemsArePreparedBeforeVideosAreAnalysedInChunksOfTen() = runTest {
        val store = store()
        store.setItems(listOf(photo(1), video(1, 25), photo(2)))
        runCurrent()
        // 26 planned samples (0..24 s and the end at 25 s): the first alone, then chunks of 10.
        assertEquals(listOf("prepare p1", "prepare v1", "prepare p2", "sample v1 0-0", "sample v1 1-10", "sample v1 11-20", "sample v1 21-25"), log)
        val t = store.timeline("v1")!!.value
        assertEquals(26, t.sampleCount)
        assertTrue(t.isComplete)
        assertNull(store.timeline("p1"))
    }

    @Test fun thumbnailRendersInterleaveWithChunks() = runTest {
        val store = store()
        store.setItems(listOf(photo(1), video(1, 25)))
        gates["sample v1"] = CompletableDeferred()
        runCurrent()
        assertEquals("sample v1 0-0", log.last())
        store.setStrength("p1", 0.4f)
        gates.getValue("sample v1").complete(Unit)
        runCurrent()
        assertEquals(listOf("prepare p1", "prepare v1", "sample v1 0-0", "render p1", "sample v1 1-10", "sample v1 11-20", "sample v1 21-25"), log)
    }

    @Test fun anExportedVideoJumpsAheadOfOtherVideosAndPreparation() = runTest {
        val store = store()
        gates["prepare p1"] = CompletableDeferred()
        gates["p1"] = CompletableDeferred()
        store.setItems(listOf(photo(1), video(1, 15), video(2, 15), photo(2)))
        runCurrent()
        val export = async { store.completeTimeline("v2") }
        runCurrent()
        gates.getValue("p1").complete(Unit)
        runCurrent()
        assertEquals(
            listOf("prepare p1", "sample v2 0-0", "sample v2 1-10", "sample v2 11-15", "prepare v1", "prepare v2", "prepare p2", "sample v1 0-0", "sample v1 1-10", "sample v1 11-15"),
            log,
        )
        val timeline = export.await()
        assertTrue(timeline.isComplete)
        assertEquals(16, timeline.sampleCount) // 0..14 s and the end
        assertEquals(12.5, timeline.matrixAt(12_500_000)!![5], 1.0)
    }

    @Test fun completeTimelineWaitsForTheLastSample() = runTest {
        val store = store()
        store.setItems(listOf(video(1, 25)))
        gates["sample v1"] = CompletableDeferred()
        runCurrent()
        val complete = async { store.completeTimeline("v1") }
        runCurrent()
        assertFalse(complete.isCompleted)
        assertEquals(0, store.timeline("v1")!!.value.sampleCount)
        gates.getValue("sample v1").complete(Unit)
        runCurrent()
        assertEquals(26, complete.await().sampleCount)
        // Asked again once complete, it answers at once.
        assertEquals(26, store.completeTimeline("v1").sampleCount)
    }

    @Test fun theViewedVideoIsAnalysedFromItsPlaybackPosition() = runTest {
        val store = store()
        gates["v2"] = CompletableDeferred()
        store.setItems(listOf(video(1, 40), video(2, 5)))
        runCurrent()
        store.viewVideo("v1", 22_300_000)
        gates.getValue("v2").complete(Unit)
        runCurrent()
        // From 23 s on, then the start of the clip, ahead of preparing v2; v1 is a viewed video, so it goes first.
        assertEquals("prepare v1", log[0])
        assertEquals("prepare v2", log[1])
        assertEquals(listOf("sample v1 23-23", "sample v1 24-33", "sample v1 34-40", "sample v1 0-9", "sample v1 10-19", "sample v1 20-22"), log.filter { it.startsWith("sample v1") })
    }

    @Test fun aSeekMovesTheAnalysis() = runTest {
        val store = store()
        store.setItems(listOf(video(1, 60)))
        gates["sample v1"] = CompletableDeferred()
        store.viewVideo("v1", 0)
        runCurrent()
        assertEquals("sample v1 0-0", log.last())
        store.viewVideo("v1", 45_000_000)
        gates.getValue("sample v1").complete(Unit)
        runCurrent()
        assertEquals(listOf("sample v1 0-0", "sample v1 45-54", "sample v1 55-60", "sample v1 1-10"), log.filter { it.startsWith("sample") }.take(4))
    }

    @Test fun partialTimelinesAreObservable() = runTest {
        val store = store()
        store.setItems(listOf(video(1, 25)))
        gates["sample v1"] = CompletableDeferred()
        runCurrent()
        gates.getValue("sample v1").complete(Unit)
        gates["sample v1"] = CompletableDeferred() // the second chunk waits
        runCurrent()
        val partial = store.timeline("v1")!!.value
        assertEquals(1, partial.sampleCount) // the first sample alone, before the chunks
        assertFalse(partial.isComplete)
    }

    @Test fun removalFreesTheTimelineAndFailsItsWaiters() = runTest {
        val store = store()
        store.setItems(listOf(video(1, 25)))
        gates["sample v1"] = CompletableDeferred()
        runCurrent()
        val complete = async { runCatching { store.completeTimeline("v1") } }
        runCurrent()
        store.setItems(emptyList())
        runCurrent()
        assertTrue(complete.await().isFailure)
        assertNull(store.timeline("v1"))
    }

    @Test fun aClipWithoutReadableFramesFailsTheExport() = runTest {
        val store = PhotoPreviewStore(preparer, backgroundScope, StandardTestDispatcher(testScheduler), sampler = { _, _ -> emptyList() })
        store.setItems(listOf(video(1, 5)))
        runCurrent()
        assertTrue(runCatching { store.completeTimeline("v1") }.isFailure)
    }

    @Test fun aFailedChunkDoesNotStallTheVideo() = runTest {
        var calls = 0
        val flaky = TimelineSampler { _, targets -> if (calls++ == 0) error("decoder hiccup") else targets.map { it to params(1f) } }
        val store = PhotoPreviewStore(preparer, backgroundScope, StandardTestDispatcher(testScheduler), sampler = flaky)
        store.setItems(listOf(video(1, 25)))
        runCurrent()
        val t = store.completeTimeline("v1")
        assertEquals(25, t.sampleCount) // the first (alone) failed, the remaining 25 are in
    }
}
