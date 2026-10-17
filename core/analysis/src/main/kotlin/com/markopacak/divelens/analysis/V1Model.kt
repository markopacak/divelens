package com.markopacak.divelens.analysis

import kotlin.math.floor
import kotlin.math.min

/** Statistics of an [AnalysisImage] that the model uses. Values are encoded RGB in 0..1. */
data class ImageStats(
    val meanR: Double, val meanG: Double, val meanB: Double,
    val lowG: Double, val highG: Double, val lowB: Double, val highB: Double,
) {
    companion object {
        /** [highPercentile] of 100 or more means the maximum. */
        fun of(image: AnalysisImage, lowPercentile: Double, highPercentile: Double): ImageStats {
            val n = image.width * image.height
            val g = DoubleArray(n); val b = DoubleArray(n)
            var sr = 0.0; var sg = 0.0; var sb = 0.0
            for (i in 0 until n) {
                sr += image.rgb[i * 3]; g[i] = image.rgb[i * 3 + 1].toDouble(); b[i] = image.rgb[i * 3 + 2].toDouble()
                sg += g[i]; sb += b[i]
            }
            g.sort(); b.sort()
            fun high(v: DoubleArray) = if (highPercentile >= 100) v.last() else percentile(v, highPercentile)
            return ImageStats(sr / n, sg / n, sb / n, percentile(g, lowPercentile), high(g), percentile(b, lowPercentile), high(b))
        }

        /** numpy's default ("linear") percentile of an already sorted array. */
        internal fun percentile(sorted: DoubleArray, p: Double): Double {
            val pos = p / 100 * (sorted.size - 1)
            val i = floor(pos).toInt()
            val f = pos - i
            return if (i + 1 >= sorted.size) sorted[i] else sorted[i] * (1 - f) + sorted[i + 1] * f
        }
    }
}

/** Fitted coefficients of the correction model (see [V1Model]). */
data class ModelCoefficients(
    val c0: Double, val c1: Double, val c2: Double, val beta: Double,
    val lowPercentile: Double, val highPercentile: Double, val redOffsetPerAlpha: Double,
    /** Maximum green/blue stretch gain; 0 for none. */
    val gainCap: Double,
    /** Upper limit of the green/blue black point, so bright open water is not turned dark (design D10). */
    val blackPointMax: Double = 1.0,
    /** If above 0, the black point is also at most this fraction of the channel's mean (look F, design D10). */
    val blackPointMeanFraction: Double = 0.0,
    /** No red cast in dark water: in dark results red may not end above green unless it already was (design D10). */
    val darkRedCap: Boolean = true,
)

/**
 * The automatic correction model, after RefApp (design D1/D2, D10 of add-auto-correction):
 * - red is rebuilt from green minus some blue, `R + a·(G − β·B) + γ·a`, with `a` from the mean R/G and B/G;
 * - green and blue get a levels stretch between a low and a high percentile, at most [ModelCoefficients.gainCap];
 * - the black point is limited, so bright open water is not turned black;
 * - red is never stretched on its own and never lowered, except that dark areas get no red cast
 *   (red may not end above green there unless it already was).
 *
 * The app uses look "H2" ([COEFFICIENTS]), fitted by `tools/reference/fit.py` with its defaults (box
 * downscale, white point at the 99.9th percentile, proportional red offset, gain cap 3.0, black point
 * at most 0.45, both red rules) on 14 RefApp scenes, 2026-10-17: in-sample mean ΔE 6.14 with the
 * strength matched per image, 7.42 at the default strength; held-out HELD_OUT.
 * [LOOK_E] and [LOOK_F] are alternatives kept for a possible choice of looks; the app does not use them.
 * Refit when the reference set changes.
 */
object V1Model {
    /** Width of the analysis copy. The coefficients were fitted at this width; changing it means refitting. */
    const val ANALYSIS_WIDTH = 200

    const val ALPHA_MAX = 2.5

