package com.markopacak.divelens.ui.help

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markopacak.divelens.R

/** The viewer's one-time tips (add-help-and-legal D6): the tips still to show, and what to call when one is done. */
class ViewerGuidance(val pending: Set<Tip>, val onDone: (Tip) -> Unit)

/** How long a page is shown before its tip appears, so swiping past does not flash one. */
const val TIP_DELAY_MILLIS = 400L

/** The tip a page shows next: on photos hold, then zoom; on videos hold. */
fun nextTip(isVideo: Boolean, pending: Set<Tip>): Tip? =
    (if (isVideo) listOf(Tip.VideoHold) else listOf(Tip.PhotoHold, Tip.PhotoZoom)).firstOrNull { it in pending }

/**
 * Calls [onUp] when the last finger of any gesture lifts: a tap, a hold, a zoom or a swipe. Observes in the Initial
 * pass and consumes nothing, so every gesture underneath works exactly as without it.
 */
fun Modifier.onEveryTouchEnd(onUp: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        onUp()
    }
}

@StringRes
private fun Tip.text(): Int = when (this) {
    Tip.PhotoHold, Tip.VideoHold -> R.string.tip_hold
    Tip.PhotoZoom -> R.string.tip_zoom
}

/** A tip in the upper part of the viewer, without a scrim and without any touch handling of its own. */
@Composable
fun TipBubble(tip: Tip, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.testTag("tip"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HelpIcons.Touch, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(tip.text()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** The (?) button (add-help-and-legal D7) and the sheet it opens, listing the gestures for a photo or a video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureHelpButton(isVideo: Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = modifier.testTag("gesture_help"),
    ) {
        Box(Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(HelpIcons.Help, contentDescription = stringResource(R.string.gesture_help), tint = Color.White)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, modifier = Modifier.testTag("gesture_sheet")) {
            val lines = if (isVideo) {
                listOf(R.string.gestures_video_hold, R.string.gestures_video_play, R.string.gestures_video_scrub, R.string.gestures_swipe)
            } else {
                listOf(R.string.gestures_photo_hold, R.string.gestures_photo_zoom, R.string.gestures_swipe)
            }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.gesture_help), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                lines.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        }
    }
}
