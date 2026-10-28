package com.markopacak.divelens.batch

import com.markopacak.divelens.export.ExportOptions
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of the batch, for the screen and the export service's notification. */
sealed interface ExportProgress {
    data object Idle : ExportProgress
    data class Running(val done: Int, val total: Int) : ExportProgress
    data class Finished(val exported: Int, val total: Int, val cancelled: Boolean) : ExportProgress
}

/**
 * Runs the export batch in an app-wide [scope] (add-video-processing D8), so it survives the activity and its view model;
 * the export service shows [progress] and can [cancel] it from the notification. [options] is read once per batch, when
 * it starts (add-branding-and-watermark D8).
 */
class ExportController(
    private val batchExporter: BatchExporter,
    private val scope: CoroutineScope,
    private val options: () -> ExportOptions = { ExportOptions.PLAIN },
) {
    private val _progress = MutableStateFlow<ExportProgress>(ExportProgress.Idle)
    val progress: StateFlow<ExportProgress> = _progress.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _progress.value is ExportProgress.Running

    /**
     * Starts exporting [items] unless a batch is running. [onEvent] sees every item start and finish, [onEnd] the end,
     * cancelled or not; both run in the controller's scope.
     */
    fun start(items: List<MediaItem>, onEvent: (BatchEvent) -> Unit = {}, onEnd: (cancelled: Boolean) -> Unit = {}): Boolean {
        if (isRunning || items.isEmpty()) return false
        _progress.value = ExportProgress.Running(0, items.size)
        val batchOptions = options()
        job = scope.launch {
            var cancelled = false
            var exported = 0
            try {
                batchExporter.run(items, batchOptions).collect { event ->
                    if (event is BatchEvent.Finished) {
                        if (event.result is ItemResult.Exported) exported++
                        _progress.value = ExportProgress.Running(event.done, event.total)
                    }
                    onEvent(event)
                }
            } catch (e: CancellationException) {
                cancelled = true
            }
            _progress.value = ExportProgress.Finished(exported, items.size, cancelled)
            onEnd(cancelled)
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to idle once the summary was seen. */
    fun acknowledge() {
        if (_progress.value is ExportProgress.Finished) _progress.value = ExportProgress.Idle
    }
}
