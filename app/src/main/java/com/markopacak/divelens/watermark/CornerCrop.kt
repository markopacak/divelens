package com.markopacak.divelens.watermark

import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.CpuCorrection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The watermark corner of a video's sampled frames (add-branding-and-watermark D6). Each sample keeps the frame's
 * measurement area box-averaged into at most [MAX_COLUMNS] × [MAX_ROWS] cells, a few hundred bytes. At export the
 * cells are corrected on the CPU at the export's strength and the median of their mean lumas picks the clip's version.
 */
object CornerCrop {
    const val MAX_COLUMNS = 24
    const val MAX_ROWS = 8

    /** The measurement area of an upright [width] × [height] frame ([pixels], ARGB), box-averaged into cells. */
    fun of(pixels: IntArray, width: Int, height: Int, markAspect: Float): IntArray {
        val area = WatermarkPlacement(width, height, markAspect).measured
        val left = floor(area.left).toInt().coerceIn(0, width - 1)
        val top = floor(area.top).toInt().coerceIn(0, height - 1)
        val right = ceil(area.right).toInt().coerceIn(left + 1, width)
        val bottom = ceil(area.bottom).toInt().coerceIn(top + 1, height)
        val cols = min(MAX_COLUMNS, right - left)
        val rows = min(MAX_ROWS, bottom - top)
        return IntArray(cols * rows) { cell ->
            val cx = cell % cols; val cy = cell / cols
            val x0 = left + (right - left) * cx / cols; val x1 = left + (right - left) * (cx + 1) / cols
            val y0 = top + (bottom - top) * cy / rows; val y1 = top + (bottom - top) * (cy + 1) / rows
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * width + x]
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
            }
            (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }

    /**
     * Mean luma of [cells] corrected with [params] at its strength, as the video shader adds the correction:
     * `c + strength · (full(c) − c)`.
     */
    fun correctedMeanLuma(cells: IntArray, params: CorrectionParams): Double {
        val out = FloatArray(3)
        val corrected = IntArray(cells.size) { i ->
            val p = cells[i]
            val r = (p shr 16 and 0xFF) / 255f; val g = (p shr 8 and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            CpuCorrection.full(params, r, g, b, out)
            val s = params.strength
            fun channel(c: Float, f: Float) = ((c + s * (f - c)).coerceIn(0f, 1f) * 255).roundToInt()
            (0xFF shl 24) or (channel(r, out[0]) shl 16) or (channel(g, out[1]) shl 8) or channel(b, out[2])
        }
        return WatermarkVersion.meanLuma(corrected, corrected.size, 1)
    }

    /**
     * The version for the whole clip: the median over [timeline]'s samples of their corner's corrected mean luma at
     * [strength]. A timeline without corners gets the light version.
     */
    fun versionFor(timeline: CorrectionTimeline, strength: Float): WatermarkVersion {
        val lumas = timeline.corners.map { (time, cells) -> correctedMeanLuma(cells, timeline.paramsAt(time, strength)) }
        return if (lumas.isEmpty()) WatermarkVersion.Light else WatermarkVersion.forLuma(WatermarkVersion.median(lumas))
    }
}
