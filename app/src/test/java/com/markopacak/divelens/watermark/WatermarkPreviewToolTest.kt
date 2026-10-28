package com.markopacak.divelens.watermark

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The threshold tool measures the corner as the app does (add-branding-and-watermark D9, task 6.2): its placement,
 * opacity, luma weights, subsampling and threshold are the app's.
 */
class WatermarkPreviewToolTest {
    private val tool = File(System.getProperty("user.dir")!!).parentFile.resolve("tools/brand/watermark_preview.py").readText()

    private fun constant(name: String): String =
        Regex("""^$name = (.+)$""", RegexOption.MULTILINE).find(tool)?.groupValues?.get(1)?.trim()
            ?: error("$name not found in watermark_preview.py")

    @Test fun placementAndOpacityMatch() {
        assertEquals(WatermarkPlacement.WIDTH_SHARE.toDouble(), constant("WIDTH_SHARE").toDouble(), 1e-6)
        assertEquals(WatermarkPlacement.MARGIN_SHARE.toDouble(), constant("MARGIN_SHARE").toDouble(), 1e-6)
        assertEquals(WatermarkPlacement.PADDING_SHARE.toDouble(), constant("PADDING_SHARE").toDouble(), 1e-6)
        assertEquals(WatermarkPlacement.OPACITY.toDouble(), constant("OPACITY").toDouble(), 1e-6)
    }

    @Test fun brightnessMatches() {
        val weights = constant("LUMA_WEIGHTS").trim('(', ')').split(",").map { it.trim().toDouble() }
        // The app's weights, read back from its luma of pure red, green and blue.
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()).map { WatermarkVersion.luma(it) }, weights)
        assertEquals(WatermarkVersion.WATERMARK_DARK_ABOVE, constant("WATERMARK_DARK_ABOVE").toDouble(), 0.0)
        assertEquals(WatermarkVersion.SUBSAMPLE_ABOVE_PIXELS, constant("SUBSAMPLE_ABOVE_PIXELS").replace("_", "").toLong())
    }
}
