package com.markopacak.divelens.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShiftEncodingTest {

    @Test fun roundTripIsWithinOneStep() {
        var worst = 0f
        for (i in -1000..1000) {
            val shift = i / 1000f
            worst = maxOf(worst, abs(ShiftEncoding.decode(ShiftEncoding.encode(shift)) - shift))
        }
        assertTrue("worst round-trip error $worst", worst <= 1f / 255 + 1e-6f)
        assertEquals(0, ShiftEncoding.encode(-3f))
        assertEquals(255, ShiftEncoding.encode(3f))
    }

    @Test fun renderMatchesTheCpuCorrectionWithinTwoSteps() {
        val w = 120; val h = 90
        val px = Synthetic.image("deep", w, h)
        val correction = CorrectionAnalyzer.of(px, w, h)
        val shifts = ShiftEncoding.encodeShifts(px, correction, w, h)
        for (s in floatArrayOf(0.3f, V1Model.DEFAULT_STRENGTH, 1f)) {
            val expected = px.copyOf().also { CpuCorrection.applyInPlace(it, correction.withStrength(s), 0, 0, w, h) }
            val actual = IntArray(px.size).also { ShiftEncoding.render(px, shifts, s, it) }
            var worst = 0
            for (i in px.indices) for (c in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs((expected[i] shr c and 0xff) - (actual[i] shr c and 0xff)))
            assertTrue("strength $s: largest channel difference $worst", worst <= 2)
        }
    }

    @Test fun zeroStrengthRendersTheOriginal() {
        val px = Synthetic.image("deep", 60, 40)
        val shifts = ShiftEncoding.encodeShifts(px, CorrectionAnalyzer.of(px, 60, 40), 60, 40)
        assertArrayEquals(px, IntArray(px.size).also { ShiftEncoding.render(px, shifts, 0f, it) })
    }
}
