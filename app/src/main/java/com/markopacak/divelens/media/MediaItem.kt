package com.markopacak.divelens.media

/**
 * An item the user imported. [source] is the content URI string returned by the picker.
 * Width and height are the stored pixel dimensions, before any EXIF rotation.
 */
sealed interface MediaItem {
    val source: String
    val displayName: String
    val mimeType: String

    data class Photo(
        override val source: String,
        override val displayName: String,
        override val mimeType: String,
        val width: Int,
        val height: Int,
    ) : MediaItem

    /** Not importable yet; video processing arrives in a later change. */
    data class Video(
        override val source: String,
        override val displayName: String,
        override val mimeType: String,
    ) : MediaItem
}
