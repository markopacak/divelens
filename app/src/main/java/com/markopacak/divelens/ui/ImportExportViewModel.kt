package com.markopacak.divelens.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markopacak.divelens.batch.BatchEvent
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.importer.ImportResult
import com.markopacak.divelens.importer.Rejection
import com.markopacak.divelens.media.ItemStatus
import com.markopacak.divelens.media.WorkingItem
import com.markopacak.divelens.media.WorkingSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BatchUi {
    data object Idle : BatchUi
    data class Running(val done: Int, val total: Int) : BatchUi
    data class Finished(val exported: Int, val problems: List<WorkingItem>, val cancelled: Boolean) : BatchUi
}

data class ImportExportUiState(
    val workingSet: WorkingSet = WorkingSet(),
    val importing: Boolean = false,
    val rejections: List<Rejection> = emptyList(),
    val batch: BatchUi = BatchUi.Idle,
)

class ImportExportViewModel(
    private val importPhotos: suspend (List<Uri>) -> ImportResult,
    private val batchExporter: BatchExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportExportUiState())
    val state: StateFlow<ImportExportUiState> = _state.asStateFlow()

    private var batchJob: Job? = null

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val result = importPhotos(uris)
            _state.update {
                it.copy(
                    importing = false,
                    workingSet = it.workingSet.add(result.accepted),
                    rejections = it.rejections + result.rejected,
                )
            }
        }
    }

    fun dismissRejections() = _state.update { it.copy(rejections = emptyList()) }

    fun remove(source: String) {
        if (isRunning()) return
        _state.update { it.copy(workingSet = it.workingSet.remove(source)) }
    }

    fun clear() {
        if (isRunning()) return
        _state.update { it.copy(workingSet = it.workingSet.clear(), batch = BatchUi.Idle) }
    }

    fun export() {
        if (isRunning()) return
        val items = _state.value.workingSet.items.map { it.item }
        if (items.isEmpty()) return
        _state.update { s ->
            var set = s.workingSet
            items.forEach { set = set.withStatus(it.source, ItemStatus.Pending) }
            s.copy(workingSet = set, batch = BatchUi.Running(0, items.size))
        }
        batchJob = viewModelScope.launch {
            var cancelled = false
            try {
                batchExporter.run(items).collect { event -> _state.update { it.apply(event) } }
            } catch (e: CancellationException) {
                cancelled = true
            }
            _state.update { it.finish(cancelled) }
        }
    }

    fun cancel() {
        batchJob?.cancel()
    }

    fun dismissSummary() = _state.update { it.copy(batch = BatchUi.Idle) }

    private fun isRunning() = _state.value.batch is BatchUi.Running

    private fun ImportExportUiState.apply(event: BatchEvent): ImportExportUiState = when (event) {
        is BatchEvent.Started -> copy(workingSet = workingSet.withStatus(event.item.source, ItemStatus.Exporting))
        is BatchEvent.Finished -> copy(
            workingSet = workingSet.withStatus(event.item.source, event.result.toStatus()),
            batch = BatchUi.Running(event.done, event.total),
        )
    }

    private fun ImportExportUiState.finish(cancelled: Boolean): ImportExportUiState {
        // Items still exporting when cancelled go back to pending: nothing was written for them.
        val set = WorkingSet(workingSet.items.map {
            if (it.status == ItemStatus.Exporting) it.copy(status = ItemStatus.Pending) else it
        })
        val exported = set.items.count { it.status == ItemStatus.Exported }
        val problems = set.items.filter { it.status is ItemStatus.Failed || it.status is ItemStatus.Refused }
        return copy(workingSet = set, batch = BatchUi.Finished(exported, problems, cancelled))
    }

    private fun ItemResult.toStatus(): ItemStatus = when (this) {
        is ItemResult.Exported -> ItemStatus.Exported
        is ItemResult.Refused -> ItemStatus.Refused(message)
        is ItemResult.Failed -> ItemStatus.Failed(reason)
    }
}
