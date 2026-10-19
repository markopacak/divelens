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

    /** add-strength-slider D3: the thumbnail at another strength, from the kept shift, equals a CPU correction at it. */
    @Test fun reRenderedThumbnailMatchesTheCpuCorrection() = runBlocking {
        val source = TestMedia.jpegWithExif("strength.jpg", 1600, 1200)
        val prepared = preparer.prepare(source.toString())
        val rendered = prepared.renderer!!.render(0.3f)

        val reduced = CorrectionEstimator(TestMedia.resolver).decode(source)
        val reference = AndroidPhotoPreparer.downscale(reduced.bitmap, AndroidPhotoPreparer.THUMBNAIL_SIZE)
        CorrectionPhotoProcessor.applyOnCpu(reference, prepared.correction.at(reference.width, reference.height).withStrength(0.3f))
        val expected = AndroidPhotoPreparer.rotate(reference, 90)

        assertEquals(expected.width to expected.height, rendered.width to rendered.height)
        val a = IntArray(expected.width * expected.height).also { expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height) }
        val b = IntArray(a.size).also { rendered.getPixels(it, 0, rendered.width, 0, 0, rendered.width, rendered.height) }
        var worst = 0
        for (i in a.indices) for (c in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs((a[i] shr c and 0xff) - (b[i] shr c and 0xff)))
        assertTrue("largest channel difference $worst", worst <= 2)
    }
}
