package com.markopacak.divelens.batch

import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchExporterTest {
    private val items = (1..5).map { MediaItem.Photo("content://p/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }

    @Test fun progressRunsFromOneToN() = runTest {
        val events = BatchExporter { it, _ -> ItemResult.Exported("out/${it.source}") }.run(items).toList()
        val finished = events.filterIsInstance<BatchEvent.Finished>()
        assertEquals((1..5).toList(), finished.map { it.done })
        assertEquals(List(5) { 5 }, finished.map { it.total })
    }

    @Test fun exportsOneAtATimeInOrder() = runTest {
        val log = mutableListOf<String>()
        BatchExporter { it, _ -> log += "start ${it.source}"; log += "end ${it.source}"; ItemResult.Exported("") }.run(items).toList()
        assertEquals(items.flatMap { listOf("start ${it.source}", "end ${it.source}") }, log)
    }

    @Test fun oneFailureDoesNotStopTheBatch() = runTest {
        val events = BatchExporter { it, _ ->
            if (it.source.endsWith("/2")) ItemResult.Failed(FailureReason.UnreadableSource) else ItemResult.Exported("")
        }.run(items).toList()
        val results = events.filterIsInstance<BatchEvent.Finished>().map { it.result }
        assertEquals(5, results.size)
        assertEquals(ItemResult.Failed(FailureReason.UnreadableSource), results[1])
        assertEquals(4, results.count { it is ItemResult.Exported })
    }

    @Test fun cancelDuringThirdItemKeepsFirstTwoAndSkipsTheRest() = runTest {
        val third = CompletableDeferred<ItemResult>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val job = launch {
            BatchExporter { it, _ ->
                started += it.source
                if (it.source.endsWith("/3")) third.await() else ItemResult.Exported("")
            }.run(items).collect { if (it is BatchEvent.Finished) finished += it.item.source }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf("content://p/1", "content://p/2", "content://p/3"), started)
        assertEquals(listOf("content://p/1", "content://p/2"), finished)
    }
}
