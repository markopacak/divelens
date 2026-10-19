package com.markopacak.divelens.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStorageTest {
    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    @Test fun expectedSizeFromTheBitrate() {
        // 60 Mbit/s for 15 s is 112.5 MB, plus 10 %.
        assertEquals(123_750_000L, VideoStorage.expectedBytes(60_000_000, 15_000_000, null))
    }

    @Test fun theFileSizeWinsWhenLargerOrTheBitrateIsUnknown() {
        assertEquals(110_000_000L, VideoStorage.expectedBytes(0, 15_000_000, 100_000_000))
        assertEquals(220_000_000L, VideoStorage.expectedBytes(8_000_000, 10_000_000, 200_000_000))
    }

    @Test fun aThreeGigabyteVideoDoesNotFitInOneGigabyte() {
        // Spec: 1 GB free, a 3 GB video fails at once.
        assertFalse(VideoStorage.fits(1 * gb, 0, 600_000_000, 3 * gb))
    }

    @Test fun theMarginIsKeptFree() {
        val expected = VideoStorage.expectedBytes(60_000_000, 15_000_000, null)
        assertTrue(VideoStorage.fits(expected + VideoStorage.MARGIN_BYTES, 60_000_000, 15_000_000, null))
        assertFalse(VideoStorage.fits(expected + VideoStorage.MARGIN_BYTES - 1, 60_000_000, 15_000_000, null))
    }
}
