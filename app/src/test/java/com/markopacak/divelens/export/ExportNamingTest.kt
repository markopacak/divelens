package com.markopacak.divelens.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ExportNamingTest {
    private val now = { LocalDateTime.of(2026, 10, 12, 7, 5, 9) }

    @Test fun usesDateTaken() =
        assertEquals("DiveLens_20260714_103205.jpg", ExportNaming.fileName("2026:07:14 10:32:05", now))

    @Test fun missingDateFallsBackToNow() =
        assertEquals("DiveLens_20261012_070509.jpg", ExportNaming.fileName(null, now))

    @Test fun malformedDateFallsBackToNow() {
        assertEquals("DiveLens_20261012_070509.jpg", ExportNaming.fileName("    :  :     :  :  ", now))
        assertEquals("DiveLens_20261012_070509.jpg", ExportNaming.fileName("2026-07-14T10:32:05", now))
    }

    @Test fun prefixFollowsAppName() =
        assertEquals("Depix_20260714_103205.jpg", ExportNaming.fileName("2026:07:14 10:32:05", now, prefix = "Depix"))

    @Test fun videoNameIsTheCreationTimeInTheDevicesZone() {
        val created = Instant.parse("2026-10-04T15:32:34Z").toEpochMilli()
        assertEquals("DiveLens_20261004_173234.mp4", ExportNaming.videoFileName(created, ZoneId.of("Europe/Zagreb"), now))
        assertEquals("DiveLens_20261004_153234.mp4", ExportNaming.videoFileName(created, ZoneId.of("UTC"), now))
    }

    @Test fun videoWithoutCreationTimeIsNamedFromNow() =
        assertEquals("DiveLens_20261012_070509.mp4", ExportNaming.videoFileName(null, ZoneId.of("UTC"), now))
}
