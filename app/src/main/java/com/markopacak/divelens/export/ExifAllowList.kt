package com.markopacak.divelens.export

import androidx.exifinterface.media.ExifInterface

/**
 * EXIF tags copied from an original to its export.
 *
 * Deliberately excluded: GPS tags (exports carry no location in v1; the photo picker returns
 * them zeroed, and copying would stamp a false 0°N 0°E), embedded thumbnails (would show the
 * unprocessed image), maker notes (vendor blobs with internal offsets), camera and lens serial
 * numbers, and dimension tags (rewritten from the exported image).
 */
object ExifAllowList {
    val tags: List<String> = listOf(
        // Date and time
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // Camera and lens
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        // Exposure
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        // Authorship
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        // Display
        ExifInterface.TAG_ORIENTATION,
    )

    /** Copies allow-listed tags present in [source] to [target] and sets the export's pixel dimensions. */
    fun copy(source: ExifInterface, target: ExifInterface, width: Int, height: Int) {
        for (tag in tags) {
            source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
    }
}
