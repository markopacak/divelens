package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
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
        val params = CorrectionParams(floatArrayOf(1f, 1.5f, -0.7f, -0.06f, 0f, 2f, 0f, -0.5f, 0f, 0f, 1.6f, -0.6f), V1Model.DEFAULT_STRENGTH)
        val timeline = MutableStateFlow(CorrectionTimeline(video.durationUs).withSample(0, params).completed())
        val support = VideoSupport(
            timeline = { if (it == video.source) timeline else null },
            onPlayback = { source, position -> playback += source to position },
            thumbnail = { Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff204080.toInt()) } },
        )
        rule.setContent {
            DiveLensTheme {
                ViewerScreen(
                    items = WorkingSet().add(listOf(video, photo)).items,
                    initialIndex = 0,
                    renderer = renderer,
                    onPageChanged = {},
                    onClose = {},
                    strengths = StrengthControls(strengths, V1Model.DEFAULT_STRENGTH, { s, v -> strengths = strengths + (s to v) }, {}),
                    videos = support,
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
}
