package com.markopacak.divelens.export

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.Branding
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Exports one photo: full-resolution decode, [processor], JPEG to a temp file, EXIF copy, then a
 * pending MediaStore insert under Pictures/<album>. Pixels keep their stored orientation and the
 * EXIF orientation tag is copied, so the export displays like the original. On failure or
 * cancellation, the temp file and any pending gallery row are removed.
 */
class MediaStorePhotoWriter(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val processor: PhotoProcessor,
    private val album: String = Branding.EXPORT_ALBUM,
    /** Test hook: runs after the bytes are written, before the export is published. */
    private val beforePublish: suspend () -> Unit = {},
) : PhotoWriter {

    override suspend fun write(photo: MediaItem.Photo): String = withContext(Dispatchers.IO) {
        val source = photo.source.toUri()
        val temp = File.createTempFile("export", ".jpg", cacheDir)
        var pending: Uri? = null
        try {
            val (width, height) = encodeProcessed(source, temp)
            coroutineContext.ensureActive()

            val exif = readExif(source)
            ExifInterface(temp).apply {
                if (exif != null) ExifAllowList.copy(exif, this, width, height)
                saveAttributes()
            }
            coroutineContext.ensureActive()

            val target = insertPending(photo, exif).also { pending = it }
            resolver.openOutputStream(target)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw ExportException(FailureReason.WriteFailed)
            beforePublish()
            coroutineContext.ensureActive()

            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            pending = null
            target.toString()
        } catch (e: OutOfMemoryError) {
            throw ExportException(FailureReason.OutOfMemory, e)
        } catch (e: IOException) {
            throw ExportException(if (e.isOutOfSpace()) FailureReason.InsufficientStorage else FailureReason.WriteFailed, e)
        } finally {
            temp.delete()
            pending?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    private suspend fun encodeProcessed(source: Uri, temp: File): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val decoded = try {
            resolver.openInputStream(source)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        } catch (e: FileNotFoundException) {
            throw ExportException(FailureReason.UnreadableSource, e)
        } catch (e: SecurityException) {
            throw ExportException(FailureReason.UnreadableSource, e)
        } ?: throw ExportException(FailureReason.UnreadableSource)

        var processed: Bitmap? = null
        try {
            coroutineContext.ensureActive()
            processed = processor.process(decoded)
            coroutineContext.ensureActive()
            val ok = temp.outputStream().buffered().use { processed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!ok) throw ExportException(FailureReason.WriteFailed)
            processed.width to processed.height
        } finally {
            if (processed != null && processed !== decoded) processed.recycle()
            decoded.recycle()
        }
    }

    private fun readExif(source: Uri): ExifInterface? = runCatching {
        resolver.openInputStream(source)?.use { ExifInterface(it) }
    }.getOrNull()

    private fun insertPending(photo: MediaItem.Photo, exif: ExifInterface?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, ExportNaming.fileName(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            dateTaken(exif)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, values) ?: throw ExportException(FailureReason.WriteFailed)
    }

    private fun dateTaken(exif: ExifInterface?): Long? = exif?.let {
        ExifDates.toEpochMillis(
            it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            it.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            it.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
        )
    }

    private fun IOException.isOutOfSpace(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any {
            (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true ||
                it.message?.contains("No space left", ignoreCase = true) == true
        }

    companion object {
        const val JPEG_QUALITY = 95
    }
}
