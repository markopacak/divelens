package com.markopacak.divelens.ui

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.markopacak.divelens.R
import com.markopacak.divelens.correction.Layer
import com.markopacak.divelens.correction.ViewerPhoto
import com.markopacak.divelens.correction.ViewerRenderer
import com.markopacak.divelens.media.WorkingItem
import kotlinx.coroutines.flow.collectLatest
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
 * Full-screen viewer over the working set (spec "Full-screen viewer", design D4): swipe between photos,
 * zoom up to 100% with corrected tiles, press and hold to see the original. Back closes it.
 */
@Composable
fun ViewerScreen(
    items: List<WorkingItem>,
    initialIndex: Int,
    renderer: ViewerRenderer,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    strength: Float? = null,
) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, max(0, items.size - 1))) { items.size }
    val pageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { pageChanged(it) } }
    HorizontalPager(
        state = pager,
        key = { items[it].item.source },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize().background(Color.Black).testTag("viewer"),
    ) { page ->
        ViewerPage(items[page], renderer, strength)
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun ViewerPage(item: WorkingItem, renderer: ViewerRenderer, strength: Float?) {
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
                PhotoCanvas(photo, viewport, holding, strength, tapped)
            }
        }
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun PhotoCanvas(photo: ViewerPhoto, viewport: IntSize, holding: Boolean, strength: Float?, shownSince: Long) {
    val fitScale = min(viewport.width.toFloat() / photo.width, viewport.height.toFloat() / photo.height)
    // Telephoto's zoom factors are relative to the photo fitted to the screen, so 100% (one photo pixel per
    // screen pixel) is 1 / fitScale.
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = max(1f, 1f / fitScale)), autoApplyTransformations = false)
    LaunchedEffect(photo) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(Size(photo.width.toFloat(), photo.height.toFloat()))) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(photo, strength) {
        if (strength != null) {
            photo.setStrength(strength)
            version++
        }
    }
    val base by produceState<Layer?>(null, photo, version) {
        value = photo.base((photo.width * fitScale).roundToInt().coerceAtLeast(1), (photo.height * fitScale).roundToInt().coerceAtLeast(1))
    }
    LaunchedEffect(base != null) {
        if (base != null) Log.i("Viewer", "${photo.width}x${photo.height} shown corrected ${SystemClock.elapsedRealtime() - shownSince} ms after opening")
    }
    var tiles by remember { mutableStateOf<List<Layer>>(emptyList()) }
    var detailUnavailable by remember { mutableStateOf(false) }

    // Content rectangle on screen after zoom and pan.
    fun contentRect(): androidx.compose.ui.geometry.Rect? = with(zoom.coordinateSystem) {
        val bounds = contentBounds(clipToViewport = false)
        runCatching { bounds.rectIn(CoordinateSpace.Viewport) }.getOrNull()?.takeIf { it.width > 0f && it.height > 0f }
    }

    LaunchedEffect(photo, base, version) {
        val baseLayer = base ?: return@LaunchedEffect
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
