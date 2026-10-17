package com.markopacak.divelens.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** The small copy a photo is analysed on: RGB in 0..1, row-major, 3 floats per pixel. */
class AnalysisImage(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(rgb.size == width * height * 3)
    }
}

/**
 * Builds an [AnalysisImage] from a full-size ARGB image fed in row bands, so the full image never
 * needs a second copy. Area average with integer bounds: output column j averages input columns
 * `j*w/W until (j+1)*w/W`, rows likewise, with `H = round(h*W/w)` (ties to even). This matches
 * `box_downscale` in `tools/reference/common.py`; the model was fitted on exactly these values.
 */
class AnalysisImageBuilder(private val sourceWidth: Int, private val sourceHeight: Int, maxWidth: Int = V1Model.ANALYSIS_WIDTH) {

    init {
        require(sourceWidth > 0 && sourceHeight > 0)
    }

    val width = min(maxWidth, sourceWidth)
    val height = max(1, round(sourceHeight.toDouble() * width / sourceWidth).toInt()) // ties to even, like Python

    private val colStart = IntArray(width + 1) { ((it.toLong() * sourceWidth) / width).toInt() }
    private val rowStart = IntArray(height + 1) { ((it.toLong() * sourceHeight) / height).toInt() }
    private val out = FloatArray(width * height * 3)
    private val sums = LongArray(width * 3)
    private var nextRow = 0
    private var outRow = 0

    /** Feeds [rowCount] rows of ARGB pixels starting at [offset], [stride] ints per row, in order from the top. */
    fun addRows(pixels: IntArray, offset: Int, stride: Int, rowCount: Int) {
        for (r in 0 until rowCount) {
            check(nextRow < sourceHeight) { "more rows than the image has" }
            val base = offset + r * stride
            var j = 0
            for (x in 0 until sourceWidth) {
                while (x >= colStart[j + 1]) j++
                val p = pixels[base + x]
                sums[j * 3] += (p shr 16 and 0xff).toLong()
                sums[j * 3 + 1] += (p shr 8 and 0xff).toLong()
                sums[j * 3 + 2] += (p and 0xff).toLong()
            }
            nextRow++
            if (nextRow == rowStart[outRow + 1]) emitRow()
        }
    }

    private fun emitRow() {
        val rows = rowStart[outRow + 1] - rowStart[outRow]
        for (j in 0 until width) {
            val n = (rows.toLong() * (colStart[j + 1] - colStart[j])) * 255.0
            for (c in 0 until 3) {
                out[(outRow * width + j) * 3 + c] = (sums[j * 3 + c] / n).toFloat()
                sums[j * 3 + c] = 0
            }
        }
        outRow++
    }

    fun build(): AnalysisImage {
        check(nextRow == sourceHeight) { "fed $nextRow of $sourceHeight rows" }
        return AnalysisImage(width, height, out)
    }

    companion object {
        /** Convenience for a whole image held in one array. */
        fun of(pixels: IntArray, width: Int, height: Int, maxWidth: Int = V1Model.ANALYSIS_WIDTH): AnalysisImage =
            AnalysisImageBuilder(width, height, maxWidth).apply { addRows(pixels, 0, width, height) }.build()
    }
}
