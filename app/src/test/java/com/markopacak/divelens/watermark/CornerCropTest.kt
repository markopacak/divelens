package com.markopacak.divelens.watermark

import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CorrectionTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One watermark version per clip, from the corrected corners of its samples (task 5.3). */
class CornerCropTest {
    private val aspect = 63f / 232.5f

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun cropAveragesTheMeasuredAreaIntoAFewCells() {
        // A 400 × 225 frame, dark everywhere except the bottom-right corner area.
        val w = 400; val h = 225
        val area = WatermarkPlacement(w, h, aspect).measured
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x >= area.left.toInt() && y >= area.top.toInt()) grey(200) else grey(20) // the crop starts at the partial pixel
        }
        val cells = CornerCrop.of(pixels, w, h, aspect)
        assertTrue(cells.size <= CornerCrop.MAX_COLUMNS * CornerCrop.MAX_ROWS)
        assertEquals(200.0 / 255, WatermarkVersion.meanLuma(cells, cells.size, 1), 0.02)
    }

    @Test fun aShortBrightRunDoesNotFlipTheClip() {
        val identity = CorrectionParams.IDENTITY
        var t = CorrectionTimeline(10_000_000)
        for (s in 0..10) {
            val bright = s in 4..5 // two seconds over sand
            t = t.withSample(s * 1_000_000L, identity, IntArray(12) { grey(if (bright) 235 else 90) })
        }
        assertEquals(WatermarkVersion.Light, CornerCrop.versionFor(t.completed(), 1f))
    }

    @Test fun strengthDecidesWhenItMovesTheCornerAcrossTheThreshold() {
        // A correction that brightens every channel by 1.6: a mid-grey corner (0.5) ends at 0.8 at full strength.
        val brighten = CorrectionParams(floatArrayOf(1.6f, 0f, 0f, 0f, 0f, 1.6f, 0f, 0f, 0f, 0f, 1.6f, 0f), 1f)
        var t = CorrectionTimeline(3_000_000)
        for (s in 0..3) t = t.withSample(s * 1_000_000L, brighten, IntArray(12) { grey(128) })
        t = t.completed()
        assertEquals(WatermarkVersion.Light, CornerCrop.versionFor(t, 0f))
        assertEquals(WatermarkVersion.Dark, CornerCrop.versionFor(t, 1f))
    }

    @Test fun noCornersMeansTheLightMark() {
        val t = CorrectionTimeline(1_000_000).withSample(0, CorrectionParams.IDENTITY).completed()
        assertEquals(WatermarkVersion.Light, CornerCrop.versionFor(t, 1f))
    }
}
