package com.markopacak.divelens.export

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifAllowListTest {
    private val tags = ExifAllowList.tags

    @Test fun excludesGps() = assertTrue(tags.none { it.startsWith("GPS") })

    @Test fun excludesThumbnailMakerNotesSerialsAndDimensions() {
        val excluded = listOf(
            ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
        )
        excluded.forEach { assertFalse(it, it in tags) }
    }

    @Test fun includesDateCameraAndOrientation() {
        listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
        ).forEach { assertTrue(it, it in tags) }
    }
}
