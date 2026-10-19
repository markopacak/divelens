package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.markopacak.divelens.analysis.AnalysisImageBuilder
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.V1Model
import java.io.IOException

/**
 * [TimelineSampler] for content URIs (add-video-processing D1): for each target time, the sync frame nearest to it
 * ([MediaExtractor] gives its exact time), decoded about [SAMPLE_SIZE] px on its long side by [MediaMetadataRetriever],
 * and the model's estimate on its analysis copy. `tools/reference/video_check.py` samples the same way.
 */
class AndroidTimelineSampler(private val resolver: ContentResolver) : TimelineSampler {

    override suspend fun sample(source: String, targetTimesUs: List<Long>): List<Pair<Long, CorrectionParams>> {
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
                    t to estimate(frame)
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun estimate(frame: Bitmap): CorrectionParams {
        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
        val w = argb.width; val h = argb.height
        val px = IntArray(w * h).also { argb.getPixels(it, 0, w, 0, 0, w, h) }
        if (argb !== frame) argb.recycle()
        return V1Model.estimate(AnalysisImageBuilder.of(px, w, h))
    }

    companion object {
        /** Long side of a decoded sample, as `video_check.py`'s SAMPLE_WIDTH. */
        const val SAMPLE_SIZE = 400
    }
}
