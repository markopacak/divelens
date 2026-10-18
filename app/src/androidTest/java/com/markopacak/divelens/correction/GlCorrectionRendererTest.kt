package com.markopacak.divelens.correction

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CpuCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GlCorrectionRendererTest {

    /** Primaries and their mixes on top, a grey ramp in the middle, an underwater-like gradient below. */
    private fun pixel(x: Int, y: Int, w: Int, h: Int): Int {
        val (r, g, b) = when {
            y < h / 4 -> listOf(0, 255, 128, 64).let { v -> Triple(v[x % 4], v[(x / 4) % 4], v[(x / 16) % 4]) }
            y < h / 2 -> (x * 255 / (w - 1)).let { Triple(it, it, it) }
            else -> Triple((x * 7 + y * 3) % 50, 60 + (x * 5 + y * 11) % 140, 100 + (x * 3 + y * 13) % 155)
        }
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = pixel(x, y, w, h)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    private fun expectedRow(y: Int, w: Int, h: Int, correction: Correction): IntArray =
        IntArray(w) { pixel(it, y, w, h) }.also { CpuCorrection.applyInPlace(it, correction, 0, y, w, 1) }

    private fun assertClose(expected: IntArray, actual: IntArray, label: String) {
        for (i in expected.indices) {
            val e = expected[i]; val a = actual[i]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs((e shr shift and 0xff) - (a shr shift and 0xff))
                assertTrue("$label pixel $i: expected ${Integer.toHexString(e)} got ${Integer.toHexString(a)}", d <= 1)
            }
            assertEquals("$label alpha", e ushr 24, a ushr 24)
        }
    }

    @Test
    fun gpuMatchesCpuAcrossTiles() {
        val w = 2500; val h = 1300
        val bmp = bitmap(w, h)
        val correction = CorrectionPhotoProcessor.estimate(bmp, strength = 0.8f)
        GlCorrectionRenderer(maxTile = 1024).apply(bmp, correction)
        val row = IntArray(w)
        for (y in 0 until h step 7) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            assertClose(expectedRow(y, w, h, correction), row, "row $y")
        }
        bmp.recycle()
    }

    @Test
    fun fiftyMegapixelsWithoutSeams() {
        val w = 8160; val h = 6120
        val bmp = bitmap(w, h)
        val correction = CorrectionPhotoProcessor.estimate(bmp)
        GlCorrectionRenderer().apply(bmp, correction)
        val row = IntArray(w)
        // Rows on both sides of every horizontal tile boundary; each row also crosses the vertical ones.
        for (y in (2048 until h step 2048).flatMap { listOf(it - 1, it) } + listOf(0, h - 1)) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            assertClose(expectedRow(y, w, h, correction), row, "row $y")
        }
        bmp.recycle()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun sessionTileEqualsTheOneShotResultOfTheSameRectangle() {
        val w = 1800; val h = 1200
        val source = bitmap(w, h)
        val correction = CorrectionPhotoProcessor.estimate(source)
        val whole = source.copy(Bitmap.Config.ARGB_8888, true)
        GlCorrectionRenderer().apply(whole, correction)

        val x0 = 700; val y0 = 300; val tw = 512; val th = 512
        val tile = Bitmap.createBitmap(source, x0, y0, tw, th).copy(Bitmap.Config.ARGB_8888, true)
        GlCorrectionRenderer().openSession(correction).use { it.apply(tile, x0, y0, w, h) }
        val expected = IntArray(tw * th).also { whole.getPixels(it, 0, tw, x0, y0, tw, th) }
        assertEquals(expected.toList(), pixels(tile).toList())
    }

    @Test
    fun sampledTileMatchesCpu() {
        val w = 2400; val h = 1600
        val correction = CorrectionPhotoProcessor.estimate(bitmap(w, h))
        // A tile of the photo decoded at 1/4: 600×400, tile at (100, 50).
        val sw = w / 4; val sh = h / 4
        val x0 = 100; val y0 = 50; val tw = 300; val th = 200
        val tile = bitmap(tw, th) // any pixels; what matters is that GPU and CPU treat the same position alike
        val cpu = pixels(tile).also { CpuCorrection.applyInPlace(it, correction.at(sw, sh), x0, y0, tw, th) }
        GlCorrectionRenderer().openSession(correction).use { session ->
            session.apply(tile, x0, y0, sw, sh)
        }
        assertClose(cpu, pixels(tile), "sampled tile")
    }

    @Test
    fun sessionStrengthOverridesTheCorrections() {
        val bmp = bitmap(400, 300)
        val correction = CorrectionPhotoProcessor.estimate(bmp)
        val original = pixels(bmp)
        GlCorrectionRenderer().openSession(correction).use { it.apply(bmp, 0, 0, 400, 300, strength = 0f) }
        assertEquals(original.toList(), pixels(bmp).toList())
    }
}
