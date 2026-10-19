package com.markopacak.divelens.export

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * The MP4 muxer of a video export (add-video-processing D7, spec "Video metadata"): writes into [output] (the pending
 * gallery row's file descriptor, so nothing is copied), keeps only video and audio tracks, and of the metadata only the
 * orientation, with the original's [creationTimeMillis] as the creation time. Location, XMP and any other entry are
 * dropped, so nothing that may carry GPS reaches the export.
 */
@UnstableApi
class CleanMp4Muxer(output: FileOutputStream, private val creationTimeMillis: Long?) : Muxer {
    private val muxer = Mp4Muxer.Builder(output).build()
    private val dropped = HashSet<Int>()
    private var nextDropped = DROPPED_BASE

    init {
        creationTimeMillis?.let {
            val seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(it)
            muxer.addMetadataEntry(Mp4TimestampData(seconds, seconds))
        }
    }

    override fun addTrack(format: Format): Int {
        val mime = format.sampleMimeType
        if (!MimeTypes.isVideo(mime) && !MimeTypes.isAudio(mime)) return nextDropped++.also { dropped += it }
        // Transformer passes the display rotation on the video format, as Media3's own InAppMp4Muxer expects.
        if (MimeTypes.isVideo(mime) && format.rotationDegrees != 0) muxer.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
        return muxer.addTrack(format)
    }

    override fun writeSampleData(trackId: Int, data: ByteBuffer, bufferInfo: BufferInfo) {
        if (trackId in dropped) return
        muxer.writeSampleData(trackId, data, bufferInfo)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
        // The creation time is the original's (set above); everything else but the orientation is dropped.
        if (metadataEntry is Mp4OrientationData) muxer.addMetadataEntry(metadataEntry)
    }

    override fun close() = muxer.close()

    /** Creates the muxer on [output] whatever path Transformer asks for. */
    class Factory(private val output: FileOutputStream, private val creationTimeMillis: Long?) : Muxer.Factory {
        override fun create(path: String): Muxer = CleanMp4Muxer(output, creationTimeMillis)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES
            else -> ImmutableList.of()
        }
    }

    private companion object {
        /** Ids handed out for dropped tracks, far from the muxer's own. */
        const val DROPPED_BASE = 1_000
    }
}
