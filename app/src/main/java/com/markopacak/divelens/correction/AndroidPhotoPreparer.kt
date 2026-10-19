package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.ShiftEncoding
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a photo or video for [PhotoPreviewStore] (design D2, D3): reduced decode (for a video, one frame), its
 * correction, and a corrected thumbnail on the CPU, rotated for display. It also keeps the uncorrected thumbnail and its encoded shift, so
 * the thumbnail can be shown at another strength in milliseconds (add-strength-slider D3).
 */
class AndroidPhotoPreparer(
    private val resolver: ContentResolver,
    private val estimator: CorrectionEstimator = CorrectionEstimator(resolver),
) : PhotoPreparer<Bitmap> {

    override suspend fun prepare(source: String): Prepared<Bitmap> {
        if (isVideo(source)) return prepareVideo(source)
        val uri = source.toUri()
        val decoded = estimator.decode(uri)
        try {
            val correction = estimator.estimate(decoded)
            return thumbnailOf(downscale(decoded.bitmap, THUMBNAIL_SIZE), correction, rotation(source))
        } finally {
            decoded.bitmap.recycle()
        }
    }

    override suspend fun estimate(source: String): Correction =
        if (isVideo(source)) prepareVideo(source).correction else estimator.estimate(source.toUri())

    /** The corrected thumbnail of a [thumbnail]-sized copy (recycled) under [correction], with its shift for re-rendering. */
    private fun thumbnailOf(thumbnail: Bitmap, correction: Correction, rotation: Int): Prepared<Bitmap> {
        val w = thumbnail.width; val h = thumbnail.height
        val px = IntArray(w * h).also { thumbnail.getPixels(it, 0, w, 0, 0, w, h) }
        val at = correction.at(w, h)
        val shifts = Bitmap.createBitmap(ShiftEncoding.encodeShifts(px, at, w, h), w, h, Bitmap.Config.ARGB_8888)
        val original = thumbnail.copy(Bitmap.Config.ARGB_8888, false)
        CorrectionPhotoProcessor.applyOnCpu(thumbnail, at)
        return Prepared(correction, rotate(thumbnail, rotation), rotation, ShiftRenderer(original, shifts, rotation))
    }

    private fun isVideo(source: String) = resolver.getType(source.toUri())?.startsWith("video/") == true

    /**
     * A video's thumbnail (add-video-processing D5): the frame [VideoThumbnailFrame] picks, already in display orientation,
     * corrected exactly as a photo with its own estimate. The correction belongs to that frame; the video's correction
     * over time comes from its timeline.
     */
    private fun prepareVideo(source: String): Prepared<Bitmap> {
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(source.toUri(), "r")?.use { retriever.setDataSource(it.fileDescriptor) }
                ?: throw IOException("cannot open $source")
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
                ?: throw IOException("no duration in $source")
            val (_, frame) = VideoThumbnailFrame.choose(
                durationUs,
                frameAt = { t -> frameAt(retriever, t) },
                meanGrey = ::meanGrey,
                release = Bitmap::recycle,
            ) ?: throw IOException("no readable frame in $source")
            val thumbnail = downscale(frame, THUMBNAIL_SIZE)
            if (thumbnail !== frame) frame.recycle()
            val correction = CorrectionPhotoProcessor.estimate(thumbnail, estimator.strength)
            return thumbnailOf(thumbnail, correction, 0)
        } finally {
            retriever.release()
        }
    }

    /** The frame at [timeUs] scaled to about the thumbnail size, in display orientation, as a mutable ARGB bitmap. */
    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = runCatching {
        val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, THUMBNAIL_SIZE * 2, THUMBNAIL_SIZE * 2)
            ?: return null
        if (frame.config == Bitmap.Config.ARGB_8888 && frame.isMutable) frame
        else frame.copy(Bitmap.Config.ARGB_8888, true).also { frame.recycle() }
    }.getOrNull()

    private fun meanGrey(bitmap: Bitmap): Double {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        var sum = 0L
        for (p in px) sum += (p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)
        return sum / (3.0 * 255 * px.size)
    }

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
