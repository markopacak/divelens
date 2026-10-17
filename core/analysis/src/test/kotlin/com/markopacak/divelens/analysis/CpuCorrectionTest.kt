package com.markopacak.divelens.analysis

import com.markopacak.divelens.analysis.Synthetic.argb
import com.markopacak.divelens.analysis.Synthetic.b
import com.markopacak.divelens.analysis.Synthetic.g
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CpuCorrectionTest {

    /** Deterministic noise in -amp..amp (an LCG, so tests never flake). */
    private class Noise(private var state: Long = 12345) {
        fun next(amp: Int): Int {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return ((state ushr 33) % (2 * amp + 1)).toInt() - amp
        }
    }

    private fun correct(px: IntArray, w: Int, h: Int, strength: Float = V1Model.DEFAULT_STRENGTH): IntArray {
        val correction = CorrectionAnalyzer.of(px, w, h, strength)
        return px.copyOf().also { CpuCorrection.applyInPlace(it, correction, 0, 0, w, h) }
    }

    @Test
    fun zeroStrengthIsIdentity() {
        val px = Synthetic.image("deep", 300, 200)
        assertArrayEquals(px, correct(px, 300, 200, strength = 0f))
    }

    @Test
    fun deepPhotoGetsMoreRed() {
        val px = Synthetic.image("deep", 300, 200)
        val out = correct(px, 300, 200)
        assertTrue(out.map(Synthetic::r).average() > 1.5 * px.map(Synthetic::r).average())
    }

    @Test
    fun neutralGreyRampChangesLittleAndStaysNeutral() {
        val w = 256; val h = 40
        val px = IntArray(w * h) { val v = it % w; argb(v, v, v) }
        val out = correct(px, w, h)
        val dE = px.indices.map { ColorDifference.deltaE(px[it], out[it]) }.average()
        val labs = out.map(ColorDifference::lab)
        assertTrue("mean ΔE $dE", dE <= 3)
        assertTrue("a*", abs(labs.map { it[1] }.average()) <= 2)
        assertTrue("b*", abs(labs.map { it[2] }.average()) <= 2)
    }

    @Test
    fun darkOpenWaterStaysBlue() {
        // Vertical gradient from dark blue (bottom) to lighter blue-green (top), almost no red, sensor noise.
        val w = 400; val h = 300
        val n = Noise()
        val px = IntArray(w * h) {
            val t = 1.0 - (it / w).toDouble() / h
            argb((3 + n.next(3)).coerceIn(0, 255), (25 + 55 * t + n.next(4)).toInt().coerceIn(0, 255), (50 + 80 * t + n.next(4)).toInt().coerceIn(0, 255))
        }
        val out = correct(px, w, h)
        val darkest = px.indices.sortedBy { ColorDifference.lab(px[it])[0] }.take(px.size / 10)
        val meanA = darkest.map { ColorDifference.lab(out[it])[1] }.average()
        assertTrue("mean a* of darkest 10%: $meanA", meanA <= 5)
    }

    /** Flat, grainy open water: a narrow range of colours, so the stretch is at its cap. */
    private fun grainyWater(w: Int, h: Int, seed: Long) = Noise(seed).let { n ->
        IntArray(w * h) { argb(20 + n.next(3), 100 + n.next(6), 150 + n.next(6)) }
    }

    @Test
    fun grainInFlatWaterIsAmplifiedAtMostOneAndAHalfTimes() {
        val w = 1200; val h = 800
        val px = grainyWater(w, h, 7)
        for (strength in floatArrayOf(V1Model.DEFAULT_STRENGTH, 1f)) {
            val out = correct(px, w, h, strength)
            for ((name, ch) in listOf("red" to { p: Int -> Synthetic.r(p) }, "green" to { p: Int -> g(p) }, "blue" to { p: Int -> b(p) })) {
                val ratio = std(out.map(ch)) / std(px.map(ch))
                assertTrue("$name noise ×$ratio at strength $strength", ratio <= 1.5)
            }
        }
    }

    @Test
    fun brightOpenWaterIsNotTurnedBlack() {
        // All green and blue values above the black-point limit: even at full strength the darkest areas keep colour.
        val w = 400; val h = 300
        val px = Synthetic.image("bright", w, h)
        val out = correct(px, w, h, strength = 1f)
        val darkest = out.map { ColorDifference.lab(it)[0] }.sorted().take(out.size / 100).average()
        assertTrue("mean L* of the darkest 1%: $darkest", darkest >= 10)
    }

    // The red rules act on the shift before smoothing, so they hold for areas rather than for every pixel.
    // Where dark and bright pixels are finely mixed, smoothing carries a little of the dark-area red
    // reduction over to bright neighbours: up to 1.5 steps of 255 on average is allowed for that.

    @Test
    fun redIsNotLoweredOutsideDarkAreas() {
        for (name in listOf("deep", "shallow", "wide", "bright")) {
            val px = Synthetic.image(name, 300, 200)
            val out = correct(px, 300, 200, strength = 1f)
            val bright = px.indices.filter { (g(out[it]) + b(out[it])) / 2 >= CpuCorrection.DARK_END * 255 }
            val before = bright.map { Synthetic.r(px[it]) }.average(); val after = bright.map { Synthetic.r(out[it]) }.average()
            assertTrue("$name: mean red $before → $after", bright.isEmpty() || after >= before - 1.5)
        }
    }

    @Test
    fun darkWaterGetsNoRedCast() {
        // Dark blue water that the stretch makes darker: red must not end above green where it was below it.
        val w = 400; val h = 300
        val px = Synthetic.image("darkwater", w, h)
        val out = correct(px, w, h, strength = 1f)
        val dark = px.indices.filter { (g(out[it]) + b(out[it])) / 2 <= CpuCorrection.DARK_FULL * 255 && Synthetic.r(px[it]) <= g(px[it]) }
        assertTrue("dark pixels: ${dark.size}", dark.size > px.size / 20)
        val red = dark.map { Synthetic.r(out[it]) }.average(); val green = dark.map { g(out[it]) }.average()
        assertTrue("dark areas: mean red $red, mean green $green", red <= green + 2)
    }

    @Test
    fun sameColourInSameSurroundingsGivesSameOutput() {
        // Two identical patches with identical surroundings, far apart, on a uniform background.
        val w = 600; val h = 400
        val px = IntArray(w * h) { argb(20, 110, 170) }
        for ((x0, y0) in listOf(60 to 60, 480 to 300)) for (y in y0 until y0 + 40) for (x in x0 until x0 + 40) px[y * w + x] = argb(90, 60, 40)
        val out = correct(px, w, h)
        for (y in 0 until 40) for (x in 0 until 40) assertEquals(out[(60 + y) * w + 60 + x], out[(300 + y) * w + 480 + x])
    }

    @Test
    fun alphaIsKept() {
        val px = IntArray(64 * 64) { 0x80102030.toInt() or (it % 64 shl 8) }
        val out = correct(px, 64, 64)
        assertTrue(out.all { it ushr 24 == 0x80 })
    }

    private fun std(v: List<Int>): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
