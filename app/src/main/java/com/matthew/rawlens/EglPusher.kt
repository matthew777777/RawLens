// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface

/**
 * Minimal EGL pusher for encoder input Surfaces (Direct Log chain).
 *
 * Creates its own display/context/window-surface on top of the MediaCodec
 * input Surface. Prefers a 30-bit (10/10/10/2) recordable config so graded
 * FP16 pixels survive to the Main10 encoder without an 8-bit choke point;
 * falls back to 8-bit with [tenBit] false (loudly visible in reports).
 * Sampling half-float EGL images needs OES_texture_half_float, checked at
 * init and logged (Mali-class GPUs all carry it).
 */
internal class EglPusher(private val surface: Surface) {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    /** True when the window surface carries 10 bits per channel. */
    var tenBit: Boolean = false
        private set

    /** True when half-float textures sample (required for FP16 exports). */
    var halfFloat: Boolean = false
        private set

    /** GL major version of the created context (3 preferred, 2 fallback). */
    var glVersion: Int = 2
        private set

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        // ES3 first (half-float textures are core; needed for FP16 exports),
        // then ES2. 30-bit recordable configs do not exist on this stack
        // (dumped: only 8/8/8/8 + 8/8/8/0), so the surface is always 8-bit:
        // precision lives in FP16 compute, dithered at the blit.
        val attempt = chooseSetup(preferEs3 = true) ?: chooseSetup(preferEs3 = false)
        check(attempt != null) { "eglChooseConfig failed" }
        val (config, es3) = attempt
        tenBit = isTenBit(config)
        glVersion = if (es3) 3 else 2
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, glVersion, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        halfFloat = glVersion >= 3 || checkHalfFloatExt()
        // Our blit shader dithers explicitly; driver dither would add a
        // second uncontrolled noise source (and break dither-off controls).
        try { GLES20.glDisable(GLES20.GL_DITHER) } catch (_: Exception) {}
        android.util.Log.i(TAG, "egl surface tenBit=$tenBit halfFloat=$halfFloat gl=$glVersion")
    }

    private data class Setup(val config: android.opengl.EGLConfig, val es3: Boolean)

    private fun chooseSetup(preferEs3: Boolean): Setup? {
        // Try 30-bit first for the record (absent on MTK), then 8-bit.
        if (preferEs3) {
            chooseConfig(10, 10, 10, 2, es3 = true)?.let { return Setup(it, true) }
        }
        chooseConfig(8, 8, 8, 8, es3 = preferEs3)?.let { return Setup(it, preferEs3) }
        return null
    }

    private fun chooseConfig(r: Int, g: Int, b: Int, a: Int, es3: Boolean): android.opengl.EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, r,
            EGL14.EGL_GREEN_SIZE, g,
            EGL14.EGL_BLUE_SIZE, b,
            EGL14.EGL_ALPHA_SIZE, a,
            EGL14.EGL_RENDERABLE_TYPE,
            if (es3) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            return null
        }
        return configs[0]
    }

    private fun isTenBit(config: android.opengl.EGLConfig): Boolean {
        val v = IntArray(1)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_RED_SIZE, v, 0)
        return v[0] >= 10
    }

    private fun checkHalfFloatExt(): Boolean {
        val exts = try {
            GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        } catch (_: Exception) {
            ""
        }
        val interesting = exts.split(" ").filter {
            it.contains("half_float") || it.contains("float_linear") ||
                it.contains("color_buffer_float") || it.contains("color_buffer_half_float") ||
                it.contains("texture_norm16")
        }
        android.util.Log.i(TAG, "egl gl extensions(capability): $interesting")
        android.util.Log.i(TAG, "egl renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
            "version=${GLES20.glGetString(GLES20.GL_VERSION)}")
        return exts.contains("OES_texture_half_float")
    }

    fun render(r: Float, g: Float, b: Float) {
        GLES20.glClearColor(r, g, b, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glFlush()
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
    }

    /**
     * Bind this pusher's context/surface on the calling thread. Required
     * when GL work happens off the creating thread (e.g. a camera handler):
     * EGL contexts are thread-local and every GL/EGLImage call below needs
     * a current context. Cheap enough to call per frame; returns false
     * instead of throwing so probes can degrade gracefully.
     */
    fun makeCurrent(): Boolean = try {
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    } catch (_: Exception) {
        false
    }

    /**
     * Release this thread's binding (Android EGL returns EGL_BAD_ACCESS
     * when a context still current elsewhere is bound on another thread;
     * there is no implicit migration). Call on the GL thread when handing
     * work to a different thread, and per-frame around cross-thread use.
     */
    fun unbind() {
        try {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
        } catch (_: Exception) {}
    }

    fun swap() {
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
    }

    /**
     * Offscreen pbuffer for CPU readback probes (dither validation etc.).
     * Returns null when pbuffers are unsupported. Pair with [unbindPbuffer];
     * the window surface is rebound afterwards.
     */
    fun bindPbuffer(width: Int, height: Int): android.opengl.EGLSurface? = try {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE,
            if (glVersion >= 3) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            null
        } else {
            val surfAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE
            )
            val surf = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                null
            } else if (!EGL14.eglMakeCurrent(display, surf, surf, context)) {
                EGL14.eglDestroySurface(display, surf)
                null
            } else {
                surf
            }
        }
    } catch (_: Exception) {
        null
    }

    fun unbindPbuffer(surf: android.opengl.EGLSurface?) {
        try {
            if (surf != null && surf != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surf)
            }
        } catch (_: Exception) {}
        try {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
        } catch (_: Exception) {}
        try {
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        } catch (_: Exception) {}
        try {
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        } catch (_: Exception) {}
        try {
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        } catch (_: Exception) {}
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT = 0x40
        private const val TAG = "EglPusher"
    }
}
