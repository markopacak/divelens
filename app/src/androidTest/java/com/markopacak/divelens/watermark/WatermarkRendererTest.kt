package com.markopacak.divelens.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** The light mark drawn large from the bundled SVG: sharp, at 92 %, with its shadow (task 4.5). */
@RunWith(AndroidJUnit4::class)
class WatermarkRendererTest {
    private val renderer = WatermarkRenderer(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    private val grey = Color.rgb(128, 128, 128)

    // The mark 2400 px wide, 100 px inside a grey bitmap.
    private val width = 2400f
    private val height = width * renderer.aspect()
    private val box = Box(100f, 100f, 100f + width, 100f + height)
    private val bitmap = Bitmap.createBitmap(2600, (height + 200).roundToInt(), Bitmap.Config.ARGB_8888).apply {
        eraseColor(grey)
        renderer.draw(Canvas(this), WatermarkVersion.Light, box)
    }

    /** Pixels per viewBox unit. */
    private val scale = width / 232.5f

    /** The bitmap pixel at viewBox coordinates ([x], [y]). */
    private fun at(x: Float, y: Float) = bitmap.getPixel((box.left + (x + 3f) * scale).toInt(), (box.top + (y + 3f) * scale).toInt())

    @Test fun cornersOutsideTheBoxAreUntouched() {
        for ((x, y) in listOf(0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1, bitmap.width - 1 to bitmap.height - 1)) {
            assertEquals("($x, $y)", grey, bitmap.getPixel(x, y))
        }
    }

    @Test fun ringIsWhiteAt92PercentOverGrey() {
        // The ring is centred at (24, 31) with radii 16 and 24: its band's middle on the left is at x = 4.
        val pixel = at(4f, 31f)
        val expected = (0.92 * 255 + 0.08 * 128).roundToInt()
        for (channel in listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
            assertTrue("channel $channel, expected $expected ± 2", channel in expected - 2..expected + 2)
        }
    }

    @Test fun ringEdgeIsAntialiased() {
        // Along the centre row, the ring's outer edge is at x = 0: some pixel within 3 px of it is between grey and white.
        val row = (box.top + 34f * scale).toInt()
        val edge = (box.left + 3f * scale).toInt()
        val values = (edge - 3..edge + 3).map { Color.green(bitmap.getPixel(it, row)) }
        assertTrue("no intermediate value in $values", values.any { it in 131..242 }) // strictly between grey (128) and the mark (245)
        assertTrue("edge not sharp: $values", values.first() <= 130 && values.last() >= 240)
    }

    @Test fun shadowIsDarkerThanTheBackground() {
        // The ring's bottom is at y = 55, its shadow's at 56.6.
        val pixel = at(24f, 55.8f)
        assertTrue("shadow ${Integer.toHexString(pixel)}", Color.green(pixel) < 120)
    }

    @Test fun overlayBitmapKeepsTransparencyAroundTheMark() {
        val overlay = renderer.bitmap(WatermarkVersion.Dark, 464, (464 * renderer.aspect()).roundToInt())
        assertEquals(0, Color.alpha(overlay.getPixel(0, 0)))
        // Navy ring at 92 %.
        val ring = overlay.getPixel(((4f + 3f) * 464 / 232.5f).toInt(), ((31f + 3f) * 464 / 232.5f).toInt())
        assertEquals(235f, Color.alpha(ring).toFloat(), 3f)
    }
}
