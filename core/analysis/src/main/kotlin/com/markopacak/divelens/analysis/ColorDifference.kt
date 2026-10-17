package com.markopacak.divelens.analysis

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** sRGB (D65) to CIE L*a*b* and CIE76 ΔE, as in `tools/reference/common.py`. */
object ColorDifference {

    /** L*, a*, b* of an sRGB-encoded colour with channels in 0..1. */
    fun lab(r: Double, g: Double, b: Double): DoubleArray {
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        val x = (0.4124 * lr + 0.3576 * lg + 0.1805 * lb) / 0.95047
        val y = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
        val z = (0.0193 * lr + 0.1192 * lg + 0.9505 * lb) / 1.08883
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun lab(argb: Int): DoubleArray = lab((argb shr 16 and 0xff) / 255.0, (argb shr 8 and 0xff) / 255.0, (argb and 0xff) / 255.0)

    fun deltaE(p: Int, q: Int): Double {
        val a = lab(p); val b = lab(q)
        return sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))
    }

    private fun lin(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
}
