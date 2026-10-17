package com.markopacak.divelens.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V1ModelTest {

    private class Case(val name: String, val w: Int, val h: Int, val aw: Int, val ah: Int, val v: DoubleArray)

    private val lines = javaClass.getResource("/parity.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
    private val fixture = lines.filterNot { it.startsWith("smooth ") }

    private val coefficients: ModelCoefficients = fixture.first().split(" ").drop(1).associate {
        val (k, v) = it.split("="); k to v.toDouble()
    }.let { ModelCoefficients(it.getValue("c0"), it.getValue("c1"), it.getValue("c2"), it.getValue("beta"), it.getValue("lo_pct"), it.getValue("hi_pct"), it.getValue("red"), it.getValue("cap"), it.getValue("black_max")) }

    private val cases = fixture.drop(1).map { line ->
        val t = line.split(" ")
        Case(t[0], t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), t.drop(5).map { if (it == "nan") Double.NaN else it.toDouble() }.toDoubleArray())
    }

    @Test
    fun matchesPythonReference() {
        assertTrue(cases.size >= 12)
        for (c in cases) {
            val label = "${c.name} ${c.w}x${c.h}"
            val image = AnalysisImageBuilder.of(Synthetic.image(c.name, c.w, c.h), c.w, c.h)
            assertEquals("$label width", c.aw, image.width)
            assertEquals("$label height", c.ah, image.height)
            val s = ImageStats.of(image, coefficients.lowPercentile, coefficients.highPercentile)
            val stats = doubleArrayOf(s.meanR, s.meanG, s.meanB, s.lowG, s.highG, s.lowB, s.highB)
            for (i in stats.indices) assertEquals("$label stat $i", c.v[i], stats[i], 1e-5)

            val p = V1Model.estimate(s, 1f, coefficients)
            if (c.v[7].isNaN()) {
                assertEquals("$label identity", CorrectionParams.IDENTITY.withStrength(1f), p)
                continue
            }
            val a = c.v[7]; val offset = c.v[8]; val gG = c.v[9]; val gB = c.v[10]
            assertEquals("$label a", a, p[0, 1].toDouble(), 0.01)
            assertEquals("$label red offset", offset, p[0, 3].toDouble(), 1.0 / 255)
            assertEquals("$label green gain", gG, p[1, 1].toDouble(), 1e-4)
            assertEquals("$label blue gain", gB, p[2, 2].toDouble(), 1e-4)
            assertEquals("$label green level", -c.v[11] * gG, p[1, 3].toDouble(), 1.0 / 255)
            assertEquals("$label blue level", -c.v[12] * gB, p[2, 3].toDouble(), 1.0 / 255)
        }
    }

    @Test
    fun smoothedCorrectionMatchesPythonReference() {
        val rows = lines.filter { it.startsWith("smooth ") }.map { it.split(" ").drop(1) }
        assertTrue(rows.size >= 100)
        for ((key, group) in rows.groupBy { it.take(5) }) {
            val (name, w, h, ww, s) = key
            val width = w.toInt(); val height = h.toInt()
            val px = Synthetic.image(name, width, height)
            val params = V1Model.estimate(AnalysisImageBuilder.of(px, width, height), s.toFloat(), coefficients)
            val map = GuidedShift.map(AnalysisImageBuilder.of(px, width, height, ww.toInt()), params)
            val out = px.copyOf().also { CpuCorrection.applyInPlace(it, Correction(params, map, width, height), 0, 0, width, height) }
            for (t in group) {
                val x = t[5].toInt(); val y = t[6].toInt(); val p = out[y * width + x]
                val got = intArrayOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
                for (c in 0 until 3) assertEquals("$name ($x, $y) channel $c", t[7 + c].toDouble(), got[c].toDouble(), 1.0)
            }
        }
    }

    @Test
    fun blackPointIsLimitedOnBrightOpenWater() {
        val p = V1Model.estimate(AnalysisImageBuilder.of(Synthetic.image("bright", 400, 300), 400, 300))
        val k = V1Model.COEFFICIENTS
        assertTrue("green black ${-p[1, 3] / p[1, 1]}", -p[1, 3] / p[1, 1] <= k.blackPointMax + 1e-6)
        assertTrue("blue black ${-p[2, 3] / p[2, 2]}", -p[2, 3] / p[2, 2] <= k.blackPointMax + 1e-6)
    }

    @Test
    fun deepBluePhotoGetsStrongRedRecovery() {
        val p = V1Model.estimate(AnalysisImageBuilder.of(Synthetic.image("deep", 400, 300), 400, 300))
        assertTrue("a = ${p[0, 1]}", p[0, 1] > 1.2f)
    }

    @Test
    fun shallowPhotoGetsLittleRedRecovery() {
        val p = V1Model.estimate(AnalysisImageBuilder.of(Synthetic.image("shallow", 400, 300), 400, 300))
        assertTrue("a = ${p[0, 1]}", p[0, 1] < 0.3f)
    }

    @Test
    fun blackPhotoIsLeftUnchanged() {
        val p = V1Model.estimate(AnalysisImageBuilder.of(Synthetic.image("black", 400, 300), 400, 300))
        assertEquals(CorrectionParams.IDENTITY.withStrength(V1Model.DEFAULT_STRENGTH), p)
    }

    @Test
    fun rowBandsGiveTheSameResultAsOneCall() {
        val w = 641; val h = 479
        val px = Synthetic.image("wide", w, h)
        val whole = AnalysisImageBuilder.of(px, w, h)
        val builder = AnalysisImageBuilder(w, h)
        var row = 0
        while (row < h) {
            val n = minOf(37, h - row); builder.addRows(px, row * w, w, n); row += n
        }
        assertTrue(whole.rgb.contentEquals(builder.build().rgb))
    }
}
