package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.markopacak.divelens.analysis.AnalysisImageBuilder
import com.markopacak.divelens.analysis.V1Model
import com.markopacak.divelens.watermark.CornerCrop
import java.io.IOException

/**
 * [TimelineSampler] for content URIs (add-video-processing D1): for each target time, the sync frame nearest to it
 * ([MediaExtractor] gives its exact time), decoded about [SAMPLE_SIZE] px on its long side by [MediaMetadataRetriever],
 * and the model's estimate on its analysis copy. `tools/reference/video_check.py` samples the same way. With a
 * [markAspect], each sample also keeps the frame's watermark corner, box-averaged by [CornerCrop]
 * (add-branding-and-watermark D6); the decoded frames are already turned upright.
 */
class AndroidTimelineSampler(private val resolver: ContentResolver, private val markAspect: Float? = null) : TimelineSampler {

    override suspend fun sample(source: String, targetTimesUs: List<Long>): List<TimelineSample> {
        val uri = source.toUri()
        val times = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IOException("no video track in $source")
                extractor.selectTrack(track)
                targetTimesUs.map { t ->
                    extractor.seekTo(t, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    extractor.sampleTime.takeIf { it >= 0 } ?: t
                }
            } finally {
                extractor.release()
            }
        } ?: throw IOException("cannot open $source")
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: throw IOException("cannot open $source")
            return times.distinct().mapNotNull { t ->
                val frame = retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SAMPLE_SIZE, SAMPLE_SIZE)
                    ?: return@mapNotNull null
                try {
                    estimate(t, frame)
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun estimate(timeUs: Long, frame: Bitmap): TimelineSample {
        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
        val w = argb.width; val h = argb.height
        val px = IntArray(w * h).also { argb.getPixels(it, 0, w, 0, 0, w, h) }
        if (argb !== frame) argb.recycle()
        return TimelineSample(timeUs, V1Model.estimate(AnalysisImageBuilder.of(px, w, h)), markAspect?.let { CornerCrop.of(px, w, h, it) })
    }

    companion object {
        /** Long side of a decoded sample, as `video_check.py`'s SAMPLE_WIDTH. */
        const val SAMPLE_SIZE = 400
    }
}
