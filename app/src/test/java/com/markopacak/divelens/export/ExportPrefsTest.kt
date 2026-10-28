package com.markopacak.divelens.export

import com.markopacak.divelens.ui.help.HelpPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watermark switch: on for a new installation, remembered across restarts (add-branding-and-watermark task 5.1). */
class ExportPrefsTest {
    private val stored = mutableMapOf<String, Boolean>()
    private val flags = object : HelpPrefs.Flags {
        override fun get(key: String, default: Boolean) = stored[key] ?: default
        override fun set(key: String, value: Boolean) { stored[key] = value }
    }

    @Test fun onForANewInstallation() {
        assertTrue(ExportPrefs(flags).watermark.value)
        assertEquals(ExportOptions(watermark = true), ExportPrefs(flags).options())
    }

    @Test fun offSurvivesARestart() {
        ExportPrefs(flags).setWatermark(false)
        val afterRestart = ExportPrefs(flags)
        assertFalse(afterRestart.watermark.value)
        assertEquals(ExportOptions.PLAIN, afterRestart.options())
        assertEquals(false, stored[ExportPrefs.KEY_WATERMARK])
    }

    @Test fun theStateFollowsTheSwitch() {
        val prefs = ExportPrefs(flags)
        prefs.setWatermark(false)
        assertFalse(prefs.watermark.value)
        prefs.setWatermark(true)
        assertTrue(prefs.watermark.value)
    }
}
