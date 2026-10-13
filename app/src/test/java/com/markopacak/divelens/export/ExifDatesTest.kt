package com.markopacak.divelens.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ExifDatesTest {
    private val utc = ZoneId.of("UTC")

    @Test fun usesOffsetWhenPresent() = assertEquals(
        Instant.parse("2026-07-14T08:32:05Z").toEpochMilli(),
        ExifDates.toEpochMillis("2026:07:14 10:32:05", "+02:00", defaultZone = utc),
    )

    @Test fun fallsBackToDefaultZone() = assertEquals(
        Instant.parse("2026-07-14T08:32:05Z").toEpochMilli(),
        ExifDates.toEpochMillis("2026:07:14 10:32:05", null, defaultZone = ZoneId.of("Europe/Zagreb")),
    )

    @Test fun addsSubSeconds() {
        val base = Instant.parse("2026-07-14T10:32:05Z").toEpochMilli()
        assertEquals(base + 123, ExifDates.toEpochMillis("2026:07:14 10:32:05", subSec = "123", defaultZone = utc))
        assertEquals(base + 500, ExifDates.toEpochMillis("2026:07:14 10:32:05", subSec = "5", defaultZone = utc))
        assertEquals(base + 987, ExifDates.toEpochMillis("2026:07:14 10:32:05", subSec = "98765", defaultZone = utc))
    }

    @Test fun malformedOffsetIsIgnored() = assertEquals(
        Instant.parse("2026-07-14T10:32:05Z").toEpochMilli(),
        ExifDates.toEpochMillis("2026:07:14 10:32:05", "garbage", defaultZone = utc),
    )

    @Test fun missingOrMalformedDateIsNull() {
        assertNull(ExifDates.toEpochMillis(null))
        assertNull(ExifDates.toEpochMillis("    :  :     :  :  "))
        assertNull(ExifDates.toEpochMillis("2026-07-14 10:32:05"))
    }
}
