package com.markopacak.divelens.media

import java.time.LocalDateTime

/**
 * An item the user imported. [source] is the content URI string returned by the picker.
 * Width and height are the stored pixel dimensions, before any rotation.
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
        /** EXIF date taken as the camera's wall-clock time, for the caption; null when the photo has none. */
        val dateTaken: LocalDateTime? = null,
    ) : MediaItem

    /** A video clip (add-video-processing D4). */
    data class Video(
        override val source: String,
        override val displayName: String,
        override val mimeType: String,
        val width: Int,
        val height: Int,
        /** Clockwise display rotation in degrees: 0, 90, 180 or 270. */
        val rotation: Int = 0,
        val durationUs: Long,
        val frameRate: Float,
        /** Average video bitrate in bits per second; 0 when unknown. */
        val bitrate: Int = 0,
        /** MIME type of the video track, such as `video/avc` or `video/hevc`. */
        val codec: String,
        /** 10-bit HLG or PQ, tone-mapped to SDR before correction. */
        val hdr: Boolean = false,
        /** Creation time from the container, epoch milliseconds (UTC); null when the file has none. */
        val creationTimeMillis: Long? = null,
    ) : MediaItem {
        /** Width and height as displayed, after [rotation]. */
        val displayWidth: Int get() = if (rotation % 180 == 0) width else height
        val displayHeight: Int get() = if (rotation % 180 == 0) height else width
    }
}
