package com.markopacak.divelens.importer

import android.content.ContentResolver
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** [VideoProbe] with [MediaExtractor] for the track format and [MediaMetadataRetriever] for the container. */
class AndroidVideoProbe(private val resolver: ContentResolver) : VideoProbe {

    override fun probe(uri: Uri): VideoInfo? {
        val format = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            } finally {
                extractor.release()
            }
        } ?: return null
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return null
            fun meta(key: Int) = retriever.extractMetadata(key)
            val width = format.int(MediaFormat.KEY_WIDTH) ?: return null
            val height = format.int(MediaFormat.KEY_HEIGHT) ?: return null
            val durationUs = format.long(MediaFormat.KEY_DURATION)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000) ?: return null
            val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val frameRate = format.number(MediaFormat.KEY_FRAME_RATE)
                ?: frames?.takeIf { durationUs > 0 }?.let { (it * 1_000_000.0 / durationUs).toFloat() } ?: 30f
            val rotation = format.int(MediaFormat.KEY_ROTATION)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val transfer = format.int(MediaFormat.KEY_COLOR_TRANSFER)
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
            val bitrate = format.int(MediaFormat.KEY_BIT_RATE) ?: meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            return VideoInfo(
                width, height, ((rotation % 360) + 360) % 360, durationUs, frameRate, bitrate,
                format.getString(MediaFormat.KEY_MIME)!!, hdr, parseCreationTime(meta(MediaMetadataRetriever.METADATA_KEY_DATE)),
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
    private fun MediaFormat.number(key: String): Float? = if (!containsKey(key)) null else
        runCatching { getInteger(key).toFloat() }.getOrNull() ?: runCatching { getFloat(key) }.getOrNull()

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]'Z'")

        /**
         * MediaMetadataRetriever's date (`20260714T083205.000Z`, the MP4 creation time in UTC) to epoch
         * milliseconds. Containers without a date report 1904 or 1970 (the MP4 and Unix epochs): null.
         */
        fun parseCreationTime(value: String?): Long? {
            val local = runCatching { LocalDateTime.parse(value?.trim(), DATE) }.getOrNull() ?: return null
            if (local.year <= 1970) return null
            return local.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }
}

/** [CodecSupport] from the device's [MediaCodecList]. */
object AndroidCodecSupport : CodecSupport {
    private val infos by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }

    private fun codecs(mime: String, encoder: Boolean) =
        infos.filter { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }

    override fun hasDecoder(mime: String) = codecs(mime, false).isNotEmpty()

    override fun hasEncoder(mime: String) = codecs(mime, true).isNotEmpty()

    override fun canDecode(mime: String, width: Int, height: Int, frameRate: Float, tenBit: Boolean) = codecs(mime, false).any { info ->
        val caps = info.getCapabilitiesForType(mime)
        supports(caps, width, height, frameRate) && (!tenBit || caps.profileLevels.any { it.profile in TEN_BIT_PROFILES })
    }

    override fun canEncode(mime: String, width: Int, height: Int, frameRate: Float) =
        codecs(mime, true).any { supports(it.getCapabilitiesForType(mime), width, height, frameRate) }

    /**
     * Either orientation, since codecs often list landscape sizes only and handle portrait the same way; and the size
     * rounded up to the codec's alignment, since hardware codecs that declare 16-pixel alignment still encode 1920×1080
     * (padded internally and cropped in the stream), as the phone's own camera does.
     */
    private fun supports(caps: MediaCodecInfo.CodecCapabilities, width: Int, height: Int, frameRate: Float): Boolean {
        val video = caps.videoCapabilities ?: return false
        val rate = frameRate.toDouble().coerceAtLeast(1.0)
        fun up(v: Int, a: Int) = (v + a - 1) / a * a
        return listOf(width to height, height to width).any { (w, h) ->
            runCatching {
                video.areSizeAndRateSupported(w, h, rate) ||
                    video.areSizeAndRateSupported(up(w, video.widthAlignment), up(h, video.heightAlignment), rate)
            }.getOrDefault(false)
        }
    }

    private val TEN_BIT_PROFILES = setOf(
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
    )
}
