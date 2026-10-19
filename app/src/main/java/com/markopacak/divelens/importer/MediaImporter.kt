package com.markopacak.divelens.importer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class RejectionReason { UnsupportedFormat, Unreadable, TooLarge, TooLargeForDevice }

data class Rejection(val displayName: String, val reason: RejectionReason)

data class ImportResult(val accepted: List<MediaItem>, val rejected: List<Rejection>)

/**
 * Validates picked URIs cheaply before they enter the working set: photos by MIME type and bounds, videos by
 * container, codec and whether this device can decode and re-encode them (add-video-processing D4).
 */
class MediaImporter(
    private val resolver: ContentResolver,
    private val probe: VideoProbe = AndroidVideoProbe(resolver),
    private val codecs: CodecSupport = AndroidCodecSupport,
) {

    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val accepted = mutableListOf<MediaItem>()
        val rejected = mutableListOf<Rejection>()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: uri.toString()
            val mime = resolver.getType(uri)?.lowercase()
            when (mime) {
                in PHOTO_MIME_TYPES -> {
                    val bounds = decodeBounds(uri)
                    when {
                        bounds == null -> rejected += Rejection(name, RejectionReason.Unreadable)
                        bounds.first.toLong() * bounds.second > MAX_PIXELS -> rejected += Rejection(name, RejectionReason.TooLarge)
                        else -> accepted += MediaItem.Photo(uri.toString(), name, mime!!, bounds.first, bounds.second, dateTaken(uri))
                    }
                }
                in VIDEO_MIME_TYPES -> {
                    val info = runCatching { probe.probe(uri) }.getOrNull()
                    if (info == null) {
                        rejected += Rejection(name, RejectionReason.Unreadable)
                    } else when (val decision = VideoAcceptance.check(info, codecs)) {
                        is VideoAcceptance.Decision.Accepted -> accepted += info.toItem(uri.toString(), name, mime!!)
                        is VideoAcceptance.Decision.Rejected -> rejected += Rejection(name, decision.reason)
                    }
                }
                else -> rejected += Rejection(name, RejectionReason.UnsupportedFormat)
            }
        }
        ImportResult(accepted, rejected)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun decodeBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    /** EXIF DateTimeOriginal (or DateTime) as the camera's wall-clock time, for the grid caption. */
    private fun dateTaken(uri: Uri): LocalDateTime? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
        }
    }.getOrNull()

    companion object {
        val PHOTO_MIME_TYPES = setOf("image/jpeg", "image/heic", "image/heif")
        val VIDEO_MIME_TYPES = setOf("video/mp4", "video/quicktime")

        /** Guard against running out of memory on full-resolution decode. */
        const val MAX_PIXELS = 100_000_000L

        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        internal fun parseExifDate(value: String?): LocalDateTime? =
            runCatching { LocalDateTime.parse(value?.trim(), EXIF_DATE) }.getOrNull()
    }
}

/** What the importer needs to know about a video file. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val frameRate: Float,
    val bitrate: Int,
    /** Video track MIME type, such as `video/avc`. */
    val codec: String,
    val hdr: Boolean,
    val creationTimeMillis: Long?,
) {
    fun toItem(source: String, name: String, mime: String) = MediaItem.Video(
        source, name, mime, width, height, rotation, durationUs, frameRate, bitrate, codec, hdr, creationTimeMillis,
    )
}

/** Reads a video's track format and container metadata; returns null when it has no readable video track. */
fun interface VideoProbe {
    fun probe(uri: Uri): VideoInfo?
}

/** Which codecs this device has, for the size and frame rate of a clip. */
interface CodecSupport {
    /** A decoder exists for [mime] at all. */
    fun hasDecoder(mime: String): Boolean
    fun canDecode(mime: String, width: Int, height: Int, frameRate: Float, tenBit: Boolean): Boolean
    /** An encoder exists for [mime] at all. */
    fun hasEncoder(mime: String): Boolean
    fun canEncode(mime: String, width: Int, height: Int, frameRate: Float): Boolean
}

/** The acceptance rules for videos (import-export "Reject unsupported photo files", design D4). */
object VideoAcceptance {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"

    sealed interface Decision {
        /** Accepted; the export encodes with [outputCodec]. */
        data class Accepted(val outputCodec: String) : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun check(info: VideoInfo, codecs: CodecSupport): Decision {
        if (info.codec !in setOf(H264, HEVC) || info.width <= 0 || info.height <= 0) return Decision.Rejected(RejectionReason.UnsupportedFormat)
        if (!codecs.canDecode(info.codec, info.width, info.height, info.frameRate, info.hdr)) {
            return Decision.Rejected(if (codecs.hasDecoder(info.codec)) RejectionReason.TooLargeForDevice else RejectionReason.UnsupportedFormat)
        }
        val output = outputCodec(info, codecs) ?: return Decision.Rejected(RejectionReason.TooLargeForDevice)
        return Decision.Accepted(output)
    }

    /** The original's codec when an encoder supports the size and rate, else H.264 when one does, else null. */
    fun outputCodec(info: VideoInfo, codecs: CodecSupport): String? = listOf(info.codec, H264).distinct()
        .firstOrNull { codecs.canEncode(it, info.width, info.height, info.frameRate) }
}
