package com.markopacak.divelens.export

import com.markopacak.divelens.ui.help.HelpPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The export settings (add-branding-and-watermark D8): whether exports get the watermark. On for a new installation and
 * available to everyone; kept in the app's preferences file next to the help flags.
 */
class ExportPrefs(private val flags: HelpPrefs.Flags) {
    private val watermarkState = MutableStateFlow(flags.get(KEY_WATERMARK, default = true))
    val watermark: StateFlow<Boolean> = watermarkState.asStateFlow()

    fun setWatermark(on: Boolean) {
        flags.set(KEY_WATERMARK, on)
        watermarkState.value = on
    }

    /** The options for a batch that starts now. */
    fun options() = ExportOptions(watermark = watermarkState.value)

    companion object {
        const val KEY_WATERMARK = "watermark_on_exports"
    }
}
