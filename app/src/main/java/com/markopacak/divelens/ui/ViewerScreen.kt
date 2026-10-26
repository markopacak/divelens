package com.markopacak.divelens.ui

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.markopacak.divelens.R
import com.markopacak.divelens.correction.Layer
import com.markopacak.divelens.correction.ViewerPhoto
import com.markopacak.divelens.correction.ViewerRenderer
import com.markopacak.divelens.analysis.V1Model
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.WorkingItem
import com.markopacak.divelens.ui.help.GestureHelpButton
import com.markopacak.divelens.ui.help.TIP_DELAY_MILLIS
import com.markopacak.divelens.ui.help.Tip
import com.markopacak.divelens.ui.help.TipBubble
import com.markopacak.divelens.ui.help.ViewerGuidance
import com.markopacak.divelens.ui.help.nextTip
import com.markopacak.divelens.ui.help.onEveryTouchEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Screen pixels per photo pixel of the viewer's photo, for tests: 1 is 100%. */
val ViewerZoom = SemanticsPropertyKey<Float>("ViewerZoom")

/** How long a still finger must stay down before the original is shown (design D4). */
const val HOLD_TO_COMPARE_MILLIS = 250L

/**
 * The strength of each photo for the viewer's adjustment panel (add-strength-slider D2, D4). Photos missing from
 * [values] are at [default].
 */
class StrengthControls(
    val values: Map<String, Float>,
    val default: Float,
    val onChange: (source: String, strength: Float) -> Unit,
    val onApplyToAll: (strength: Float) -> Unit,
) {
    fun of(source: String): Float = values[source] ?: default
}

/** How long the viewer fades and scales in and out, and from which scale (add-screen-transitions D3). */
const val VIEWER_TRANSITION_MILLIS = 250
const val VIEWER_TRANSITION_SCALE = 0.92f

/** How long the "applied to all" message stays. */
private const val MESSAGE_MILLIS = 3000L

private val AUTO_LABEL_WIDTH = 48.dp

/**
 * Full-screen viewer over the working set (spec "Full-screen viewer", design D4): swipe between photos and videos,
 * zoom photos up to 100% with corrected tiles, press and hold to see the original. Back, or the panel's Back button, closes it. With [strengths],
 * a panel adjusts the strength of the item shown (add-strength-slider D4). With [videos], videos play corrected
 * ([VideoPage]). With [guidance], one-time gesture tips appear; a (?) button explains the gestures (add-help-and-legal).
 */
