package com.markopacak.divelens.ui

import com.markopacak.divelens.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class MediaLabelsTest {
    private fun video(created: Long?) =
        MediaItem.Video("v", "v.mp4", "video/mp4", 1920, 1080, durationUs = 1, frameRate = 30f, codec = "video/avc", creationTimeMillis = created)

    @Test fun durationFormats() {
        assertEquals("0:00", MediaLabels.duration(0))
        assertEquals("0:42", MediaLabels.duration(42_900_000))
        assertEquals("10:05", MediaLabels.duration(605_000_000))
        assertEquals("1:00:00", MediaLabels.duration(3_600_000_000))
        assertEquals("1:02:03", MediaLabels.duration(3_723_000_000))
    }

    @Test fun photoCaptionIsTheRecordedWallClockTime() {
        val photo = MediaItem.Photo("p", "p.jpg", "image/jpeg", 1, 1, LocalDateTime.of(2026, 7, 14, 10, 32, 5))
        // The zone does not move a photo's time: EXIF records what the camera's clock showed.
        assertEquals("14 Jul 2026, 10:32", MediaLabels.caption(photo, Locale.UK, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun videoCaptionIsTheCreationTimeInTheDevicesZone() {
        val created = Instant.parse("2026-10-04T15:32:34Z").toEpochMilli()
        assertEquals("4 Oct 2026, 17:32", MediaLabels.caption(video(created), Locale.UK, ZoneId.of("Europe/Zagreb")))
        assertEquals("4 Oct 2026, 15:32", MediaLabels.caption(video(created), Locale.UK, ZoneId.of("UTC")))
    }

    @Test fun captionFollowsTheLanguage() {
        val photo = MediaItem.Photo("p", "p.jpg", "image/jpeg", 1, 1, LocalDateTime.of(2026, 7, 14, 10, 32))
        assertEquals("14.07.2026, 10:32", MediaLabels.caption(photo, Locale.GERMANY))
    }

    @Test fun noDateNoCaption() {
        assertNull(MediaLabels.caption(MediaItem.Photo("p", "p.jpg", "image/jpeg", 1, 1)))
        assertNull(MediaLabels.caption(video(null)))
    }
}
