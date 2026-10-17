package com.markopacak.divelens.analysis

/** Synthetic test images. The integer formulas match `VARIANTS` in `tools/reference/fixture.py`. */
object Synthetic {
    private val variants: Map<String, (Int, Int) -> Triple<Int, Int, Int>> = mapOf(
        "deep" to { x, y -> Triple((x * 7 + y * 3) % 40 + 5, 70 + (x * 5 + y * 11) % 120, 110 + (x * 3 + y * 13) % 140) },
        "shallow" to { x, y -> Triple(60 + (x * 7 + y * 3) % 140, 70 + (x * 5 + y * 11) % 150, 60 + (x * 3 + y * 13) % 120) },
        "black" to { x, y -> Triple((x + y) % 2, (x + y) % 2, (x * y) % 2) },
        "wide" to { x, y -> Triple((x * 7 + y * 3) % 256, (x * 5 + y * 11) % 256, 40 + (x * 3 + y * 13) % 200) },
        "darkwater" to { x, y -> Triple(25 + (x * 7 + y * 3) % 30, 40 + (x * 5 + y * 11) % 50 + y / 6, 90 + (x * 3 + y * 13) % 60 + y / 8) },
        "bright" to { x, y -> Triple((x * 7 + y * 3) % 20 + 10, 120 + (x * 5 + y * 11) % 80, 180 + (x * 3 + y * 13) % 70) },
    )

    fun image(name: String, w: Int, h: Int): IntArray {
        val f = variants.getValue(name)
        return IntArray(w * h) { i -> val (r, g, b) = f(i % w, i / w); argb(r, g, b) }
    }

    fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    fun r(p: Int) = p shr 16 and 0xff
    fun g(p: Int) = p shr 8 and 0xff
    fun b(p: Int) = p and 0xff
}
