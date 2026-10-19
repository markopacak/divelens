package com.markopacak.divelens.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.markopacak.divelens.R
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.EasedTimeline
import com.markopacak.divelens.correction.CorrectionEffect
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import androidx.media3.common.MediaItem as Media3Item

/** What the viewer needs for videos (add-video-processing D10). */
class VideoSupport(
    /** The video's correction over time as analysed so far. */
    val timeline: (source: String) -> StateFlow<CorrectionTimeline>?,
    /** The viewer plays [source] at [positionUs], or no video (null): analysis follows it. */
    val onPlayback: (source: String?, positionUs: Long) -> Unit,
    /** The corrected thumbnail frame, shown on pages that are not playing. */
    val thumbnail: (source: String) -> Bitmap?,
)

/** The playing video's position in milliseconds, for tests. */
val VideoPositionMs = SemanticsPropertyKey<Long>("VideoPositionMs")

/** Players alive right now, for tests: at most one, the current page's. */
object VideoPlayers {
    val live = AtomicInteger(0)
}

/** The strength the effect reads on every frame, from the GL thread. */
private class LiveStrength(@Volatile var value: Float)

/** How long a paused video waits after the last strength change before it shows the frame again. */
private const val PAUSED_REDRAW_MILLIS = 60L

/**
 * A video page of the viewer (add-video-processing D10): on the current, settled page an [ExoPlayer] plays the clip in a
 * loop with the [CorrectionEffect], following the analysed timeline and the live strength. Hold to compare shows strength
 * 0, playing or paused. Play/pause, the time and a scrub bar sit at the bottom, [bottomInset] above the screen edge (the
 * strength panel is below them). Videos do not zoom. Other pages show the corrected thumbnail frame.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoPage(video: MediaItem.Video, active: Boolean, support: VideoSupport, strength: Float, bottomInset: Dp = 0.dp) {
    var holding by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val live = remember { LiveStrength(strength) }
    val shown = if (holding) 0f else strength
    live.value = shown
    Box(
        Modifier.fillMaxSize().semantics {
            contentDescription = video.displayName
            stateDescription = (if (holding) "original" else "corrected") + if (playing) ", playing" else ", paused"
        },
    ) {
        val controller = if (active) rememberPlayer(video, support, live, shown, onPlaying = { playing = it }) else null
        // Hold and pinch apply to the picture area, which holds the video; the playback bar is drawn above it, so its
        // touches never reach them.
        BoxWithConstraints(Modifier.fillMaxSize().holdToCompare { holding = it }.ignorePinch(), contentAlignment = Alignment.Center) {
            val aspect = video.displayWidth.toFloat() / video.displayHeight
            val fitW = min(maxWidth.value, maxHeight.value * aspect)
            val fitted = Modifier.size(fitW.dp, (fitW / aspect).dp)
            if (controller == null) {
                support.thumbnail(video.source)?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = fitted)
                } ?: CircularProgressIndicator()
            } else {
                AndroidView(
                    factory = { SurfaceView(it).also(controller.player::setVideoSurfaceView) },
                    modifier = fitted.testTag("video_surface"),
                )
                // Until the first corrected frame is on screen; the timeline analysis does not block playback.
                if (!controller.firstFrame) CircularProgressIndicator()
            }
        }
        if (controller != null) PlaybackBar(controller, video, support, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset))
    }
}

/**
 * Videos do not zoom (spec "Video playback in the viewer"): a gesture with two or more fingers is consumed here, so the
 * pager does not take a pinch for a swipe. One-finger swipes reach the pager as before.
 */
private fun Modifier.ignorePinch(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var multi = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) multi = true
            if (multi) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** The current page's player and the state its controls show. */
private class PlayerController(val player: ExoPlayer) {
    var firstFrame by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)
    var scrubValue by mutableFloatStateOf(0f)
}

@kotlin.OptIn(FlowPreview::class)
@OptIn(UnstableApi::class)
@Composable
private fun rememberPlayer(
    video: MediaItem.Video,
    support: VideoSupport,
    live: LiveStrength,
    shown: Float,
    onPlaying: (Boolean) -> Unit,
): PlayerController {
    val context = LocalContext.current
    val flow = remember(video.source) { support.timeline(video.source) }
    val eased = remember(video.source) { EasedTimeline(flow?.value ?: CorrectionTimeline(video.durationUs)) }
    LaunchedEffect(flow) { flow?.collect { eased.update(it) } }
    val openedAt = remember(video.source) { SystemClock.elapsedRealtime() }
    val c = remember(video.source) {
        val player = ExoPlayer.Builder(context).build()
        PlayerController(player).also { c ->
            c.duration = video.durationUs / 1000
            // Timing logs for the device check (spec "Video responsiveness").
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    if (!c.firstFrame) Log.i("Viewer", "${video.width}x${video.height} first corrected frame ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
                    c.firstFrame = true
                }

                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    Log.i("Viewer", "$droppedFrames frames dropped in $elapsedMs ms")
                }
            })
            player.setVideoEffects(listOf(CorrectionEffect.forPlayback(eased) { live.value }))
            player.setMediaItem(Media3Item.fromUri(video.source))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            player.prepare()
            VideoPlayers.live.incrementAndGet()
        }
    }
    val player = c.player
    DisposableEffect(c) {
        support.onPlayback(video.source, 0)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                c.isPlaying = playing; onPlaying(playing)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration > 0) c.duration = player.duration
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            VideoPlayers.live.decrementAndGet()
            support.onPlayback(null, 0)
        }
    }
    LaunchedEffect(c) {
        while (true) {
            if (!c.scrubbing) c.position = player.currentPosition
            delay(100)
        }
    }
    // A paused video shows no new frames, so a strength change (the slider, or hold to compare) shows the current frame
    // again: seeking to where it is decodes it once more through the effect.
    val currentShown by rememberUpdatedState(shown)
    LaunchedEffect(c) {
        snapshotFlow { currentShown }.distinctUntilChanged().drop(1).debounce(PAUSED_REDRAW_MILLIS).collect {
            if (!player.isPlaying && c.firstFrame) player.seekTo(player.currentPosition)
        }
    }
    return c
}

/** Play/pause, the elapsed time, the scrub bar and the total time. */
@OptIn(UnstableApi::class)
@Composable
private fun PlaybackBar(c: PlayerController, video: MediaItem.Video, support: VideoSupport, modifier: Modifier) {
    val player = c.player
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.fillMaxWidth().semantics { this[VideoPositionMs] = c.position }.testTag("playback_bar"),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = stringResource(if (c.isPlaying) R.string.pause else R.string.play)
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.testTag("play_pause")) {
                Icon(if (c.isPlaying) PauseIcon else PlayIcon, contentDescription = label)
            }
            Text(MediaLabels.duration(c.position * 1000), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("video_time"))
            ThinSlider(
                value = if (c.scrubbing) c.scrubValue else if (c.duration > 0) c.position.toFloat() / c.duration else 0f,
                onValueChange = {
                    c.scrubbing = true; c.scrubValue = it
                    val target = (it * c.duration).toLong()
                    player.seekTo(target)
                    c.position = target
                },
                onValueChangeFinished = {
                    c.scrubbing = false
                    support.onPlayback(video.source, c.position * 1000)
                },
                // White, unlike the strength slider below, which uses the theme's colours.
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("video_scrub"),
            )
            Text(MediaLabels.duration(c.duration * 1000), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Play: a right-pointing triangle. */
private val PlayIcon: ImageVector = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
}.build()

/** Pause: two bars. */
private val PauseIcon: ImageVector = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }
}.build()
