package com.markopacak.divelens.ui

import com.markopacak.divelens.media.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Texts the grid shows about an item (add-video-processing D5). */
object MediaLabels {
    /** A video's duration as m:ss, or h:mm:ss from one hour on; rounded down to the second. */
    fun duration(durationUs: Long): String {
        val total = durationUs.coerceAtLeast(0) / 1_000_000
        val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /**
     * The date and time taken, in the device's language: a photo's EXIF time as recorded, a video's creation time (UTC in
     * the file) in [zone]. Null when the item has no date.
     */
    fun caption(item: MediaItem, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        return when (item) {
            is MediaItem.Photo -> item.dateTaken?.format(format)
            is MediaItem.Video -> item.creationTimeMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(format) }
        }
    }
}
