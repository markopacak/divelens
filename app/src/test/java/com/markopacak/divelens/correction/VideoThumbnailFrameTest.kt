package com.markopacak.divelens.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoThumbnailFrameTest {
    /** Frames are their own mean grey; [grey] maps a time in seconds to it, null for unreadable. */
    private fun choose(durationS: Double, grey: (Long) -> Double?): Pair<Long, Double>? {
        var read = 0
        val released = mutableListOf<Double>()
        val chosen = VideoThumbnailFrame.choose(
            (durationS * 1_000_000).toLong(),
            { t -> grey(t / 1_000_000)?.also { read++ } },
            { it },
            { released += it },
        )
        // Every frame read is either chosen or released.
        assertEquals(read, released.size + (if (chosen != null) 1 else 0))
        return chosen
    }

    @Test fun frameAtOneSecond() {
        assertEquals(1_000_000L to 0.4, choose(42.0) { 0.4 })
    }

    @Test fun shortClipsUseTheFirstFrame() {
        assertEquals(listOf(0L), VideoThumbnailFrame.candidateTimesUs(1_999_999))
        assertEquals(0L to 0.3, choose(1.5) { 0.3 })
    }

    @Test fun nearlyBlackFramesAreSkipped() {
        // Black for the first two seconds, as when the clip starts in the housing's shadow.
        assertEquals(3_000_000L to 0.2, choose(42.0) { t -> if (t < 3) 0.01 else 0.2 })
    }

    @Test fun allBlackKeepsTheFirstFrame() {
        assertEquals(1_000_000L to 0.01, choose(42.0) { 0.01 })
    }

    @Test fun candidatesStayInsideTheClip() {
        assertEquals(listOf(1_000_000L, 2_000_000L), VideoThumbnailFrame.candidateTimesUs(2_500_000))
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), VideoThumbnailFrame.candidateTimesUs(60_000_000))
    }

    @Test fun unreadableFramesAreTriedPast() {
        assertEquals(2_000_000L to 0.5, choose(42.0) { t -> if (t == 1L) null else 0.5 })
        assertNull(choose(42.0) { null })
    }
}
