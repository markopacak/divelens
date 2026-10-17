package com.markopacak.divelens.analysis

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The correction on the CPU: the reference for the GPU shader and its fallback. Alpha is kept;
 * colour channels are treated as unpremultiplied (photos are opaque).
 */
object CpuCorrection {

    /** Corrected green/blue level (mean) at and below which red is fully capped, and above which it is free. */
    const val DARK_FULL = 0.2f
    const val DARK_END = 0.4f

    /**
     * The full correction (strength 1) of one pixel into [out], as `fit.full` in `tools/reference`: the
     * matrix; red never lowered, except that in dark results red may not end above green unless it
     * already was (no red cast in dark water, design D10); clamped to 0..1.
     */
    fun full(params: CorrectionParams, r: Float, g: Float, b: Float, out: FloatArray) {
        var rf = params[0, 0] * r + params[0, 1] * g + params[0, 2] * b + params[0, 3]
        val gf = params[1, 0] * r + params[1, 1] * g + params[1, 2] * b + params[1, 3]
        val bf = params[2, 0] * r + params[2, 1] * g + params[2, 2] * b + params[2, 3]
        rf = max(rf, r)
        if (params.darkRedCap) {
            val gc = gf.coerceIn(0f, 1f)
            val limit = gc + max(r - g, 0f)
            val w = ((DARK_END - (gc + bf.coerceIn(0f, 1f)) / 2) / (DARK_END - DARK_FULL)).coerceIn(0f, 1f)
            rf -= w * max(rf - limit, 0f)
        }
        out[0] = rf.coerceIn(0f, 1f); out[1] = gf.coerceIn(0f, 1f); out[2] = bf.coerceIn(0f, 1f)
    }

    /**
     * Corrects the [w]×[h] rectangle at ([x0], [y0]) of the photo, held as ARGB pixels in [pixels]
     * from [offset] with [stride] ints per row, in place. Must stay equivalent to `CorrectionShader`.
     */
    fun applyInPlace(pixels: IntArray, correction: Correction, x0: Int, y0: Int, w: Int, h: Int, offset: Int = 0, stride: Int = w) {
        val map = correction.map
        val s = correction.params.strength
        val mw = map.width; val mh = map.height
        val col0 = IntArray(w); val col1 = IntArray(w); val fx = FloatArray(w)
        for (i in 0 until w) {
            val u = mapCoordinate(x0 + i, mw, correction.width)
            col0[i] = floor(u).toInt(); col1[i] = min(col0[i] + 1, mw - 1); fx[i] = u - col0[i]
        }
        val a = map.a; val b = map.b
        for (j in 0 until h) {
            val v = mapCoordinate(y0 + j, mh, correction.height)
            val row0 = floor(v).toInt(); val row1 = min(row0 + 1, mh - 1); val fy = v - row0
            for (i in 0 until w) {
                val idx = offset + j * stride + i
                val p = pixels[idx]
                val r = (p shr 16 and 0xff) / 255f; val g = (p shr 8 and 0xff) / 255f; val bl = (p and 0xff) / 255f
                val grey = (r + g + bl) / 3f
                val q00 = (row0 * mw + col0[i]) * 4; val q01 = (row0 * mw + col1[i]) * 4
                val q10 = (row1 * mw + col0[i]) * 4; val q11 = (row1 * mw + col1[i]) * 4
                var out = p and 0xff000000.toInt()
                for (c in 0 until 3) {
                    val ac = bilinear(a, q00 + c, q01 + c, q10 + c, q11 + c, fx[i], fy)
                    val bc = bilinear(b, q00 + c, q01 + c, q10 + c, q11 + c, fx[i], fy)
                    val shift = ac * grey + bc
                    val xc = when (c) { 0 -> r; 1 -> g; else -> bl }
                    val o = ((xc + s * shift).coerceIn(0f, 1f) * 255f).roundToInt()
                    out = out or (o shl (16 - 8 * c))
                }
                pixels[idx] = out
            }
        }
    }

    /**
     * The shift at pixel ([x], [y]) for input [r], [g], [b] in 0..1, written to [out] (3 floats), before
     * strength and clamping: the same values [applyInPlace] uses, for evaluation in float.
     */
    fun shift(correction: Correction, x: Int, y: Int, r: Float, g: Float, b: Float, out: FloatArray) {
        val map = correction.map
        val u = mapCoordinate(x, map.width, correction.width); val v = mapCoordinate(y, map.height, correction.height)
        val c0 = floor(u).toInt(); val c1 = min(c0 + 1, map.width - 1); val r0 = floor(v).toInt(); val r1 = min(r0 + 1, map.height - 1)
        val q00 = (r0 * map.width + c0) * 4; val q01 = (r0 * map.width + c1) * 4
        val q10 = (r1 * map.width + c0) * 4; val q11 = (r1 * map.width + c1) * 4
        val grey = (r + g + b) / 3f
        for (c in 0 until 3) {
            out[c] = bilinear(map.a, q00 + c, q01 + c, q10 + c, q11 + c, u - c0, v - r0) * grey +
                bilinear(map.b, q00 + c, q01 + c, q10 + c, q11 + c, u - c0, v - r0)
        }
    }

    /** Position of pixel [p] of an image [imageSize] long on a map [mapSize] long: centres aligned, clamped. */
    fun mapCoordinate(p: Int, mapSize: Int, imageSize: Int): Float =
        ((p + 0.5f) * mapSize / imageSize - 0.5f).coerceIn(0f, mapSize - 1f)

    private fun mix(x: Float, y: Float, t: Float) = x * (1 - t) + y * t

    private fun bilinear(m: FloatArray, q00: Int, q01: Int, q10: Int, q11: Int, fx: Float, fy: Float) =
        mix(mix(m[q00], m[q01], fx), mix(m[q10], m[q11], fx), fy)
}
