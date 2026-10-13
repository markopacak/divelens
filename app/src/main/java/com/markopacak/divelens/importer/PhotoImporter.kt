package com.markopacak.divelens.importer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RejectionReason { UnsupportedFormat, Unreadable, TooLarge }

data class Rejection(val displayName: String, val reason: RejectionReason)

data class ImportResult(val accepted: List<MediaItem.Photo>, val rejected: List<Rejection>)

/** Validates picked URIs cheaply (MIME type and bounds only) before they enter the working set. */
class PhotoImporter(private val resolver: ContentResolver) {

    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val accepted = mutableListOf<MediaItem.Photo>()
        val rejected = mutableListOf<Rejection>()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: uri.toString()
            val mime = resolver.getType(uri)?.lowercase()
            if (mime !in SUPPORTED_MIME_TYPES) {
                rejected += Rejection(name, RejectionReason.UnsupportedFormat)
                continue
            }
            val bounds = decodeBounds(uri)
            when {
                bounds == null -> rejected += Rejection(name, RejectionReason.Unreadable)
                bounds.first.toLong() * bounds.second > MAX_PIXELS -> rejected += Rejection(name, RejectionReason.TooLarge)
                else -> accepted += MediaItem.Photo(uri.toString(), name, mime!!, bounds.first, bounds.second)
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

    companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/heic", "image/heif")

        /** Guard against running out of memory on full-resolution decode. */
        const val MAX_PIXELS = 100_000_000L
    }
}
