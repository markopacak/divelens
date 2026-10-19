package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.export.MediaStorePhotoWriter
import com.markopacak.divelens.export.PhotoProcessor
import com.markopacak.divelens.importer.PhotoImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/** The viewer's tiles (design D4, spec "Zoom to real pixels" and "Preview matches the export"). */
class GpuViewerRendererTest {
    private val resolver = TestMedia.resolver
    private val estimator = CorrectionEstimator(resolver)
    private val corrections = CorrectionSource { estimator.estimate(Uri.parse(it)) }

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun worstDifference(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var worst = 0
        for (i in a.indices) for (shift in intArrayOf(0, 8, 16)) worst = maxOf(worst, abs((a[i] shr shift and 0xff) - (b[i] shr shift and 0xff)))
        return worst
    }

    /** Draws [tiles] into one bitmap of [width]×[height] at sample size [n]. */
    private fun assemble(tiles: List<Layer>, width: Int, height: Int, n: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (t in tiles) canvas.drawBitmap(t.corrected, (t.rect.left / n).toFloat(), (t.rect.top / n).toFloat(), null)
        return out
    }

    @Test fun tilesAt100PercentEqualTheExport() = runBlocking { assertTilesEqualTheExport(strength = null) }

    /** add-strength-slider: at a changed strength, the viewer at 100% still equals the export at that strength. */
    @Test fun tilesAtAChangedStrengthEqualTheExport() = runBlocking { assertTilesEqualTheExport(strength = 0.4f) }

    private suspend fun assertTilesEqualTheExport(strength: Float?) {
        val source = TestMedia.jpegWithExif("tiles.jpg", 1500, 1100) // orientation 6: shown as 1100×1500
        var exported: Bitmap? = null
        val processor = if (strength == null) CorrectionPhotoProcessor(corrections) else CorrectionPhotoProcessor(corrections, { strength })
        val capture = PhotoProcessor { s, input -> processor.process(s, input).also { exported = it.copy(Bitmap.Config.ARGB_8888, false) } }
        MediaStorePhotoWriter(resolver, TestMedia.context.cacheDir, capture, TestMedia.TEST_ALBUM)
            .write(PhotoImporter(resolver).import(listOf(source)).accepted.single())
        val export = exported!!
        val exportShown = Bitmap.createBitmap(export, 0, 0, export.width, export.height, Matrix().apply { postRotate(90f) }, false)

        val photo = GpuViewerRenderer(resolver, corrections).open(source.toString())
        assertEquals(1100 to 1500, photo.width to photo.height)
        if (strength != null) {
            photo.tiles(1, Rect(0, 0, photo.width, photo.height)) // made at the default, then corrected again
            photo.setStrength(strength)
        }
        val tiles = photo.tiles(1, Rect(0, 0, photo.width, photo.height))!!
        assertEquals(9, tiles.size) // 1500×1100 stored in 512 px tiles: 3×3
        val shown = assemble(tiles, photo.width, photo.height, 1)
        val worst = worstDifference(pixels(exportShown), pixels(shown))
        // The tiles' originals are the full decode's pixels: tiles are decoded with a margin (no border artefacts).
        val full = resolver.openInputStream(source)!!.use { android.graphics.BitmapFactory.decodeStream(it)!! }
        val fullShown = Bitmap.createBitmap(full, 0, 0, full.width, full.height, Matrix().apply { postRotate(90f) }, false)
        val originals = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888).also { out ->
            val c = Canvas(out); for (t in tiles) c.drawBitmap(t.original, t.rect.left.toFloat(), t.rect.top.toFloat(), null)
        }
        assertEquals(0, worstDifference(pixels(fullShown), pixels(originals)))
        assertTrue("largest channel difference to the export $worst", worst <= 1)
        photo.close()
    }

    @Test fun sampledTilesJoinWithoutSeams() = runBlocking {
        val source = TestMedia.jpegWithExif("seams.jpg", 2100, 1300)
        val photo = GpuViewerRenderer(resolver, corrections).open(source.toString())
        val n = 2
        val tiles = photo.tiles(n, Rect(0, 0, photo.width, photo.height))!!
        // Shown as 1300×2100; at 1/2 that is 650×1050 in 512 px tiles: 2×3.
        assertEquals(6, tiles.size)
        val shown = assemble(tiles, 650, 1050, n)

        // Reference: the whole photo decoded the same way at 1/2, corrected on the CPU in one piece, then rotated.
        val decoder = resolver.openInputStream(source)!!.use { BitmapRegionDecoder.newInstance(it)!! }
        val whole = decoder.decodeRegion(Rect(0, 0, 2100, 1300), android.graphics.BitmapFactory.Options().apply { inSampleSize = n })
        val correction: Correction = estimator.estimate(source)
        val px = pixels(whole).also { CpuCorrection.applyInPlace(it, correction.at(whole.width, whole.height), 0, 0, whole.width, whole.height) }
        val corrected = Bitmap.createBitmap(px, whole.width, whole.height, Bitmap.Config.ARGB_8888)
        val reference = Bitmap.createBitmap(corrected, 0, 0, corrected.width, corrected.height, Matrix().apply { postRotate(90f) }, false)
        val worst = worstDifference(pixels(reference), pixels(shown))
        assertTrue("largest channel difference across tiles $worst", worst <= 1)
        photo.close()
    }

    @Test fun heicZoomsWithTilesOrReportsNoDetail() = runBlocking {
        val source = TestMedia.asset("zoom.heic", "image/heic", "synthetic_320x240.heic")
        val photo = GpuViewerRenderer(resolver, corrections).open(source.toString())
        assertNotNull(photo.base(160, 120))
        val tiles = photo.tiles(1, Rect(0, 0, photo.width, photo.height))
        if (tiles != null) assertTrue(tiles.isNotEmpty())
        photo.close()
    }

    @Test fun strengthReRendersFromTheKeptOriginals() = runBlocking {
        val source = TestMedia.jpegWithExif("strength.jpg", 800, 600)
        var estimates = 0
        val counting = CorrectionSource { estimates++; estimator.estimate(Uri.parse(it)) }
        val photo = GpuViewerRenderer(resolver, counting).open(source.toString())
        val before = photo.base(300, 400)
        val all = Rect(0, 0, photo.width, photo.height)
        val tilesBefore = photo.tiles(1, all)!!
        photo.setStrength(0f)
        val after = photo.base(300, 400)
        // Strength 0 is the original; no new estimate was needed, and the original bitmap was reused, not decoded again.
        assertEquals(pixels(before.original).toList(), pixels(after.corrected).toList())
        assertTrue(after.original === before.original)
        // Kept tiles come back at the new strength, from their kept originals.
        val tilesAfter = photo.tiles(1, all)!!
        assertEquals(tilesBefore.size, tilesAfter.size)
        for ((old, new) in tilesBefore.zip(tilesAfter)) {
            assertTrue(new.original === old.original)
            assertEquals(0f, new.strength)
            assertEquals(pixels(old.original).toList(), pixels(new.corrected).toList())
        }
        assertEquals(1, estimates)
        photo.close()
    }
}
