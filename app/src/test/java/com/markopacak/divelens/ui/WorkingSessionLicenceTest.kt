package com.markopacak.divelens.ui

import android.net.Uri
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.batch.ExportController
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.importer.ImportResult
import com.markopacak.divelens.licensing.LicenceState
import com.markopacak.divelens.media.ItemStatus
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.RefusalReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The working set and the start screen state follow the licence (add-free-limits-and-purchase D6). */
class WorkingSessionLicenceTest {
    private fun photos(range: IntRange) = range.map { MediaItem.Photo("content://p/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
    private val licence = MutableStateFlow(LicenceState(full = false))
    private var remaining = 10

    private fun TestScope.session(picked: MutableList<List<MediaItem>>): WorkingSession {
        val controller = ExportController(BatchExporter { _, _ -> remaining--; ItemResult.Exported("out") }, backgroundScope)
        return WorkingSession(backgroundScope, { ImportResult(picked.removeAt(0), emptyList()) }, controller, licence = licence, remainingToday = { remaining })
            .also { runCurrent() }
    }

    private fun TestScope.pick(session: WorkingSession) { session.onPicked(listOf(Uri.EMPTY)); runCurrent() }

    @Test fun freeSevenPlusFiveKeepsTenAndLeavesTwoOut() = runTest {
        val session = session(mutableListOf(photos(1..7), photos(8..12)))
        pick(session); pick(session)
        val state = session.state.value
        assertEquals(10, state.workingSet.items.size)
        assertEquals(2, state.leftOut)
        assertEquals(10, state.limit)
        assertTrue(state.free)
        assertTrue(state.workingSet.isFull(state.limit))
    }

    @Test fun theFullVersionHoldsTwenty() = runTest {
        licence.value = LicenceState(full = true)
        val session = session(mutableListOf(photos(1..15), photos(16..23)))
        pick(session); pick(session)
        val state = session.state.value
        assertEquals(20, state.workingSet.items.size)
        assertEquals(3, state.leftOut)
        assertFalse(state.free)
        assertNull(state.remainingToday)
    }

    @Test fun losingTheFullVersionKeepsTwentyItemsAndStopsAdding() = runTest {
        licence.value = LicenceState(full = true)
        val session = session(mutableListOf(photos(1..20), photos(21..21)))
        pick(session)
        licence.value = LicenceState(full = false) // refunded
        runCurrent()
        assertEquals(20, session.state.value.workingSet.items.size)
        assertEquals(10, session.state.value.limit)
        assertTrue(session.state.value.workingSet.isFull(10))
        pick(session)
        assertEquals(20, session.state.value.workingSet.items.size)
        assertEquals(1, session.state.value.leftOut)
    }

    @Test fun nothingLeftShowsTheNoticeAndStartsNothing() = runTest {
        remaining = 0
        val session = session(mutableListOf(photos(1..3)))
        pick(session)
        session.export()
        runCurrent()
        assertTrue(session.state.value.dailyLimitNotice)
        assertEquals(BatchUi.Idle, session.state.value.batch)
        assertEquals(0, remaining) // nothing exported
        session.dismissDailyLimit()
        assertFalse(session.state.value.dailyLimitNotice)
    }

    @Test fun fourLeftExportsFourAndKeepsTheOtherSixMarked() = runTest {
        remaining = 4
        val controller = ExportController(
            BatchExporter { _, _ -> if (remaining > 0) { remaining--; ItemResult.Exported("out") } else ItemResult.Refused(RefusalReason.DailyLimit) },
            backgroundScope,
        )
        val picked = mutableListOf(photos(1..10))
        val session = WorkingSession(backgroundScope, { ImportResult(picked.removeAt(0), emptyList()) }, controller, licence = licence, remainingToday = { remaining })
        runCurrent()
        pick(session)
        session.export()
        runCurrent()
        val state = session.state.value
        val finished = state.batch as BatchUi.Finished
        assertEquals(4, finished.exported)
        assertEquals(6, finished.problems.size)
        assertEquals(10, state.workingSet.items.size)
        assertEquals(List(6) { ItemStatus.Refused(RefusalReason.DailyLimit) }, state.workingSet.items.drop(4).map { it.status })
        assertEquals(0, state.remainingToday)
    }

    @Test fun theFullVersionNeverShowsTheNotice() = runTest {
        licence.value = LicenceState(full = true)
        remaining = 0
        val session = session(mutableListOf(photos(1..3)))
        pick(session)
        session.export()
        runCurrent()
        assertFalse(session.state.value.dailyLimitNotice)
        assertTrue(session.state.value.batch is BatchUi.Finished)
    }

    @Test fun theRemainingCountFollowsABatch() = runTest {
        val session = session(mutableListOf(photos(1..3)))
        assertEquals(10, session.state.value.remainingToday)
        pick(session)
        session.export()
        runCurrent()
        assertEquals(7, session.state.value.remainingToday)
    }
}
