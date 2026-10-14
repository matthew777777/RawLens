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
    const val IMAGE_UNSUPPORTED = 12

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
        IMAGE_UNSUPPORTED -> "image-unsupported"
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
     * step, pitch], fparams = Q6 fixed-point [blackQ0..3, denQ0..3] bit-cast to float
     * ([VfLevels]; the native fill memcpys the words and the shader recovers the
     * ints with `floatBitsToInt`). Pitch is the sensor-plane stride in pixels
     * (rowStride / pixelStride), the same value the CPU sampler uses.
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
        val (blackQ, denQ) = VfLevels.toFixedQ6(levels, white)
        return iparams to VfLevels.toBits(blackQ, denQ)
    }

    /** Ceil-division dispatch group count shared with the native dispatch. */
    fun dispatchGroups(extent: Int): Int = (extent + 7) / 8

    /** Create the persistent device + superpixel pipeline from SPIR-V bytes. Idempotent. */
    external fun initNative(spv: ByteArray): Int

    /**
     * Persistence file for the process pipeline cache. Call before
     * [initNative] for a warm start; also applies to the live device
     * (subsequently created pipelines) and every later (re)init.
     */
    external fun setPipelineCachePathNative(path: String)

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
     * @param fparams Q6 [blackQ0..3, denQ0..3] bit-cast to float (8 words, see [VfLevels])
     */
    external fun computeNative(
        inputBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray
    ): Int

    /**
     * Create the sampled-image superpixel pipeline ([computeImageNative])
     * on the shared device. Call after [initNative]; idempotent. A non-OK
     * code means the image tier is unavailable and callers must upload.
     */
    external fun initImageNative(spv: ByteArray): Int

    /**
     * Sampled-image primary tier: import [inputBuffer] as a sampled R16_UINT
     * image and run superpixel. [IMAGE_UNSUPPORTED] means this stream is
     * not zero-copyable (BLOB / non-R16 / unsampled) — latch the tier and
     * use [computePlaneNative] / [computeCopyNative] for the session.
     */
    external fun computeImageNative(
        inputBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray
    ): Int

    /**
     * TRANSFER bridge-copy tier: buffer import (shared cache) + copy engine
     * into an owned R16 image + the sampled superpixel pipeline. Zero CPU
     * copies even where direct compute reads misbehave; sits below the
     * direct-read tiers and above the CPU-touching uploads. Uses the
     * [initImageNative] pipeline; [IMAGE_UNSUPPORTED] here means the tier is
     * unavailable (advance), not that the stream is unimportable.
     */
    external fun computeBridgeNative(
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
     * CPU-plane upload tier: copy the Camera2 plane's direct buffer
     * ([byteCount] = rowStride * height bytes) into staging and dispatch.
     * No hardware-buffer lock or import, so it covers streams whose
     * allocation cannot be imported (packed or BLOB layouts).
     */
    external fun computePlaneNative(
        planeBuffer: java.nio.ByteBuffer,
        byteCount: Int,
        iparams: IntArray,
        fparams: FloatArray
    ): Int

    /**
     * Upload the normalize LUT ([VfLevels.lutFor]: [lutMax] cutoff + 4
     * packed site rows) into the binding-3 table before [computeNative] /
     * [computeCopyNative]. The table memoizes the Q6 formula per sensor
     * site, so the shader replaces 4 integer divisions per pixel with 4
     * buffer fetches, bit-exact by construction. Call every dispatch (a
     * few KB memcpy); a non-OK code means the dispatch must not run with
     * a stale table — drop the frame like a compute failure.
     */
    external fun uploadLutNative(lutMax: Int, lut: ByteArray): Int

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

    /**
     * True when the shared device enabled shaderFloat16
     * (VK_KHR_shader_float16_int8): the fused record kernel then runs its
     * fp16 SPIR-V ([VfLogGrade.fusedAsset]), else the fp32 twin. False
     * before [initNative].
     */
    external fun supportsF16MathNative(): Boolean
}

/** Next step after one VF tier attempt (pure, unit-tested). */
internal enum class VfTierAction {
    PRESENT,
    ADVANCE_TO_BRIDGE,
    ADVANCE_TO_PLANE,
    ADVANCE_TO_COPY,
    ADVANCE_TO_CPU,
    SKIP_FRAME
}

/**
 * Tier-ladder verdict for the sampled-image tier: present on a clean
 * dispatch, skip on backpressure, otherwise advance to the bridge-copy
 * tier. A black output ([blackOutput] from the probation probe) advances
 * too — a validated image import that reads black is not retried per frame.
 */
internal fun vfImageAction(code: Int, blackOutput: Boolean): VfTierAction = when {
    code == VfVulkan.BUSY -> VfTierAction.SKIP_FRAME
    code == VfVulkan.OK && !blackOutput -> VfTierAction.PRESENT
    else -> VfTierAction.ADVANCE_TO_BRIDGE
}

/**
 * Ladder verdict for the TRANSFER bridge-copy tier: the last rung before
 * CPU-touching uploads. Present on a clean dispatch, skip on backpressure,
 * otherwise advance to the plane-upload tier.
 */
internal fun vfBridgeAction(code: Int, blackOutput: Boolean): VfTierAction = when {
    code == VfVulkan.BUSY -> VfTierAction.SKIP_FRAME
    code == VfVulkan.OK && !blackOutput -> VfTierAction.PRESENT
    else -> VfTierAction.ADVANCE_TO_PLANE
}

/** Ladder verdict for the CPU-plane upload tier (no import involved). */
internal fun vfPlaneAction(code: Int, blackOutput: Boolean): VfTierAction = when {
    code == VfVulkan.BUSY -> VfTierAction.SKIP_FRAME
    code == VfVulkan.OK && !blackOutput -> VfTierAction.PRESENT
    else -> VfTierAction.ADVANCE_TO_COPY
}

/** Ladder verdict for the lock+memcpy upload tier. */
internal fun vfCopyAction(code: Int, blackOutput: Boolean): VfTierAction = when {
    code == VfVulkan.BUSY -> VfTierAction.SKIP_FRAME
    code == VfVulkan.OK && !blackOutput -> VfTierAction.PRESENT
    else -> VfTierAction.ADVANCE_TO_CPU
}
