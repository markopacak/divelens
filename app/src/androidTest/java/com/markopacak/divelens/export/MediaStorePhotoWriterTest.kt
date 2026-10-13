package com.markopacak.divelens.export

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.importer.PhotoImporter
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

class MediaStorePhotoWriterTest {
    private val resolver = TestMedia.resolver
    private val cacheDir get() = TestMedia.context.cacheDir

    private fun writer(beforePublish: suspend () -> Unit = {}) =
        MediaStorePhotoWriter(resolver, cacheDir, IdentityPhotoProcessor, TestMedia.TEST_ALBUM, beforePublish)

    private suspend fun photo(uri: Uri): MediaItem.Photo = PhotoImporter(resolver).import(listOf(uri)).accepted.single()

    private fun exif(uri: Uri) = resolver.openInputStream(uri)!!.use { ExifInterface(it) }

    private fun tempFiles() = cacheDir.listFiles { f -> f.name.startsWith("export") }.orEmpty().toList()

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun exportKeepsDimensionsAndLeavesOriginalUntouched() = runBlocking {
        val source = TestMedia.jpegWithExif("GOPR0123.JPG", 400, 300)
        val before = TestMedia.sha256(source)
        val out = Uri.parse(writer().write(photo(source)))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(out)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(400 to 300, bounds.outWidth to bounds.outHeight)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(before, TestMedia.sha256(source))
        assertEquals(listOf(out), TestMedia.exportedRows())
        assertEquals("DiveLens_20260714_103205.jpg", TestMedia.column(out, MediaStore.Images.Media.DISPLAY_NAME) { it.getString(0) })
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun copiesAllowListedMetadataAndOrientationButNotGpsOrSerial() = runBlocking {
        val source = TestMedia.jpegWithExif("a.jpg")
        val out = Uri.parse(writer().write(photo(source)))
        val src = exif(source)
        val dst = exif(out)

        assertTrue("fixture must carry GPS", src.latLong != null)
        ExifAllowList.tags.forEach { tag -> assertEquals(tag, src.getAttribute(tag), dst.getAttribute(tag)) }
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, dst.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("TestMake", dst.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(dst.latLong)
        assertNull(dst.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(dst.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertEquals("400", dst.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertEquals("300", dst.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION))
        assertTrue("original keeps GPS", exif(source).latLong != null)
    }

    @Test fun setsDateTakenFromExif() = runBlocking {
        val out = Uri.parse(writer().write(photo(TestMedia.jpegWithExif("a.jpg"))))
        val dateTaken = TestMedia.column(out, MediaStore.Images.Media.DATE_TAKEN) { it.getLong(0) }
        assertEquals(Instant.parse("2026-07-14T08:32:05Z").toEpochMilli(), dateTaken)
    }

    @Test fun dateWithoutOffsetIsKeptAsRecordedAndNoOffsetAdded() = runBlocking {
        val out = Uri.parse(writer().write(photo(TestMedia.jpegWithExif("a.jpg", withOffset = false))))
        val dst = exif(out)
        assertEquals(TestMedia.DATE, dst.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(dst.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
    }

    @Test fun heicExportsAsJpegWithSameDimensions() = runBlocking {
        val source = TestMedia.asset("dive.heic", "image/heic", "synthetic_320x240.heic")
        val out = Uri.parse(writer().write(photo(source)))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(out)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(Triple(320, 240, "image/jpeg"), Triple(bounds.outWidth, bounds.outHeight, bounds.outMimeType))
        val name = TestMedia.column(out, MediaStore.Images.Media.DISPLAY_NAME) { it.getString(0) }
        assertTrue(name, Regex("""DiveLens_\d{8}_\d{6}\.jpg""").matches(name))
    }

    @Test fun exportingTwiceCreatesTwoFiles() = runBlocking {
        val item = photo(TestMedia.jpegWithExif("same.jpg"))
        val first = Uri.parse(writer().write(item))
        val second = Uri.parse(writer().write(item))
        assertNotEquals(first, second)
        val names = listOf(first, second).map { TestMedia.column(it, MediaStore.Images.Media.DISPLAY_NAME) { c -> c.getString(0) } }
        assertEquals(2, names.toSet().size)
        assertEquals(2, TestMedia.exportedRows().size)
    }

    @Test fun outOfSpaceFailureLeavesNoPendingRowOrTempFile() = runBlocking {
        val item = photo(TestMedia.jpegWithExif("a.jpg"))
        try {
            writer(beforePublish = { throw IOException("write failed: ENOSPC (No space left on device)") }).write(item)
            fail("expected ExportException")
        } catch (e: ExportException) {
            assertEquals(FailureReason.InsufficientStorage, e.reason)
        }
        assertEquals(emptyList<Uri>(), TestMedia.exportedRows())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun cancelMidItemLeavesNoPendingRowOrTempFile() = runBlocking {
        val item = photo(TestMedia.jpegWithExif("a.jpg"))
        val reached = CompletableDeferred<Unit>()
        val job = async { writer(beforePublish = { reached.complete(Unit); CompletableDeferred<Unit>().await() }).write(item) }
        reached.await()
        assertEquals("pending row exists mid-item", 1, TestMedia.exportedRows().size)
        job.cancel()
        runCatching { job.await() }
        yield()
        assertEquals(emptyList<Uri>(), TestMedia.exportedRows())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun unreadableSourceFails() = runBlocking {
        val item = photo(TestMedia.jpegWithExif("gone.jpg"))
        resolver.delete(Uri.parse(item.source), null, null)
        try {
            writer().write(item)
            fail("expected ExportException")
        } catch (e: ExportException) {
            assertEquals(FailureReason.UnreadableSource, e.reason)
        }
        assertFalse(TestMedia.exportedRows().isNotEmpty())
    }
}
