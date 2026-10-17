package com.markopacak.divelens.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.correction.CorrectionPhotoProcessor
import com.markopacak.divelens.importer.PhotoImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Export through the real correction (spec: "Exports are corrected"). */
class CorrectedExportTest {
    private val resolver = TestMedia.resolver

    private fun writer() = MediaStorePhotoWriter(resolver, TestMedia.context.cacheDir, CorrectionPhotoProcessor(), TestMedia.TEST_ALBUM)

    private suspend fun export(source: Uri): Uri =
        Uri.parse(writer().write(PhotoImporter(resolver).import(listOf(source)).accepted.single()))

    private fun decode(uri: Uri): Bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it)!! }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun blueCastPhotoIsCorrectedAndOriginalUntouched() = runBlocking {
        val source = TestMedia.jpegWithExif("blue.jpg", 400, 300)
        val before = TestMedia.sha256(source)
        val original = decode(source); val exported = decode(export(source))

        assertEquals(original.width to original.height, exported.width to exported.height)
        val redBefore = pixels(original).map { it shr 16 and 0xff }.average()
        val redAfter = pixels(exported).map { it shr 16 and 0xff }.average()
        assertTrue("mean red $redBefore -> $redAfter", redAfter > redBefore + 20)
        assertEquals(before, TestMedia.sha256(source))
    }

    @Test fun exportingTwiceGivesIdenticalPixels() = runBlocking {
        val source = TestMedia.jpegWithExif("twice.jpg", 400, 300)
        val a = pixels(decode(export(source))); val b = pixels(decode(export(source)))
        assertArrayEquals(a, b)
    }
}
