package com.markopacak.divelens.correction

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/** Offscreen EGL 1.4 context for OpenGL ES 3 with a 1×1 pbuffer (rendering goes to an FBO). */
internal class OffscreenEgl {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw GlException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlException("eglInitialize failed")
        try {
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw GlException("no ES 3 config")
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface failed")
        } catch (e: Throwable) {
            release(); throw e
        }
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw GlException("eglMakeCurrent failed")
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        // No eglTerminate: the default display is shared with the UI renderer in this process.
    }

    companion object {
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    }
}
