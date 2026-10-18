package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.V1Model
import java.io.IOException

/** A photo decoded at 1/[sampleSize] of its stored size, [fullWidth]×[fullHeight] (no EXIF rotation). */
class ReducedDecode(val bitmap: Bitmap, val sampleSize: Int, val fullWidth: Int, val fullHeight: Int)

/**
 * The definition of a photo's correction, shared by preview and export (add-preview-compare, design D1):
 * the analyzer's result on a decode at [sampleSize], which libjpeg scales in the DCT. The result applies to
 * the photo at full size; use [Correction.at] for other sizes. `tools/reference/draft_check.py` checks
 * that this differs from the full-resolution estimate by at most 0.5 mean ΔE.
 */
class CorrectionEstimator(
    private val resolver: ContentResolver,
    private val strength: Float = V1Model.DEFAULT_STRENGTH,
) {

    /** Decodes [source] at the estimate's sample size. Throws [IOException] if it cannot be read or decoded. */
    fun decode(source: Uri): ReducedDecode {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("cannot decode $source")
        val n = sampleSize(bounds.outWidth)
        val options = BitmapFactory.Options().apply {
            inSampleSize = n
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open(source).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("cannot decode $source")
        return ReducedDecode(bitmap, n, bounds.outWidth, bounds.outHeight)
    }

    /** The correction of the photo [decoded] came from, for its full size. */
    fun estimate(decoded: ReducedDecode): Correction =
        CorrectionPhotoProcessor.estimate(decoded.bitmap, strength).at(decoded.fullWidth, decoded.fullHeight)

    /** Decodes [source], estimates its correction and frees the decode. */
    fun estimate(source: Uri): Correction {
        val decoded = decode(source)
        try {
            return estimate(decoded)
        } finally {
            decoded.bitmap.recycle()
        }
    }

    private fun open(source: Uri) = resolver.openInputStream(source) ?: throw IOException("cannot open $source")

    companion object {
        /** Twice the shift maps' working width, so the working copy is still an area average of real pixels. */
        const val MIN_ESTIMATE_WIDTH = 2048

        /** Largest power of two that keeps the decoded width at least [MIN_ESTIMATE_WIDTH]. */
        fun sampleSize(width: Int): Int {
            var n = 1
            while (width / (n * 2) >= MIN_ESTIMATE_WIDTH) n *= 2
            return n
        }
    }
}
