package com.markopacak.divelens.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class CorrectionTimelineTest {

    private fun params(gainG: Float) =
        CorrectionParams(floatArrayOf(1f, 1.8f, -0.9f, -0.07f, 0f, gainG, 0f, -0.6f, 0f, 0f, 1.7f, -0.7f), 0.78f)

    private fun timeline(vararg samples: Pair<Double, Float>, durationS: Double = 10.0) =
        samples.fold(CorrectionTimeline(us(durationS))) { t, (s, g) -> t.withSample(us(s), params(g)) }

    private fun us(s: Double) = (s * 1_000_000).toLong()

    private fun gainAt(t: CorrectionTimeline, s: Double) = t.matrixAt(us(s))!![5]

    @Test
    fun emptyTimelineIsTheIdentity() {
        val t = CorrectionTimeline(us(5.0))
        assertNull(t.matrixAt(0))
        assertEquals(CorrectionParams.IDENTITY.withStrength(0.5f), t.paramsAt(0, 0.5f))
    }

    @Test
    fun constantClipGivesConstantParameters() {
        val t = timeline(*Array(11) { it.toDouble() to 2.3f })
        for (s in listOf(-1.0, 0.0, 0.5, 3.3, 9.99, 10.0, 12.0)) assertEquals(2.3, gainAt(t, s), 1e-6)
        val p = t.paramsAt(us(4.2), 0.4f)
        assertEquals(0.4f, p.strength)
        assertArrayEquals(params(2.3f).matrix, p.matrix, 1e-6f)
    }

    @Test
    fun stepChangeBecomesGradual() {
        // Gain 2.0 for the first 10 s, 3.0 for the next 10 s.
        val t = timeline(*Array(21) { it.toDouble() to if (it < 10) 2.0f else 3.0f }, durationS = 20.0)
        val values = (0..200).map { gainAt(t, it / 10.0) }
        // Monotonic, no jumps between frames 0.1 s apart, and the far ends keep their own level.
        values.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-9); assertTrue(b - a < 0.03) }
        assertEquals(2.0, values.first(), 0.01)
        assertEquals(3.0, values.last(), 0.01)
        assertEquals(2.5, gainAt(t, 9.5), 0.02)
    }

    @Test
    fun interpolatesBetweenSamplesAndHoldsAtBothEnds() {
        val t = timeline(1.0 to 2.0f, 2.0 to 3.0f)
        val a = gainAt(t, 1.0); val b = gainAt(t, 2.0)
        assertEquals((a + b) / 2, gainAt(t, 1.5), 1e-9)
        assertEquals(a + (b - a) * 0.25, gainAt(t, 1.25), 1e-9)
        assertEquals(a, gainAt(t, 0.0), 0.0)
        assertEquals(b, gainAt(t, 7.0), 0.0)
    }

    @Test
    fun unevenSampleTimesAreWeightedByTime() {
        // Two samples close together at the start barely outweigh a lone one far away.
        val even = timeline(0.0 to 2.0f, 4.0 to 3.0f)
        val uneven = timeline(0.0 to 2.0f, 0.2 to 2.0f, 4.0 to 3.0f)
        assertTrue(gainAt(uneven, 4.0) < gainAt(even, 4.0))
        // Insertion order does not matter.
        val shuffled = timeline(4.0 to 3.0f, 0.2 to 2.0f, 0.0 to 2.0f)
        assertEquals(gainAt(uneven, 1.7), gainAt(shuffled, 1.7), 0.0)
        assertEquals(listOf(0L, 200_000L, 4_000_000L), shuffled.sampleTimesUs)
    }

    @Test
    fun sampleAtTheSameTimeReplacesTheOldOne() {
        val t = timeline(1.0 to 2.0f).withSample(us(1.0), params(3.0f))
        assertEquals(1, t.sampleCount)
        assertEquals(3.0, gainAt(t, 1.0), 1e-6)
    }

    @Test
    fun partialTimelineAnswersLikeAClipEndingAtItsLastSample() {
        val samples = Array(8) { it.toDouble() to (2.0f + it * 0.1f) }
        val partial = timeline(*samples.copyOf(4).requireNoNulls(), durationS = 8.0)
        val shortClip = timeline(*samples.copyOf(4).requireNoNulls(), durationS = 3.0)
        for (s in listOf(0.0, 1.5, 3.0, 6.0)) assertEquals(gainAt(shortClip, s), gainAt(partial, s), 0.0)
        assertFalse(partial.isComplete)
        assertTrue(partial.completed().isComplete)
    }

    @Test
    fun plannedSamplesEverySecondAndTheEnd() {
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L, 2_500_000L), CorrectionTimeline.plannedSampleTimes(2_500_000L))
        assertEquals(listOf(0L, 1_000_000L), CorrectionTimeline.plannedSampleTimes(1_000_000L))
    }

    @Test
    fun easeReachesTheNewValueAfterHalfASecond() {
        val first = timeline(0.0 to 2.0f)
        val eased = EasedTimeline(first)
        assertEquals(2.0, eased.matrixAt(us(3.0))!![5], 1e-6)  // shown at 3 s
        val next = first.withSample(us(1.0), params(3.0f)).withSample(us(3.0), params(3.0f))
        eased.update(next)
        val target = { s: Double -> next.matrixAt(us(s))!![5] }
        assertEquals(2.0, eased.matrixAt(us(3.0))!![5], 1e-6)  // no jump at the moment it arrives
        val mid = eased.matrixAt(us(3.25))!![5]
        assertEquals(2.0 + (target(3.25) - 2.0) * 0.5, mid, 1e-6)
        assertEquals(target(3.5), eased.matrixAt(us(3.5))!![5], 1e-9)
        assertEquals(target(5.0), eased.matrixAt(us(5.0))!![5], 1e-9)
    }

    @Test
    fun firstSamplesApplyAtOnceAndASeekBackShowsTheNewTimeline() {
        val eased = EasedTimeline(CorrectionTimeline(us(10.0)))
        assertNull(eased.matrixAt(us(1.0)))
        val t1 = timeline(0.0 to 2.0f)
        eased.update(t1)
        assertEquals(2.0, eased.matrixAt(us(1.0))!![5], 1e-6)  // nothing to ease from
        val t2 = t1.withSample(us(1.0), params(3.0f))
        eased.update(t2)
        assertEquals(t2.matrixAt(us(0.2))!![5], eased.matrixAt(us(0.2))!![5], 1e-9)  // before the arrival point
    }

    @Test
    fun updatesDuringAnEaseStayContinuous() {
        val eased = EasedTimeline(timeline(0.0 to 2.0f))
        eased.matrixAt(us(2.0))
        eased.update(timeline(0.0 to 2.0f, 2.0 to 3.0f))
        val before = eased.matrixAt(us(2.2))!![5]
        eased.update(timeline(0.0 to 2.0f, 2.0 to 3.0f, 3.0 to 4.0f))
        val after = eased.matrixAt(us(2.2))!![5]
        assertEquals(before, after, 1e-9)
        assertEquals(timeline(0.0 to 2.0f, 2.0 to 3.0f, 3.0 to 4.0f).matrixAt(us(2.7))!![5], eased.matrixAt(us(2.7))!![5], 1e-9)
    }

    /** Agrees with `video_check.py --parity` (core/analysis/src/test/resources/timeline_parity.txt). */
    @Test
    fun matchesTheReferenceScript() {
        val times = listOf(0.0, 0.9, 2.1, 3.0, 4.4, 5.0, 7.5, 8.0)
        fun matrix(k: Int) = FloatArray(12) { i -> (sin(0.7 * k + 0.3 * i) * 0.5 + if (i in listOf(0, 5, 10)) 1.0 else 0.0).toFloat() }
        val lines = javaClass.getResource("/timeline_parity.txt")!!.readText().lines().filter { it.startsWith("at ") }
        assertEquals(20, lines.size)
        var worst = 0.0
        for (line in lines) {
            val f = line.split(" ")
            val n = f[1].toInt()
            val t = (0 until n).fold(CorrectionTimeline(us(8.0))) { acc, k -> acc.withSample(us(times[k]), CorrectionParams(matrix(k), 0.78f)) }
            val m = t.matrixAt(us(f[2].toDouble()))!!
            for (e in 0 until 12) worst = maxOf(worst, abs(m[e] - f[3 + e].toDouble()))
        }
        assertTrue("worst difference $worst", worst <= 1e-5)
    }
    @Test fun cornerPixelsStayWithTheirSamples() {
        val t = CorrectionTimeline(us(4.0))
            .withSample(us(2.0), params(2.0f), intArrayOf(2))
            .withSample(us(0.0), params(1.0f), intArrayOf(0))
            .withSample(us(1.0), params(1.0f))
            .completed()
        assertEquals(listOf(us(0.0), us(2.0)), t.corners.map { it.first })
        assertEquals(listOf(0, 2), t.corners.map { it.second.single() })
        // Replacing a sample replaces its corner, and the matrices are unaffected by corners.
        val replaced = t.withSample(us(2.0), params(2.0f), intArrayOf(9))
        assertEquals(9, replaced.corners.last().second.single())
        assertEquals(t.matrixAt(us(1.5))!!.toList(), replaced.matrixAt(us(1.5))!!.toList())
    }
}