@Composable
fun ViewerScreen(
    items: List<WorkingItem>,
    initialIndex: Int,
    renderer: ViewerRenderer,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    strengths: StrengthControls? = null,
    videos: VideoSupport? = null,
    guidance: ViewerGuidance? = null,
    transitioning: Boolean = false,
    closing: Boolean = false,
) {
    // While closing, a second Back goes to the start screen (add-screen-transitions D4).
    BackHandler(enabled = !closing, onBack = onClose)
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, max(0, items.size - 1))) { items.size }
    val pageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { pageChanged(it) } }
    var dragging by remember { mutableStateOf(false) }
    var panelHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    fun isVideoPage(page: Int) = items.getOrNull(page)?.item is MediaItem.Video && videos != null
    // The one-time tip of the settled page (add-help-and-legal D6): shown after a short delay, gone when a touch ends.
    val candidate = guidance?.let { nextTip(isVideoPage(pager.settledPage), it.pending) }
    var tip by remember { mutableStateOf<Tip?>(null) }
    LaunchedEffect(candidate, pager.settledPage) {
        tip = null
        if (candidate != null) { delay(TIP_DELAY_MILLIS); tip = candidate }
    }
    val shownTip by rememberUpdatedState(tip)
    val currentGuidance by rememberUpdatedState(guidance)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items[it].item.source },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().testTag("viewer").onEveryTouchEnd {
                shownTip?.let { currentGuidance?.onDone(it); tip = null }
            },
        ) { page ->
            val item = items[page]
            val source = item.item.source
            val video = item.item as? MediaItem.Video
            if (video != null && videos != null) {
                // Only the settled current page plays, so at most one decoder runs (add-video-processing D10).
                // A SurfaceView doesn't follow the fade and scale, so videos show their thumbnail during a transition
                // and the player starts once it ends (add-screen-transitions D4).
                VideoPage(video, active = page == pager.settledPage && !transitioning, videos, strengths?.of(source) ?: V1Model.DEFAULT_STRENGTH, bottomInset = panelHeight)
            } else {
                ViewerPage(item, renderer, strengths?.of(source), dragging && page == pager.currentPage)
            }
        }
        tip?.let { TipBubble(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)) }
        // Siblings of the pager: their touches reach neither hold to compare nor zoom and swipe.
        if (items.isNotEmpty()) {
            GestureHelpButton(isVideoPage(pager.currentPage), Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp))
        }
        if (strengths != null && items.isNotEmpty()) {
            val source = items[pager.currentPage.coerceIn(items.indices)].item.source
            AdjustmentPanel(
                strength = strengths.of(source),
                default = strengths.default,
                photoCount = items.size,
                onChange = { strengths.onChange(source, it) },
                onDragging = { dragging = it },
                onApplyToAll = strengths.onApplyToAll,
                onBack = onClose,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
            )
        }
        // While closing, touches are taken here so they reach neither the viewer nor the grid it is fading over.
        if (closing) Box(Modifier.fillMaxSize().testTag("viewer_closing").pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } })
    }
}

/**
 * The adjustment panel (add-strength-slider D4), always shown at the bottom: the strength slider from 0 to 100% with
 * the default marked "Auto", Reset and Apply to all. On videos the playback bar sits above it; its white slider and
 * the times tell it apart from this one.
 */
