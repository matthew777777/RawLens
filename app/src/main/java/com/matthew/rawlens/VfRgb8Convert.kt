// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Direct-Log 8-bit SDR fallback convert: staged P010 -> full-res RGBA8
 * for the EGL encoder present (see `vf_p010torgb8.comp` for the 1:1
 * kernel, `vf_vulkan_vf.cpp` for the host side). Shares the [VfVulkan]
 * device; initialized alongside the record pipelines, only on takes
 * that negotiate the SDR path.
 */
internal object VfRgb8Convert {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    external fun initRgb8Native(spv: ByteArray): Int

    /**
     * Convert staged P010 [p010Buffer] to full-res RGBA8 [rgb8Buffer]
     * ([idims]/[isrc] both W/H of the encode; [istrides] OUR P010
     * staging convention; [slot] 0..2 ping-pong selector).
     *
     * @return exportable sync fd (>= 0) gating the RGBA8 result, negative
     * on failure (same codes as the preview submit, -20 on layout
     * mismatch). Caller adopts the fd (poll + close).
     */
    external fun rgb8SubmitNative(
        p010Buffer: HardwareBuffer,
        rgb8Buffer: HardwareBuffer,
        idims: IntArray,
        isrc: IntArray,
        istrides: IntArray,
        slot: Int,
    ): Int
}
