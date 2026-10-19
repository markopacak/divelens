package com.markopacak.divelens.export

import android.content.ContentResolver
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore

/**
 * Removes this app's exports that are still pending in Pictures/<album> (add-video-processing D8): left behind when the
 * process died mid-export, so no partial file survives (import-export "Batch export with progress and cancel"). Only
 * the app's own pending rows are visible to it, so other apps' files are never touched.
 */
object PendingRows {
    fun removeStale(resolver: ContentResolver, album: String): Int {
        var removed = 0
        for (volume in listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${Environment.DIRECTORY_PICTURES}/$album/"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val ids = runCatching {
                resolver.query(volume, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
            }.getOrNull().orEmpty()
            for (id in ids) {
                removed += runCatching { resolver.delete(android.content.ContentUris.withAppendedId(volume, id), null, null) }.getOrDefault(0)
            }
        }
        return removed
    }
}
