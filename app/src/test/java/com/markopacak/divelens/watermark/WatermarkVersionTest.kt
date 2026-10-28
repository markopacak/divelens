package com.markopacak.divelens.watermark

import com.markopacak.divelens.watermark.WatermarkVersion.Companion.WATERMARK_DARK_ABOVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** The light or dark version from the corner's brightness (task 4.4). */
class WatermarkVersionTest {

    @Test fun darkWaterGetsTheLightMark() {
        assertEquals(WatermarkVersion.Light, WatermarkVersion.forLuma(meanOfFlat(0.38)))
    }

    @Test fun brightSandGetsTheDarkMark() {
        assertEquals(WatermarkVersion.Dark, WatermarkVersion.forLuma(meanOfFlat(0.75)))
    }

    @Test fun theThresholdItselfStillGetsTheLightMark() {
        assertEquals(WatermarkVersion.Light, WatermarkVersion.forLuma(WATERMARK_DARK_ABOVE))
        assertEquals(WatermarkVersion.Dark, WatermarkVersion.forLuma(WATERMARK_DARK_ABOVE + 1e-6))
    }

    @Test fun lumaUsesRec709Weights() {
        assertEquals(0.2126, WatermarkVersion.luma(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, WatermarkVersion.luma(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, WatermarkVersion.luma(0xFF0000FF.toInt()), 1e-9)
        assertEquals(1.0, WatermarkVersion.luma(0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test fun subsamplingBarelyMovesTheMean() {
        val random = Random(7)
        val w = 801; val h = 233
        val pixels = IntArray(w * h) {
            val base = 90 + (it % w) / 8 // a gentle gradient with grain
            val v = (base + random.nextInt(-40, 41)).coerceIn(0, 255)
            val r = (v * 0.7).roundToInt(); val g = v; val b = (v * 1.1).roundToInt().coerceAtMost(255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val full = WatermarkVersion.meanLuma(pixels, w, h)
        val half = WatermarkVersion.meanLuma(pixels, w, h, step = 2)
        assertTrue("full $full, subsampled $half", abs(full - half) < 0.005)
    }

    @Test fun strideReadsOnlyTheRegion() {
        // A 4 × 2 region inside a 10-wide picture whose other pixels are white.
        val stride = 10
        val pixels = IntArray(stride * 2) { 0xFFFFFFFF.toInt() }
        for (y in 0 until 2) for (x in 0 until 4) pixels[y * stride + x] = 0xFF000000.toInt()
        assertEquals(0.0, WatermarkVersion.meanLuma(pixels, 4, 2, stride), 0.0)
    }

    @Test fun subsampleOnlyAboveEightMegapixels() {
        assertEquals(1, WatermarkVersion.step(4000, 2000))
        assertEquals(2, WatermarkVersion.step(4000, 3000))
    }

    @Test fun medianOfOddAndEvenCounts() {
        assertEquals(0.3, WatermarkVersion.median(listOf(0.9, 0.3, 0.2)), 0.0)
        assertEquals(0.35, WatermarkVersion.median(listOf(0.9, 0.3, 0.4, 0.2)), 1e-9)
    }

    /** The mean luma of a flat grey corner close to [luma]. */
    private fun meanOfFlat(luma: Double): Double {
        val v = (luma * 255).roundToInt()
        val grey = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        return WatermarkVersion.meanLuma(IntArray(100) { grey }, 10, 10)
    }
}
