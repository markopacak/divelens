package com.markopacak.divelens.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.correction.CorrectionPhotoProcessor
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.watermark.PhotoWatermark
import com.markopacak.divelens.watermark.WatermarkRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Exported photos carry the mark in the displayed bottom-right corner, and nothing else changes (task 5.2). */
class WatermarkedPhotoExportTest {
    private val resolver = TestMedia.resolver
    private val watermark = PhotoWatermark(WatermarkRenderer(TestMedia.context.assets))

    private fun writer(processor: PhotoProcessor = IdentityPhotoProcessor) =
        MediaStorePhotoWriter(resolver, TestMedia.context.cacheDir, processor, TestMedia.TEST_ALBUM, watermark = watermark)

    private val on = ExportOptions(watermark = true)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun landscapePhotoGetsTheMarkAndNothingElseChanges() = runBlocking {
        val item = photo(flatJpeg("land.jpg", 1200, 900, DARK_WATER, ExifInterface.ORIENTATION_NORMAL))
        val marked = decode(writer().write(item, on))
        val plain = decode(writer().write(item, ExportOptions.PLAIN))
        assertEquals(1200 to 900, marked.width to marked.height)

        val box = expectedBox(1200, 900)
        assertTrue("ring", isWhite(marked.at(box, 4f, 31f)))
        assertTrue("no ring without the mark", !isWhite(plain.at(box, 4f, 31f)))
        // Outside the box (and the JPEG blocks it touches) the two exports are the same.
        val grown = Rect(box.left - 16, box.top - 16, box.right + 16, box.bottom + 16)
        var differing = 0
        for (y in 0 until 900 step 3) for (x in 0 until 1200 step 3) {
            if (!grown.contains(x, y) && marked.getPixel(x, y) != plain.getPixel(x, y)) differing++
        }
        assertEquals("pixels changed outside the mark", 0, differing)
    }

    @Test fun rotatedPortraitGetsTheMarkUprightInTheDisplayedCorner() = runBlocking {
        // Stored 1200 × 900 with orientation 6: shown as a 900 × 1200 portrait.
        val item = photo(flatJpeg("portrait.jpg", 1200, 900, DARK_WATER, ExifInterface.ORIENTATION_ROTATE_90))
        val stored = decode(writer().write(item, on))
        val shown = Bitmap.createBitmap(stored, 0, 0, stored.width, stored.height, Matrix().apply { postRotate(90f) }, false)
        assertEquals(900 to 1200, shown.width to shown.height)

        val box = expectedBox(900, 1200)
        assertTrue("ring band", isWhite(shown.at(box, 4f, 31f)))
        assertTrue("dot", isWhite(shown.at(box, 49f, 6f)))
        assertTrue("ring hole is background", isBackground(shown.at(box, 24f, 31f)))
        assertTrue("below the dot is background (not flipped)", isBackground(shown.at(box, 49f, 51f)))
    }

    @Test fun strengthZeroStillGetsTheMark() = runBlocking {
        val item = photo(flatJpeg("zero.jpg", 800, 600, DARK_WATER, ExifInterface.ORIENTATION_NORMAL))
        val marked = decode(writer(CorrectionPhotoProcessor(strengthFor = { 0f })).write(item, on))
        val box = expectedBox(800, 600)
        assertTrue(isWhite(marked.at(box, 4f, 31f)))
        assertTrue("colours uncorrected", isBackground(marked.getPixel(100, 100)))
    }

    @Test fun brightCornerGetsTheDarkMark() = runBlocking {
        val item = photo(flatJpeg("sand.jpg", 1200, 900, BRIGHT_SAND, ExifInterface.ORIENTATION_NORMAL))
        val marked = decode(writer().write(item, on))
        val ring = marked.at(expectedBox(1200, 900), 4f, 31f)
        assertTrue("navy ring ${Integer.toHexString(ring)}", Color.red(ring) < 80 && Color.green(ring) < 100 && Color.blue(ring) < 120)
    }

    @Test fun metadataIsTheSameWithAndWithoutTheMark() = runBlocking {
        val item = photo(TestMedia.jpegWithExif("meta.jpg", 800, 600))
        val marked = exif(writer().write(item, on))
        val plain = exif(writer().write(item, ExportOptions.PLAIN))
        val tags = ExifAllowList.tags + listOf(ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_GPS_LATITUDE)
        tags.forEach { tag -> assertEquals(tag, plain.getAttribute(tag), marked.getAttribute(tag)) }
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }

    /** The mark's box for a picture shown [w] × [h] (BRAND.md: 12 % wide, 3 % margin). */
    private fun expectedBox(w: Int, h: Int): Rect {
        val bw = 0.12f * w
        val bh = bw * 63f / 232.5f
        val m = 0.03f * w
        return Rect((w - m - bw).toInt(), (h - m - bh).toInt(), (w - m).toInt(), (h - m).toInt())
    }

    /** The pixel at viewBox coordinates ([x], [y]) of the mark in [box]. */
    private fun Bitmap.at(box: Rect, x: Float, y: Float): Int {
        val s = (box.right - box.left) / 232.5f
        return getPixel((box.left + (x + 3f) * s).toInt(), (box.top + (y + 3f) * s).toInt())
    }

    private fun isWhite(c: Int) = Color.red(c) > 215 && Color.green(c) > 215 && Color.blue(c) > 215

    private fun isBackground(c: Int) =
        listOf(Color.red(c) - Color.red(DARK_WATER), Color.green(c) - Color.green(DARK_WATER), Color.blue(c) - Color.blue(DARK_WATER)).all { it in -8..8 }

    private suspend fun photo(uri: Uri) = MediaImporter(resolver).import(listOf(uri)).accepted.single() as MediaItem.Photo

    private fun decode(uri: String): Bitmap = resolver.openInputStream(Uri.parse(uri))!!.use { BitmapFactory.decodeStream(it)!! }

    private fun exif(uri: String) = resolver.openInputStream(Uri.parse(uri))!!.use { ExifInterface(it) }

    /** A flat [colour] JPEG of [width] × [height] stored pixels with the EXIF [orientation]. */
    private fun flatJpeg(name: String, width: Int, height: Int, colour: Int, orientation: Int): Uri {
        val file = File.createTempFile("fixture", ".jpg", TestMedia.context.cacheDir)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(colour)
            file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
            recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, TestMedia.DATE)
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return TestMedia.insert(name, "image/jpeg", file.readBytes()).also { file.delete() }
    }

    private companion object {
        val DARK_WATER = Color.rgb(30, 70, 100)
        val BRIGHT_SAND = Color.rgb(235, 225, 200)
    }
}
