package com.markopacak.divelens.analysis

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Quality against the maintainer's local reference set (spec "Quality against the reference set").
 * Reads the pairs written by `tools/reference/prepare.py`; skipped when they are absent.
 * The RefApp strength of each file is unknown, so it is reported both matched per image (oracle,
 * least squares in RGB, as in `fit.py`) and at the app's default strength. The whole correction is
 * evaluated, smoothing included: shift maps from the full-size original, applied at evaluation size.
 */
class ReferenceSetTest {

    private class Sample(val x: FloatArray, val y: FloatArray, val shift: FloatArray)

    @Test
    fun meetsQualityLimits() {
        val root = System.getProperty("divelens.reference")?.let(::File)
        assumeTrue("reference set not found at $root", root != null && root.isDirectory)

        val rows = mutableListOf<String>()
        var sumOriginal = 0.0; var sumOracle = 0.0; var sumDefault = 0.0; var scenes = 0
        val notBetter = mutableListOf<String>()
        for (dir in root!!.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            if (File(dir, "scene.txt").readText().split(" ").first() != "refapp") continue
            val mask = maskAt(File(dir, "mask.png"))
            val samples = dir.listFiles { f -> f.name.endsWith("_original.png") }!!.sortedBy { it.name }
                .filterIndexed { i, _ -> i % FRAME_STEP == 0 }
                .map { sample(it, File(dir, it.name.replace("_original", "_reference")), mask) }
            val original = samples.map { meanDeltaE(it.x, it.y) }.average()
            val oracle = samples.map { meanDeltaE(blend(it, oracleStrength(it)), it.y) }.average()
            val default = samples.map { meanDeltaE(blend(it, V1Model.DEFAULT_STRENGTH), it.y) }.average()
            rows += "%-22s %8.2f %9.2f %10.2f".format(dir.name, original, oracle, default)
            if (default >= original) notBetter += dir.name
            sumOriginal += original; sumOracle += oracle; sumDefault += default; scenes++
        }
        assumeTrue("no RefApp scenes in $root", scenes > 0)
        val meanOracle = sumOracle / scenes; val meanDefault = sumDefault / scenes
        val report = buildString {
            appendLine("%-22s %8s %9s %10s".format("scene", "original", "oracle s", "default s"))
            rows.forEach(::appendLine)
            appendLine("%-22s %8.2f %9.2f %10.2f".format("MEAN", sumOriginal / scenes, meanOracle, meanDefault))
            appendLine("default strength ${V1Model.DEFAULT_STRENGTH}, limits: oracle ≤ $ORACLE_LIMIT, default ≤ $DEFAULT_LIMIT")
        }
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/reference-set.txt").writeText(report)

        assertTrue("mean ΔE with matched strength $meanOracle > $ORACLE_LIMIT", meanOracle <= ORACLE_LIMIT)
        assertTrue("mean ΔE at default strength $meanDefault > $DEFAULT_LIMIT", meanDefault <= DEFAULT_LIMIT)
        assertTrue("not closer than the original: $notBetter", notBetter.isEmpty())
    }

    /** The mask at evaluation size: true where a pixel lies fully outside the watermark. */
    private fun maskAt(file: File): BooleanArray {
        val img = ImageIO.read(file)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val small = AnalysisImageBuilder.of(px, img.width, img.height, EVAL_WIDTH)
        return BooleanArray(small.width * small.height) { small.rgb[it * 3] > 0.999f }
    }

    private fun sample(original: File, reference: File, mask: BooleanArray): Sample {
        val o = ImageIO.read(original); val r = ImageIO.read(reference)
        val op = o.getRGB(0, 0, o.width, o.height, null, 0, o.width)
        val rp = r.getRGB(0, 0, r.width, r.height, null, 0, r.width)
        val full = CorrectionAnalyzer.of(op, o.width, o.height, 1f)
        val x = AnalysisImageBuilder.of(op, o.width, o.height, EVAL_WIDTH)
        val evalSize = Correction(full.params, full.map, x.width, x.height)
        val shift = FloatArray(x.rgb.size); val one = FloatArray(3)
        for (j in 0 until x.height) for (i in 0 until x.width) {
            val p = (j * x.width + i) * 3
            CpuCorrection.shift(evalSize, i, j, x.rgb[p], x.rgb[p + 1], x.rgb[p + 2], one)
            one.copyInto(shift, p)
        }
        return Sample(masked(x.rgb, mask), masked(AnalysisImageBuilder.of(rp, r.width, r.height, EVAL_WIDTH).rgb, mask), masked(shift, mask))
    }

    private fun masked(rgb: FloatArray, mask: BooleanArray): FloatArray {
        val out = FloatArray(mask.count { it } * 3); var k = 0
        for (i in mask.indices) if (mask[i]) { out[k++] = rgb[i * 3]; out[k++] = rgb[i * 3 + 1]; out[k++] = rgb[i * 3 + 2] }
        return out
    }

    private fun blend(s: Sample, strength: Float): FloatArray =
        FloatArray(s.x.size) { (s.x[it] + strength * s.shift[it]).coerceIn(0f, 1f) }

    private fun oracleStrength(s: Sample): Float {
        var num = 0.0; var den = 0.0
        for (i in s.shift.indices) { val d = s.shift[i]; num += d * (s.y[i] - s.x[i]); den += d * d }
        return (num / maxOf(den, 1e-9)).coerceIn(0.0, 1.3).toFloat()
    }

    private fun meanDeltaE(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (p in a.indices step 3) {
            val la = ColorDifference.lab(a[p].toDouble(), a[p + 1].toDouble(), a[p + 2].toDouble())
            val lb = ColorDifference.lab(b[p].toDouble(), b[p + 1].toDouble(), b[p + 2].toDouble())
            sum += Math.sqrt((la[0] - lb[0]).let { it * it } + (la[1] - lb[1]).let { it * it } + (la[2] - lb[2]).let { it * it })
        }
        return sum / (a.size / 3)
    }

    companion object {
        const val EVAL_WIDTH = 400
        const val FRAME_STEP = 4
        const val ORACLE_LIMIT = 6.5
        const val DEFAULT_LIMIT = 9.0
    }
}
