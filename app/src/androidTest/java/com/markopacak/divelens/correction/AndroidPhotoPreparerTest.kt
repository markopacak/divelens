package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.markopacak.divelens.TestMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Corrected thumbnails (design D3). */
class AndroidPhotoPreparerTest {
    private val preparer = AndroidPhotoPreparer(TestMedia.resolver)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    private fun meanRed(b: Bitmap): Double {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.map { it shr 16 and 0xff }.average()
    }

    @Test fun thumbnailIsCorrectedAndInDisplayOrientation() = runBlocking {
        val source = TestMedia.jpegWithExif("thumb.jpg", 1600, 1200) // blue-green, orientation 6 (rotate 90)
        val prepared = preparer.prepare(source.toString())
        val thumb = prepared.thumbnail

        assertEquals(90, prepared.rotation)
        assertEquals(288 to 384, thumb.width to thumb.height) // stored 1600×1200, shown as portrait
        assertEquals(1600 to 1200, prepared.correction.width to prepared.correction.height)

        val original = TestMedia.resolver.openInputStream(source)!!.use { BitmapFactory.decodeStream(it)!! }
        val red = meanRed(thumb); val originalRed = meanRed(original)
        assertTrue("mean red $originalRed -> $red", red > originalRed + 20)
    }
}
