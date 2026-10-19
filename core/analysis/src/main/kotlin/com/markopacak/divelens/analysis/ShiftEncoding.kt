package com.markopacak.divelens.analysis

import kotlin.math.roundToInt

/**
 * A small image's full-strength shift stored per pixel, so it can be shown at any strength without the shift maps
 * (add-strength-slider D3). Each channel's shift in -1..1 is stored as `round((shift + 1) · 127.5)` in the colour
 * bytes of an ARGB pixel, which is off by at most 1/255; shifts beyond ±1 are clamped, which only matters for
 * shifts the map's smoothing pushed past the full range.
 */
object ShiftEncoding {
    fun encode(shift: Float): Int = ((shift.coerceIn(-1f, 1f) + 1f) * 127.5f).roundToInt()

    fun decode(code: Int): Float = code / 127.5f - 1f

    /** The encoded shifts of the [width]×[height] ARGB [pixels] under [correction] (sized for that image). */
    fun encodeShifts(pixels: IntArray, correction: Correction, width: Int, height: Int): IntArray {
        val shift = FloatArray(3)
        return IntArray(width * height) { i ->
            val p = pixels[i]
            CpuCorrection.shift(correction, i % width, i / width, (p shr 16 and 0xff) / 255f, (p shr 8 and 0xff) / 255f, (p and 0xff) / 255f, shift)
            (0xff shl 24) or (encode(shift[0]) shl 16) or (encode(shift[1]) shl 8) or encode(shift[2])
        }
    }

    /** [pixels] corrected at [strength] by their encoded [shifts], into [out]: `clamp(x + s · shift)` as [CpuCorrection] does. */
    fun render(pixels: IntArray, shifts: IntArray, strength: Float, out: IntArray) {
        // One table per strength: the decoded shift times the strength, for every code.
        val scaled = FloatArray(256) { strength * decode(it) }
        for (i in pixels.indices) {
            val p = pixels[i]; val q = shifts[i]
            var o = p and 0xff000000.toInt()
            for (shift in intArrayOf(16, 8, 0)) {
                val x = (p shr shift and 0xff) / 255f
                val v = ((x + scaled[q shr shift and 0xff]).coerceIn(0f, 1f) * 255f).roundToInt()
                o = o or (v shl shift)
            }
            out[i] = o
        }
    }
}
