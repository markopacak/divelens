package com.markopacak.divelens.analysis

/**
 * A photo's model parameters: the full correction is `M·rgb + t` with the red rules of
 * [CpuCorrection.full], on encoded values in 0..1, applied at [strength]. [matrix] is 3×4 row-major:
 * for each output channel R, G, B the weights of input R, G, B followed by the offset t.
 * [darkRedCap] turns on "no red cast in dark water" (design D10).
 */
class CorrectionParams(matrix: FloatArray, val strength: Float, val darkRedCap: Boolean = true) {

    init {
        require(matrix.size == 12) { "matrix must be 3×4" }
        require(strength in 0f..1f) { "strength must be in 0..1" }
    }

    private val m = matrix.copyOf()

    /** A copy of the 3×4 row-major matrix. */
    val matrix: FloatArray get() = m.copyOf()

    operator fun get(row: Int, col: Int): Float = m[row * 4 + col]

    fun withStrength(strength: Float) = CorrectionParams(m, strength, darkRedCap)

    override fun equals(other: Any?) =
        other is CorrectionParams && other.strength == strength && other.darkRedCap == darkRedCap && other.m.contentEquals(m)

    override fun hashCode() = (31 * m.contentHashCode() + strength.hashCode()) * 31 + darkRedCap.hashCode()

    override fun toString() = "CorrectionParams(matrix=${m.toList()}, strength=$strength, darkRedCap=$darkRedCap)"

    companion object {
        private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        /** Leaves every pixel unchanged. */
        val IDENTITY = CorrectionParams(IDENTITY_MATRIX, 0f)
    }
}
