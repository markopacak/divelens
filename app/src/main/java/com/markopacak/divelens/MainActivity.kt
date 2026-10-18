package com.markopacak.divelens

import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.correction.AndroidPhotoPreparer
import com.markopacak.divelens.correction.CorrectionPhotoProcessor
import com.markopacak.divelens.correction.GpuViewerRenderer
import com.markopacak.divelens.correction.PhotoPreviewStore
import com.markopacak.divelens.export.AlwaysAllowExportGate
import com.markopacak.divelens.export.ExportItemUseCase
import com.markopacak.divelens.export.MediaStorePhotoWriter
import com.markopacak.divelens.importer.PhotoImporter
import com.markopacak.divelens.ui.ImportExportScreen
import com.markopacak.divelens.ui.ImportExportViewModel
import com.markopacak.divelens.ui.theme.DiveLensTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiveLensTheme {
                val vm: ImportExportViewModel = viewModel(factory = viewModelFactory { initializer { createViewModel() } })
                val state by vm.state.collectAsStateWithLifecycle()
                val previews by vm.previews.collectAsStateWithLifecycle()
                val viewer = remember(vm) { vm.viewer }
                val room = state.workingSet.room
                val contract = remember(room) { ActivityResultContracts.PickMultipleVisualMedia(pickerMaxItems(room)) }
                val picker = rememberLauncherForActivityResult(contract) { vm.onPicked(it) }
                ImportExportScreen(
                    state = state,
                    previews = previews,
                    onSelectPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemove = vm::remove,
                    onClear = vm::clear,
                    onExport = vm::export,
                    onCancel = vm::cancel,
                    onDismissSummary = vm::dismissSummary,
                    onDismissRejections = vm::dismissRejections,
                    viewer = viewer,
                    onOpen = vm::openViewer,
                    onCloseViewer = vm::closeViewer,
                    onViewerPage = vm::onViewerPage,
                )
            }
        }
    }

    /** Manual wiring. A later change swaps the gate (licensing). */
    private fun createViewModel(): ImportExportViewModel {
        val resolver = applicationContext.contentResolver
        val importer = PhotoImporter(resolver)
        val previewScope = CoroutineScope(SupervisorJob())
        val store = PhotoPreviewStore(AndroidPhotoPreparer(resolver), previewScope)
        // The export applies the correction the preview shows.
        val writer = MediaStorePhotoWriter(resolver, applicationContext.cacheDir, CorrectionPhotoProcessor(store::correction))
        val exportItem = ExportItemUseCase(AlwaysAllowExportGate, writer)
        val viewer = GpuViewerRenderer(resolver, store::correction)
        return ImportExportViewModel(importer::import, BatchExporter { exportItem(it) }, store, viewer).apply {
            addCloseable { previewScope.cancel() }
        }
    }

    /**
     * The picker's selection limit for the working set's remaining room. The contract needs more than 1, and the
     * system picker refuses more than its own limit; the view model drops whatever does not fit.
     */
    private fun pickerMaxItems(room: Int): Int {
        val systemPicker = Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)
        val systemLimit = if (systemPicker) MediaStore.getPickImagesMaxLimit() else Int.MAX_VALUE
        return room.coerceAtMost(systemLimit).coerceAtLeast(2)
    }
}
