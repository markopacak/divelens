package com.markopacak.divelens

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest

/** Synthetic fixtures inserted into MediaStore under Pictures/[TEST_ALBUM]. No real photos or locations. */
object TestMedia {
    const val TEST_ALBUM = "DiveLensTest"
    const val DATE = "2026:07:14 10:32:05"
    const val OFFSET = "+02:00"

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver: ContentResolver get() = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** A width×height gradient JPEG with date, camera, orientation 6 and fake GPS tags. */
    fun jpegWithExif(name: String, width: Int = 400, height: Int = 300, withOffset: Boolean = true): Uri {
        val file = File.createTempFile("fixture", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = Color.rgb(40, 120 + x * 100 / width, 200 - y * 100 / height)
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DATE)
            if (withOffset) setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, OFFSET)
            setAttribute(ExifInterface.TAG_MAKE, "TestMake")
            setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SERIAL123")
            setLatLong(43.5034, 16.4491)
            saveAttributes()
        }
        return insert(name, "image/jpeg", file.readBytes()).also { file.delete() }
    }

    fun asset(name: String, mime: String, asset: String): Uri =
        insert(name, mime, InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() })

    fun png(name: String): Uri {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return insert(name, "image/png", bytes)
    }

    fun corruptJpeg(name: String): Uri = insert(name, "image/jpeg", ByteArray(2048) { (it * 31).toByte() })

    /** The synthetic clip from `tools/reference/video_fixture.py`: 2 s, 640×360, rotation 90, location, text track. */
    fun driftVideo(name: String = "drift.mp4"): Uri = asset(name, "video/mp4", "synthetic_drift.mp4")

    /** The synthetic 10-bit HLG HEVC clip from `tools/reference/video_fixture.py`. */
    fun hlgVideo(name: String = "hlg.mp4"): Uri = asset(name, "video/mp4", "synthetic_hlg.mp4")

    /** The synthetic 20 s clip from `tools/reference/video_fixture.py`, for viewer tests that need playback to last. */
    fun longVideo(name: String = "long.mp4"): Uri = asset(name, "video/mp4", "synthetic_long.mp4")

    /** An MP4 header followed by garbage. */
    fun corruptMp4(name: String): Uri =
        insert(name, "video/mp4", byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(4096) { (it * 31).toByte() })

    fun insert(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            // Each fixture gets its own folder so leftovers from an aborted run can't collide.
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$TEST_ALBUM/src/${System.nanoTime()}")
        }
        val uri = resolver.insert(if (mime.startsWith("video/")) videoCollection else collection, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    fun sha256(uri: Uri): String =
        MessageDigest.getInstance("SHA-256").digest(resolver.openInputStream(uri)!!.use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }

    /** All photo rows in Pictures/[TEST_ALBUM]/ (exports only, not sources), including pending ones. */
    fun exportedRows(): List<Uri> = query("${MediaStore.Images.Media.RELATIVE_PATH} = ?", "Pictures/$TEST_ALBUM/")

    /** All video rows in Pictures/[TEST_ALBUM]/ (exports only, not sources), including pending ones. */
    fun exportedVideoRows(): List<Uri> = query("${MediaStore.Video.Media.RELATIVE_PATH} = ?", "Pictures/$TEST_ALBUM/", videoCollection)

    fun deleteAll() {
        for (c in listOf(collection, videoCollection)) {
            query("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", "Pictures/$TEST_ALBUM/%", c).forEach { resolver.delete(it, null, null) }
        }
    }

    private fun query(selection: String, arg: String, collection: Uri = this.collection): List<Uri> {
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(arg))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.MediaColumns._ID), selection, arrayOf(arg), null)
        }
        return cursor!!.use { c ->
            buildList { while (c.moveToNext()) add(Uri.withAppendedPath(collection, c.getLong(0).toString())) }
        }
    }

    fun <T> column(uri: Uri, column: String, read: (android.database.Cursor) -> T): T =
        resolver.query(uri, arrayOf(column), null, null, null)!!.use { it.moveToFirst(); read(it) }
}
