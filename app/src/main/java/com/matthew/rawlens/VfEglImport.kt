// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

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

    /**
     * BGU guide-sample handoff: export the current GL stream position as a
     * dup'd native fence fd, signaled when prior GL work completes. -1 on
     * failure; close with [closeSyncFd].
     */
    external fun exportFenceFd(): Int

    /**
     * CPU-fill [buffer] (RGBA_8888, allocated with USAGE_CPU_WRITE_OFTEN)
     * with an animated gradient + moving white bar. Probe-only stand-in
     * for the Vulkan superpixel export: real AHB bytes -> EGL import ->
     * encoder surface. Returns 0 on success, nonzero diagnostic otherwise.
     */
    external fun fillTestPattern(buffer: HardwareBuffer, width: Int, height: Int, frameIndex: Int): Int

    /**
     * Phase-A probe: allocate a synthetic sensor buffer (R16_UINT, byte-
     * identical to packed RAW16 for the format-agnostic storage import).
     * Null on failure.
     */
    external fun createBayerInput(width: Int, height: Int): HardwareBuffer?

    /**
     * Phase-A probe: fill a synthetic RAW16 buffer with an animated RGGB
     * mosaic. Returns row stride in pixels (superpixel `u_pitch`), or a
     * negative diagnostic on failure.
     */
    external fun fillBayerPattern(buffer: HardwareBuffer, width: Int, height: Int, frameIndex: Int): Int

    /**
     * Perf-harness CFA (see vf_egl_jni.cpp): BLOB-backed mosaic with
     * GPU_DATA_BUFFER, modeling camera HAL cacheability. Fill returns
     * pitch (== width, tightly packed) or a negative diagnostic.
     */
    external fun createBlobBayerInput(width: Int, height: Int): HardwareBuffer?
    external fun fillBlobBayer(buffer: HardwareBuffer, width: Int, height: Int): Int

    /**
     * Copy [byteCount] bytes from direct [src] into BLOB [dst] (JPEG RCD
     * CFA upload; Java cannot lock a HardwareBuffer). Returns 0 or a
     * negative diagnostic.
     */
    external fun blitBytesToBlob(dst: HardwareBuffer, src: ByteBuffer, byteCount: Int): Int

    /**
     * Phase-A fence sync (EGL_KHR_fence_sync; the Java bindings only
     * expose ANDROID fences, so this lives in native code). [createFence]
     * inserts a fence after the current GL commands (call after swap);
     * returns 0 when unsupported. [waitFence] waits up to [timeoutNs].
     */
    external fun createFence(): Long
    external fun waitFence(sync: Long, timeoutNs: Long): Boolean
    external fun destroyFence(sync: Long)

    /**
     * Wait-free handoff consumer: adopt a Vulkan-exported sync fd so the
     * GPU waits for prior compute before sampling. Never blocks the CPU.
     * Takes ownership of [fd]; returns 0 on success.
     */
    external fun adoptNativeFence(fd: Int): Int

    /**
     * True-10-bit slot gating: non-blocking poll of a Vulkan-exported sync
     * fd (true = GPU work complete). Never closes; see [closeSyncFd].
     */
    external fun pollSyncFd(fd: Int): Boolean
    external fun closeSyncFd(fd: Int)

    /** App-owned P010 staging buffer (GPU-written, CPU-readable). Null on failure. */
    external fun createP010(width: Int, height: Int): HardwareBuffer?

    /** [yStrideBytes, uvStrideBytes, 0] from the allocator; null on failure. */
    external fun describeP010(buffer: HardwareBuffer): IntArray?

    /**
     * Dumb CPU copy: ours semi-planar P010 -> codec planar input planes
     * (direct ByteBuffers + strides + chroma spacing). All color math is
     * already on GPU. Returns 0, negative on failure.
     */
    external fun copyP010ToCodec(
        src: HardwareBuffer,
        yDst: java.nio.ByteBuffer, yDstStride: Int,
        uDst: java.nio.ByteBuffer, uDstStride: Int, uDstPxB: Int,
        vDst: java.nio.ByteBuffer, vDstStride: Int, vDstPxB: Int,
        width: Int, height: Int,
        srcYStrideB: Int, srcUvStrideB: Int,
    ): Int

    /**
     * Precision-probe readback: {Y, U, V} P010 words at ([x],[y]) from an
     * app-owned tight semi-planar buffer, or null. Caller polls the submit
     * fd first (a CPU lock would stall, not fail, on unfinished GPU work —
     * but the probe wants an explicit completion gate, not an implicit one).
     */
    external fun sampleP010Center(
        buffer: HardwareBuffer, yStrideB: Int, uvStrideB: Int,
        width: Int, height: Int, x: Int, y: Int,
    ): IntArray?

    /**
     * Phase-B validation: sparse CPU sample of an RGBA_8888 buffer
     * (needs USAGE_CPU_READ_OFTEN). Returns {avgR, avgG, avgB,
     * centerLuma} 0..1, or null when unreadable.
     */
    external fun sampleRgba(buffer: HardwareBuffer, width: Int, height: Int): FloatArray?

    /**
     * Ten-bit proof: CPU-fill an RGBA_FP16 buffer with a smooth ramp.
     * Returns row stride in pixels, negative on failure.
     */
    external fun fillHalfGradient(buffer: HardwareBuffer, width: Int, height: Int, frameIndex: Int): Int

    /**
     * RCD parity readback: full RGBA_FP16 buffer as interleaved RGBA
     * floats, or null. Caller polls the submit fd first.
     */
    external fun dumpFp16(buffer: HardwareBuffer, width: Int, height: Int): FloatArray?
}
