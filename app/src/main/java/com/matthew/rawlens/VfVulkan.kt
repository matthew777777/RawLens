// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Vulkan zero-copy viewfinder compute (see `app/src/main/cpp/vf_vulkan_vf.cpp`).
 * Imports the camera HAL's RAW `AHardwareBuffer` (which EGL cannot import on this
 * gralloc) as `R16_UINT`, runs the superpixel compute shader, and writes the RGBA8
 * result into an app-allocated export buffer that GL re-imports for tonemap +
 * present. All calls run serialized on the viewfinder GL worker. Result codes are
 * best-effort diagnostics; any nonzero code means "fall back".
 */
internal object VfVulkan {
    const val OK = 0
    const val NOT_INITIALIZED = 1
    const val NO_EXTENSION = 2
    const val DEVICE_FAILED = 3
    const val PIPELINE_FAILED = 4
    const val INPUT_IMPORT_FAILED = 5
    const val OUTPUT_IMPORT_FAILED = 6
    const val SUBMIT_FAILED = 7
    const val BAD_ARGUMENT = 8

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

    fun describe(code: Int): String = when (code) {
        OK -> "ok"
        NOT_INITIALIZED -> "not-initialized"
        NO_EXTENSION -> "no-extension"
        DEVICE_FAILED -> "device-failed"
        PIPELINE_FAILED -> "pipeline-failed"
        INPUT_IMPORT_FAILED -> "input-import"
        OUTPUT_IMPORT_FAILED -> "output-import"
        SUBMIT_FAILED -> "submit"
        BAD_ARGUMENT -> "bad-argument"
        else -> "code-$code"
    }

    /**
     * Pack compute params. Layout must match `vf_vulkan_vf.cpp` and the push-constant
     * block in `vf_superpixel.comp`: iparams = [ch0..ch3, left, top, width, height,
     * step, pitch], fparams = [black0..3, invRange0..3]. Pitch is the sensor-plane
     * stride in pixels (rowStride / pixelStride), the same value the CPU sampler uses.
     */
    fun packParams(
        channels: IntArray,
        left: Int, top: Int, width: Int, height: Int, step: Int, pitch: Int,
        levels: FloatArray, white: Float
    ): Pair<IntArray, FloatArray> {
        require(channels.size == 4 && levels.size == 4)
        val iparams = intArrayOf(
            channels[0], channels[1], channels[2], channels[3],
            left, top, width, height, step, pitch
        )
        val fparams = FloatArray(8) { i ->
            if (i < 4) levels[i] else 1f / (white - levels[i - 4]).coerceAtLeast(1f)
        }
        return iparams to fparams
    }

    /** Ceil-division dispatch group count shared with the native dispatch. */
    fun dispatchGroups(extent: Int): Int = (extent + 7) / 8

    /** Create the persistent device + superpixel pipeline from SPIR-V bytes. Idempotent. */
    external fun initNative(spv: ByteArray): Int

    /** Import (or reuse) the export buffer as the compute shader's RGBA8 target. */
    external fun ensureOutputNative(outputBuffer: HardwareBuffer): Int

    /**
     * Run superpixel on [inputBuffer].
     * @param iparams [ch0..ch3, left, top, width, height, step, pitch] (10 ints)
     * @param fparams [black0..3, invRange0..3] (8 floats)
     */
    external fun computeNative(
        inputBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray
    ): Int

    /** Evict all cached imports (session boundary). Keeps device + pipeline. */
    external fun resetNative()
}
