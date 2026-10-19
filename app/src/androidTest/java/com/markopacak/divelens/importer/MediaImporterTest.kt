package com.markopacak.divelens.importer

import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class MediaImporterTest {
    private val importer = MediaImporter(TestMedia.resolver)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun acceptsJpegAndHeicWithStoredDimensions() = runBlocking {
        val jpeg = TestMedia.jpegWithExif("a.jpg", 400, 300)
        val heic = TestMedia.asset("b.heic", "image/heic", "synthetic_320x240.heic")
        val result = importer.import(listOf(jpeg, heic))
        assertEquals(emptyList<Rejection>(), result.rejected)
        assertEquals(listOf("a.jpg" to (400 to 300), "b.heic" to (320 to 240)), result.accepted.map { it as MediaItem.Photo; it.displayName to (it.width to it.height) })
        assertEquals(listOf("image/jpeg", "image/heic"), result.accepted.map { it.mimeType })
        assertEquals(LocalDateTime.of(2026, 7, 14, 10, 32, 5), (result.accepted[0] as MediaItem.Photo).dateTaken)
    }

    @Test fun importsTheFixtureVideoWithItsFields() = runBlocking {
        val result = importer.import(listOf(TestMedia.jpegWithExif("a.jpg"), TestMedia.driftVideo("clip.mp4")))
        assertEquals(emptyList<Rejection>(), result.rejected)
        val video = result.accepted[1] as MediaItem.Video
        assertEquals("clip.mp4", video.displayName)
        assertEquals("video/mp4", video.mimeType)
        assertEquals(640 to 360, video.width to video.height)
        assertEquals(90, video.rotation)
        assertEquals(360 to 640, video.displayWidth to video.displayHeight)
        assertEquals(2_000_000.0, video.durationUs.toDouble(), 50_000.0)
        assertEquals(30f, video.frameRate, 0.5f)
        assertEquals("video/avc", video.codec)
        assertFalse(video.hdr)
        assertEquals(Instant.parse("2026-07-14T08:32:05Z").toEpochMilli(), video.creationTimeMillis)
        assertTrue(video.bitrate > 0)
    }

    @Test fun marksTheHlgFixtureAsHdr() = runBlocking {
        val result = importer.import(listOf(TestMedia.hlgVideo()))
        // Devices without a 10-bit HEVC decoder reject it as too large for this device.
        val video = result.accepted.singleOrNull() as MediaItem.Video?
        if (video != null) {
            assertEquals("video/hevc", video.codec)
            assertTrue(video.hdr)
        } else {
            assertEquals(RejectionReason.TooLargeForDevice, result.rejected.single().reason)
        }
    }

    @Test fun commonVideoSizesCountAsEncodable() {
        // Hardware encoders that declare 16-pixel alignment still encode 1080 rows (the camera records 1080p).
        assertTrue(AndroidCodecSupport.canEncode(VideoAcceptance.H264, 1920, 1080, 60f))
        assertTrue(AndroidCodecSupport.canEncode(VideoAcceptance.H264, 640, 360, 30f))
        assertTrue(AndroidCodecSupport.canEncode(VideoAcceptance.H264, 2704, 1520, 59.94f))
    }

    @Test fun rejectsACorruptVideo() = runBlocking {
        val result = importer.import(listOf(TestMedia.corruptMp4("broken.mp4"), TestMedia.driftVideo("ok.mp4")))
        assertEquals(listOf("ok.mp4"), result.accepted.map { it.displayName })
        assertEquals(listOf(Rejection("broken.mp4", RejectionReason.Unreadable)), result.rejected)
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
