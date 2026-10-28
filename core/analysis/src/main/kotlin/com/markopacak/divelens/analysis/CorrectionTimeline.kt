package com.markopacak.divelens.analysis

import kotlin.math.exp

/**
 * A video's correction over time (add-video-processing D1): the model's 3×4 matrices estimated on frames
 * sampled through the clip, smoothed over time with a Gaussian of [SIGMA_US] weighted by the real sample
 * times, linearly interpolated between samples and held before the first and after the last.
 *
 * Each sample may also carry a few ARGB pixels from the frame's watermark corner (add-branding-and-watermark D6), which
 * the export uses to pick the watermark version once per clip; nothing here reads them.
 *
 * Immutable: [withSample] returns a new timeline. A timeline with only some samples answers as if the clip
 * ended at its last sample, so playback can start before the analysis is done. The matrices may come from
 * any look's estimator; nothing here depends on the model. `tools/reference/video_check.py` is the reference.
 */
class CorrectionTimeline private constructor(
    /** Length of the clip, for planning samples. */
    val durationUs: Long,
    private val times: LongArray,
    private val raw: Array<DoubleArray>,
    private val cornerPixels: Array<IntArray?>,
    internal val darkRedCap: Boolean,
    /** True once every planned sample is in. */
    val isComplete: Boolean,
) {
    constructor(durationUs: Long) : this(durationUs, LongArray(0), emptyArray(), emptyArray(), true, false)

    private val smoothed: Array<DoubleArray> = smooth(times, raw)

    val sampleCount: Int get() = times.size

    /** Times of the samples, in order. */
    val sampleTimesUs: List<Long> get() = times.toList()

    /** The samples' corner pixels, by sample time, for the samples that have them. */
    val corners: List<Pair<Long, IntArray>>
        get() = times.indices.mapNotNull { i -> cornerPixels[i]?.let { times[i] to it.copyOf() } }

    /** This timeline with the sample at [timeUs] (replacing one at the same time), with its [corner] pixels if given. */
    fun withSample(timeUs: Long, params: CorrectionParams, corner: IntArray? = null): CorrectionTimeline {
        val m = DoubleArray(12) { params.matrix[it].toDouble() }
        val c = corner?.copyOf()
        val i = times.binarySearch(timeUs)
        if (i >= 0) {
            val r = raw.copyOf(); r[i] = m
            val k = cornerPixels.copyOf(); k[i] = c
            return CorrectionTimeline(durationUs, times, r, k, params.darkRedCap, isComplete)
        }
        val at = -i - 1
        val t = LongArray(times.size + 1) { if (it < at) times[it] else if (it == at) timeUs else times[it - 1] }
        val r = Array(raw.size + 1) { if (it < at) raw[it] else if (it == at) m else raw[it - 1] }
        val k = Array(cornerPixels.size + 1) { if (it < at) cornerPixels[it] else if (it == at) c else cornerPixels[it - 1] }
        return CorrectionTimeline(durationUs, t, r, k, params.darkRedCap, isComplete)
    }

    /** This timeline marked as complete: every planned sample is in. */
    fun completed(): CorrectionTimeline = CorrectionTimeline(durationUs, times, raw, cornerPixels, darkRedCap, true)

    /** The smoothed, interpolated 3×4 row-major matrix at [timeUs], or null without samples. */
    fun matrixAt(timeUs: Long): DoubleArray? {
        if (times.isEmpty()) return null
        if (timeUs <= times[0]) return smoothed[0].copyOf()
        if (timeUs >= times.last()) return smoothed.last().copyOf()
        var k = times.binarySearch(timeUs)
        if (k >= 0) return smoothed[k].copyOf()
        k = -k - 2
        val f = (timeUs - times[k]).toDouble() / (times[k + 1] - times[k])
        return DoubleArray(12) { smoothed[k][it] * (1 - f) + smoothed[k + 1][it] * f }
    }

    /** The correction at [timeUs] and [strength]; the identity without samples. */
    fun paramsAt(timeUs: Long, strength: Float): CorrectionParams = params(matrixAt(timeUs), strength, darkRedCap)

    companion object {
        /** One sample per second of the clip (design D1); shared with `video_check.py`. */
        const val SAMPLE_INTERVAL_US = 1_000_000L

        /** Gaussian smoothing over time, 2 s, settled by the video check (design D9). */
        const val SIGMA_US = 2_000_000L

        /** Times to sample a clip of [durationUs] at: every [SAMPLE_INTERVAL_US] from 0, and the end. */
        fun plannedSampleTimes(durationUs: Long): List<Long> =
            (0 until durationUs step SAMPLE_INTERVAL_US).toList() + durationUs

        internal fun params(m: DoubleArray?, strength: Float, darkRedCap: Boolean): CorrectionParams =
            if (m == null) CorrectionParams.IDENTITY.withStrength(strength)
            else CorrectionParams(FloatArray(12) { m[it].toFloat() }, strength, darkRedCap)

        private fun smooth(times: LongArray, raw: Array<DoubleArray>): Array<DoubleArray> {
            val twoSigma2 = 2.0 * SIGMA_US.toDouble() * SIGMA_US
            return Array(times.size) { i ->
                val out = DoubleArray(12)
                var wsum = 0.0
                for (j in times.indices) {
                    val d = (times[i] - times[j]).toDouble()
                    val w = exp(-d * d / twoSigma2)
                    wsum += w
                    for (e in 0 until 12) out[e] += w * raw[j][e]
                }
                for (e in 0 until 12) out[e] /= wsum
                out
            }
        }
    }
}

