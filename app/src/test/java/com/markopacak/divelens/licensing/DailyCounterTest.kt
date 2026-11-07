package com.markopacak.divelens.licensing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyCounterTest {
    private val flags = MapFlags()
    private var day = LocalDate.of(2026, 11, 7)
    private val counter = DailyCounter(flags) { day }

    @Test fun tenExportsThenNoneLeft() {
        assertEquals(10, counter.remaining())
        repeat(6) { counter.record() }
        assertEquals(4, counter.remaining())
        repeat(4) { counter.record() }
        assertEquals(0, counter.remaining())
        counter.record()
        assertEquals(0, counter.remaining()) // never negative
    }

    @Test fun theNextDayStartsAgainAtTen() {
        repeat(10) { counter.record() }
        day = day.plusDays(1)
        assertEquals(10, counter.remaining())
        counter.record()
        assertEquals(9, counter.remaining())
    }

    @Test fun aStoredDayLaterThanTodayResets() {
        repeat(10) { counter.record() }
        day = day.minusDays(1) // clock set back
        assertEquals(10, counter.remaining())
    }

    @Test fun recordAcrossMidnightCountsTowardTheNewDay() {
        repeat(9) { counter.record() }
        day = day.plusDays(1)
        counter.record()
        assertEquals(1, counter.count())
        assertEquals(9, counter.remaining())
    }

    @Test fun theCountSurvivesANewCounterOnTheSameFlags() {
        repeat(3) { counter.record() }
        assertEquals(7, DailyCounter(flags) { day }.remaining()) // as after a restart
    }

    @Test fun setOverridesTodaysCount() {
        counter.set(7)
        assertEquals(3, counter.remaining())
    }
}