@Composable
private fun AdjustmentPanel(
    strength: Float,
    default: Float,
    photoCount: Int,
    onChange: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    onApplyToAll: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var applied by remember { mutableStateOf<Pair<Int, Int>?>(null) } // percent, items
    LaunchedEffect(applied) { if (applied != null) { delay(MESSAGE_MILLIS); applied = null } }
    val percent = (strength * 100).roundToInt()
    // The colour is set explicitly: a translucent surface has no matching content colour, so the text fell back to black.
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().testTag("adjustment_panel"),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.correction_strength), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.percent, percent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("strength_value"))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The default's position: the 4 dp thumb's track runs from 2 dp to the width minus 2 dp.
                val mark = 2.dp + (maxWidth - 4.dp) * default
                Column {
                    ThinSlider(
                        value = strength,
                        onValueChange = { onDragging(true); onChange(it) },
                        onValueChangeFinished = { onDragging(false) },
                        modifier = Modifier.fillMaxWidth().testTag("strength_slider"),
                    )
                    Box(Modifier.offset(x = mark - 1.dp).width(2.dp).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
                    Text(
                        stringResource(R.string.strength_auto),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.offset(x = mark - AUTO_LABEL_WIDTH / 2).width(AUTO_LABEL_WIDTH),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(default) }, enabled = percent != (default * 100).roundToInt()) {
                    Text(stringResource(R.string.reset))
                }
                TextButton(onClick = { onApplyToAll(strength); applied = percent to photoCount }) {
                    Text(stringResource(R.string.apply_to_all))
                }
                Spacer(Modifier.weight(1f))
                // Closes the viewer like the system Back (add-screen-transitions D5).
                TextButton(onClick = onBack, modifier = Modifier.testTag("viewer_back")) {
                    Text(stringResource(R.string.back))
                }
            }
            applied?.let { (p, n) ->
                Text(
                    pluralStringResource(R.plurals.applied_to_all, n, p, n),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp).testTag("applied_message"),
                )
            }
        }
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun ViewerPage(item: WorkingItem, renderer: ViewerRenderer, strength: Float?, dragging: Boolean) {
    val source = item.item.source
    val tapped = remember(source) { SystemClock.elapsedRealtime() }
    val opened by produceState<Result<ViewerPhoto>?>(null, source) {
        val result = runCatching { renderer.open(source) }
        value = result
        awaitDispose { result.getOrNull()?.close() }
    }
    var holding by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier.fillMaxSize().holdToCompare { holding = it }.semantics {
            contentDescription = item.item.displayName
            stateDescription = if (holding) "original" else "corrected"
        },
    ) {
        val photo = opened?.getOrNull()
        when {
            opened == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            photo == null -> {
                AsyncImage(model = source, contentDescription = null, modifier = Modifier.fillMaxSize())
                Notice(stringResource(R.string.preview_unavailable), Modifier.align(Alignment.TopCenter))
            }
            else -> {
                val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
                PhotoCanvas(photo, viewport, holding, strength, dragging, tapped)
            }
        }
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun PhotoCanvas(photo: ViewerPhoto, viewport: IntSize, holding: Boolean, strength: Float?, dragging: Boolean, shownSince: Long) {
    val fitScale = min(viewport.width.toFloat() / photo.width, viewport.height.toFloat() / photo.height)
    // Telephoto's zoom factors are relative to the photo fitted to the screen, so 100% (one photo pixel per
    // screen pixel) is 1 / fitScale.
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = max(1f, 1f / fitScale)), autoApplyTransformations = false)
    LaunchedEffect(photo) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(Size(photo.width.toFloat(), photo.height.toFloat()))) }
    var version by remember { mutableStateOf(0) }
    var tiles by remember { mutableStateOf<List<Layer>>(emptyList()) }
    // Timing logs for the device check: when the last strength change started, and whether its detail is still to come.
    var changedAt by remember { mutableStateOf(0L) }
    var detailPending by remember { mutableStateOf(false) }
    val currentStrength by rememberUpdatedState(strength)
    // Strength changes re-render the base only; the newest value wins while a render runs (add-strength-slider D5).
    LaunchedEffect(photo) {
        var first = true
        snapshotFlow { currentStrength }.conflate().collect { s ->
            if (s == null) return@collect
            val start = SystemClock.elapsedRealtime()
            photo.setStrength(s)
            tiles = emptyList() // stale until requested again at the new strength
            if (!first) { changedAt = start; detailPending = true }
            first = false
            version++
        }
    }
    val base by produceState<Layer?>(null, photo, version) {
        value = photo.base((photo.width * fitScale).roundToInt().coerceAtLeast(1), (photo.height * fitScale).roundToInt().coerceAtLeast(1))
    }
    LaunchedEffect(base != null) {
        if (base != null) Log.i("Viewer", "${photo.width}x${photo.height} shown corrected ${SystemClock.elapsedRealtime() - shownSince} ms after opening")
    }
    LaunchedEffect(base) {
        if (base != null && changedAt != 0L) Log.i("Viewer", "strength ${base?.strength} shown ${SystemClock.elapsedRealtime() - changedAt} ms after the change")
        changedAt = 0L
    }
    var detailUnavailable by remember { mutableStateOf(false) }

    // Content rectangle on screen after zoom and pan.
    fun contentRect(): androidx.compose.ui.geometry.Rect? = with(zoom.coordinateSystem) {
        val bounds = contentBounds(clipToViewport = false)
        runCatching { bounds.rectIn(CoordinateSpace.Viewport) }.getOrNull()?.takeIf { it.width > 0f && it.height > 0f }
    }

    LaunchedEffect(photo, base, version, dragging) {
        val baseLayer = base ?: return@LaunchedEffect
        if (dragging) return@LaunchedEffect // the base alone while the slider moves
        val released = SystemClock.elapsedRealtime()
        val baseSample = max(1, photo.width / max(1, baseLayer.corrected.width))
        snapshotFlow { contentRect() }.distinctUntilChanged().collectLatest { rect ->
            if (rect == null) return@collectLatest
            val photoPerScreen = photo.width / rect.width // photo pixels per screen pixel
            var n = 1
            while (n * 2 <= photoPerScreen) n *= 2
            if (n >= baseSample) {
                tiles = emptyList(); detailUnavailable = false
                return@collectLatest
            }
            val sx = photo.width / rect.width; val sy = photo.height / rect.height
            val visible = Rect(
                ((0f - rect.left) * sx).toInt().coerceIn(0, photo.width),
                ((0f - rect.top) * sy).toInt().coerceIn(0, photo.height),
                ((viewport.width - rect.left) * sx).toInt().coerceIn(0, photo.width),
                ((viewport.height - rect.top) * sy).toInt().coerceIn(0, photo.height),
            )
            if (visible.isEmpty) return@collectLatest
            val made = photo.tiles(n, visible)
            detailUnavailable = made == null
            tiles = made.orEmpty()
            if (detailPending) {
                Log.i("Viewer", "${tiles.size} tiles at 1/$n shown ${SystemClock.elapsedRealtime() - released} ms after the slider was released")
                detailPending = false
            }
        }
    }

    Box(Modifier.fillMaxSize().zoomable(zoom, onLongClick = null).semantics {
        contentRect()?.let { this[ViewerZoom] = it.width / photo.width } // screen pixels per photo pixel; 1 = 100%
    }) {
        val shown = base
        if (shown == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            val images = remember(shown, tiles, holding) {
                (listOf(shown) + tiles).map { (if (holding) it.original else it.corrected).asImageBitmap() to it.rect }
            }
            Canvas(Modifier.fillMaxSize().testTag("viewer_canvas")) {
                val rect = contentRect() ?: return@Canvas
                for ((image, area) in images) drawLayer(image, area, rect, photo)
            }
        }
        if (detailUnavailable) Notice(stringResource(R.string.detail_unavailable), Modifier.align(Alignment.TopCenter))
    }
}

