package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorrectionPhotoProcessorTest {

    private val w = 640
    private val h = 480

    private fun source(): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (0xff shl 24) or (((x * 7 + y * 3) % 40 + 5) shl 16) or ((70 + (x * 5 + y * 11) % 120) shl 8) or (110 + (x * 3 + y * 13) % 140)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun pixels(bmp: Bitmap) = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }

    private fun cpuReference(): IntArray {
        val bmp = source()
        CorrectionPhotoProcessor.applyOnCpu(bmp, CorrectionPhotoProcessor.estimate(bmp))
        return pixels(bmp)
    }

    @Test
    fun gpuFailureBeforeAnyTileFallsBackToCpu() = runBlocking {
        val input = source()
        val processor = CorrectionPhotoProcessor(gpu = { _, _ -> throw GpuCorrectionException(CorrectedRegion.NONE, GlException("no EGL")) })
        val out = processor.process("test", input)
        assertSame(input, out)
        assertArrayEquals(cpuReference(), pixels(out))
    }

    @Test
    fun gpuFailureMidwayFinishesOnlyTheRemainingPixels() = runBlocking {
        val expected = cpuReference()
        val corrected = Bitmap.createBitmap(expected, w, h, Bitmap.Config.ARGB_8888)
        val region = CorrectedRegion(bandTop = 100, bandBottom = 200, bandDoneX = 256)
        // Simulates a GPU path that wrote all rows above 100 and the left part of the band, then failed.
        val processor = CorrectionPhotoProcessor(gpu = { bmp, _ ->
            val c = Canvas(bmp)
            c.drawBitmap(corrected, Rect(0, 0, w, 100), Rect(0, 0, w, 100), null)
            c.drawBitmap(corrected, Rect(0, 100, 256, 200), Rect(0, 100, 256, 200), null)
            throw GpuCorrectionException(region, GlException("lost context"))
        })
        assertArrayEquals(expected, pixels(processor.process("test", source())))
    }

    @Test
    fun realGpuPathMatchesCpuWithinOneStep() = runBlocking {
        val gpu = pixels(CorrectionPhotoProcessor().process("test", source()))
        val cpu = cpuReference()
        for (i in cpu.indices) for (s in intArrayOf(16, 8, 0)) {
            val d = kotlin.math.abs((cpu[i] shr s and 0xff) - (gpu[i] shr s and 0xff))
            assert(d <= 1) { "pixel $i differs by $d" }
        }
    }

    /** Spec "Export at zero strength": the pixels pass through, without a correction or the GPU. */
    @Test
    fun zeroStrengthReturnsTheInputUntouched() = runBlocking {
        var lookups = 0; var gpuCalls = 0
        val processor = CorrectionPhotoProcessor(
            corrections = { lookups++; error("no correction expected") },
            strengthFor = { 0f },
            gpu = { _, _ -> gpuCalls++ },
        )
        val input = source()
        val out = processor.process("test", input)
        assertSame(input, out)
        assertArrayEquals(pixels(source()), pixels(out))
        org.junit.Assert.assertEquals(0 to 0, lookups to gpuCalls)
    }
}
