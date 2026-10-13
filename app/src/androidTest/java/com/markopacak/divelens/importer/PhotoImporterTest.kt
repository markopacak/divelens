package com.markopacak.divelens.importer

import com.markopacak.divelens.TestMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PhotoImporterTest {
    private val importer = PhotoImporter(TestMedia.resolver)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun acceptsJpegAndHeicWithStoredDimensions() = runBlocking {
        val jpeg = TestMedia.jpegWithExif("a.jpg", 400, 300)
        val heic = TestMedia.asset("b.heic", "image/heic", "synthetic_320x240.heic")
        val result = importer.import(listOf(jpeg, heic))
        assertEquals(emptyList<Rejection>(), result.rejected)
        assertEquals(listOf("a.jpg" to (400 to 300), "b.heic" to (320 to 240)), result.accepted.map { it.displayName to (it.width to it.height) })
        assertEquals(listOf("image/jpeg", "image/heic"), result.accepted.map { it.mimeType })
    }

    @Test fun rejectsUnsupportedAndCorruptFilesButKeepsTheRest() = runBlocking {
        val uris = listOf(
            TestMedia.jpegWithExif("ok1.jpg"),
            TestMedia.png("image.png"),
            TestMedia.corruptJpeg("broken.jpg"),
            TestMedia.jpegWithExif("ok2.jpg"),
        )
        val result = importer.import(uris)
        assertEquals(listOf("ok1.jpg", "ok2.jpg"), result.accepted.map { it.displayName })
        assertEquals(
            listOf(Rejection("image.png", RejectionReason.UnsupportedFormat), Rejection("broken.jpg", RejectionReason.Unreadable)),
            result.rejected,
        )
    }
}
