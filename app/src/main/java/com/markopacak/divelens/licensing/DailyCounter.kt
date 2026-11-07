package com.markopacak.divelens.licensing

import com.markopacak.divelens.ui.help.HelpPrefs
import java.time.LocalDate

/** The free and full version's numbers (add-free-limits-and-purchase D4). */
object Limits {
    const val FREE_DAILY_EXPORTS = 10
    const val FREE_WORKING_SET = 10
    const val FULL_WORKING_SET = 20
}

/**
 * The free version's exports today, kept in the preferences as the epoch day and the count (add-free-limits-and-purchase
 * D4). A stored day other than today, earlier or later, counts as 0, so the count starts again at midnight and a clock
 * set back resets it (accepted). [today] is read at every call, so a batch running past midnight uses the new day.
 */
class DailyCounter(
    private val flags: HelpPrefs.Flags,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** Successful free exports today. */
    fun count(): Int {
        val day = today().toEpochDay()
        return if (flags.getLong(KEY_DAY, Long.MIN_VALUE) == day) flags.getLong(KEY_COUNT).toInt() else 0
    }

    fun remaining(): Int = (Limits.FREE_DAILY_EXPORTS - count()).coerceAtLeast(0)

    /** Counts one successful export toward today. */
    fun record() = set(count() + 1)

    /** Sets today's count; used by [record] and the debug developer panel. */
    fun set(count: Int) {
        flags.setLong(KEY_DAY, today().toEpochDay())
        flags.setLong(KEY_COUNT, count.toLong())
    }

    companion object {
        const val KEY_DAY = "exports_day"
        const val KEY_COUNT = "exports_count"
    }
}
