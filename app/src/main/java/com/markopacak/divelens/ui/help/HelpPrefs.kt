package com.markopacak.divelens.ui.help

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The viewer's one-time gesture tips (add-help-and-legal D6), in the order a page shows them. */
enum class Tip(val key: String) {
    PhotoHold("tip_photo_hold"),
    PhotoZoom("tip_photo_zoom"),
    VideoHold("tip_video_hold"),
}

/** Which tips are still to show. A tip marked done stays done across restarts until [resetTips]. */
class HelpPrefs(private val flags: Flags) {
    /** Where the flags are kept: SharedPreferences in the app, a map in tests. A missing flag reads as [default]. */
    interface Flags {
        fun get(key: String, default: Boolean = false): Boolean
        fun set(key: String, value: Boolean)
    }

    private val pendingState = MutableStateFlow(Tip.entries.filterNot { flags.get(it.key) }.toSet())
    val pending: StateFlow<Set<Tip>> = pendingState.asStateFlow()

    fun done(tip: Tip) {
        if (tip !in pendingState.value) return
        flags.set(tip.key, true)
        pendingState.update { it - tip }
    }

    fun resetTips() {
        Tip.entries.forEach { flags.set(it.key, false) }
        pendingState.value = Tip.entries.toSet()
    }

    companion object {
        fun of(prefs: SharedPreferences) = HelpPrefs(flags(prefs))

        /** [Flags] kept in [prefs]. */
        fun flags(prefs: SharedPreferences): Flags = object : Flags {
            override fun get(key: String, default: Boolean) = prefs.getBoolean(key, default)
            override fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
        }
    }
}
