package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markopacak.divelens.analysis.V1Model
import com.markopacak.divelens.batch.BatchEvent
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.correction.PhotoPreviewStore
import com.markopacak.divelens.correction.PreviewEntry
import com.markopacak.divelens.correction.ViewerRenderer
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Photos of the last selections that did not fit within [WorkingSet.LIMIT]. */
    val leftOut: Int = 0,
    /** Index in the working set of the photo shown full screen, or null when the grid is shown. */
    val viewing: Int? = null,
    val batch: BatchUi = BatchUi.Idle,
)

class ImportExportViewModel(
    private val importPhotos: suspend (List<Uri>) -> ImportResult,
    private val batchExporter: BatchExporter,
    /** Corrected thumbnails and corrections per photo; follows the working set. */
    private val previewStore: PhotoPreviewStore<Bitmap>? = null,
    /** Renders the full-screen viewer; null disables it. */
    val viewer: ViewerRenderer? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportExportUiState())
    val state: StateFlow<ImportExportUiState> = _state.asStateFlow()

    val previews: StateFlow<Map<String, PreviewEntry<Bitmap>>> = previewStore?.entries ?: MutableStateFlow(emptyMap())

    /** Strengths the user set per photo (add-strength-slider D2); photos without one are at [defaultStrength]. */
    val strengths: StateFlow<Map<String, Float>> = previewStore?.strengths ?: MutableStateFlow(emptyMap())
    val defaultStrength: Float = previewStore?.defaultStrength ?: V1Model.DEFAULT_STRENGTH

    /** Whether the viewer offers the strength panel: only with a store that applies it to the export. */
    val adjustable: Boolean get() = previewStore != null

    init {
        if (previewStore != null) {
            viewModelScope.launch {
                _state.map { s -> s.workingSet.items.map { it.item.source } }.distinctUntilChanged().collect { previewStore.setPhotos(it) }
            }
        }
    }

    private var batchJob: Job? = null

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val result = importPhotos(uris)
            _state.update {
                val added = it.workingSet.addWithinLimit(result.accepted)
                it.copy(
                    importing = false,
                    workingSet = added.set,
                    rejections = it.rejections + result.rejected,
                    leftOut = it.leftOut + added.leftOut,
                )
            }
        }
    }

    fun openViewer(index: Int) = _state.update { if (index in it.workingSet.items.indices) it.copy(viewing = index) else it }

    fun closeViewer() = _state.update { it.copy(viewing = null) }

    /** The viewer shows [index]: prepare it and its neighbours first and keep their corrections. */
    fun onViewerPage(index: Int) {
        val items = _state.value.workingSet.items
        val sources = listOf(index, index + 1, index - 1).filter { it in items.indices }.map { items[it].item.source }
        previewStore?.let { store -> viewModelScope.launch { store.prioritize(sources) } }
    }

    /** A photo exported during a batch uses the strength it has when its turn comes. */
    fun setStrength(source: String, strength: Float) {
        previewStore?.setStrength(source, strength)
    }

    fun applyStrengthToAll(strength: Float) {
        previewStore?.let { store -> viewModelScope.launch { store.setAllStrengths(strength) } }
    }

    fun dismissRejections() = _state.update { it.copy(rejections = emptyList(), leftOut = 0) }

    fun remove(source: String) {
        if (isRunning()) return
        _state.update { it.copy(workingSet = it.workingSet.remove(source), viewing = null) }
    }

    fun clear() {
        if (isRunning()) return
        _state.update { it.copy(workingSet = it.workingSet.clear(), batch = BatchUi.Idle, viewing = null) }
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
