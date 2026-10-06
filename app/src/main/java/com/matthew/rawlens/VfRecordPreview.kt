// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Direct-Log record preview: staged P010 (the exact bytes queued to the
 * encoder) -> small RGBA8 monitor image (see `vf_p010preview.comp` for the
 * downsample kernel, `vf_vulkan_vf.cpp` for the host side). Shares the
 * [VfVulkan] device; the RAW viewfinder never initializes it.
 *
 * The take monitor shows this INSTEAD of a separately-demosaiced
 * viewfinder: no second WB/CCM/grade, no contended full-res dispatch.
 */
internal object VfRecordPreview {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    /** Encode-to-preview downsample factor (4K -> 540p, exact 4x4 box). */
    const val PREVIEW_BOX = 4

    /**
     * Preview dims for an encode size: quarter res per axis, floored to
     * even (the chroma grid is half-res). 3840x2160 -> 960x540.
     */
    fun previewDims(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "encode size must be positive" }
        return intArrayOf(evenFloor(width / PREVIEW_BOX), evenFloor(height / PREVIEW_BOX))
    }

    private fun evenFloor(v: Int): Int = v / 2 * 2

    /**
     * Downsample box the native submit validates: both axes must scale by
     * the same integer factor (see the shader's box contract).
     */
    fun previewBox(srcWidth: Int, srcHeight: Int, prevWidth: Int, prevHeight: Int): Int {
        require(prevWidth > 0 && prevHeight > 0) { "preview size must be positive" }
        val boxX = srcWidth / prevWidth
        val boxY = srcHeight / prevHeight
        require(boxX == boxY && boxX >= 2 && boxX % 2 == 0) {
            "preview must be an exact even downsample of encode (${srcWidth}x$srcHeight -> ${prevWidth}x$prevHeight)"
        }
        require(srcWidth == prevWidth * boxX && srcHeight == prevHeight * boxY) {
            "preview must divide encode exactly (${srcWidth}x$srcHeight -> ${prevWidth}x$prevHeight)"
        }
        return boxX
    }

    /**
     * CPU golden for the preview kernel's color step: limited-range
     * BT.709 YUV (10-bit codes as floats) -> display RGB. Exact inverse
     * of the record stages' `rgbToYuv` + `pack10` (Kr = 0.2126,
     * Kb = 0.0722); the P010 already holds sRGB-encoded components, so
     * no OETF runs here. The shader must implement this bit-for-bit
     * (modulo fp32 rounding); [DirectLogPreviewTest] pins the round trip.
     */
    fun yuvToRgb709(y: Float, u: Float, v: Float): FloatArray {
        val yn = (y - 64f) / (940f - 64f)
        val cb = (u - 64f) / (960f - 64f) - 0.5f
        val cr = (v - 64f) / (960f - 64f) - 0.5f
        val r = yn + 2f * (1f - 0.2126f) * cr
        val b = yn + 2f * (1f - 0.0722f) * cb
        val g = (yn - 0.2126f * r - 0.0722f * b) / 0.7152f
        return floatArrayOf(r, g, b)
    }

    /** Forward step (record-stage `rgbToYuv` + `pack10`): test-only round-trip source. */
    fun rgbToYuv709Limited(rgb: FloatArray): FloatArray {
        require(rgb.size == 3)
        val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        val cb = (rgb[2] - y) / (2f * (1f - 0.0722f)) + 0.5f
        val cr = (rgb[0] - y) / (2f * (1f - 0.2126f)) + 0.5f
        return floatArrayOf(
            y.coerceIn(0f, 1f) * (940f - 64f) + 64f,
            cb.coerceIn(0f, 1f) * (960f - 64f) + 64f,
            cr.coerceIn(0f, 1f) * (960f - 64f) + 64f
        )
    }

    /** Create the preview pipeline from SPIR-V bytes. Idempotent. */
    external fun initPreviewNative(spv: ByteArray): Int

    /**
     * Preview dispatch: staged P010 storage buffer -> RGBA8 preview image
     * (box downsample + BT.709 limited-range inverse). Call on the camera
     * thread right after the encode submit: same-queue FIFO orders it
     * behind the P010 write, no extra sync. Returns the completion sync
     * fd (>= 0; the presenter adopts it with
     * [VfEglImport.adoptNativeFence] before sampling, EGL owns it after)
     * or a negative code (skip the preview, the encode is unaffected).
     *
     * @param idims preview [W, H]; @param isrc encode [W, H] (P010
     * domain); @param istrides [yStrideBytes, uvStrideBytes, width,
     * height] of OUR P010 staging (same convention as the grade submits);
     * @param box exact even downsample factor (see [previewBox]);
     * @param slot 0..2 ping-pong selector.
     */
    external fun previewSubmitNative(
        p010Buffer: HardwareBuffer,
        previewBuffer: HardwareBuffer,
        idims: IntArray,
        isrc: IntArray,
        istrides: IntArray,
        box: Int,
        slot: Int,
    ): Int
}
