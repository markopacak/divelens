package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.analysis.AnalysisImageBuilder
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.analysis.GuidedShift
import com.markopacak.divelens.analysis.V1Model
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The effect in a real Transformer run (task 4.3): a decoded frame of the export matches the CPU correction of the same
 * original frame. This also confirms that Media3 hands SDR frames to the effect in their encoded values, as the model needs.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CorrectionEffectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val output = File(context.cacheDir, "effect_test.mp4")

    @Before @After fun cleanUp() {
        TestMedia.deleteAll()
        output.delete()
    }

    private fun frameAt(uri: Uri, timeUs: Long): Bitmap = MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            release()
        }
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test fun exportedFrameMatchesTheCpuCorrection() {
        val source = TestMedia.driftVideo()
        val original = frameAt(source, 1_000_000)
        val w = original.width; val h = original.height
        val px = pixels(original)
        val params = V1Model.estimate(AnalysisImageBuilder.of(px, w, h), 0.78f)
        val timeline = CorrectionTimeline(2_000_000).withSample(0, params).completed()

        val done = CountDownLatch(1)
        var error: ExportException? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) = done.countDown()
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    error = exception; done.countDown()
                }
            }).build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), listOf(CorrectionEffect.forExport(timeline, 0.78f))))
                .build()
            transformer.start(item, output.absolutePath)
        }
        assertTrue("export timed out", done.await(60, TimeUnit.SECONDS))
        error?.let { throw AssertionError("export failed", it) }

        val exported = frameAt(Uri.fromFile(output), 1_000_000)
        assertEquals(w to h, exported.width to exported.height)
        val expected = px.copyOf()
        CpuCorrection.applyInPlace(expected, Correction(params, GuidedShift.map(AnalysisImageBuilder.of(px, w, h, GuidedShift.SMOOTH_WIDTH), params), w, h), 0, 0, w, h)
        val got = pixels(exported)
        val signed = DoubleArray(3); var absSum = 0.0
        for (i in got.indices) for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
            val d = (got[i] shr shift and 0xff) - (expected[i] shr shift and 0xff)
            signed[c] += d.toDouble(); absSum += abs(d)
        }
        val n = got.size.toDouble()
        val means = signed.map { it / n }
        val meanAbs = absSum / (3 * n)
        println("effect vs CPU at 1 s: mean difference per channel $means, mean absolute $meanAbs")
        assertTrue("mean colour differs from the CPU correction: $means (absolute $meanAbs)", means.all { abs(it) <= 1.5 })
    }
}
