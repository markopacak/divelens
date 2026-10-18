package com.markopacak.divelens.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.markopacak.divelens.R
import com.markopacak.divelens.correction.PreviewEntry
import com.markopacak.divelens.correction.ViewerRenderer
import com.markopacak.divelens.importer.RejectionReason
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.ItemStatus
import com.markopacak.divelens.media.WorkingItem
import com.markopacak.divelens.media.WorkingSet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(
    state: ImportExportUiState,
    previews: Map<String, PreviewEntry<Bitmap>> = emptyMap(),
    onSelectPhotos: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onDismissSummary: () -> Unit,
    onDismissRejections: () -> Unit,
    viewer: ViewerRenderer? = null,
    onOpen: (Int) -> Unit = {},
    onCloseViewer: () -> Unit = {},
    onViewerPage: (Int) -> Unit = {},
    viewerStrength: Float? = null,
) {
    val items = state.workingSet.items
    val running = state.batch as? BatchUi.Running
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    // Back returns to the start screen; closing the activity mid-export would cancel the batch silently.
    BackHandler(enabled = items.isNotEmpty()) {
        if (running != null) confirmCancel = true else onClear()
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.empty_state), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onSelectPhotos, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.select_photos))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.weight(1f).testTag("photo_grid"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.item.source }) { index, item ->
                        PhotoCell(
                            item, previews[item.item.source], removable = running == null,
                            onOpen = { onOpen(index) }.takeIf { viewer != null }, onRemove = { onRemove(item.item.source) },
                        )
                    }
                }
                if (running != null) {
                    Text(
                        stringResource(R.string.export_progress, running.done, running.total),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    LinearProgressIndicator(
                        progress = { running.done.toFloat() / running.total },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    if (state.workingSet.isFull) {
                        Text(
                            stringResource(R.string.limit_reached, WorkingSet.LIMIT),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSelectPhotos, enabled = !state.workingSet.isFull) {
                            Text(stringResource(R.string.add_photos))
                        }
                        TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                        Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                            Text(pluralStringResource(R.plurals.export_photos, items.size, items.size))
                        }
                    }
                }
            }
        }
    }

    if (confirmCancel && running != null) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onCancel() }) { Text(stringResource(R.string.cancel_export)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.keep_exporting)) }
            },
            title = { Text(stringResource(R.string.cancel_export_title)) },
            text = { Text(stringResource(R.string.cancel_export_message)) },
        )
    }
    val viewing = state.viewing
    if (viewer != null && viewing != null && viewing in items.indices) {
        // Over the grid, so the grid keeps its scroll position; its Back handler is composed earlier and yields to this one.
        ViewerScreen(items, viewing, viewer, onViewerPage, onCloseViewer, viewerStrength)
    }
    (state.batch as? BatchUi.Finished)?.let { SummaryDialog(it, onDismissSummary) }
    if ((state.rejections.isNotEmpty() || state.leftOut > 0) && state.batch !is BatchUi.Finished) {
        AlertDialog(
            onDismissRequest = onDismissRejections,
            confirmButton = { TextButton(onClick = onDismissRejections) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.rejected_title)) },
            text = {
                Column {
                    if (state.leftOut > 0) {
                        Text(pluralStringResource(R.plurals.left_out, state.leftOut, state.leftOut, WorkingSet.LIMIT))
                    }
                    state.rejections.forEach { Text("${it.displayName}: ${stringResource(it.reason.label())}") }
                }
            },
        )
    }
}

@Composable
private fun PhotoCell(item: WorkingItem, preview: PreviewEntry<Bitmap>?, removable: Boolean, onOpen: (() -> Unit)?, onRemove: () -> Unit) {
    Column {
        Box(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier) {
            val thumbnail = (preview as? PreviewEntry.Ready)?.thumbnail
            if (thumbnail != null) {
                val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = item.item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag("corrected_thumbnail"),
                )
            } else {
                // The original until the corrected thumbnail exists, or if it cannot be made.
                AsyncImage(
                    model = item.item.source,
                    contentDescription = item.item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                val label = if (preview is PreviewEntry.Failed) R.string.preview_unavailable else R.string.preview_correcting
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.align(Alignment.TopStart)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
                }
            }
            statusLabel(item.status)?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.BottomStart)) {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
                }
            }
        }
        if (removable) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun statusLabel(status: ItemStatus): String? = when (status) {
    ItemStatus.Pending -> null
    ItemStatus.Exporting -> stringResource(R.string.status_exporting)
    ItemStatus.Exported -> stringResource(R.string.status_exported)
    is ItemStatus.Failed -> stringResource(R.string.status_failed)
    is ItemStatus.Refused -> stringResource(R.string.status_refused)
}

@Composable
private fun SummaryDialog(summary: BatchUi.Finished, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = {
            Text(stringResource(if (summary.cancelled) R.string.summary_cancelled_title else R.string.summary_title))
        },
        text = {
            Column {
                Text(pluralStringResource(R.plurals.summary_exported, summary.exported, summary.exported))
                summary.problems.forEach { problem ->
                    val reason = when (val s = problem.status) {
                        is ItemStatus.Failed -> stringResource(s.reason.label())
                        is ItemStatus.Refused -> s.message
                        else -> ""
                    }
                    Text("${problem.item.displayName}: $reason")
                }
            }
        },
    )
}

private fun FailureReason.label(): Int = when (this) {
    FailureReason.InsufficientStorage -> R.string.reason_storage
    FailureReason.UnreadableSource -> R.string.reason_unreadable
    FailureReason.OutOfMemory -> R.string.reason_memory
    FailureReason.WriteFailed -> R.string.reason_write
}

private fun RejectionReason.label(): Int = when (this) {
    RejectionReason.UnsupportedFormat -> R.string.reject_unsupported
    RejectionReason.Unreadable -> R.string.reason_unreadable
    RejectionReason.TooLarge -> R.string.reject_too_large
}
