// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.opengl.EGL14
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct-Log take monitor: presents the staged encode pixels (see
 * [VfRecordPreview]) INSTEAD of a separately-demosaiced viewfinder.
 *
 * Stacked over the (idle, untouched) RAW viewfinder and visible only
 * during a Direct-Log take: the recorder submits one tiny P010->RGBA8
 * dispatch per frame on the camera thread (same-queue ordered behind
 * the encode) and hands the RGBA buffer + completion fd here; this
 * worker adopts the fence (GPU-side wait, zero CPU stall), EGL-imports
 * the buffer, blits aspect-fit with the sensor-orientation UV remap,
 * and releases the slot latch so the camera thread may reuse it.
 *
 * Latest-only on every path: a slow present never stacks superseded
 * frames (the recorder also skips submits while this worker is busy),
 * and the encode is never gated by the monitor — every skip degrades
 * to an older monitor frame, never a dropped record frame.
 *
 * Fd + latch contract: [offerPreview] consumes both exactly once on
 * every path (presented, superseded, surfaceless, detached, or
 * destroyed). The slot buffers stay recorder-owned; this view never
 * closes them.
 */
class DirectLogPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    private data class Pending(
        val buffer: android.hardware.HardwareBuffer,
        val fd: Int,
        val rotation: Int,
        val mirrored: Boolean,
        val release: () -> Unit,
    )

    private val lock = Any()
    private var pending: Pending? = null
    private val thread = HandlerThread("DirectLogPreview", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)

    /** Set by the recorder for the take; false otherwise. Any thread. */
    @Volatile private var attached = false

    /** True while a draw is queued or running (recorder skips offers then). Any thread. */
    @Volatile private var workOutstanding = false

    @Volatile private var hasSurface = false
    private var window: Surface? = null
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var nextInitAttemptMs = 0L

    private var quadRotation = Int.MIN_VALUE
    private var quadMirrored = false
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { position(0) }

    private val presented = AtomicInteger(0)
    private val adoptFailed = AtomicInteger(0)
    private val presentFailed = AtomicInteger(0)

    init {
        holder.addCallback(this)
    }

    /**
     * Attach/detach the take monitor. Detach flushes any queued preview
     * (fd closed, slot latch released) and logs the take totals. Safe
     * from any thread; idempotent.
     */
    fun setPreviewAttached(active: Boolean) {
        if (attached == active) return
        attached = active
        if (!active) {
            flushPending()
            Log.i(TAG, "preview detached: presented=${presented.get()} " +
                "adoptFailed=${adoptFailed.get()} presentFailed=${presentFailed.get()}")
        }
    }

    /** True when offers will be presented (attached + live surface). Any thread. */
    val isPreviewActive: Boolean get() = attached && hasSurface

    /**
     * Preview image dims for the take (session-constant, set by the
     * recorder at attach; drives the aspect-fit viewport). Any thread.
     */
    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0

    fun setPreviewSize(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    /**
     * True when the worker has no draw queued or running (mirrors the
     * RAW viewfinder's offer gate). Any thread.
     */
    fun isIdleForOffer(): Boolean =
        !workOutstanding && synchronized(lock) { pending == null }

    /**
     * Offer one graded preview frame. Consumes [fd] and calls [release]
     * exactly once on every path. Latest-only: a queued-but-undrawn
     * preview is superseded (its fd closed, its latch released) instead
     * of stacking. Camera thread only (the recorder's).
     */
    fun offerPreview(
        buffer: android.hardware.HardwareBuffer,
        fd: Int,
        rotation: Int,
        mirrored: Boolean,
        release: () -> Unit,
    ) {
        if (!isPreviewActive) {
            consumeNow(fd, release)
            return
        }
        synchronized(lock) {
            pending?.let {
                consumeNow(it.fd, it.release)
                pending = null
            }
            pending = Pending(buffer, fd, rotation, mirrored, release)
        }
        worker.removeCallbacks(draw)
        worker.post(draw)
        workOutstanding = true
    }

    /** Immediate consume for offers that will never draw. Any thread. */
    private fun consumeNow(fd: Int, release: () -> Unit) {
        try {
            VfEglImport.closeSyncFd(fd)
        } catch (_: Exception) {
        }
        try {
            release()
        } catch (_: Exception) {
        }
    }

    /** Drop any queued preview (detach / surface loss / dispose). Any thread. */
    private fun flushPending() {
        val dropped = synchronized(lock) { pending.also { pending = null } }
        if (dropped != null) {
            consumeNow(dropped.fd, dropped.release)
            // The removed draw will never run its finally: release the
            // idle gate here, or offers wedge until the next attach. (A
            // concurrently running draw resets the same flag idempotently;
            // a transient early idle just supersede-queues, harmless.)
            workOutstanding = false
        }
        worker.removeCallbacks(draw)
    }

    fun dispose() {
        setPreviewAttached(false)
        worker.post {
            worker.removeCallbacks(draw)
            releaseGl()
            thread.quitSafely()
        }
    }

    private val draw = Runnable {
        val pv = synchronized(lock) { pending.also { pending = null } } ?: return@Runnable
        // Set the moment the fd is adopted-or-closed by native (or was
        // already invalid): the finally below must then NOT close it.
        var fdConsumed = false
        try {
            val target = window
            if (target == null || !hasSurface) return@Runnable
            if (surface == EGL14.EGL_NO_SURFACE) {
                if (!tryInitializeGl(target)) return@Runnable
            }
            // GPU-side wait on the preview dispatch (never blocks the CPU;
            // EGL owns the fd from here, even on failure paths).
            if (pv.fd >= 0) {
                try {
                    if (VfEglImport.adoptNativeFence(pv.fd) != 0) adoptFailed.incrementAndGet()
                } catch (_: Exception) {
                    adoptFailed.incrementAndGet()
                } finally {
                    fdConsumed = true
                }
            } else {
                fdConsumed = true
            }
            val eglImage = try {
                VfEglImport.createEGLImage(pv.buffer)
            } catch (_: Exception) {
                0L
            }
            if (eglImage == 0L) {
                presentFailed.incrementAndGet()
                return@Runnable
            }
            try {
                present(eglImage, pv.rotation, pv.mirrored)
                presented.incrementAndGet()
            } finally {
                try {
                    VfEglImport.destroyEGLImage(eglImage)
                } catch (_: Exception) {
                    // Best effort: the import is already fully consumed.
                }
            }
        } catch (failure: Exception) {
            presentFailed.incrementAndGet()
            Log.w(TAG, "preview present failed", failure)
            releaseGl()
        } finally {
            workOutstanding = false
            if (!fdConsumed) {
                try {
                    VfEglImport.closeSyncFd(pv.fd)
                } catch (_: Exception) {
                }
            }
            try {
                pv.release()
            } catch (_: Exception) {
            }
        }
    }

    /** EGL import + aspect-fit blit + swap. GL worker only. */
    private fun present(eglImage: Long, rotation: Int, mirrored: Boolean) {
        val texW = previewWidth
        val texH = previewHeight
        check(texW > 0 && texH > 0) { "preview size unset" }
        // Full-surface black first (letterbox bars), then the fitted
        // image. glClear is viewport-scissored, so reset to the full
        // surface first — otherwise the bars keep stale buffer content
        // after the first frame insets the viewport below.
        android.opengl.GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
        android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
        val rect = fitViewport(viewportWidth, viewportHeight, texW, texH, rotation)
        android.opengl.GLES20.glViewport(rect[0], rect[1], rect[2], rect[3])
        android.opengl.GLES20.glUseProgram(program)
        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texture)
        val bindError = VfEglImport.bindEGLImageToTexture2D(eglImage, texture)
        check(bindError == android.opengl.GLES20.GL_NO_ERROR) { "preview egl-bind=$bindError" }
        android.opengl.GLES20.glUniform1i(
            android.opengl.GLES20.glGetUniformLocation(program, "uTex"), 0
        )
        GlBlit.drawQuad(program, quadFor(rotation, mirrored))
        // Sampling must complete before the slot latch releases (the
        // camera thread reuses the buffer on release). Swap presents
        // after; it never samples.
        android.opengl.GLES20.glFinish()
        val glError = android.opengl.GLES20.glGetError()
        check(glError == android.opengl.GLES20.GL_NO_ERROR) { "preview draw failed: 0x${glError.toString(16)}" }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            val eglError = EGL14.eglGetError()
            error("preview swap failed: 0x${eglError.toString(16)}")
        }
    }

    /**
     * Fullscreen quad with the sensor-orientation UV remap: same vertex
     * order and [RawPreviewGeometry.sensorPoint] mapping as the RAW
     * viewfinder's proven present path. GL worker only.
     */
    private fun quadFor(rotation: Int, mirrored: Boolean): java.nio.FloatBuffer {
        if (quadRotation != rotation || quadMirrored != mirrored) {
            quad.clear()
            val pos = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val uv = arrayOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f).map { (x, y) ->
                RawPreviewGeometry.sensorPoint(x, y, rotation, mirrored)
            }
            for (i in 0 until 4) {
                quad.put(pos[i * 2]).put(pos[i * 2 + 1]).put(uv[i].first).put(uv[i].second)
            }
            quad.flip()
            quadRotation = rotation
            quadMirrored = mirrored
        }
        return quad
    }

    /** Single-shot ES2 init (the blit needs nothing newer). GL worker only. */
    private fun tryInitializeGl(target: Surface): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < nextInitAttemptMs) return false
        nextInitAttemptMs = now + 2000L
        try {
            val disp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(disp != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            check(EGL14.eglInitialize(disp, IntArray(2), 0, IntArray(2), 0)) { "eglInitialize failed" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(disp, attrs, 0, configs, 0, 1, IntArray(1), 0)) {
                "eglChooseConfig failed"
            }
            val ctx = EGL14.eglCreateContext(
                disp, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            val surf = EGL14.eglCreateWindowSurface(disp, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(disp, ctx)
                error("eglCreateWindowSurface failed")
            }
            if (!EGL14.eglMakeCurrent(disp, surf, surf, ctx)) {
                EGL14.eglDestroySurface(disp, surf)
                EGL14.eglDestroyContext(disp, ctx)
                error("eglMakeCurrent failed")
            }
            check(EGL14.eglSwapInterval(disp, 1)) { "swap interval setup failed" }
            display = disp
            eglContext = ctx
            surface = surf
            program = GlBlit.buildProgram()
            texture = GlBlit.genTexture()
            Log.i(TAG, "preview GL ready ${viewportWidth}x$viewportHeight")
            return true
        } catch (failure: Exception) {
            Log.w(TAG, "preview GL init failed; retrying", failure)
            releaseGl()
            return false
        }
    }

    private fun releaseGl() {
        // Runs on the GL worker: drop GL state, never the framework Surface.
        program = 0
        texture = 0
        quadRotation = Int.MIN_VALUE
        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            } catch (_: Exception) {
            }
            try {
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            } catch (_: Exception) {
            }
            try {
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglTerminate(display)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglReleaseThread()
            } catch (_: Exception) {
            }
        }
        surface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        worker.post {
            releaseGl()
            window = holder.surface
            val frame = holder.surfaceFrame
            viewportWidth = frame.width().coerceAtLeast(1)
            viewportHeight = frame.height().coerceAtLeast(1)
            hasSurface = holder.surface.isValid
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        worker.post {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
            window = holder.surface
            hasSurface = holder.surface.isValid
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The framework owns the Surface: just drop GL state + any queued
        // preview (its fd closes, its slot latch releases). The recorder
        // keeps encoding; presents resume if the surface returns.
        flushPending()
        worker.post { hasSurface = false; window = null; releaseGl() }
    }

    companion object {
        private const val TAG = "DirectLogPreview"

        /**
         * Aspect-fit viewport [x, y, w, h] for an [imgW]x[imgH] image on a
         * [surfW]x[surfH] surface, accounting for the sensor-orientation
         * rotation (90/270 swap the displayed axes). Letterboxes with
         * caller-cleared bars; exact-fit returns the full surface.
         */
        fun fitViewport(
            surfW: Int, surfH: Int, imgW: Int, imgH: Int, rotation: Int,
        ): IntArray {
            require(surfW > 0 && surfH > 0 && imgW > 0 && imgH > 0) {
                "viewport needs positive dims"
            }
            val (dw, dh) = if (rotation == 90 || rotation == 270) imgH to imgW else imgW to imgH
            // Compare surfW/surfH vs dw/dh without floats: surface wider
            // than the image -> pillarbox (fit height), else letterbox.
            return if (surfW.toLong() * dh > surfH.toLong() * dw) {
                val w = (surfH.toLong() * dw / dh).toInt().coerceAtLeast(1)
                intArrayOf((surfW - w) / 2, 0, w, surfH)
            } else {
                val h = (surfW.toLong() * dh / dw).toInt().coerceAtLeast(1)
                intArrayOf(0, (surfH - h) / 2, surfW, h)
            }
        }
    }
}
