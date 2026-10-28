package com.markopacak.divelens.export

/**
 * Choices that apply to a whole export batch, read once when it starts (add-branding-and-watermark D8), so a setting
 * changed during an export applies from the next one.
 */
data class ExportOptions(val watermark: Boolean) {
    companion object {
        /** No watermark; for tests that compare exported pixels with the source. */
        val PLAIN = ExportOptions(watermark = false)
    }
}
