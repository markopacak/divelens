package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.analysis.Correction
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a photo for [PhotoPreviewStore] (design D2, D3): reduced decode, its correction, and a corrected
 * thumbnail on the CPU, rotated for display.
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
            CorrectionPhotoProcessor.applyOnCpu(thumbnail, correction.at(thumbnail.width, thumbnail.height))
            return Prepared(correction, rotate(thumbnail, rotation), rotation)
        } finally {
            decoded.bitmap.recycle()
        }
    }

    override suspend fun estimate(source: String): Correction = estimator.estimate(source.toUri())

    private fun rotation(source: String): Int = runCatching {
        resolver.openInputStream(source.toUri())?.use { ExifInterface(it).rotationDegrees }
    }.getOrNull() ?: 0

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
