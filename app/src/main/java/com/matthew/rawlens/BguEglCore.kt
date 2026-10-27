// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/** Minimal ES3-only EGL owner: display, config, context, window surface. */
internal class BguEglCore(surface: android.view.Surface) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) ||
            count[0] == 0 || configs[0] == null
        ) {
            EGL14.eglTerminate(display)
            throw IllegalStateException("no ES3 config")
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (context === EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            throw IllegalStateException("no ES3 context")
        }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            throw IllegalStateException("no window surface")
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            destroy()
            throw IllegalStateException("makeCurrent failed")
        }
        EGL14.eglSwapInterval(display, 1)
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun destroy() {
        try {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglDestroySurface(display, eglSurface)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglDestroyContext(display, context)
        } catch (_: Exception) {
        }
        try {
            EGL14.eglTerminate(display)
        } catch (_: Exception) {
        }
    }
}