/** Draws [image], covering [area] of the photo (full-resolution display coordinates), into the photo's screen rectangle [content]. */
private fun DrawScope.drawLayer(image: ImageBitmap, area: Rect, content: androidx.compose.ui.geometry.Rect, photo: ViewerPhoto) {
    val sx = content.width / photo.width; val sy = content.height / photo.height
    val left = (content.left + area.left * sx).roundToInt(); val top = (content.top + area.top * sy).roundToInt()
    val right = (content.left + area.right * sx).roundToInt(); val bottom = (content.top + area.bottom * sy).roundToInt()
    if (right <= 0 || bottom <= 0 || left >= size.width || top >= size.height) return
    drawImage(
        image,
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(right - left, bottom - top),
        filterQuality = FilterQuality.Medium,
    )
}

@Composable
private fun Notice(text: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
    }
}

/**
 * Press and hold to compare (design D4): observes pointer events in the Initial pass without consuming them,
 * so zoom, pan and swipe keep working. One finger held still for [HOLD_TO_COMPARE_MILLIS] calls
 * [onHold] with true until it lifts; movement beyond touch slop or a second finger cancels the hold.
 */
fun Modifier.holdToCompare(onHold: (Boolean) -> Unit): Modifier = composed {
    val slop = LocalViewConfiguration.current.touchSlop
    val hold by rememberUpdatedState(onHold)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val still = withTimeoutOrNull(HOLD_TO_COMPARE_MILLIS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) return@withTimeoutOrNull false
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!change.pressed || (change.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (still != null) return@awaitEachGesture // cancelled before the hold time
            hold(true)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1 || event.changes.none { it.pressed }) break
                }
            } finally {
                hold(false)
            }
        }
    }
}
