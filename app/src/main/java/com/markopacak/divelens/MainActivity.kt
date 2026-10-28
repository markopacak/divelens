package com.markopacak.divelens

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markopacak.divelens.ui.AppScreens
import com.markopacak.divelens.ui.ImportExportScreen
import com.markopacak.divelens.ui.ImportExportViewModel
import com.markopacak.divelens.ui.StrengthControls
import com.markopacak.divelens.ui.help.HelpIntents
import com.markopacak.divelens.ui.help.SettingsHelpScreen
import com.markopacak.divelens.ui.help.ViewerGuidance
import com.markopacak.divelens.ui.help.WatermarkSetting
import com.markopacak.divelens.ui.theme.DarkColors
import com.markopacak.divelens.ui.theme.DiveLensTheme
import com.markopacak.divelens.ui.theme.LightColors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme's window background is the launch screen; behind the app it is the screen colour, so page
        // transitions never show the launch picture (add-branding-and-watermark D2).
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable((if (night) DarkColors else LightColors).background.toArgb().toDrawable())
        enableEdgeToEdge()
        setContent {
            DiveLensTheme {
                val graph = (application as DiveLensApp).graph
                val vm: ImportExportViewModel = viewModel(factory = viewModelFactory { initializer { ImportExportViewModel(graph.session) } })
                val state by vm.state.collectAsStateWithLifecycle()
                val previews by vm.previews.collectAsStateWithLifecycle()
                val strengths by vm.strengths.collectAsStateWithLifecycle()
                val viewer = remember(vm) { vm.viewer }
                val room = state.workingSet.room
                val contract = remember(room) { ActivityResultContracts.PickMultipleVisualMedia(pickerMaxItems(room)) }
                val picker = rememberLauncherForActivityResult(contract) { vm.onPicked(it) }
                // The notification permission is asked once, at the first export; the export runs whatever the answer.
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.export() }
                val context = LocalContext.current
                val version = remember { HelpIntents.appVersion(context) }
                val pendingTips by graph.help.pending.collectAsStateWithLifecycle()
                val helpActions = remember(context) { HelpIntents.actions(context, version, graph.help::resetTips) }
                AppScreens(
                    settingsHelp = { back ->
                        SettingsHelpScreen(appVersion = version, actions = helpActions, onBack = back, settings = {
                            val watermark by graph.exportPrefs.watermark.collectAsStateWithLifecycle()
                            WatermarkSetting(on = watermark, onChange = graph.exportPrefs::setWatermark)
                        })
                    },
                ) { openSettings ->
                    ImportExportScreen(
                        state = state,
                        previews = previews,
                        onSelectPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onRemove = vm::remove,
                        onClear = vm::clear,
                        onExport = { if (askForNotifications()) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.export() },
                        onCancel = vm::cancel,
                        onDismissSummary = vm::dismissSummary,
                        onDismissRejections = vm::dismissRejections,
                        viewer = viewer,
                        onOpen = vm::openViewer,
                        onCloseViewer = vm::closeViewer,
                        onViewerPage = vm::onViewerPage,
                        strengths = if (vm.adjustable) StrengthControls(strengths, vm.defaultStrength, vm::setStrength, vm::applyStrengthToAll) else null,
                        videos = vm.videos,
                        onOpenSettings = openSettings,
                        guidance = ViewerGuidance(pendingTips, graph.help::done),
                    )
                }
            }
        }
    }

    /** True the first time an export starts on Android 13+ without the notification permission (add-video-processing D8). */
    private fun askForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val prefs = getSharedPreferences(AppGraph.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return false
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        return true
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

    private companion object {
        const val ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
