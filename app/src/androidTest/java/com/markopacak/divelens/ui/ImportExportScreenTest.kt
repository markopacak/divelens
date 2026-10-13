package com.markopacak.divelens.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun setScreen() {
        rule.setContent {
            DiveLensTheme {
                ImportExportScreen(
                    state = state,
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
}
