package com.markopacak.divelens.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.correction.CorrectionEstimator
import com.markopacak.divelens.correction.CorrectionPhotoProcessor
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Export through the real correction (spec: "Exports are corrected"). */
class CorrectedExportTest {
    private val resolver = TestMedia.resolver

    private val estimator = CorrectionEstimator(resolver)
    private val processor = CorrectionPhotoProcessor({ estimator.estimate(Uri.parse(it)) })

    private fun writer(processor: PhotoProcessor = this.processor) =
        MediaStorePhotoWriter(resolver, TestMedia.context.cacheDir, processor, TestMedia.TEST_ALBUM)

    private suspend fun export(source: Uri, processor: PhotoProcessor = this.processor): Uri =
        Uri.parse(writer(processor).write(MediaImporter(resolver).import(listOf(source)).accepted.single() as MediaItem.Photo))

    private fun decode(uri: Uri): Bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it)!! }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun blueCastPhotoIsCorrectedAndOriginalUntouched() = runBlocking {
        val source = TestMedia.jpegWithExif("blue.jpg", 400, 300)
        val before = TestMedia.sha256(source)
        val original = decode(source); val exported = decode(export(source))

        assertEquals(original.width to original.height, exported.width to exported.height)
        val redBefore = pixels(original).map { it shr 16 and 0xff }.average()
        val redAfter = pixels(exported).map { it shr 16 and 0xff }.average()
        assertTrue("mean red $redBefore -> $redAfter", redAfter > redBefore + 20)
        assertEquals(before, TestMedia.sha256(source))
    }

    @Test fun exportingTwiceGivesIdenticalPixels() = runBlocking {
        val source = TestMedia.jpegWithExif("twice.jpg", 400, 300)
        val a = pixels(decode(export(source))); val b = pixels(decode(export(source)))
        assertArrayEquals(a, b)
    }

    /** Spec "Preview matches the export": the export applies the shared correction, as the CPU reference does. */
    @Test fun exportAppliesTheSharedCorrection() = runBlocking {
        assertExportMatchesTheCpuReference(processor, strength = null)
    }

    /** Spec "Strength chosen by the user": a photo set to 40% exports at strength 0.4. */
    @Test fun exportUsesThePhotosStrength() = runBlocking {
        val atForty = CorrectionPhotoProcessor({ estimator.estimate(Uri.parse(it)) }, strengthFor = { 0.4f })
        assertExportMatchesTheCpuReference(atForty, strength = 0.4f)
    }

    private suspend fun assertExportMatchesTheCpuReference(processor: PhotoProcessor, strength: Float?) {
        val source = TestMedia.jpegWithExif("shared.jpg", 4800, 600) // wide enough for a sample size of 2
        var exported: IntArray? = null
        val capture = PhotoProcessor { s, input -> processor.process(s, input).also { exported = pixels(it) } }
        export(source, capture)

        val reference = decode(source).copy(Bitmap.Config.ARGB_8888, true)
        val correction = estimator.estimate(source).let { if (strength != null) it.withStrength(strength) else it }
        CorrectionPhotoProcessor.applyOnCpu(reference, correction)
        val expected = pixels(reference)
        val actual = exported!!
        assertEquals(expected.size, actual.size)
        var worst = 0
        for (i in expected.indices) for (shift in intArrayOf(0, 8, 16)) {
            worst = maxOf(worst, kotlin.math.abs((expected[i] shr shift and 0xff) - (actual[i] shr shift and 0xff)))
        }
        assertTrue("largest channel difference $worst", worst <= 1)
    }
}
