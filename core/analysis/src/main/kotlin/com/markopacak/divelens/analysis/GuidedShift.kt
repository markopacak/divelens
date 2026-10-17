package com.markopacak.divelens.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Coefficient maps of the correction shift (design D10): at each point of the working copy,
 * `shift_c = a_c·grey + b_c`, with `grey = (R + G + B) / 3`. Row-major, 4 floats per point (RGBA
 * order, the fourth unused) so the GPU can upload them as they are.
 */
class CorrectionMap(val width: Int, val height: Int, val a: FloatArray, val b: FloatArray) {
    init {
        require(a.size == width * height * 4 && b.size == a.size)
    }
}

/**
 * Grain-aware application of the correction (design D10), matching `tools/reference/smooth.py`.
 * The per-pixel colour shift `full(x) − x` goes through a guided filter with the grey level as the
 * guide: in flat areas such as open water the shift becomes its local average, so grain is not
 * amplified; at edges it follows the photo. The filter runs on a working copy [SMOOTH_WIDTH] wide;
 * its coefficients are interpolated to full resolution when the correction is applied.
 */
object GuidedShift {
    const val SMOOTH_WIDTH = 1024
    const val RADIUS_AT_SMOOTH_WIDTH = 2
    const val EPS = 0.02 * 0.02

    fun radius(workingWidth: Int): Int = max(1, round(RADIUS_AT_SMOOTH_WIDTH.toDouble() * workingWidth / SMOOTH_WIDTH).toInt())

    fun map(work: AnalysisImage, params: CorrectionParams): CorrectionMap {
        val w = work.width; val h = work.height; val n = w * h
        val r = min(radius(w), min(w, h))
        val rgb = work.rgb
        val g = FloatArray(n) { (rgb[it * 3] + rgb[it * 3 + 1] + rgb[it * 3 + 2]) / 3f }
        val tmp = FloatArray(n)
        val mg = FloatArray(n).also { box(g, it, tmp, w, h, r) }
        val vg = FloatArray(n).also { v ->
            val gg = FloatArray(n) { g[it] * g[it] }
            box(gg, v, tmp, w, h, r)
            for (i in 0 until n) v[i] -= mg[i] * mg[i]
        }
        val shifts = Array(3) { FloatArray(n) }
        val f = FloatArray(3)
        for (i in 0 until n) {
            CpuCorrection.full(params, rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], f)
            for (c in 0 until 3) shifts[c][i] = f[c] - rgb[i * 3 + c]
        }
        val mp = FloatArray(n); val gp = FloatArray(n); val out = FloatArray(n)
        val a = FloatArray(n * 4); val b = FloatArray(n * 4)
        for (c in 0 until 3) {
            val d = shifts[c]
            box(d, mp, tmp, w, h, r)
            for (i in 0 until n) d[i] *= g[i]
            box(d, gp, tmp, w, h, r)
            for (i in 0 until n) {
                val ai = ((gp[i] - mg[i] * mp[i]) / (vg[i] + EPS)).toFloat()
                gp[i] = ai; mp[i] = mp[i] - ai * mg[i]
            }
            box(gp, out, tmp, w, h, r)
            for (i in 0 until n) a[i * 4 + c] = out[i]
            box(mp, out, tmp, w, h, r)
            for (i in 0 until n) b[i * 4 + c] = out[i]
        }
        return CorrectionMap(w, h, a, b)
    }

    /** Mean over a (2r+1)² window with mirrored edges (d c b a | a b c d); [r] at most min(w, h). */
    internal fun box(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        val k = 2.0 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0.0
            for (i in -r..r) sum += src[base + mirror(i, w)]
            tmp[base] = (sum / k).toFloat()
            for (i in 1 until w) {
                sum += src[base + mirror(i + r, w)] - src[base + mirror(i - r - 1, w)]
                tmp[base + i] = (sum / k).toFloat()
            }
        }
        // Vertical pass row by row with running column sums, so memory is read in order.
        val col = DoubleArray(w)
        for (j in -r..r) {
            val row = mirror(j, h) * w
            for (x in 0 until w) col[x] += tmp[row + x]
        }
        for (x in 0 until w) dst[x] = (col[x] / k).toFloat()
        for (y in 1 until h) {
            val add = mirror(y + r, h) * w; val sub = mirror(y - r - 1, h) * w; val out = y * w
            for (x in 0 until w) {
                col[x] += tmp[add + x] - tmp[sub + x]
                dst[out + x] = (col[x] / k).toFloat()
            }
        }
    }

    private fun mirror(i: Int, n: Int) = if (i < 0) -i - 1 else if (i >= n) 2 * n - i - 1 else i
}
