package com.markopacak.divelens.export

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ExifDates {
    private val format = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * Converts EXIF DateTimeOriginal (`2026:07:14 10:32:05`), with optional OffsetTimeOriginal
     * (`+02:00`) and SubSecTimeOriginal (`123`), to epoch milliseconds. Without an offset the
     * time is interpreted in [defaultZone]. Returns null when the date is missing or malformed.
     */
    fun toEpochMillis(
        dateTime: String?,
        offset: String? = null,
        subSec: String? = null,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val local = runCatching { LocalDateTime.parse(dateTime?.trim(), format) }.getOrNull() ?: return null
        val zone = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: defaultZone
        val millis = local.atZone(zone).toInstant().toEpochMilli()
        val fraction = subSec?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val subMillis = fraction?.take(3)?.padEnd(3, '0')?.toLong() ?: 0L
        return millis + subMillis
    }
}
