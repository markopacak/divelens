package com.markopacak.divelens.analysis

/** Everything needed to correct one photo of [width]×[height] pixels: model parameters and shift maps. */
class Correction(val params: CorrectionParams, val map: CorrectionMap, val width: Int, val height: Int)

/**
 * Builds a photo's [Correction] from its ARGB pixels fed in row bands from the top, so the full
 * photo never needs a second copy: the analysis copy for the model and the working copy for the
 * shift maps are downscaled in the same pass.
 */
class CorrectionAnalyzer(private val width: Int, private val height: Int) {
    private val analysis = AnalysisImageBuilder(width, height)
    private val working = AnalysisImageBuilder(width, height, GuidedShift.SMOOTH_WIDTH)

    fun addRows(pixels: IntArray, offset: Int, stride: Int, rowCount: Int) {
        analysis.addRows(pixels, offset, stride, rowCount)
        working.addRows(pixels, offset, stride, rowCount)
    }

    fun build(strength: Float = V1Model.DEFAULT_STRENGTH, k: ModelCoefficients = V1Model.COEFFICIENTS): Correction {
        val params = V1Model.estimate(analysis.build(), strength, k)
        return Correction(params, GuidedShift.map(working.build(), params), width, height)
    }

    companion object {
        /** Convenience for a whole photo held in one array. */
        fun of(pixels: IntArray, width: Int, height: Int, strength: Float = V1Model.DEFAULT_STRENGTH): Correction =
            CorrectionAnalyzer(width, height).apply { addRows(pixels, 0, width, height) }.build(strength)
    }
}
