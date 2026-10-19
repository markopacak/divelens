package com.markopacak.divelens.correction

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markopacak.divelens.analysis.AnalysisImageBuilder
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.analysis.GuidedShift
import com.markopacak.divelens.analysis.V1Model
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** The GPU guided filter and per-frame correction against the CPU reference (add-video-processing D2, task 4.2). */
@RunWith(AndroidJUnit4::class)
class GpuFrameCorrectionTest {
    private lateinit var egl: OffscreenEgl
    private lateinit var gpu: GpuFrameCorrection

    @Before fun setUp() {
        egl = OffscreenEgl().also { it.makeCurrent() }
        gpu = GpuFrameCorrection()
    }

    @After fun tearDown() {
        gpu.close()
        egl.release()
    }

    /** Underwater-like texture with a bright edge and a dark area, so all branches of the correction run. */
    private fun frame(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val edge = if (x in w / 3 until w / 3 + w / 20) 120 else 0
        val dark = if (y > h * 3 / 4) 60 else 0
        val r = ((x * 7 + y * 3) % 40 + 10 + edge - dark / 3).coerceIn(0, 255)
        val g = (70 + (x * 5 + y * 11) % 110 + edge - dark).coerceIn(0, 255)
        val b = (110 + (x * 3 + y * 13) % 130 + edge / 2 - dark).coerceIn(0, 255)
        (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun params(pixels: IntArray, w: Int, h: Int) = V1Model.estimate(AnalysisImageBuilder.of(pixels, w, h), 0.78f)

    private fun upload(pixels: IntArray, w: Int, h: Int): Int {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) buf.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put((p ushr 24).toByte())
        buf.flip()
        val id = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return id
    }

    private fun readFloat(texture: Int, w: Int, h: Int): FloatArray {
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        Gl.check("read maps")
        return FloatArray(w * h * 4).also { buf.asFloatBuffer().get(it) }
    }

    private fun mapsMatchCpu(w: Int, h: Int) {
        val pixels = frame(w, h)
        val p = params(pixels, w, h)
        val cpu = GuidedShift.map(AnalysisImageBuilder.of(pixels, w, h, GuidedShift.SMOOTH_WIDTH), p)
        val tex = upload(pixels, w, h)
        gpu.buildMaps(tex, w, h, p)
        assertTrue(gpu.mapWidth == cpu.width && gpu.mapHeight == cpu.height)
        val a = readFloat(gpu.mapA, cpu.width, cpu.height)
        val b = readFloat(gpu.mapB, cpu.width, cpu.height)
        val tolerance = if (gpu.fullPrecision) 1e-3f else 4e-3f
        var worst = 0f
        for (i in 0 until cpu.width * cpu.height) for (c in 0 until 3) {
            worst = max(worst, max(abs(a[i * 4 + c] - cpu.a[i * 4 + c]), abs(b[i * 4 + c] - cpu.b[i * 4 + c])))
        }
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        assertTrue("${w}x$h: worst map difference $worst > $tolerance (32F: ${gpu.fullPrecision})", worst <= tolerance)
    }

    @Test fun mapsMatchTheCpuOnTheSameWorkingImage() = mapsMatchCpu(400, 250)

    @Test fun mapsMatchTheCpuAfterTheBoxDownscale() = mapsMatchCpu(1500, 844)

    private fun frameMatchesCpu(w: Int, h: Int, params: (IntArray) -> CorrectionParams) {
        val pixels = frame(w, h)
        val p = params(pixels)
        val expected = pixels.copyOf()
        CpuCorrection.applyInPlace(expected, Correction(p, GuidedShift.map(AnalysisImageBuilder.of(pixels, w, h, GuidedShift.SMOOTH_WIDTH), p), w, h), 0, 0, w, h)

        val src = upload(pixels, w, h)
        val out = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, out)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, out, 0)
        GLES30.glViewport(0, 0, w, h)
        gpu.draw(src, w, h, p, p.strength)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        Gl.check("read frame")
        var worst = 0
        for (i in 0 until w * h) {
            for ((k, shift) in intArrayOf(16, 8, 0).withIndex()) {
                val g = buf.get(i * 4 + k).toInt() and 0xff
                worst = max(worst, abs(g - (expected[i] shr shift and 0xff)))
            }
        }
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(2, intArrayOf(src, out), 0)
        assertTrue("${w}x$h: worst channel difference $worst/255 > 2", worst <= 2)
    }

    @Test fun correctedFrameMatchesTheCpu() = frameMatchesCpu(640, 360) { params(it, 640, 360) }

    @Test fun correctedFrameMatchesTheCpuAtAnotherStrengthAndSize() = frameMatchesCpu(1500, 844) { params(it, 1500, 844).withStrength(0.4f) }

    @Test fun identityLeavesTheFrameUnchanged() = frameMatchesCpu(320, 180) { CorrectionParams.IDENTITY.withStrength(1f) }
}
