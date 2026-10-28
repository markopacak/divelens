package com.markopacak.divelens.watermark

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.roundToInt

/**
 * The watermark on an exported video (add-branding-and-watermark D7): the mark drawn once into a bitmap of its box's
 * pixel size, laid over every frame by a Media3 [OverlayEffect] placed after the correction. Media3 hands effects
 * upright frames, so the box is placed in display coordinates.
 */
@UnstableApi
class VideoWatermark(private val renderer: WatermarkRenderer) {

    /** The overlay for frames shown [displayWidth] × [displayHeight], with [version] for the whole clip. */
    fun effect(displayWidth: Int, displayHeight: Int, version: WatermarkVersion): OverlayEffect {
        val box = WatermarkPlacement(displayWidth, displayHeight, renderer.aspect()).box
        val bitmap = renderer.bitmap(version, box.width.roundToInt(), box.height.roundToInt())
        // Normalised device coordinates: x from −1 (left) to 1 (right), y from −1 (bottom) to 1 (top).
        val settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, -1f) // the overlay's bottom-right corner...
            .setBackgroundFrameAnchor(2f * box.right / displayWidth - 1f, 1f - 2f * box.bottom / displayHeight) // ...at the box's
            .build()
        return OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)))
    }
}
