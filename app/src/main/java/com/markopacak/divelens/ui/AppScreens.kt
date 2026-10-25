package com.markopacak.divelens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** The top-level screens (add-help-and-legal D1); the viewer is part of the start screen. */
enum class Screen { Start, SettingsHelp }

/**
 * Switches between the start screen and Settings & help. The working set and a running export live in the app-wide
 * session, so leaving the start screen does not touch them.
 */
@Composable
fun AppScreens(
    settingsHelp: @Composable (back: () -> Unit) -> Unit,
    start: @Composable (openSettings: () -> Unit) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Start) }
    when (screen) {
        Screen.Start -> start { screen = Screen.SettingsHelp }
        Screen.SettingsHelp -> settingsHelp { screen = Screen.Start }
    }
}
