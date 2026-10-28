package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.markopacak.divelens.ui.help.HelpPrefs
import com.markopacak.divelens.ui.help.TIP_DELAY_MILLIS
import com.markopacak.divelens.ui.help.Tip
import com.markopacak.divelens.ui.help.ViewerGuidance
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

    /** The tip flags as SharedPreferences would keep them; a new [HelpPrefs] on them is like a restart. */
    private val stored = mutableMapOf<String, Boolean>()
    private val flags = object : HelpPrefs.Flags {
        override fun get(key: String, default: Boolean) = stored[key] ?: default
        override fun set(key: String, value: Boolean) { stored[key] = value }
    }
    private var help: HelpPrefs? = null
    private var cleared = 0

    private fun setScreen() {
        rule.setContent {
            DiveLensTheme {
                val tips = help?.let { prefs -> prefs.pending.collectAsState().value.let { ViewerGuidance(it, prefs::done) } }
                ImportExportScreen(
                    guidance = tips,
                    strengths = controls(),
                    state = state,
                    onSelectPhotos = {}, onRemove = {}, onClear = { cleared++ }, onExport = {}, onCancel = {},
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

    /** The panel is always shown (no Adjust button since the device check of add-video-processing). */
    private fun openPanel() {
        rule.onNodeWithTag("adjustment_panel").assertIsDisplayed()
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
        rule.onNodeWithTag("applied_message").assertTextEquals("Strength 60% applied to 5 items")
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

    // One-time gesture tips and the gesture sheet (add-help-and-legal D6, D7; tasks 4.2, 4.4).

    private fun tip() = rule.onNodeWithTag("tip")

    private fun waitForTip(text: String) = rule.waitUntil(3_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun photoTipsShowHoldThenZoomAndDoNotBlockTheGestures() {
        help = HelpPrefs(flags)
        state = state.copy(viewing = 0)
        setScreen()
        waitForTip("Hold to see the original")
        // Holding with the tip on screen compares exactly as without it.
        page(1).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_TO_COMPARE_MILLIS + 100)
        page(1).assert(shows("original"))
        page(1).performTouchInput { up() }
        page(1).assert(shows("corrected"))
        rule.onAllNodesWithText("Hold to see the original").assertCountEquals(0)
        waitForTip("Double-tap to zoom")
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(ViewerZoom))[0].performTouchInput { doubleClick(center) }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        rule.onAllNodesWithTag("tip").assertCountEquals(0)
        assertEquals(setOf(Tip.VideoHold), help!!.pending.value)
    }

    @Test fun dismissedTipsDoNotReturnAfterARestart() {
        HelpPrefs(flags).apply { done(Tip.PhotoHold); done(Tip.PhotoZoom) }
        help = HelpPrefs(flags) // a new instance on the same stored flags, as after restarting the app
        state = state.copy(viewing = 0)
        setScreen()
        rule.mainClock.advanceTimeBy(TIP_DELAY_MILLIS + 1000); rule.waitForIdle()
        rule.onAllNodesWithTag("tip").assertCountEquals(0)
    }

    @Test fun showTipsAgainBringsTheHoldTipBack() {
        help = HelpPrefs(flags).apply { Tip.entries.forEach(::done) }
        state = state.copy(viewing = 0)
        setScreen()
        rule.mainClock.advanceTimeBy(TIP_DELAY_MILLIS + 500); rule.waitForIdle()
        rule.onAllNodesWithTag("tip").assertCountEquals(0)
        rule.runOnUiThread { help!!.resetTips() } // what Show tips again in Settings & help calls (task 4.5)
        waitForTip("Hold to see the original")
    }

    @Test fun gestureSheetExplainsThePhotoGestures() {
        state = state.copy(viewing = 0)
        setScreen()
        rule.onNodeWithTag("gesture_help").assertIsDisplayed().performClick()
        rule.onNodeWithTag("gesture_sheet").assertIsDisplayed()
        rule.onNodeWithText("Hold: see the original photo").assertIsDisplayed()
        rule.onNodeWithText("Double-tap or pinch: zoom in, up to the real pixels").assertIsDisplayed()
        rule.onNodeWithText("Swipe left or right: next or previous item").assertIsDisplayed()
        // Swipe the sheet away (adb-style key injection is blocked on the test phone).
        rule.onNodeWithTag("gesture_sheet").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onAllNodesWithTag("gesture_sheet").assertCountEquals(0)
        page(1).assertIsDisplayed() // still the same photo in the viewer
    }

    @Test fun gestureHelpStaysClearOfTheStrengthPanel() {
        state = state.copy(viewing = 0)
        setScreen()
        val button = rule.onNodeWithTag("gesture_help").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("adjustment_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("button $button overlaps panel $panel", button.bottom <= panel.top)
        assertTrue("button is in the top half", button.bottom < rule.onRoot().fetchSemanticsNode().size.height / 2f)
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun viewerFadesInAndOut() {
        setScreen()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("IMG_3.jpg").performClick()
        rule.mainClock.advanceTimeBy(100)
        assertEquals("viewer mid-opening", 1, count("viewer"))
        assertEquals("grid under the viewer", 1, count("photo_grid"))
        rule.mainClock.advanceTimeBy(VIEWER_TRANSITION_MILLIS + 100L)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(100)
        assertNull(state.viewing)
        assertEquals("viewer mid-closing", 1, count("viewer"))
        assertEquals("closing viewer ignores touches", 1, count("viewer_closing"))
        rule.mainClock.advanceTimeBy(VIEWER_TRANSITION_MILLIS + 100L)
        assertEquals("viewer after closing", 0, count("viewer"))
        rule.onNodeWithContentDescription("IMG_3.jpg").assertIsDisplayed() // the grid, as it was
    }

    @Test fun removingTheShownItemClosesAtOnce() {
        state = state.copy(viewing = 0)
        setScreen()
        page(1).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        state = state.copy(workingSet = state.workingSet.remove(photos[0].source), viewing = null)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0, count("viewer"))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("photo_grid").assertIsDisplayed()
    }

    @Test fun aSecondBackWhileClosingReachesTheStartScreen() {
        state = state.copy(viewing = 0)
        setScreen()
        page(1).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(50)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(VIEWER_TRANSITION_MILLIS + 100L)
        assertEquals(1, cleared) // the start screen's Back, which clears the set
    }

    @Test fun backButtonClosesTheViewerAndKeepsTheStrength() {
        strengths = mapOf(photos[0].source to 0.5f)
        state = state.copy(viewing = 0)
        setScreen()
        page(1).assertIsDisplayed()
        val back = rule.onNodeWithTag("viewer_back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val reset = rule.onNodeWithText("Reset").fetchSemanticsNode().boundsInRoot
        val all = rule.onNodeWithText("Apply to all").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("adjustment_panel").fetchSemanticsNode().boundsInRoot
        assertEquals("same row as Reset", reset.center.y, back.center.y, 2f)
        assertTrue("right of Apply to all", back.left >= all.right)
        assertTrue("inside the panel", back.right <= panel.right && back.bottom <= panel.bottom)
        rule.onNodeWithTag("viewer_back").performClick()
        rule.waitForIdle()
        assertNull(state.viewing)
        assertEquals(0, count("viewer"))
        assertEquals(mapOf(photos[0].source to 0.5f), strengths)
    }
}
