package com.markopacak.divelens.batch

import com.markopacak.divelens.export.ExportOptions
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface BatchEvent {
    data class Started(val item: MediaItem, val index: Int, val total: Int) : BatchEvent
    data class Finished(val item: MediaItem, val result: ItemResult, val done: Int, val total: Int) : BatchEvent
}

/**
 * Exports items strictly one at a time, all with the same [ExportOptions]. Cancelling the collector stops the batch: the item
 * in progress is cleaned up by its writer and later items are not started.
 */
class BatchExporter(private val exportItem: suspend (MediaItem, ExportOptions) -> ItemResult) {

    fun run(items: List<MediaItem>, options: ExportOptions = ExportOptions.PLAIN): Flow<BatchEvent> = flow {
        items.forEachIndexed { index, item ->
            emit(BatchEvent.Started(item, index, items.size))
            emit(BatchEvent.Finished(item, exportItem(item, options), index + 1, items.size))
        }
    }
}
