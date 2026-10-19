package com.markopacak.divelens.correction

import android.opengl.GLES30
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.analysis.GuidedShift
import kotlin.math.max
import kotlin.math.min

/**
 * The correction of one video frame entirely on the GPU (add-video-processing D2): the frame's own guided-filter maps,
 * then [CorrectionShader.FRAGMENT] unchanged. Passes, all at the working size `min(1024, frame width)`:
 * 1. box downscale of the frame with the integer bounds of `AnalysisImageBuilder`, then per working pixel the
 *    full-correction shift `d` (the GLSL port of [CpuCorrection.full]) and the grey level `g`, written as
 *    `(d, g)` and `(d·g, g²)` into two float textures;
 * 2. the separable box filter of `GuidedShift.radius` (2 passes, mirrored edges as `GuidedShift.box`);
 * 3. `a = cov(g, d) / (var g + EPS)` and `b = mean d − a·mean g`;
 * 4. the box filter of `a` and `b` (2 passes): the maps of [GuidedShift.map];
 * 5. the correction shader with these maps, into the framebuffer bound by the caller.
 *
 * Uses the GL context current on the calling thread (Media3's or [OffscreenEgl]); call everything on that thread.
 * Leaves the caller's framebuffer, viewport and vertex array bound as they were.
 */
class GpuFrameCorrection : AutoCloseable {
    private var downscale = 0
    private var box = 0
    private var coefficients = 0
    private var apply = 0
    private val textures = IntArray(4)
    private val fbos = IntArray(2)
    private val vao = IntArray(1)
    private var internalFormat = 0
    private var workW = 0
    private var workH = 0

    /** Size of the maps the last frame used. */
    val mapWidth get() = workW
    val mapHeight get() = workH

    /** True when the float targets are 32-bit; 16-bit on GPUs without `EXT_color_buffer_float`. */
    val fullPrecision get() = internalFormat == GLES30.GL_RGBA32F

    private fun setUp() {
        if (downscale != 0) return
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        internalFormat = when {
            "GL_EXT_color_buffer_float" in extensions -> GLES30.GL_RGBA32F
            "GL_EXT_color_buffer_half_float" in extensions -> GLES30.GL_RGBA16F
            else -> throw GlException("no float render targets")
        }
        downscale = Gl.program(CorrectionShader.VERTEX, DOWNSCALE)
        box = Gl.program(CorrectionShader.VERTEX, BOX)
        coefficients = Gl.program(CorrectionShader.VERTEX, COEFFICIENTS)
        apply = Gl.program(CorrectionShader.VERTEX, CorrectionShader.FRAGMENT)
        GLES30.glGenTextures(4, textures, 0)
        GLES30.glGenFramebuffers(2, fbos, 0)
        GLES30.glGenVertexArrays(1, vao, 0)
        Gl.check("frame correction setup")
    }

