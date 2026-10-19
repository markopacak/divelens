package com.markopacak.divelens.export

import com.markopacak.divelens.Branding
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExportNaming {
    private val exifFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * `DiveLens_20260714_103205.jpg` from EXIF DateTimeOriginal, kept as the camera's local
     * wall-clock time. Falls back to [now] when the date is missing or malformed. The photo
     * picker hides original file names, so they can't be used.
     */
    fun fileName(
        dateTimeOriginal: String?,
        now: () -> LocalDateTime = LocalDateTime::now,
        prefix: String = Branding.EXPORT_PREFIX,
    ): String {
        val taken = runCatching { LocalDateTime.parse(dateTimeOriginal?.trim(), exifFormat) }.getOrNull()
        return "${prefix}_${(taken ?: now()).format(nameFormat)}.jpg"
    }

    /**
     * `DiveLens_20261004_173234.mp4` from a video's creation time (UTC in the file), in [zone]; [now] when the file
     * has none (add-video-processing, import-export "Exported file location and naming").
     */
    fun videoFileName(
        creationTimeMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        now: () -> LocalDateTime = LocalDateTime::now,
        prefix: String = Branding.EXPORT_PREFIX,
    ): String {
        val created = creationTimeMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        return "${prefix}_${(created ?: now()).format(nameFormat)}.mp4"
    }
}
