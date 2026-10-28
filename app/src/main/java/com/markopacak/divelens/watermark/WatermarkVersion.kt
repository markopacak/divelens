package com.markopacak.divelens.watermark

/** The two watermark files in docs/brand/watermark/, by asset name. */
enum class WatermarkVersion(val asset: String) {
    /** White with a dark shadow, for dark and mid-tone corners. */
    Light("brand/watermark_light.svg"),

    /** Navy with a light halo, for bright corners. */
    Dark("brand/watermark_dark.svg");

    companion object {
        /**
         * Mean luma above which the dark version is used (BRAND.md). 0.60 is the starting value; it is tuned on real
         * photos (add-branding-and-watermark D9).
         */
        const val WATERMARK_DARK_ABOVE = 0.60

        /** Pictures above this many pixels are measured on every second pixel in each direction (D6). */
        const val SUBSAMPLE_ABOVE_PIXELS = 8_000_000L

        fun forLuma(luma: Double): WatermarkVersion = if (luma > WATERMARK_DARK_ABOVE) Dark else Light

        /** The step between measured pixels for a picture of [width] × [height]. */
        fun step(width: Int, height: Int): Int = if (width.toLong() * height > SUBSAMPLE_ABOVE_PIXELS) 2 else 1

        /**
         * Mean Rec. 709 luma (0 to 1) of the gamma-encoded ARGB [pixels], a [width]-wide region with rows [stride] apart,
         * reading every [step]th pixel in each direction (D6).
         */
        fun meanLuma(pixels: IntArray, width: Int, height: Int, stride: Int = width, step: Int = 1): Double {
            require(width > 0 && height > 0 && step > 0)
            var sum = 0.0
            var n = 0
            var y = 0
            while (y < height) {
                var x = 0
                val row = y * stride
                while (x < width) {
                    sum += luma(pixels[row + x])
                    n++
                    x += step
                }
                y += step
            }
            return sum / n
        }

        /** Rec. 709 luma of one ARGB pixel's encoded values, 0 to 1. */
        fun luma(argb: Int): Double =
            (0.2126 * (argb shr 16 and 0xFF) + 0.7152 * (argb shr 8 and 0xFF) + 0.0722 * (argb and 0xFF)) / 255.0

        /** The median of [values] (the mean of the middle two for an even count); used once per video clip (D6). */
        fun median(values: List<Double>): Double {
            require(values.isNotEmpty())
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
