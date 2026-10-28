package com.markopacak.divelens.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mark's box, its measurement area and the EXIF mapping to stored pixels (task 4.3). */
class WatermarkPlacementTest {
    private val aspect = 63f / 232.5f

    @Test fun landscapePhoto() {
        val p = WatermarkPlacement(4000, 3000, aspect)
        assertEquals(480f, p.box.width, 0.01f)
        assertEquals(4000f - 120f, p.box.right, 0.01f)
        assertEquals(3000f - 120f, p.box.bottom, 0.01f)
        assertEquals(480f * aspect, p.box.height, 0.01f)
    }

    @Test fun portraitPhoto() {
        val p = WatermarkPlacement(3000, 4000, aspect)
        assertEquals(360f, p.box.width, 0.01f)
        assertEquals(3000f - 90f, p.box.right, 0.01f)
        assertEquals(4000f - 90f, p.box.bottom, 0.01f)
    }

    @Test fun measuredAreaIsTheBoxPlusTenPercentClampedToThePicture() {
        val p = WatermarkPlacement(4000, 3000, aspect)
        assertEquals(p.box.left - 48f, p.measured.left, 0.01f)
        assertEquals(p.box.right + 48f, p.measured.right, 0.01f)
        assertEquals(p.box.top - 0.1f * p.box.height, p.measured.top, 0.01f)
        // A mark much taller than the margin: its padding is clamped at the bottom edge.
        val tall = WatermarkPlacement(1000, 1000, 4f)
        assertEquals(1000f, tall.measured.bottom, 0.0f)
    }

    /**
     * For every orientation, the box mapped to stored pixels and shown again through an independent model of the EXIF
     * orientations (mirror first, then rotate clockwise, as the EXIF specification describes them) lands back on the
     * box, corner for corner. So the mark is upright, unmirrored and in the displayed bottom-right corner.
     */
    @Test fun everyOrientationMapsBackToTheDisplayedCorner() {
        val stored = 4000 to 3000
        for (orientation in 1..8) {
            val dw = ExifOrientation.displayWidth(stored.first, stored.second, orientation)
            val dh = ExifOrientation.displayHeight(stored.first, stored.second, orientation)
            val box = WatermarkPlacement(dw, dh, aspect).box
            val toStored = ExifOrientation.displayToStored(stored.first, stored.second, orientation)
            val corners = listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
            for ((x, y) in corners) {
                val sx = toStored.mapX(x, y); val sy = toStored.mapY(x, y)
                assertTrue("orientation $orientation: ($sx, $sy) outside stored pixels", sx in 0f..4000f && sy in 0f..3000f)
                val (bx, by) = show(sx, sy, stored.first.toFloat(), stored.second.toFloat(), orientation)
                assertEquals("orientation $orientation x", x, bx, 0.01f)
                assertEquals("orientation $orientation y", y, by, 0.01f)
            }
            val area = toStored.map(box)
            assertEquals("orientation $orientation area", box.width * box.height, area.width * area.height, 1f)
        }
    }

    /** How the gallery shows stored pixel ([x], [y]) of a [w] × [h] picture: optional left-right mirror, then rotation. */
    private fun show(x: Float, y: Float, w: Float, h: Float, orientation: Int): Pair<Float, Float> {
        val mirror = orientation in setOf(2, 4, 5, 7)
        val quarterTurns = when (orientation) { 1, 2 -> 0; 3 -> 2; 4 -> 2; 5 -> 3; 6 -> 1; 7 -> 1; 8 -> 3; else -> 0 }
        var px = if (mirror) w - x else x
        var py = y
        var cw = w; var ch = h
        repeat(quarterTurns) { // rotate 90° clockwise: (x, y) in cw × ch → (ch − y, x) in ch × cw
            val nx = ch - py; val ny = px
            px = nx; py = ny
            val t = cw; cw = ch; ch = t
        }
        return px to py
    }
}
