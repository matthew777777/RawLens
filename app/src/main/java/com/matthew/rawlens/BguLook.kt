// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Low-res look output: unlensed guide RGB (fit input) + developed RGB (fit
 * output), both [w] x [h] x 3 row-major floats. No dither by design.
 */
data class BguLookOut(
    val guide: FloatArray,
    val developed: FloatArray,
    val w: Int,
    val h: Int
) {
    init {
        require(guide.size == w * h * 3 && developed.size == w * h * 3)
    }

    override fun equals(other: Any?): Boolean =
        other is BguLookOut && w == other.w && h == other.h &&
            guide.contentEquals(other.guide) && developed.contentEquals(other.developed)

    override fun hashCode(): Int =
        31 * (31 * (31 * w + h) + guide.contentHashCode()) + developed.contentHashCode()
}

/**
 * BGU low-res look facade: runs the Halide AOT [bgu_look] filter that evaluates
 * the full VF look (RAW or calibrated AgX) on one low-res quad frame. Shares
 * the rawLensBgu library with [BguSpike]/[BguFit].
 */
internal object BguLook {
    val available: Boolean = BguSpike.available

    /** [fparams] order: EV, AgX contrast/saturation/purity/hue/shadowEv/highlightEv/gamut, shoulder. */
    const val FP_COUNT = 9
    const val FP_EV = 0
    const val FP_CONTRAST = 1
    const val FP_SATURATION = 2
    const val FP_PURITY = 3
    const val FP_HUE = 4
    const val FP_SHADOW_EV = 5
    const val FP_HIGHLIGHT_EV = 6
    const val FP_GAMUT = 7
    const val FP_SHOULDER = 8

    /** [iparams] order: jpeg, p3, applyLens, greenRow, qbX, qbY, lowStep, aL, aT, aR, aB. */
    const val IP_COUNT = 11
    const val IP_JPEG = 0
    const val IP_P3 = 1
    const val IP_APPLY_LENS = 2
    const val IP_GREEN_ROW = 3
    const val IP_QB_X = 4
    const val IP_QB_Y = 5
    const val IP_LOW_STEP = 6
    const val IP_AL = 7
    const val IP_AT = 8
    const val IP_AR = 9
    const val IP_AB = 10

    /**
     * Run the look on [quad] ([w] x [h] x 4 normalized u8 quads) with the HAL
     * lens map ([lensCols] x [lensRows] x 4 Camera2-order floats), WB/CCM and
     * calibrated color. Returns null when unavailable, on shape mismatch, or
     * on filter failure.
     *
     * Layout contract (Halide planar, channel outermost — NOT texel
     * interleaved): quad index = ch * w * h + y * w + x. Guide/developed
     * outputs use the same layout with 3 channels. The lens map uses the
     * same planar layout with 4 channels ([R, Ge, Go, B]) — the caller must
     * transpose LensShadingMap.copyGainFactors output (which is
     * cell-interleaved) because the AOT filter requires dim-0 stride 1.
     *
     * [ccm]/[aces] arrive column-major (Camera2/GL convention, matching the
     * legacy engine) and are transposed to row-major here: the filter reads
     * mat(x, y) at x + 3 * y, i.e. row by row.
     */
    fun run(
        quad: ByteArray, w: Int, h: Int,
        lens: FloatArray, lensCols: Int, lensRows: Int,
        wb: FloatArray, ccm: FloatArray, aces: FloatArray, white: FloatArray,
        fparams: FloatArray, iparams: IntArray
    ): BguLookOut? {
        if (!available) return null
        if (quad.size != w * h * 4 || lens.size != lensCols * lensRows * 4) return null
        if (wb.size != 4 || ccm.size != 9 || aces.size != 9 || white.size != 3) return null
        if (fparams.size != FP_COUNT || iparams.size != IP_COUNT) return null
        val guide = FloatArray(w * h * 3)
        val developed = FloatArray(w * h * 3)
        val rc = runInto(
            quad, w, h, lens, lensCols, lensRows, wb, ccm, aces, white,
            fparams, iparams, guide, developed
        )
        if (rc != 0) return null
        return BguLookOut(guide, developed, w, h)
    }

    /**
     * Zero-alloc look into caller buffers (fit hot loop reuses them).
     * [guideOut]/[devOut] must be w*h*3. Returns the filter status (0 = ok).
     */
    fun runInto(
        quad: ByteArray, w: Int, h: Int,
        lens: FloatArray, lensCols: Int, lensRows: Int,
        wb: FloatArray, ccm: FloatArray, aces: FloatArray, white: FloatArray,
        fparams: FloatArray, iparams: IntArray,
        guideOut: FloatArray, devOut: FloatArray
    ): Int {
        if (!available) return -1
        if (quad.size != w * h * 4 || lens.size != lensCols * lensRows * 4) return -1
        if (wb.size != 4 || ccm.size != 9 || aces.size != 9 || white.size != 3) return -1
        if (fparams.size != FP_COUNT || iparams.size != IP_COUNT) return -1
        if (guideOut.size != w * h * 3 || devOut.size != w * h * 3) return -1
        return lookIntoNative(
            quad, w, h, lens, lensCols, lensRows, wb, transpose(ccm), transpose(aces),
            white, fparams, iparams, guideOut, devOut
        )
    }

    /** Column-major 3x3 to row-major (out[3 * r + c] = m[3 * c + r]). Pure. */
    internal fun transpose(m: FloatArray): FloatArray {
        require(m.size == 9)
        return FloatArray(9) { i -> m[(i % 3) * 3 + i / 3] }
    }

    private external fun lookIntoNative(
        quad: ByteArray, w: Int, h: Int,
        lens: FloatArray, lensCols: Int, lensRows: Int,
        wb: FloatArray, ccm: FloatArray, aces: FloatArray, white: FloatArray,
        fparams: FloatArray, iparams: IntArray,
        guideOut: FloatArray, devOut: FloatArray
    ): Int
}
