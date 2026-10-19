package com.markopacak.divelens.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.markopacak.divelens.Branding
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.correction.CorrectionEffect
import com.markopacak.divelens.importer.AndroidCodecSupport
import com.markopacak.divelens.importer.CodecSupport
import com.markopacak.divelens.importer.VideoAcceptance
import com.markopacak.divelens.importer.VideoInfo
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.transformer.ExportException as Media3ExportException

/** Writes one video to the gallery and returns the new item's URI string. */
fun interface VideoWriter {
    suspend fun write(video: MediaItem.Video): String
}

/**
 * Exports one video (add-video-processing D7): storage check, the clip's complete timeline, a pending `MediaStore.Video`
 * row in Pictures/<album> named from the creation time, then Transformer with the [CorrectionEffect] at the video's
 * strength, the original's codec (or H.264) and bitrate at the exact size, audio passed through, HDR tone-mapped to SDR,
 * muxed by [CleanMp4Muxer] straight into the row. On failure or cancellation the row is deleted.
 */
@UnstableApi
class MediaStoreVideoWriter(
    private val context: Context,
    private val timelineOf: suspend (String) -> CorrectionTimeline,
    private val strengthOf: (String) -> Float,
    private val album: String = Branding.EXPORT_ALBUM,
    private val codecs: CodecSupport = AndroidCodecSupport,
    private val availableBytes: () -> Long = { StatFs(Environment.getExternalStorageDirectory().path).availableBytes },
    /** Test hook: runs once Transformer has started, while it encodes. */
    private val whileEncoding: suspend () -> Unit = {},
    /** Test hook: runs after the file is written, before the export is published. */
    private val beforePublish: suspend () -> Unit = {},
) : VideoWriter {
    private val resolver: ContentResolver = context.contentResolver

    override suspend fun write(video: MediaItem.Video): String {
        val source = video.source.toUri()
        if (!VideoStorage.fits(availableBytes(), video.bitrate, video.durationUs, fileSize(source))) {
            throw ExportException(FailureReason.InsufficientStorage)
        }
        val timeline = try {
            timelineOf(video.source)
        } catch (e: IllegalStateException) {
            throw ExportException(FailureReason.UnreadableSource, e)
        }
        val outputCodec = VideoAcceptance.outputCodec(video.toInfo(), codecs) ?: throw ExportException(FailureReason.UnreadableSource)
        val strength = strengthOf(video.source)
        var pending: Uri? = null
        try {
            val target = withContext(Dispatchers.IO) { insertPending(video) }.also { pending = it }
            val started = System.nanoTime()
            withContext(Dispatchers.IO) { resolver.openFileDescriptor(target, "rw") }?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    transform(video, timeline, strength, outputCodec, out)
                }
            } ?: throw ExportException(FailureReason.WriteFailed)
            // Timing log for the device check (spec "Video responsiveness": at most twice the duration).
            Log.i("VideoExport", "${video.width}x${video.height} ${video.durationUs / 1000} ms clip exported in ${(System.nanoTime() - started) / 1_000_000} ms as $outputCodec")
            beforePublish()
            coroutineContext.ensureActive()
            withContext(Dispatchers.IO) {
                resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            pending = null
            return target.toString()
        } catch (e: IOException) {
            throw ExportException(if (e.isOutOfSpace()) FailureReason.InsufficientStorage else FailureReason.WriteFailed, e)
        } finally {
            pending?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(it, null, null) } } }
        }
    }

    /** Runs Transformer on the main looper and waits for it; cancelling the caller cancels the export. */
    private suspend fun transform(video: MediaItem.Video, timeline: CorrectionTimeline, strength: Float, codec: String, out: FileOutputStream) {
        val scratch = File(context.cacheDir, "transformer-unused.mp4") // Transformer wants a path; the muxer ignores it.
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val settings = VideoEncoderSettings.Builder().apply { if (video.bitrate > 0) setBitrate(video.bitrate) }.build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(codec)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false) // keep the exact size: see design D7
                        .setRequestedVideoEncoderSettings(settings)
                        .build(),
                )
                .setMuxerFactory(CleanMp4Muxer.Factory(out, video.creationTimeMillis))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: Media3ExportException) {
                        done.completeExceptionally(exportException.toExportException())
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(Media3Item.fromUri(video.source))
                .setEffects(Effects(emptyList(), listOf(CorrectionEffect.forExport(timeline, strength))))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
                .setTransmuxAudio(true)
                .apply { if (video.hdr) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
                .build()
            try {
                transformer.start(composition, scratch.absolutePath)
                coroutineScope {
                    val hook = launch { whileEncoding() }
                    done.await()
                    hook.cancel()
                }
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            } finally {
                scratch.delete()
            }
        }
    }

    private fun insertPending(video: MediaItem.Video): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ExportNaming.videoFileName(video.creationTimeMillis))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            video.creationTimeMillis?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
        }
        return resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw ExportException(FailureReason.WriteFailed)
    }

    private fun fileSize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun MediaItem.Video.toInfo() =
        VideoInfo(width, height, rotation, durationUs, frameRate, bitrate, codec, hdr, creationTimeMillis)

    private fun Media3ExportException.toExportException(): ExportException {
        val reason = when (errorCode) {
            Media3ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, Media3ExportException.ERROR_CODE_IO_NO_PERMISSION,
            Media3ExportException.ERROR_CODE_DECODER_INIT_FAILED, Media3ExportException.ERROR_CODE_DECODING_FAILED,
            Media3ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> FailureReason.UnreadableSource
            else -> if (isOutOfSpace()) {
                FailureReason.InsufficientStorage
            } else FailureReason.WriteFailed
        }
        return ExportException(reason, this)
    }
}
