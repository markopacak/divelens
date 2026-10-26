package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.V1Model
import com.markopacak.divelens.correction.Layer
import com.markopacak.divelens.correction.ViewerPhoto
import com.markopacak.divelens.correction.ViewerRenderer
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.WorkingSet
import com.markopacak.divelens.ui.help.HelpPrefs
import com.markopacak.divelens.ui.help.TIP_DELAY_MILLIS
import com.markopacak.divelens.ui.help.Tip
import com.markopacak.divelens.ui.help.ViewerGuidance
import com.markopacak.divelens.ui.theme.DiveLensTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The viewer's video page (add-video-processing D10, task 8.1) on the synthetic 20 s clip. */
class VideoViewerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var video: MediaItem.Video
    private val photo = MediaItem.Photo("content://test/photo", "IMG_1.jpg", "image/jpeg", 400, 300)
    private val playback = mutableListOf<Pair<String?, Long>>()
    private var strengths by mutableStateOf(emptyMap<String, Float>())
    private var screen by mutableStateOf(ImportExportUiState())
    private val stored = mutableMapOf<String, Boolean>()
    private val help = HelpPrefs(object : HelpPrefs.Flags {
        override fun get(key: String) = stored[key] ?: false
        override fun set(key: String, value: Boolean) { stored[key] = value }
    })

    private val renderer = ViewerRenderer {
        object : ViewerPhoto {
            override val width = 400
            override val height = 300
            private fun solid() = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2050c0.toInt()) }
            override suspend fun base(fitWidth: Int, fitHeight: Int) = Layer(solid(), solid(), Rect(0, 0, 400, 300))
            override suspend fun tiles(sampleSize: Int, visible: Rect): List<Layer> = emptyList()
            override suspend fun setStrength(strength: Float) = Unit
            override fun close() = Unit
        }
    }

    @Before fun setUp() {
        TestMedia.deleteAll()
        video = runBlocking { MediaImporter(TestMedia.resolver).import(listOf(TestMedia.longVideo())).accepted.single() as MediaItem.Video }
        screen = ImportExportUiState(workingSet = WorkingSet().add(listOf(video, photo)), viewing = 0)
        val params = CorrectionParams(floatArrayOf(1f, 1.5f, -0.7f, -0.06f, 0f, 2f, 0f, -0.5f, 0f, 0f, 1.6f, -0.6f), V1Model.DEFAULT_STRENGTH)
        val timeline = MutableStateFlow(CorrectionTimeline(video.durationUs).withSample(0, params).completed())
        val support = VideoSupport(
            timeline = { if (it == video.source) timeline else null },
            onPlayback = { source, position -> playback += source to position },
            thumbnail = { Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff204080.toInt()) } },
        )
        rule.setContent {
            DiveLensTheme {
                // The start screen, so opening and closing run the viewer's transition (add-screen-transitions D3).
                ImportExportScreen(
                    state = screen,
                    onSelectPhotos = {}, onRemove = {}, onClear = {}, onExport = {}, onCancel = {},
                    onDismissSummary = {}, onDismissRejections = {},
                    viewer = renderer,
                    onOpen = { screen = screen.copy(viewing = it) },
                    onCloseViewer = { screen = screen.copy(viewing = null) },
                    strengths = StrengthControls(strengths, V1Model.DEFAULT_STRENGTH, { s, v -> strengths = strengths + (s to v) }, {}),
                    videos = support,
                    guidance = ViewerGuidance(help.pending.collectAsState().value, help::done),
                )
            }
        }
    }

    @After fun tearDown() = TestMedia.deleteAll()

    private fun page() = rule.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(video.displayName))
            .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)),
    )

    private fun state(): String = page().fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun waitForState(text: String) = rule.waitUntil(10_000) { runCatching { state() }.getOrNull()?.contains(text) == true }

    private fun position(): Long = rule.onNodeWithTag("playback_bar").fetchSemanticsNode().config[VideoPositionMs]

    @Test fun playsPausesAndScrubs() {
        waitForState("playing")
        assertEquals(1, VideoPlayers.live.get())
        rule.onNodeWithTag("play_pause").performClick()
        waitForState("paused")
        rule.onNodeWithTag("video_scrub").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.waitUntil(3_000) { position() in 9_500L..10_500L }
        rule.onNodeWithTag("play_pause").performClick()
        waitForState("playing")
        // Analysis follows the scrubbed position.
        rule.waitUntil(3_000) { playback.any { it.first == video.source && it.second >= 9_000_000 } }
    }

    @Test fun holdingShowsTheOriginalWhilePlayingContinues() {
        waitForState("playing")
        page().performTouchInput { down(center) }
        waitForState("original, playing")
        val during = position()
        rule.waitUntil(3_000) { position() > during + 500 } // still playing while held
        assertTrue(state().startsWith("original"))
        page().performTouchInput { up() }
        waitForState("corrected, playing")
    }

    @Test fun aSliderChangeKeepsItPlaying() {
        waitForState("playing")
        rule.onNodeWithTag("strength_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        rule.waitForIdle()
        assertEquals(0.4f, strengths.getValue(video.source), 0.01f)
        assertTrue(state().endsWith("playing"))
        assertEquals(1, VideoPlayers.live.get())
    }

    @Test fun loopsAtTheEnd() {
        waitForState("playing")
        rule.onNodeWithTag("video_scrub").performSemanticsAction(SemanticsActions.SetProgress) { it(0.97f) }
        rule.waitUntil(5_000) { position() in 1L..3_000L } // back at the start
        assertTrue(state().endsWith("playing"))
    }

    @Test fun holdWorksWhilePaused() {
        waitForState("playing")
        rule.onNodeWithTag("play_pause").performClick()
        waitForState("paused")
        page().performTouchInput { down(center) }
        waitForState("original, paused")
        page().performTouchInput { up() }
        waitForState("corrected, paused")
    }

    @Test fun controlsSitAboveTheStrengthPanel() {
        waitForState("playing")
        val bar = rule.onNodeWithTag("playback_bar").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("adjustment_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("bar $bar overlaps panel $panel", bar.bottom <= panel.top + 1f)
        assertTrue("bar is in the lower half", bar.top > rule.onRoot().fetchSemanticsNode().size.height / 2f)
    }

    @Test fun pinchKeepsItFitted() {
        waitForState("playing")
        val before = rule.onNodeWithTag("video_surface").fetchSemanticsNode().boundsInRoot
        page().performTouchInput { pinch(center - Offset(40f, 0f), center - Offset(200f, 0f), center + Offset(40f, 0f), center + Offset(200f, 0f)) }
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("video_surface").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun swipingAwayReleasesThePlayer() {
        waitForState("playing")
        assertEquals(1, VideoPlayers.live.get())
        rule.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { VideoPlayers.live.get() == 0 }
        rule.waitUntil(3_000) { playback.lastOrNull()?.first == null }
    }

    // The video's one-time tip and the gesture sheet (add-help-and-legal D6, D7; tasks 4.3, 4.4).

    @Test fun holdTipShowsWhilePlayingAndGoesAwayForGood() {
        waitForState("playing")
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("tip").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Hold to see the original").assertIsDisplayed()
        assertTrue(state().endsWith("playing")) // the video plays on behind the tip
        page().performTouchInput { down(center) }
        waitForState("original, playing")
        page().performTouchInput { up() }
        waitForState("corrected, playing")
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("tip").fetchSemanticsNodes().isEmpty() }
        assertEquals(setOf(Tip.PhotoHold, Tip.PhotoZoom), help.pending.value)
        // Swipe to the photo and back: the video tip does not come back.
        rule.onNodeWithTag("viewer").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("viewer").performTouchInput { swipeRight() }
        waitForState("playing")
        Thread.sleep(TIP_DELAY_MILLIS + 600)
        rule.onAllNodesWithText("Hold to see the original").assertCountEquals(0)
    }

    @Test fun gestureSheetKeepsAPausedVideoWhereItWas() {
        waitForState("playing")
        rule.onNodeWithTag("play_pause").performClick()
        waitForState("paused")
        val before = position()
        rule.onNodeWithTag("gesture_help").performClick()
        rule.onNodeWithText("Hold: see the original video, while it keeps playing").assertIsDisplayed()
        rule.onNodeWithText("Time bar: drag to move through the clip").assertIsDisplayed()
        // Swipe the sheet away (adb-style key injection is blocked on the test phone).
        rule.onNodeWithTag("gesture_sheet").performTouchInput { swipeDown() }
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("gesture_sheet").fetchSemanticsNodes().isEmpty() }
        assertTrue(state().endsWith("paused"))
        assertEquals(before, position())
    }

    @Test fun gestureHelpStaysClearOfThePlaybackBar() {
        waitForState("playing")
        val button = rule.onNodeWithTag("gesture_help").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("playback_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("button $button overlaps bar $bar", button.bottom <= bar.top)
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun closingStopsPlaybackAtOnceAndShowsTheThumbnail() {
        waitForState("playing")
        assertEquals(1, VideoPlayers.live.get())
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("viewer_back").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals("viewer still closing", 1, count("viewer"))
        assertEquals("no video surface while closing", 0, count("video_surface"))
        assertEquals(0, VideoPlayers.live.get())
        rule.mainClock.advanceTimeBy(VIEWER_TRANSITION_MILLIS + 100L)
        assertEquals(0, count("viewer"))
    }

    @Test fun openingStartsPlaybackOnceTheTransitionEnds() {
        waitForState("playing")
        rule.onNodeWithTag("viewer_back").performClick()
        rule.waitUntil(3_000) { count("viewer") == 0 && VideoPlayers.live.get() == 0 }
        rule.mainClock.autoAdvance = false
        screen = screen.copy(viewing = 0)
        rule.mainClock.advanceTimeBy(100)
        assertEquals("viewer opening", 1, count("viewer"))
        assertEquals("thumbnail, not the player, while opening", 0, count("video_surface"))
        rule.mainClock.advanceTimeBy(VIEWER_TRANSITION_MILLIS + 100L)
        assertEquals(1, count("video_surface"))
        rule.mainClock.autoAdvance = true
        waitForState("playing")
    }

    @Test fun backButtonSitsBelowThePlaybackBar() {
        waitForState("playing")
        val back = rule.onNodeWithTag("viewer_back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("playback_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("back $back below bar $bar", back.top >= bar.bottom - 1f)
    }
}