    /** Below this mean green the photo is treated as black and left unchanged. */
    const val MIN_MEAN_GREEN = 0.01

    /** Look "H2", the app's (design D10). */
    val COEFFICIENTS = ModelCoefficients(
        c0 = 1.5409, c1 = -2.1504, c2 = 0.4625, beta = 0.4840, lowPercentile = 0.2277, redOffsetPerAlpha = -0.0422,
        highPercentile = 99.9, gainCap = 3.0, blackPointMax = 0.45,
    )

    /** Median estimated RefApp strength in the reference fit of look H2 (0.783). */
    const val DEFAULT_STRENGTH = 0.78f

    /**
     * Look "E" (design D10): stretch at most 2×, no dark red cap. Calmer open water on photos, but
     * darkens and purples deep-blue video. Fitted with `fit.py --gain-cap 2.0 --no-red-cap`.
     */
    val LOOK_E = ModelCoefficients(
        c0 = 1.9588, c1 = -2.4943, c2 = 0.2485, beta = 0.4219, lowPercentile = 0.1875, redOffsetPerAlpha = -0.0872,
        highPercentile = 99.9, gainCap = 2.0, blackPointMax = 0.45, darkRedCap = false,
    )
    const val LOOK_E_STRENGTH = 0.77f

    /**
     * Look "F" (design D10): E, with the black point also at most half the channel's mean. Better than E
     * on some blue scenes, worse on others. Fitted with `fit.py --gain-cap 2.0 --no-red-cap --black-mean-fraction 0.5`.
     */
    val LOOK_F = ModelCoefficients(
        c0 = 2.3856, c1 = -3.6735, c2 = 0.2490, beta = 0.4643, lowPercentile = 0.0446, redOffsetPerAlpha = -0.0600,
        highPercentile = 99.9, gainCap = 2.0, blackPointMax = 0.45, blackPointMeanFraction = 0.5, darkRedCap = false,
    )
    const val LOOK_F_STRENGTH = 0.89f

    fun estimate(image: AnalysisImage, strength: Float = DEFAULT_STRENGTH, k: ModelCoefficients = COEFFICIENTS): CorrectionParams =
        estimate(ImageStats.of(image, k.lowPercentile.coerceIn(0.0, 20.0), k.highPercentile), strength, k)

    fun estimate(stats: ImageStats, strength: Float = DEFAULT_STRENGTH, k: ModelCoefficients = COEFFICIENTS): CorrectionParams {
        if (stats.meanG < MIN_MEAN_GREEN) return CorrectionParams.IDENTITY.withStrength(strength)
        val a = alpha(stats, k)
        val lG = blackPoint(stats.lowG, stats.meanG, k); val lB = blackPoint(stats.lowB, stats.meanB, k)
        val gG = gain(lG, stats.highG, k.gainCap)
        val gB = gain(lB, stats.highB, k.gainCap)
        val matrix = doubleArrayOf(
            1.0, a, -a * k.beta, k.redOffsetPerAlpha * a,
            0.0, gG, 0.0, -lG * gG,
            0.0, 0.0, gB, -lB * gB,
        )
        return CorrectionParams(FloatArray(12) { matrix[it].toFloat() }, strength, k.darkRedCap)
    }

    fun alpha(stats: ImageStats, k: ModelCoefficients = COEFFICIENTS): Double =
        (k.c0 + k.c1 * stats.meanR / stats.meanG + k.c2 * stats.meanB / stats.meanG).coerceIn(0.0, ALPHA_MAX)

    private fun blackPoint(low: Double, mean: Double, k: ModelCoefficients): Double {
        val l = min(low, k.blackPointMax)
        return if (k.blackPointMeanFraction > 0) min(l, k.blackPointMeanFraction * mean) else l
    }

    private fun gain(low: Double, high: Double, cap: Double): Double {
        val g = 1 / maxOf(high - low, 1e-3)
        return if (cap > 0) min(g, cap) else g
    }
}