/**
 * The timeline a playing video follows (design D1, spec "Playback starts without waiting"): when a new
 * timeline changes the answer for times already shown, the correction eases from the old answer to the new
 * one over [EASE_US] of playback from the position shown when it arrived, so no jump is visible. Safe to
 * call from the render thread and the thread that delivers new timelines.
 */
class EasedTimeline(timeline: CorrectionTimeline) {
    private sealed interface Source {
        fun matrixAt(timeUs: Long): DoubleArray?
    }

    private class Plain(val timeline: CorrectionTimeline) : Source {
        override fun matrixAt(timeUs: Long) = timeline.matrixAt(timeUs)
    }

    private class Blend(val old: Source, val new: Plain, val startUs: Long) : Source {
        fun progress(timeUs: Long): Double =
            if (timeUs < startUs) 1.0 else ((timeUs - startUs).toDouble() / EASE_US).coerceAtMost(1.0)

        override fun matrixAt(timeUs: Long): DoubleArray? {
            val b = new.matrixAt(timeUs) ?: return null
            val f = progress(timeUs)
            if (f >= 1.0) return b
            val a = old.matrixAt(timeUs) ?: return b
            return DoubleArray(12) { a[it] * (1 - f) + b[it] * f }
        }
    }

    private var source: Source = Plain(timeline)
    private var lastShownUs = 0L

    @get:Synchronized
    val timeline: CorrectionTimeline
        get() = when (val s = source) { is Plain -> s.timeline; is Blend -> s.new.timeline }

    /** Follows [next] from now on, easing from the current answer at the last position shown. */
    @Synchronized
    fun update(next: CorrectionTimeline) {
        var current = source
        if (current is Blend && current.progress(lastShownUs) >= 1.0) current = current.new
        source = if (current is Plain && current.timeline.sampleCount == 0) Plain(next) else Blend(current, Plain(next), lastShownUs)
    }

    /** The matrix for the frame at [timeUs], which is now the position shown; null without samples. */
    @Synchronized
    fun matrixAt(timeUs: Long): DoubleArray? {
        lastShownUs = timeUs
        return source.matrixAt(timeUs)
    }

    @Synchronized
    fun paramsAt(timeUs: Long, strength: Float): CorrectionParams =
        CorrectionTimeline.params(matrixAt(timeUs), strength, timeline.darkRedCap)

    companion object {
        /** How long the change to a newly analysed answer takes, in playback time (design D1). */
        const val EASE_US = 500_000L
    }
}
