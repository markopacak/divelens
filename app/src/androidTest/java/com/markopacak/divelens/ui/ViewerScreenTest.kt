package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.markopacak.divelens.correction.Layer
import com.markopacak.divelens.correction.ViewerPhoto
import com.markopacak.divelens.correction.ViewerRenderer
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.WorkingSet
import com.markopacak.divelens.ui.theme.DiveLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test

class ViewerScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val photos = (1..5).map { MediaItem.Photo("content://test/$it", "IMG_$it.jpg", "image/jpeg", 400, 300) }
    private var state by mutableStateOf(ImportExportUiState(workingSet = WorkingSet().add(photos)))
    private val pages = mutableListOf<Int>()
    private var strengths by mutableStateOf(emptyMap<String, Float>())
    private val default = 0.78f

    private fun controls() = StrengthControls(
        strengths, default,
        onChange = { source, s -> strengths = strengths + (source to (s * 100).roundToInt() / 100f) },
        onApplyToAll = { s -> strengths = photos.associate { it.source to s } },
    )

    /** Solid-colour photos: blue corrected, grey original. */
    private val renderer = ViewerRenderer { FakePhoto() }

    private class FakePhoto(override val width: Int = 400, override val height: Int = 300) : ViewerPhoto {
        private fun solid(color: Int) = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        override suspend fun base(fitWidth: Int, fitHeight: Int) = Layer(solid(0xff2050c0.toInt()), solid(0xff808080.toInt()), Rect(0, 0, 400, 300))
        override suspend fun tiles(sampleSize: Int, visible: Rect): List<Layer> = emptyList()
        override suspend fun setStrength(strength: Float) = Unit
        override fun close() = Unit
    }

    private fun setScreen() {
        rule.setContent {
            DiveLensTheme {
                ImportExportScreen(
                    strengths = controls(),
                    state = state,
                    onSelectPhotos = {}, onRemove = {}, onClear = {}, onExport = {}, onCancel = {},
                    onDismissSummary = {}, onDismissRejections = {},
                    viewer = renderer,
                    onOpen = { state = state.copy(viewing = it) },
                    onCloseViewer = { state = state.copy(viewing = null) },
                    onViewerPage = { pages += it },
                )
            }
        }
    }

    private fun shows(description: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    /** The viewer page of IMG_n; the grid cell has the same description but no state description. */
    private fun page(n: Int) = rule.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("IMG_$n.jpg"))
            .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)),
    )

    @Test fun openSwipeAndBack() {
        setScreen()
        rule.onNodeWithContentDescription("IMG_3.jpg").performClick()
        page(3).assertIsDisplayed()
        rule.waitUntil { pages.lastOrNull() == 2 }

        rule.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        page(4).assertIsDisplayed()
        rule.waitUntil { pages.lastOrNull() == 3 }

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertNull(state.viewing)
        rule.onNodeWithTag("photo_grid").assertIsDisplayed()
        assertEquals(photos.map { it.source }, state.workingSet.items.map { it.item.source })
    }

    @Test fun holdShowsTheOriginalUntilReleased() {
        state = state.copy(viewing = 0)
        setScreen()
        rule.waitUntil { runCatching { page(1).assert(shows("corrected")) }.isSuccess }
        page(1).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_TO_COMPARE_MILLIS + 100)
        page(1).assert(shows("original"))
        page(1).performTouchInput { up() }
        page(1).assert(shows("corrected"))
    }

    @Test fun panAfterAShortPressDoesNotShowTheOriginal() {
        state = state.copy(viewing = 0)
        setScreen()
        rule.waitForIdle()
        page(1).performTouchInput {
            down(center)
        }
        rule.mainClock.advanceTimeBy(100)
        page(1).performTouchInput { moveBy(Offset(0f, 200f)) }
        rule.mainClock.advanceTimeBy(300)
        page(1).assert(shows("corrected"))
        page(1).performTouchInput { up() }
    }

    @Test fun pinchNeverShowsTheOriginal() {
        state = state.copy(viewing = 0)
        setScreen()
        rule.waitForIdle()
        page(1).performTouchInput {
            down(0, center - Offset(50f, 0f))
            down(1, center + Offset(50f, 0f))
        }
        rule.mainClock.advanceTimeBy(400)
        page(1).assert(shows("corrected"))
        page(1).performTouchInput { up(0); up(1) }
    }

    @Test fun zoomStaysAfterThePinch() {
        val big = ViewerRenderer { FakePhoto(5184, 3888) }
        state = state.copy(viewing = 0)
        rule.setContent {
            DiveLensTheme {
                ViewerScreen(state.workingSet.items, 0, big, onPageChanged = {}, onClose = {})
            }
        }
        rule.waitForIdle()
        page(1).performTouchInput {
            down(0, center - Offset(50f, 0f)); down(1, center + Offset(50f, 0f))
            repeat(10) { moveBy(0, Offset(-30f, 0f)); moveBy(1, Offset(30f, 0f)) }
            up(0); up(1)
        }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val zoomed = zoomOf()
        assertTrue("zoom after the pinch $zoomed (fit $fit)", zoomed > 1.5f * fit)

        // Double-tap while zoomed returns to fit; double-tap at fit goes to the maximum, 100%: one photo pixel per screen pixel.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(ViewerZoom))[0].performTouchInput { doubleClick(center) }
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        assertEquals(fit, zoomOf(), 0.01f)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(ViewerZoom))[0].performTouchInput { doubleClick(center) }
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        assertEquals(1f, zoomOf(), 0.02f)
    }

    private val fit get() = rule.onRoot().fetchSemanticsNode().size.width / 5184f

    private fun zoomOf(): Float = rule.onAllNodes(SemanticsMatcher.keyIsDefined(ViewerZoom))[0].fetchSemanticsNode().config[ViewerZoom] // first page; its neighbour is composed too

    private fun openPanel() {
        rule.onNodeWithTag("adjust").performClick()
        rule.waitForIdle()
    }

    private fun setSlider(value: Float) {
        rule.onNodeWithTag("strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        rule.waitForIdle()
    }

    private fun value() = rule.onNodeWithTag("strength_value")

    @Test fun unadjustedPhotoShowsTheDefault() {
        state = state.copy(viewing = 0)
        setScreen()
        openPanel()
        value().assertTextEquals("78%")
        rule.onNodeWithText("Auto").assertIsDisplayed()
    }

    @Test fun strengthStaysWithItsPhotoAcrossSwipes() {
        state = state.copy(viewing = 1)
        setScreen()
        openPanel()
        setSlider(0.4f)
        value().assertTextEquals("40%")
        assertEquals(mapOf(photos[1].source to 0.4f), strengths)

        rule.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        page(3).assertIsDisplayed()
        value().assertTextEquals("78%") // the panel stays open on the next photo

        rule.onNodeWithTag("viewer").performTouchInput { swipeRight() }
        rule.waitForIdle()
        page(2).assertIsDisplayed()
        value().assertTextEquals("40%")
    }

    @Test fun resetReturnsToTheDefault() {
        state = state.copy(viewing = 0)
        setScreen()
        openPanel()
        setSlider(0.4f)
        rule.onNodeWithText("Reset").performClick()
        rule.waitForIdle()
        value().assertTextEquals("78%")
    }

    @Test fun applyToAllSetsEveryPhotoAndSaysSo() {
        state = state.copy(viewing = 0)
        setScreen()
        openPanel()
        setSlider(0.6f)
        rule.onNodeWithText("Apply to all").performClick()
        rule.waitForIdle()
        assertEquals(photos.associate { it.source to 0.6f }, strengths)
        rule.onNodeWithTag("applied_message").assertTextEquals("Strength 60% applied to 5 photos")
    }

    @Test fun holdComparesWhileThePanelIsOpen() {
        state = state.copy(viewing = 0)
        setScreen()
        openPanel()
        setSlider(0.3f)
        rule.waitUntil { runCatching { page(1).assert(shows("corrected")) }.isSuccess }
        page(1).performTouchInput { down(Offset(centerX, height * 0.25f)) } // above the panel
        rule.mainClock.advanceTimeBy(HOLD_TO_COMPARE_MILLIS + 100)
        page(1).assert(shows("original"))
        page(1).performTouchInput { up() }
        page(1).assert(shows("corrected"))
        value().assertTextEquals("30%")
    }

    @Test fun panelAndSliderKeepTheZoom() {
        val big = ViewerRenderer { FakePhoto(5184, 3888) }
        state = state.copy(viewing = 0)
        rule.setContent {
            DiveLensTheme {
                ViewerScreen(state.workingSet.items, 0, big, onPageChanged = {}, onClose = {}, strengths = controls())
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(ViewerZoom))[0].performTouchInput { doubleClick(Offset(centerX, height * 0.25f)) }
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        assertEquals(1f, zoomOf(), 0.02f)
        openPanel()
        setSlider(0.2f)
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertEquals(1f, zoomOf(), 0.02f)
    }
}
