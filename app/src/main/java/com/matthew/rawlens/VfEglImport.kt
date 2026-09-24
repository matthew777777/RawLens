// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Minimal native bridge for zero-copy viewfinder upload. The Java EGL bindings hide
 * `eglGetNativeClientBufferANDROID` / `eglCreateImageKHR`, so the import lives in
 * `app/src/main/cpp/vf_egl_jni.cpp` (lib `rawLensVfEgl`), where they are public NDK API.
 *
 * All calls must run on the viewfinder GL worker while its EGL context is current.
 * Every function fails soft (0 handle / GL error code) so the caller falls back to
 * the NEON sampler; a missing library disables the GPU path via [available].
 */
internal object VfEglImport {
    val available: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("rawLensVfEgl")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
        available = loaded
    }

    /** Import [buffer] as an `EGLImageKHR`; returns 0 on failure. */
    external fun createEGLImage(buffer: HardwareBuffer): Long

    /** Bind [eglImage] to [textureId] as `GL_TEXTURE_2D`; returns the GL error code. */
    external fun bindEGLImageToTexture2D(eglImage: Long, textureId: Int): Int

    /** Destroy an image created by [createEGLImage]. */
    external fun destroyEGLImage(eglImage: Long)
}
