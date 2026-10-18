package com.markopacak.divelens.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
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
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.WorkingItem
import com.markopacak.divelens.media.WorkingSet
import com.markopacak.divelens.ui.theme.DiveLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ImportExportScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val photos = (1..3).map { MediaItem.Photo("content://test/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
    private val events = mutableListOf<String>()

    private var state by mutableStateOf(ImportExportUiState())
    private var previews by mutableStateOf<Map<String, PreviewEntry<Bitmap>>>(emptyMap())

    private fun setScreen() {
        rule.setContent {
            DiveLensTheme {
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
                )
            }
        }
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun emptyToSelectedToExportingToSummary() {
        setScreen()
        rule.onNodeWithText("Select photos").assertIsDisplayed().performClick()

        state = ImportExportUiState(workingSet = WorkingSet().add(photos))
        rule.onNodeWithTag("photo_grid").assertIsDisplayed()
        rule.onNodeWithText("Export 3 photos").assertIsDisplayed().performClick()

        state = state.copy(
            workingSet = state.workingSet.withStatus(photos[0].source, ItemStatus.Exported)
                .withStatus(photos[1].source, ItemStatus.Exporting),
            batch = BatchUi.Running(done = 1, total = 3),
        )
        rule.onNodeWithText("Exporting 1 of 3").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()

        val failed = WorkingItem(photos[2], ItemStatus.Failed(FailureReason.InsufficientStorage))
        state = state.copy(batch = BatchUi.Finished(exported = 2, problems = listOf(failed), cancelled = false))
        rule.onNodeWithText("Export finished").assertIsDisplayed()
        rule.onNodeWithText("2 photos saved to the gallery.").assertIsDisplayed()
        rule.onNodeWithText("IMG_3.jpg: not enough storage").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()

        assertEquals(listOf("select", "export", "cancel", "dismiss"), events)
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
        rule.onNodeWithText("A batch holds at most 50 photos. Remove some or clear the set to add more.").assertIsDisplayed()

        state = state.copy(workingSet = state.workingSet.remove(many[0].source))
        rule.onNodeWithText("Add").assertIsEnabled()
    }

    @Test fun leftOutPhotosAreReported() {
        state = ImportExportUiState(workingSet = WorkingSet().add(photos), leftOut = 3)
        setScreen()
        rule.onNodeWithText("3 photos were left out. A batch holds at most 50 photos.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismissRejections"), events)
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
