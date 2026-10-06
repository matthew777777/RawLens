// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Vulkan viewfinder compute (see `app/src/main/cpp/vf_vulkan_vf.cpp`), two input
 * tiers sharing one superpixel pipeline and export buffer. Zero-copy imports the
 * camera HAL's RAW `AHardwareBuffer` (which EGL cannot import on this gralloc) as
 * `R16_UINT`; gpu-copy (motioncam pattern) lock+memcpys it into a host-visible
 * staging buffer instead, for HALs whose import reads back zeros (Adreno 750).
 * Both write RGBA8 into an app-allocated export buffer that GL re-imports for
 * tonemap + present. All calls run serialized on the viewfinder GL worker. Result
 * codes are best-effort diagnostics; any nonzero code means "fall back", except
 * BUSY (backpressure: skip the frame, keep the tier).
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
    const val DEVICE_LOST = 9
    const val BUSY = 10
    const val COPY_UPLOAD_FAILED = 11

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
        DEVICE_LOST -> "device-lost"
        BUSY -> "busy"
        COPY_UPLOAD_FAILED -> "copy-upload"
        else -> "code-$code"
    }

    // Recreating the device cannot repair incompatible HAL buffer metadata.
    fun shouldRecover(reason: String): Boolean =
        reason == "submit" || reason == "device-failed" || reason == "not-initialized" || reason == "device-lost"

    // Retrying submissions on a lost VkDevice cannot recover it.
    fun shouldDisableImmediately(reason: String): Boolean = reason == "device-lost"

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

    /**
     * Create the Direct-Log f16 superpixel variant (same bindings, rgba16f
     * store) on the shared device. Idempotent. The viewfinder never calls
     * this; wait-free twins bind it via scope swap.
     */
    external fun initF16Native(spv: ByteArray): Int

    /**
     * Tear down and recreate the device + pipeline from SPIR-V bytes after
     * persistent submit failures (wedged queue or lost device). Imports are
     * dropped and re-created on demand, so the next frame re-probes a fresh
     * device instead of failing on a dead one forever.
     */
    external fun reinitNative(spv: ByteArray): Int

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

    /**
     * GPU-copy twin of [computeNative]: lock+memcpy [inputBuffer] into the
     * host-visible staging buffer, then the same superpixel dispatch. Same
     * fence/export/reap semantics; only the input mechanism differs.
     */
    external fun computeCopyNative(
        inputBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray
    ): Int

    /**
     * Tier black-output probe: max byte over a coarse grid of the export
     * buffer (0..255), or -1 when the export cannot be CPU-locked. Call after
     * a compute fence, with GL drained, on probation frames only.
     */
    external fun sampleOutputNative(): Int

    /**
     * Wait-free twin of [computeNative]: submits without
     * `vkQueueWaitIdle`. Correct only when the consumer submit follows on
     * the same queue ([VfLogGrade.gradeSubmitNative]) or otherwise carries
     * completion (fd handoff). [slot] selects the ping-pong command buffer
     * (0/1); reuse must stay gated by the caller's EGL fence. [expBuffer]
     * is imported into recorder-private state (never evicts the
     * viewfinder's output). Falls back to [computeNative] on any error.
     * [shadeDims] is null (unshaded) or [rows, cols, l, t, r, b] with
     * [shadeGains] the Camera2-order HAL map (rows*cols*4 fp16 bits),
     * hardware-bilinear sampled; per-quad-channel, pre-WB like the VF.
     */
    external fun computeSubmitNative(
        inputBuffer: HardwareBuffer,
        expBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray,
        slot: Int,
        shadeDims: IntArray?,
        shadeGains: ShortArray?,
    ): Int

    /** Evict all cached imports (session boundary). Keeps device + pipeline. */
    external fun resetNative()
}
