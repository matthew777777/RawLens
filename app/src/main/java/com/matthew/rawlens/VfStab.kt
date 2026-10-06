// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/**
 * Post-record stabilization warp: one decoded frame (host-uploaded tight
 * P010) -> warped P010 staging (the exact bytes queued to the re-encode),
 * in one blocking dispatch per frame (see `vf_stabwarp.comp` for the
 * kernel, `vf_vulkan_vf.cpp` for the host side). Shares the [VfVulkan]
 * device; the RAW viewfinder never initializes it.
 *
 * Unlike the record stages this is an offline pass (background thread,
 * after the take): single command buffer, fence-gated blocking submits,
 * no twin ping-pong, no fd export, plain [VfVulkan] return codes.
 */
internal object VfStab {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    /** Create the warp pipeline + upload staging for [width]x[height]. Idempotent per dims. */
    external fun initStabNative(spv: ByteArray, width: Int, height: Int): Int

    /**
     * Uploads one decoded frame (direct plane buffers from the decoder
     * output image) into the tight-P010 staging. Strides/px-strides are
     * bytes from the image planes; [is10Bit] selects the 10-bit copy vs
     * the 8-bit video-range upscale. Same-thread ordered ahead of
     * [stabSubmitNative] (a host barrier inside the submit carries it).
     */
    external fun stabUploadNative(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        yStrideB: Int,
        uStrideB: Int,
        vStrideB: Int,
        uPxB: Int,
        vPxB: Int,
        width: Int,
        height: Int,
        is10Bit: Boolean,
    ): Int

    /**
     * Blocking warp dispatch: staging -> [p010Buffer] (OUR P010 staging,
     * same layout contract as the grade outputs). Returns when the GPU
     * work is complete (fence), so the caller copies straight ahead.
     *
     * @param iparams [W, H, outYStrideB, outUvStrideB] (4 ints)
     * @param fparams row-major out->source homography (9 floats)
     */
    external fun stabSubmitNative(
        p010Buffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray,
    ): Int

    /** Frees the warp pipeline + staging (session boundary). Keeps the device. */
    external fun resetStabNative()
}
