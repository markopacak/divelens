package com.markopacak.divelens.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/** The top-level screens (add-help-and-legal D1); the viewer is part of the start screen. */
enum class Screen { Start, SettingsHelp }

/** How long a page slides (add-screen-transitions D1). */
const val PAGE_SLIDE_MILLIS = 300

/**
 * The slide between a page and the one it opens (add-screen-transitions D1): going [forward], the new page comes in
 * from the right while the old one moves a quarter to the left and fades; going back is the mirror image.
 */
fun pageSlide(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(PAGE_SLIDE_MILLIS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(PAGE_SLIDE_MILLIS, easing = FastOutSlowInEasing)
    return if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade))
    } else {
        (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(fade))
    }
}

/** [pageSlide] for an [AnimatedContent] whose states are ordered from the first page to the deepest. */
fun <S : Comparable<S>> AnimatedContentTransitionScope<S>.pageSlide(): ContentTransform = pageSlide(targetState > initialState)

/**
 * Switches between the start screen and Settings & help with a slide. The working set and a running export live in the
 * app-wide session, so leaving the start screen does not touch them; each screen's saved UI state, such as the grid's
 * scroll position, is kept while the other one is shown (add-screen-transitions D2).
 */
@Composable
fun AppScreens(
    settingsHelp: @Composable (back: () -> Unit) -> Unit,
    start: @Composable (openSettings: () -> Unit) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Start) }
    val saved = rememberSaveableStateHolder()
    AnimatedContent(targetState = screen, transitionSpec = { pageSlide() }, label = "screen") { shown ->
        saved.SaveableStateProvider(shown.name) {
            when (shown) {
                Screen.Start -> start { screen = Screen.SettingsHelp }
                Screen.SettingsHelp -> settingsHelp { screen = Screen.Start }
            }
        }
    }
}
