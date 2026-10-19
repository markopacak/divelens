package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.opengl.GLES30
import com.markopacak.divelens.analysis.Correction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class GlException(message: String) : RuntimeException(message)

/**
 * The part of a bitmap already corrected when the GPU path failed: every row above [bandTop], and
 * columns left of [bandDoneX] in rows `bandTop until bandBottom`.
 */
data class CorrectedRegion(val bandTop: Int, val bandBottom: Int, val bandDoneX: Int) {
    companion object {
        val NONE = CorrectedRegion(0, 0, 0)
    }
}

/** The GPU path failed; [corrected] tells the fallback which pixels it already wrote. */
class GpuCorrectionException(val corrected: CorrectedRegion, cause: Throwable) : RuntimeException(cause.message, cause)

/**
 * Applies a [Correction] to mutable `ARGB_8888` bitmaps in place on the GPU (add-auto-correction D4, D10;
 * add-preview-compare D5): an offscreen EGL context, the shift maps as float textures, tiles of at most
 * [maxTile] pixels uploaded to a texture, one draw with the correction shader into a framebuffer object, read
 * back and written into the same bitmap. Each pixel's result depends only on the pixel, its position in the
 * photo and the maps, so tiles cannot leave seams.
 *
 * EGL contexts are bound to a thread: use a [Session] from one thread only ([GlThread]).
 */
class GlCorrectionRenderer(private val maxTile: Int = 2048) {

    /**
     * Corrects the whole photo [bitmap] with a session opened and closed for the call.
     * @throws GpuCorrectionException when EGL or GL fails, saying which pixels were already corrected.
     */
    fun apply(bitmap: Bitmap, correction: Correction) {
        require(bitmap.width == correction.width && bitmap.height == correction.height) { "correction is for another size" }
        openSession(correction).use { it.apply(bitmap, 0, 0, bitmap.width, bitmap.height) }
    }

    /**
     * Keeps a context, the program and the map textures of [correction] for many [Session.apply] calls,
     * such as the viewer's base image and tiles. Close it on the thread that opened it.
     * @throws GpuCorrectionException when EGL or GL setup fails.
     */
    fun openSession(correction: Correction): Session = Session(correction)

    inner class Session internal constructor(private val correction: Correction) : AutoCloseable {
        private val egl: OffscreenEgl
        private var program = 0
        private val ids = IntArray(4)
        private val fbo = IntArray(1)
        private val vao = IntArray(1)
        private val tile: Int
        private var originLocation = 0
        private var imageSizeLocation = 0
        private var strengthLocation = 0
        private var closed = false

        init {
            try {
                egl = OffscreenEgl()
                try {
                    egl.makeCurrent()
                    val maxTexture = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
                    tile = min(maxTile, maxTexture)
                    setUp()
                } catch (e: Throwable) {
                    releaseGl()
                    egl.release()
                    throw e
                }
            } catch (e: RuntimeException) {
                throw GpuCorrectionException(CorrectedRegion.NONE, e)
            }
        }

        private fun setUp() {
            program = program()
            GLES30.glGenTextures(4, ids, 0)
            for (id in ids.take(2)) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, tile, tile)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            }
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[1], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("framebuffer incomplete")
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])

            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            val map = correction.map
            for ((unit, values) in listOf(1 to map.a, 2 to map.b)) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1 + unit])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
                buf.asFloatBuffer().put(values)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, map.width, map.height, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
            }
            checkError("maps")

            GLES30.glUseProgram(program)
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uMapSize"), map.width, map.height)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSource"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMapA"), 1)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMapB"), 2)
            originLocation = GLES30.glGetUniformLocation(program, "uOrigin")
            imageSizeLocation = GLES30.glGetUniformLocation(program, "uImageSize")
            strengthLocation = GLES30.glGetUniformLocation(program, "uStrength")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_DITHER)
            checkError("setup")
        }

        /**
         * Corrects [bitmap] in place as the region at ([originX], [originY]) of the photo shown at
         * [imageWidth]×[imageHeight], such as a tile of a sampled decode, at [strength].
         * @throws GpuCorrectionException when GL fails, saying which pixels of [bitmap] were already corrected.
         */
        fun apply(
            bitmap: Bitmap,
            originX: Int,
            originY: Int,
            imageWidth: Int,
            imageHeight: Int,
            strength: Float = correction.params.strength,
        ) {
            require(bitmap.isMutable) { "bitmap must be mutable" }
            check(!closed) { "session closed" }
            var region = CorrectedRegion.NONE
            try {
                egl.makeCurrent()
                GLES30.glUniform1f(strengthLocation, strength)
                GLES30.glUniform2f(imageSizeLocation, imageWidth.toFloat(), imageHeight.toFloat())
                val tw = min(tile, bitmap.width); val th = min(tile, bitmap.height)
                val tiles = BitmapTiles(bitmap, tw, th)
                var y = 0
                while (y < bitmap.height) {
                    val h = min(th, bitmap.height - y)
                    var x = 0
                    while (x < bitmap.width) {
                        val w = min(tw, bitmap.width - x)
                        tiles.read(x, y, w, h)
                        GLES30.glUniform2i(originLocation, originX + x, originY + y)
                        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tiles.buffer)
                        GLES30.glViewport(0, 0, w, h)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                        tiles.buffer.clear()
                        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tiles.buffer)
                        checkError("tile $x,$y")
                        tiles.write(x, y, w, h)
                        x += w
                        region = CorrectedRegion(y, y + h, x)
                    }
                    y += h
                    region = CorrectedRegion(y, y, 0)
                }
            } catch (e: RuntimeException) {
                throw GpuCorrectionException(region, e)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { egl.makeCurrent() }
            releaseGl()
            egl.release()
        }

        private fun releaseGl() {
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(4, ids, 0)
            if (program != 0) GLES30.glDeleteProgram(program)
        }
    }

    private fun program(): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, CorrectionShader.VERTEX)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, CorrectionShader.FRAGMENT)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val ok = IntArray(1).also { GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, it, 0) }[0]
        if (ok == 0) {
            val log = GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p)
            throw GlException("link failed: $log")
        }
        return p
    }

    private fun shader(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1).also { GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, it, 0) }[0]
        if (ok == 0) {
            val log = GLES30.glGetShaderInfoLog(s); GLES30.glDeleteShader(s)
            throw GlException("compile failed: $log")
        }
        return s
    }

    private fun checkError(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw GlException("GL error 0x${Integer.toHexString(e)} at $where")
    }
}
