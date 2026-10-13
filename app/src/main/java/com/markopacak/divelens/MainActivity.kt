package com.markopacak.divelens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.export.AlwaysAllowExportGate
import com.markopacak.divelens.export.ExportItemUseCase
import com.markopacak.divelens.export.IdentityPhotoProcessor
import com.markopacak.divelens.export.MediaStorePhotoWriter
import com.markopacak.divelens.importer.PhotoImporter
import com.markopacak.divelens.ui.ImportExportScreen
import com.markopacak.divelens.ui.ImportExportViewModel
import com.markopacak.divelens.ui.theme.DiveLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiveLensTheme {
                val vm: ImportExportViewModel = viewModel(factory = viewModelFactory { initializer { createViewModel() } })
                val state by vm.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
                    vm.onPicked(it)
                }
                ImportExportScreen(
                    state = state,
                    onSelectPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemove = vm::remove,
                    onClear = vm::clear,
                    onExport = vm::export,
                    onCancel = vm::cancel,
                    onDismissSummary = vm::dismissSummary,
                    onDismissRejections = vm::dismissRejections,
                )
            }
        }
    }

    /** Manual wiring. Later changes swap the processor (auto-correction) and the gate (licensing). */
    private fun createViewModel(): ImportExportViewModel {
        val resolver = applicationContext.contentResolver
        val importer = PhotoImporter(resolver)
        val writer = MediaStorePhotoWriter(resolver, applicationContext.cacheDir, IdentityPhotoProcessor)
        val exportItem = ExportItemUseCase(AlwaysAllowExportGate, writer)
        return ImportExportViewModel(importer::import, BatchExporter { exportItem(it) })
    }
}