    private fun allocate(w: Int, h: Int) {
        if (w == workW && h == workH) return
        workW = w; workH = h
        for (id in textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GLES30.GL_RGBA,
                if (internalFormat == GLES30.GL_RGBA32F) GLES30.GL_FLOAT else GLES30.GL_HALF_FLOAT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        for (pair in 0..1) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[pair])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[pair * 2], 0)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, textures[pair * 2 + 1], 0)
            GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("map framebuffer incomplete")
        }
        Gl.check("frame correction targets ${w}x$h")
    }

    /**
     * Builds the guided-filter maps of the frame in [source] (a `GL_TEXTURE_2D` of [width]×[height]) for [params]. Afterwards
     * [mapA] and [mapB] hold them, [mapWidth]×[mapHeight]. Restores the caller's framebuffer and viewport.
     */
    fun buildMaps(source: Int, width: Int, height: Int, params: CorrectionParams) {
        setUp()
        val saved = SavedState()
        try {
            val w = min(GuidedShift.SMOOTH_WIDTH, width)
            val h = max(1, (height.toDouble() * w / width).let { Math.rint(it) }.toInt()) // ties to even, as AnalysisImageBuilder
            allocate(w, h)
            val r = min(GuidedShift.radius(w), min(w, h))
            GLES30.glBindVertexArray(vao[0])
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDisable(GLES30.GL_BLEND)

            // 1. downscale and statistics into pair 0
            GLES30.glUseProgram(downscale)
            bindTexture(0, source)
            GLES30.glUniform1i(loc(downscale, "uSource"), 0)
            GLES30.glUniform2i(loc(downscale, "uSourceSize"), width, height)
            GLES30.glUniform2i(loc(downscale, "uSize"), w, h)
            val m = params.matrix
            GLES30.glUniformMatrix3fv(loc(downscale, "uMatrix"), 1, true, floatArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]), 0)
            GLES30.glUniform3f(loc(downscale, "uOffset"), m[3], m[7], m[11])
            GLES30.glUniform1i(loc(downscale, "uDarkRedCap"), if (params.darkRedCap) 1 else 0)
            GLES30.glUniform2f(loc(downscale, "uDark"), CpuCorrection.DARK_FULL, CpuCorrection.DARK_END)
            draw(0)
            // 2. box filter: pair 0 -> pair 1 (horizontal) -> pair 0 (vertical)
            boxPasses(r, w, h)
            // 3. coefficients: pair 0 -> pair 1
            GLES30.glUseProgram(coefficients)
            bindTexture(0, textures[0]); bindTexture(1, textures[1])
            GLES30.glUniform1i(loc(coefficients, "uMeans0"), 0)
            GLES30.glUniform1i(loc(coefficients, "uMeans1"), 1)
            GLES30.glUniform1f(loc(coefficients, "uEps"), GuidedShift.EPS.toFloat())
            draw(1)
            // 4. box filter of a and b: pair 1 -> pair 0 -> pair 1
            boxPasses(r, w, h, from = 1)
            Gl.check("frame maps")
        } finally {
            saved.restore()
        }
    }

    /** The map textures after [buildMaps]: `a` and `b` per channel in RGB. */
    val mapA get() = textures[2]
    val mapB get() = textures[3]

    /**
     * Corrects the frame in [source] ([width]×[height]) with [params] at [strength] into the framebuffer and viewport the
     * caller has bound, building its maps first.
     */
    fun draw(source: Int, width: Int, height: Int, params: CorrectionParams, strength: Float) {
        buildMaps(source, width, height, params)
        val saved = SavedState()
        try {
            GLES30.glBindVertexArray(vao[0])
            GLES30.glUseProgram(apply)
            bindTexture(0, source); bindTexture(1, mapA); bindTexture(2, mapB)
            GLES30.glUniform1i(loc(apply, "uSource"), 0)
            GLES30.glUniform1i(loc(apply, "uMapA"), 1)
            GLES30.glUniform1i(loc(apply, "uMapB"), 2)
            GLES30.glUniform2i(loc(apply, "uOrigin"), 0, 0)
            GLES30.glUniform2f(loc(apply, "uImageSize"), width.toFloat(), height.toFloat())
            GLES30.glUniform2i(loc(apply, "uMapSize"), workW, workH)
            GLES30.glUniform1f(loc(apply, "uStrength"), strength)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            Gl.check("frame apply")
        } finally {
            saved.restore(keepFramebuffer = true)
        }
    }

    private fun boxPasses(r: Int, w: Int, h: Int, from: Int = 0) {
        GLES30.glUseProgram(box)
        GLES30.glUniform1i(loc(box, "uA"), 0)
        GLES30.glUniform1i(loc(box, "uB"), 1)
        GLES30.glUniform1i(loc(box, "uRadius"), r)
        GLES30.glUniform2i(loc(box, "uSize"), w, h)
        for ((i, dir) in listOf(1 to 0, 0 to 1).withIndex()) {
            val src = if (i == 0) from else 1 - from
            bindTexture(0, textures[src * 2]); bindTexture(1, textures[src * 2 + 1])
            GLES30.glUniform2i(loc(box, "uDirection"), dir.first, dir.second)
            draw(1 - src)
        }
    }

    private fun draw(pair: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[pair])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun bindTexture(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    private fun loc(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)

    override fun close() {
        if (downscale == 0) return
        for (p in listOf(downscale, box, coefficients, apply)) GLES30.glDeleteProgram(p)
        GLES30.glDeleteTextures(4, textures, 0)
        GLES30.glDeleteFramebuffers(2, fbos, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        downscale = 0; workW = 0; workH = 0
    }

    /** The caller's GL bindings that the passes change. */
    private class SavedState {
        private val framebuffer = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        private val viewport = IntArray(4).also { GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, it, 0) }
        private val vertexArray = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, it, 0) }[0]
        private val program = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, it, 0) }[0]

        fun restore(keepFramebuffer: Boolean = false) {
            if (!keepFramebuffer) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GLES30.glBindVertexArray(vertexArray)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    companion object {
        /** Working-size statistics; must stay equivalent to `CpuCorrection.full` and `AnalysisImageBuilder`. */
        const val DOWNSCALE = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uSource;
uniform ivec2 uSourceSize;
uniform ivec2 uSize;
uniform mat3 uMatrix;
uniform vec3 uOffset;
uniform bool uDarkRedCap;
uniform vec2 uDark;
layout(location = 0) out vec4 outDG;
layout(location = 1) out vec4 outProducts;

void main() {
    ivec2 j = ivec2(gl_FragCoord.xy);
    ivec2 s0 = (j * uSourceSize) / uSize;
    ivec2 s1 = ((j + 1) * uSourceSize) / uSize;
    vec3 sum = vec3(0.0);
    for (int y = s0.y; y < s1.y; y++) {
        for (int x = s0.x; x < s1.x; x++) sum += texelFetch(uSource, ivec2(x, y), 0).rgb;
    }
    vec3 c = sum / float((s1.x - s0.x) * (s1.y - s0.y));
    vec3 f = uMatrix * c + uOffset;
    float r = max(f.r, c.r);
    if (uDarkRedCap) {
        float gc = clamp(f.g, 0.0, 1.0);
        float limit = gc + max(c.r - c.g, 0.0);
        float w = clamp((uDark.y - (gc + clamp(f.b, 0.0, 1.0)) / 2.0) / (uDark.y - uDark.x), 0.0, 1.0);
        r -= w * max(r - limit, 0.0);
    }
    vec3 d = clamp(vec3(r, f.g, f.b), 0.0, 1.0) - c;
    float g = (c.r + c.g + c.b) / 3.0;
    outDG = vec4(d, g);
    outProducts = vec4(d * g, g * g);
}
"""

        /** One direction of the box filter on two textures at once; edges mirrored as `GuidedShift.box`. */
        const val BOX = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uA;
uniform highp sampler2D uB;
uniform ivec2 uDirection;
uniform ivec2 uSize;
uniform int uRadius;
layout(location = 0) out vec4 outA;
layout(location = 1) out vec4 outB;

int mirror(int i, int n) {
    return i < 0 ? -i - 1 : (i >= n ? 2 * n - i - 1 : i);
}

void main() {
    ivec2 j = ivec2(gl_FragCoord.xy);
    vec4 a = vec4(0.0);
    vec4 b = vec4(0.0);
    for (int k = -uRadius; k <= uRadius; k++) {
        ivec2 q = j + uDirection * k;
        ivec2 p = ivec2(mirror(q.x, uSize.x), mirror(q.y, uSize.y));
        a += texelFetch(uA, p, 0);
        b += texelFetch(uB, p, 0);
    }
    float n = float(2 * uRadius + 1);
    outA = a / n;
    outB = b / n;
}
"""

        /** Guided-filter coefficients from the box means of `(d, g)` and `(d·g, g²)`. */
        const val COEFFICIENTS = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uMeans0;
uniform highp sampler2D uMeans1;
uniform float uEps;
layout(location = 0) out vec4 outA;
layout(location = 1) out vec4 outB;

void main() {
    ivec2 j = ivec2(gl_FragCoord.xy);
    vec4 m0 = texelFetch(uMeans0, j, 0);
    vec4 m1 = texelFetch(uMeans1, j, 0);
    float meanG = m0.a;
    float varG = m1.a - meanG * meanG;
    vec3 a = (m1.rgb - meanG * m0.rgb) / (varG + uEps);
    outA = vec4(a, 0.0);
    outB = vec4(m0.rgb - a * meanG, 0.0);
}
"""
    }
}

/** Program and error helpers shared by the GL classes. */
internal object Gl {
    fun program(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
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

    fun check(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw GlException("GL error 0x${Integer.toHexString(e)} at $where")
    }
}
