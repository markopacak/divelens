package com.markopacak.divelens.correction

import com.markopacak.divelens.TestMedia
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The shared estimate of design D1: reduced decode, same correction every time. */
class CorrectionEstimatorTest {
    private val estimator = CorrectionEstimator(TestMedia.resolver)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun sampleSizeKeepsTheWidthAtLeast2048() {
        assertEquals(1, CorrectionEstimator.sampleSize(4095))
        assertEquals(2, CorrectionEstimator.sampleSize(4096))
        assertEquals(2, CorrectionEstimator.sampleSize(6000))
        assertEquals(4, CorrectionEstimator.sampleSize(8192))
        assertEquals(1, CorrectionEstimator.sampleSize(400))
    }

    @Test fun largeJpegIsDecodedAtHalfSizeAndEstimatedTheSameEveryTime() {
        val source = TestMedia.jpegWithExif("large.jpg", 6000, 4000)
        val decoded = estimator.decode(source)
        assertEquals(2, decoded.sampleSize)
        assertEquals(3000 to 2000, decoded.bitmap.width to decoded.bitmap.height)
        assertEquals(6000 to 4000, decoded.fullWidth to decoded.fullHeight)
        decoded.bitmap.recycle()

        val a = estimator.estimate(source); val b = estimator.estimate(source)
        assertEquals(6000 to 4000, a.width to a.height)
        assertEquals(a.params, b.params)
        assertArrayEquals(a.map.a, b.map.a, 0f)
        assertArrayEquals(a.map.b, b.map.b, 0f)
    }
}
