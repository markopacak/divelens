package com.markopacak.divelens.licensing

import com.markopacak.divelens.batch.BatchEvent
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.export.ExportException
import com.markopacak.divelens.export.ExportItemUseCase
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.export.PhotoWriter
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.RefusalReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LicenceGateTest {
    private val flags = MapFlags()
    private var day = LocalDate.of(2026, 11, 7)
    private val counter = DailyCounter(flags) { day }
    private val photos = (1..10).map { MediaItem.Photo("content://p/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }

    private suspend fun batch(gate: LicenceGate, writer: PhotoWriter = PhotoWriter { p, _ -> "out/${p.source}" }) =
        BatchExporter { item, options -> ExportItemUseCase(gate, writer)(item, options) }.run(photos)
            .filterIsInstance<BatchEvent.Finished>().map { it.result }.toList()

    private fun kotlinx.coroutines.test.TestScope.licence(full: Boolean) =
        Licence(TestStore(), flags.also { it.set(Licence.KEY_FULL, full) }, backgroundScope)

    @Test fun fourLeftExportsTheFirstFourAndRefusesSix() = runTest(UnconfinedTestDispatcher()) {
        counter.set(6)
        val results = batch(LicenceGate(licence(full = false), counter))
        assertEquals(4, results.take(4).count { it is ItemResult.Exported })
        assertEquals(List(6) { ItemResult.Refused(RefusalReason.DailyLimit) }, results.drop(4))
        assertEquals(0, counter.remaining())
    }

    @Test fun aFailedExportDoesNotCount() = runTest(UnconfinedTestDispatcher()) {
        counter.set(8)
        val writer = PhotoWriter { p, _ -> if (p.source.endsWith("/1")) throw ExportException(FailureReason.InsufficientStorage) else "out" }
        val results = batch(LicenceGate(licence(full = false), counter), writer)
        assertEquals(ItemResult.Failed(FailureReason.InsufficientStorage), results[0])
        assertEquals(2, results.count { it is ItemResult.Exported }) // items 2 and 3 use the two exports left
        assertEquals(7, results.count { it is ItemResult.Refused })
    }

    @Test fun aBatchCrossingMidnightExportsTheRemainingItems() = runTest(UnconfinedTestDispatcher()) {
        counter.set(8)
        val writer = PhotoWriter { p, _ -> if (p.source.endsWith("/2")) day = day.plusDays(1); "out" }
        val results = batch(LicenceGate(licence(full = false), counter), writer)
        assertEquals(10, results.count { it is ItemResult.Exported })
        assertEquals(9, counter.count()) // item 2 finished after midnight, then items 3 to 10
    }

    @Test fun theFullVersionNeitherRefusesNorCounts() = runTest(UnconfinedTestDispatcher()) {
        counter.set(10)
        val results = batch(LicenceGate(licence(full = true), counter))
        assertEquals(10, results.count { it is ItemResult.Exported })
        assertEquals(10, counter.count())
    }
}
