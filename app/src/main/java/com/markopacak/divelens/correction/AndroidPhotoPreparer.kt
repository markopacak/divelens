package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.ShiftEncoding
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a photo for [PhotoPreviewStore] (design D2, D3): reduced decode, its correction, and a corrected
 * thumbnail on the CPU, rotated for display. It also keeps the uncorrected thumbnail and its encoded shift, so
 * the thumbnail can be shown at another strength in milliseconds (add-strength-slider D3).
 */
class AndroidPhotoPreparer(
    private val resolver: ContentResolver,
    private val estimator: CorrectionEstimator = CorrectionEstimator(resolver),
) : PhotoPreparer<Bitmap> {

    override suspend fun prepare(source: String): Prepared<Bitmap> {
        val uri = source.toUri()
        val decoded = estimator.decode(uri)
        try {
            val correction = estimator.estimate(decoded)
            val rotation = rotation(source)
            val thumbnail = downscale(decoded.bitmap, THUMBNAIL_SIZE)
            val w = thumbnail.width; val h = thumbnail.height
            val px = IntArray(w * h).also { thumbnail.getPixels(it, 0, w, 0, 0, w, h) }
            val at = correction.at(w, h)
            val shifts = Bitmap.createBitmap(ShiftEncoding.encodeShifts(px, at, w, h), w, h, Bitmap.Config.ARGB_8888)
            val original = thumbnail.copy(Bitmap.Config.ARGB_8888, false)
            CorrectionPhotoProcessor.applyOnCpu(thumbnail, at)
            return Prepared(correction, rotate(thumbnail, rotation), rotation, ShiftRenderer(original, shifts, rotation))
        } finally {
            decoded.bitmap.recycle()
        }
    }

    override suspend fun estimate(source: String): Correction = estimator.estimate(source.toUri())

    private fun rotation(source: String): Int = runCatching {
        resolver.openInputStream(source.toUri())?.use { ExifInterface(it).rotationDegrees }
    }.getOrNull() ?: 0

    /** Renders the thumbnail at a strength from its uncorrected pixels and encoded shift, both in stored orientation. */
    class ShiftRenderer(private val original: Bitmap, private val shifts: Bitmap, private val rotation: Int) : ThumbnailRenderer<Bitmap> {
        override fun render(strength: Float): Bitmap {
            val w = original.width; val h = original.height
            val px = IntArray(w * h).also { original.getPixels(it, 0, w, 0, 0, w, h) }
            val sh = IntArray(w * h).also { shifts.getPixels(it, 0, w, 0, 0, w, h) }
            ShiftEncoding.render(px, sh, strength, px)
            return rotate(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), rotation)
        }
    }

    companion object {
        /** Longest side of a thumbnail in pixels. */
        const val THUMBNAIL_SIZE = 384

        /** A mutable copy of [bitmap] with its longest side at most [size], halving with filtering so detail is averaged, not skipped. */
        fun downscale(bitmap: Bitmap, size: Int): Bitmap {
            var current = bitmap
            while (max(current.width, current.height) >= 2 * size) {
                val half = Bitmap.createScaledBitmap(current, max(1, current.width / 2), max(1, current.height / 2), true)
                if (current !== bitmap) current.recycle()
                current = half
            }
            val scale = size.toFloat() / max(current.width, current.height)
            val result = if (scale < 1f) {
                Bitmap.createScaledBitmap(current, max(1, (current.width * scale).roundToInt()), max(1, (current.height * scale).roundToInt()), true)
            } else {
                current.copy(Bitmap.Config.ARGB_8888, true)
            }
            if (current !== bitmap && current !== result) current.recycle()
            return if (result.isMutable && result.config == Bitmap.Config.ARGB_8888) result
            else result.copy(Bitmap.Config.ARGB_8888, true).also { result.recycle() }
        }

        /** [bitmap] rotated clockwise by [degrees]; recycles the input when a new bitmap is made. */
        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return bitmap
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        }
    }
}
