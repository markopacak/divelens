package com.markopacak.divelens.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Puts the watermark on a corrected photo in place (add-branding-and-watermark D5, D6): the box is placed in the picture
 * as it is shown, the brightness under it picks the version, and the upright mark is drawn through the map from display
 * to stored pixels, so it is right for every EXIF orientation.
 */
class PhotoWatermark(private val renderer: WatermarkRenderer) {

    /** Draws the mark on [bitmap] (mutable, stored orientation, EXIF [orientation] 1–8) and returns the version used. */
    fun apply(bitmap: Bitmap, orientation: Int): WatermarkVersion {
        require(bitmap.isMutable) { "the watermark is drawn in place" }
        val w = bitmap.width
        val h = bitmap.height
        val placement = WatermarkPlacement(
            ExifOrientation.displayWidth(w, h, orientation), ExifOrientation.displayHeight(w, h, orientation), renderer.aspect(),
        )
        val toStored = ExifOrientation.displayToStored(w, h, orientation)
        val version = WatermarkVersion.forLuma(meanLuma(bitmap, toStored.map(placement.measured)))
        val canvas = Canvas(bitmap)
        canvas.concat(Matrix().apply { setValues(toStored.matrixValues()) })
        renderer.draw(canvas, version, placement.box)
        return version
    }

    /** Mean luma of [bitmap] under [area] (stored pixels), read row by row with the subsampling step for its size. */
    private fun meanLuma(bitmap: Bitmap, area: Box): Double {
        val left = floor(area.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(area.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(area.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(area.bottom).toInt().coerceIn(top + 1, bitmap.height)
        val step = WatermarkVersion.step(bitmap.width, bitmap.height)
        val width = right - left
        val row = IntArray(width)
        var sum = 0.0
        var n = 0
        var y = top
        while (y < bottom) {
            bitmap.getPixels(row, 0, width, left, y, width, 1)
            var x = 0
            while (x < width) {
                sum += WatermarkVersion.luma(row[x])
                n++
                x += step
            }
            y += step
        }
        return sum / n
    }
}
