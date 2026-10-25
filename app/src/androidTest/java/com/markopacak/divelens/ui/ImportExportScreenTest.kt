package com.markopacak.divelens.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.correction.PreviewEntry
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.ItemStatus
import com.markopacak.divelens.importer.Rejection
import com.markopacak.divelens.importer.RejectionReason
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.WorkingItem
import com.markopacak.divelens.media.WorkingSet
import com.markopacak.divelens.ui.help.HelpActions
import com.markopacak.divelens.ui.help.SettingsHelpScreen
import com.markopacak.divelens.ui.theme.DiveLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ImportExportScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val photos = (1..3).map { MediaItem.Photo("content://test/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
    private val events = mutableListOf<String>()

    private var state by mutableStateOf(ImportExportUiState())
    private var previews by mutableStateOf<Map<String, PreviewEntry<Bitmap>>>(emptyMap())

    private fun setScreen() {
        rule.setContent {
            DiveLensTheme {
                startScreen(onOpenSettings = { events += "settings" })
            }
        }
    }

    @Composable
    private fun startScreen(onOpenSettings: () -> Unit) {
        ImportExportScreen(
            state = state,
            previews = previews,
            onSelectPhotos = { events += "select" },
            onRemove = { events += "remove $it" },
            onClear = { events += "clear" },
            onExport = { events += "export" },
            onCancel = { events += "cancel" },
            onDismissSummary = { events += "dismiss" },
            onDismissRejections = { events += "dismissRejections" },
            onOpenSettings = onOpenSettings,
        )
    }

    /** The start screen and Settings & help as the app switches between them (add-help-and-legal D1, task 3.4). */
    private fun setApp() {
        rule.setContent {
            DiveLensTheme {
                AppScreens(
                    settingsHelp = { back -> SettingsHelpScreen("1.0", HelpActions({ true }, { true }, {}), onBack = back) },
                ) { openSettings -> startScreen(openSettings) }
            }
        }
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun emptyToSelectedToExportingToSummary() {
        setScreen()
        rule.onNodeWithText("Select photos and videos").assertIsDisplayed().performClick()

        state = ImportExportUiState(workingSet = WorkingSet().add(photos))
        rule.onNodeWithTag("photo_grid").assertIsDisplayed()
        rule.onNodeWithText("Export 3 items").assertIsDisplayed().performClick()

        state = state.copy(
            workingSet = state.workingSet.withStatus(photos[0].source, ItemStatus.Exported)
                .withStatus(photos[1].source, ItemStatus.Exporting),
            batch = BatchUi.Running(done = 1, total = 3),
        )
        rule.onNodeWithText("Exporting 2 of 3").assertIsDisplayed() // the item in progress, as in the notification
        rule.onNodeWithTag("export_spinner").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()

        val failed = WorkingItem(photos[2], ItemStatus.Failed(FailureReason.InsufficientStorage))
        state = state.copy(batch = BatchUi.Finished(exported = 2, problems = listOf(failed), cancelled = false))
        rule.onNodeWithText("Export finished").assertIsDisplayed()
        rule.onNodeWithText("2 items saved to the gallery.").assertIsDisplayed()
        rule.onNodeWithText("IMG_3.jpg: not enough storage").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()

        assertEquals(listOf("select", "export", "cancel", "dismiss"), events)
    }

    @Test fun settingsOpenFromTheTopBarAndBackReturnsWithTheSetUnchanged() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos))
        setApp()
        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("settings_help").assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag("photo_grid").assertIsDisplayed()
        rule.onNodeWithText("Export 3 items").assertIsDisplayed()
        assertEquals(emptyList<String>(), events) // back left Settings & help without clearing the set
    }

    @Test fun settingsOpenDuringAnExportAndTheProgressIsStillShownAfter() {
        state = ImportExportUiState(
            workingSet = WorkingSet().add(photos).withStatus(photos[0].source, ItemStatus.Exporting),
            batch = BatchUi.Running(done = 0, total = 3),
        )
        setApp()
        rule.onNodeWithTag("open_settings").assertIsDisplayed().performClick()
        rule.onNodeWithTag("settings_help").assertIsDisplayed()
        state = state.copy(batch = BatchUi.Running(done = 1, total = 3)) // the export moves on meanwhile
        pressBack()
        rule.onNodeWithText("Exporting 2 of 3").assertIsDisplayed()
        assertEquals(emptyList<String>(), events) // no cancel
    }

    @Test fun backOnStartScreenIsLeftToTheSystem() {
        setScreen()
        rule.waitForIdle()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun backWithPhotosSelectedClearsTheWorkingSet() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos))
        setScreen()
        pressBack()
        rule.waitForIdle()
        assertEquals(listOf("clear"), events)
    }

    @Test fun backDuringExportAsksBeforeCancelling() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos), batch = BatchUi.Running(done = 1, total = 3))
        setScreen()

        pressBack()
        rule.onNodeWithText("Cancel export?").assertIsDisplayed()
        rule.onNodeWithText("Keep exporting").performClick()
        rule.onAllNodesWithText("Cancel export?").assertCountEquals(0)
        assertEquals(emptyList<String>(), events)

        pressBack()
        rule.onNodeWithText("Cancel export").performClick()
        rule.onAllNodesWithText("Cancel export?").assertCountEquals(0)
        assertEquals(listOf("cancel"), events)
    }

    @Test fun fullSetDisablesAddAndExplainsTheLimit() {
        val many = (1..WorkingSet.LIMIT).map { MediaItem.Photo("content://test/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
        state = ImportExportUiState(workingSet = WorkingSet().add(many))
        setScreen()
        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithText("A batch holds at most 20 items. Remove some or clear the set to add more.").assertIsDisplayed()

        state = state.copy(workingSet = state.workingSet.remove(many[0].source))
        rule.onNodeWithText("Add").assertIsEnabled()
    }

    @Test fun leftOutPhotosAreReported() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos), leftOut = 3)
        setScreen()
        rule.onNodeWithText("3 items were left out. A batch holds at most 20 items.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismissRejections"), events)
    }

    @Test fun videosShowTheirDurationAndEveryDatedItemACaption() {
        val dated = MediaItem.Photo("content://test/d", "IMG_d.jpg", "image/jpeg", 10, 10, LocalDateTime.of(2026, 7, 14, 10, 32))
        val clip = MediaItem.Video(
            "content://test/v", "VID.mp4", "video/mp4", 1920, 1080, durationUs = 42_500_000, frameRate = 30f, codec = "video/avc",
            creationTimeMillis = Instant.parse("2026-10-04T15:32:34Z").toEpochMilli(),
        )
        state = ImportExportUiState(workingSet = WorkingSet().add(listOf(photos[0], dated, clip)))
        setScreen()
        rule.onNodeWithText("▶ 0:42").assertIsDisplayed()
        rule.onAllNodesWithTag("duration_badge").assertCountEquals(1)
        // The undated photo has no caption; the others show the date in the device's language and zone.
        rule.onAllNodesWithTag("caption").assertCountEquals(2)
        rule.onNodeWithText(MediaLabels.caption(dated)!!).assertIsDisplayed()
        rule.onNodeWithText(MediaLabels.caption(clip)!!).assertIsDisplayed()
    }

    @Test fun pickedItemsShowThatTheyAreBeingAdded() {
        state = ImportExportUiState(importing = true)
        setScreen()
        rule.onNodeWithTag("importing").assertIsDisplayed()
        rule.onNodeWithText("Adding photos and videos…").assertIsDisplayed()
        rule.onNodeWithText("Select photos and videos").assertIsNotEnabled()
        state = state.copy(importing = false, workingSet = WorkingSet().add(photos))
        rule.onAllNodesWithTag("importing").assertCountEquals(0)
    }

    @Test fun itemsWaitingInARunningExportAreQueued() {
        val set = WorkingSet().add(photos).withStatus(photos[0].source, ItemStatus.Exported).withStatus(photos[1].source, ItemStatus.Exporting)
        state = ImportExportUiState(workingSet = set, batch = BatchUi.Running(1, 3))
        setScreen()
        rule.onNodeWithText("Exported").assertIsDisplayed()
        rule.onNodeWithText("Exporting…").assertIsDisplayed()
        rule.onAllNodesWithTag("exporting_spinner").assertCountEquals(1)
        rule.onNodeWithText("Queued").assertIsDisplayed()
        // Without a running export, a pending item has no label.
        state = state.copy(workingSet = WorkingSet().add(photos), batch = BatchUi.Idle)
        rule.onAllNodesWithText("Queued").assertCountEquals(0)
    }

    @Test fun rejectedVideosNameTheFileAndTheReason() {
        state = ImportExportUiState(
            workingSet = WorkingSet().add(photos),
            rejections = listOf(
                Rejection("dive8k.mp4", RejectionReason.TooLargeForDevice),
                Rejection("clip.webm", RejectionReason.UnsupportedFormat),
            ),
        )
        setScreen()
        rule.onNodeWithText("dive8k.mp4: video is too large for this device").assertIsDisplayed()
        rule.onNodeWithText("clip.webm: format not supported (photos: JPEG, HEIC; videos: MP4, MOV in H.264 or HEVC)").assertIsDisplayed()
    }

    @Test fun thumbnailShowsCorrectingThenCorrectedOrUnavailable() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos.take(1)))
        setScreen()
        rule.onNodeWithText("Correcting…").assertIsDisplayed()
        rule.onAllNodesWithTag("corrected_thumbnail").assertCountEquals(0)

        val thumb = Bitmap.createBitmap(384, 288, Bitmap.Config.ARGB_8888)
        previews = mapOf(photos[0].source to PreviewEntry.Ready(CorrectionParams.IDENTITY, 10, 10, 0, thumb))
        rule.onNodeWithTag("corrected_thumbnail").assertIsDisplayed()
        rule.onAllNodesWithText("Correcting…").assertCountEquals(0)

        previews = mapOf(photos[0].source to PreviewEntry.Failed(IllegalStateException("decode")))
        rule.onNodeWithText("Preview unavailable").assertIsDisplayed()
        rule.onAllNodesWithTag("corrected_thumbnail").assertCountEquals(0)
    }
}
