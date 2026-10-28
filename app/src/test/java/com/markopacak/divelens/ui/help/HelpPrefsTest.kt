package com.markopacak.divelens.ui.help

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpPrefsTest {
    private val stored = mutableMapOf<String, Boolean>()
    private val flags = object : HelpPrefs.Flags {
        override fun get(key: String, default: Boolean) = stored[key] ?: default
        override fun set(key: String, value: Boolean) { stored[key] = value }
    }

    @Test fun everyTipIsPendingAtFirst() {
        assertEquals(Tip.entries.toSet(), HelpPrefs(flags).pending.value)
    }

    @Test fun aDismissedTipStaysDismissedAfterARestart() {
        HelpPrefs(flags).done(Tip.PhotoHold)
        assertEquals(setOf(Tip.PhotoZoom, Tip.VideoHold), HelpPrefs(flags).pending.value) // a new instance, as after a restart
    }

    @Test fun resetBringsEveryTipBack() {
        val prefs = HelpPrefs(flags)
        Tip.entries.forEach(prefs::done)
        assertEquals(emptySet<Tip>(), prefs.pending.value)
        prefs.resetTips()
        assertEquals(Tip.entries.toSet(), prefs.pending.value)
        assertEquals(Tip.entries.toSet(), HelpPrefs(flags).pending.value)
    }

    @Test fun photosShowHoldThenZoomAndVideosHold() {
        assertEquals(Tip.PhotoHold, nextTip(isVideo = false, Tip.entries.toSet()))
        assertEquals(Tip.PhotoZoom, nextTip(isVideo = false, setOf(Tip.PhotoZoom, Tip.VideoHold)))
        assertEquals(null, nextTip(isVideo = false, setOf(Tip.VideoHold)))
        assertEquals(Tip.VideoHold, nextTip(isVideo = true, Tip.entries.toSet()))
        assertEquals(null, nextTip(isVideo = true, setOf(Tip.PhotoHold, Tip.PhotoZoom)))
    }
}
