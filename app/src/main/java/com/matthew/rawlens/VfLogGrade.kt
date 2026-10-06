// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Record grade stage: WB gains + 3x3 CCM + exposure, then a DIRECT output
 * encode selected by [DirectLogProfile] (see `vf_loggrade.comp` for the
 * probe-only log stage, `vf_gradeyuv.comp` / `vf_mhcyuv.comp` for the
 * record stages, `vf_vulkan_vf.cpp` for the host side). Shares the [VfVulkan]
 * device; the viewfinder never initializes it.
 *
 * The record path currently applies NO contrast curve and NO saturation in
 * any profile: each output pixel is the developed linear value through the
 * profile's transfer function only. The S-shaped contrast warp and
 * saturation below ([slog3], [contrastCurve], [saturate], [bakeLut],
 * [developLinear]) are DORMANT reference code — still unit-tested, not on
 * the record path — reserved for the upcoming look/LUT work.
 */
internal object VfLogGrade {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    /**
     * Pack grade params. C++ reads dims[2] + up to 18 floats
     * (gains[4], ccm row-major[9], exposure, bypass01, contrast,
     * saturation, outMode). Slots 15/16 (contrast/saturation) are RESERVED:
     * the record shaders ignore them (direct encode, no look). Slot 17 is
     * the [DirectLogProfile] wire id (record submits only; the probe-only
     * loggrade stage reads the first 15 and always outputs log).
     */
    fun packGrade(
        dims: IntArray,
        gains: FloatArray,
        ccm: FloatArray,
        exposureEv: Float,
        bypass: Boolean,
        contrast: Float = DEFAULT_CONTRAST,
        saturation: Float = DEFAULT_SATURATION,
        profile: DirectLogProfile = DirectLogProfile.BT709,
    ): Pair<IntArray, FloatArray> {
        require(dims.size == 2 && gains.size == 4 && ccm.size == 9)
        val iparams = intArrayOf(dims[0], dims[1])
        val fparams = FloatArray(18)
        gains.copyInto(fparams, 0)
        ccm.copyInto(fparams, 4)
        fparams[13] = exposureEv
        fparams[14] = if (bypass) 1f else 0f
        fparams[15] = contrast
        fparams[16] = saturation
        fparams[17] = profile.wireId.toFloat()
        return iparams to fparams
    }

    /** Neutral grade knobs: 1 = no-op for both (mirrors the JPEG sliders). */
    const val DEFAULT_CONTRAST = 1f
    const val DEFAULT_SATURATION = 1f

    /** Cut point shared by the GLSL and this reference. */
    const val LOG_CUT = 0.01125f

    /**
     * DORMANT working-space log shape (normalized 0..1), JVM-testable.
     * S-Log3-shaped but not Sony-exact (divisor 0.4625, toe through
     * black): it only ever fed the developed contrast warp. The live
     * log curve is [sonySlog3].
     */
    fun slog3(x: Float): Float {
        val c = x.coerceAtLeast(0f)
        return if (c >= LOG_CUT) {
            (420.0 + Math.log10(((c + 0.01) / 0.4625).toDouble()) * 261.5).toFloat() / 1023f
        } else {
            // Linear toe through the cut point: continuous by construction.
            c * slog3(LOG_CUT) / LOG_CUT
        }
    }

    /** Contrast pivot: 18% gray in log domain, so contrast preserves mids by definition. */
    val LOG_PIVOT = slog3(0.18f)

    /**
     * DORMANT contrast S-curve around [LOG_PIVOT] (CPU bake only).
     * Endpoints pinned (0 -> 0, 1 -> 1), 1.0 = neutral. Monotonic over
     * the slider range [0.5, 1.5]. Reserved for look/LUT work.
     */
    fun contrastCurve(v: Float, contrast: Float): Float {
        val amount = contrast - 1f
        return (v + amount * (v - LOG_PIVOT) * v * (1f - v)).coerceIn(0f, 1f)
    }

    /** DORMANT saturation about BT.709 luma. 1.0 = neutral. Reserved for look/LUT work. */
    fun saturate(v: Float, luma: Float, saturation: Float): Float =
        (luma + saturation * (v - luma)).coerceIn(0f, 1f)

    /** Exact sRGB OETF (IEC 61966-2-1), reference only (no record profile encodes sRGB). */
    fun srgbOetf(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) 12.92f * c
        else 1.055f * c.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    /** DORMANT inverse of [slog3]: log working space back to scene-linear. */
    fun invSlog3(y: Float): Float {
        val cutY = slog3(LOG_CUT)
        return if (y >= cutY) {
            (0.4625 * 10.0.pow(((y * 1023.0 - 420.0) / 261.5).toDouble()) - 0.01).toFloat()
                .coerceAtLeast(0f)
        } else {
            y * LOG_CUT / cutY
        }
    }

    /**
     * DORMANT (not on the record path; reserved for look/LUT work): the
     * developed grade WITHOUT output encoding or saturation: log ->
     * contrast curve -> inverse log, per channel. Scene-linear in AND
     * out. Split twice deliberately: the white clip is a hard knee
     * (baking it errs ~27 codes, measured), and a log-lattice LUT
     * interpolates linearly in log-x while linear values are
     * exponential there (+4% convexity bias at 17^3, measured). The 1D
     * warp below is smooth and densely sampled (129 over 14 EV: ~0.07%
     * interp error).
     */
    fun developLinear(developed: FloatArray, contrast: Float): FloatArray {
        require(developed.size == 3)
        return FloatArray(3) { invSlog3(contrastCurve(slog3(developed[it]), contrast)) }
    }

    /** 1D warp LUT length over [2^-10, 2^4] (mirrors the shader). */
    const val LUT_SIZE = 129
    const val LUT_LOG_MIN = -10f
    const val LUT_LOG_SPAN = 14f

    /**
     * Bake the 1D contrast warp (see [developLinear]) to RGBA half bits
     * (warp in RGB, A=1) at texel centers. The record path uploads one
     * neutral bake per take (satisfies the native LUT-ready gate and
     * reserves the binding for upcoming LUT support); the record
     * shaders currently ignore the fetch (direct encode, no warp).
     */
    fun bakeLut(contrast: Float): ShortArray {
        val n = LUT_SIZE
        val out = ShortArray(n * 4)
        var o = 0
        for (i in 0 until n) {
            val x = 2.0.pow((((i + 0.5f) / n) * LUT_LOG_SPAN + LUT_LOG_MIN).toDouble()).toFloat()
            val w = developLinear(floatArrayOf(x, x, x), contrast)[0]
            val bits = floatToHalfBits(w).toShort()
            out[o++] = bits
            out[o++] = bits
            out[o++] = bits
            out[o++] = floatToHalfBits(1f).toShort()
        }
        return out
    }

    /** IEEE754 float -> half bits (finite inputs; clamps overflow to Inf). */
    fun floatToHalfBits(value: Float): Int {
        val bits = value.toBits()
        val sign = (bits ushr 16) and 0x8000
        var exp = ((bits ushr 23) and 0xFF) - 112
        val mant = bits and 0x7FFFFF
        if (exp <= 0) {
            if (exp < -10) return sign
            val shifted = mant or 0x800000
            val half = shifted shr (1 - exp)
            return sign or ((half + 0xFFF + ((half shr 13) and 1)) shr 13)
        }
        if (exp >= 31) return sign or 0x7BFF
        return sign or (exp shl 10) or (mant shr 13)
    }

    // ---- direct-encode record profiles (live on the record path) ----

    /** Sony S-Log3 linear cut point (published). */
    const val SLOG3_CUT = 0.01125f

    /** Sony S-Log3 10-bit black code (pedestal): 0 linear -> 95/1023. */
    const val SLOG3_BLACK_CODE = 95f

    /**
     * Sony-exact S-Log3 (normalized 0..1), mirroring the record/probe
     * GLSL: log branch divisor 0.19 with the 95-code pedestal toe
     * (Sony S-Log3 whitepaper). Anchors: 0 -> 95/1023, 0.01125 ->
     * ~171.21/1023, 18% gray -> 420/1023, 90% white -> ~597.9/1023.
     * JVM-testable golden for [DirectLogProfile.SLOG3].
     */
    fun sonySlog3(x: Float): Float {
        val c = x.coerceAtLeast(0f)
        return if (c >= SLOG3_CUT) {
            (420.0 + Math.log10(((c + 0.01) / 0.19).toDouble()) * 261.5).toFloat() / 1023f
        } else {
            // Linear toe through (0, 95) and the cut point: continuous by construction.
            (SLOG3_BLACK_CODE + c * (sonySlog3(SLOG3_CUT) * 1023f - SLOG3_BLACK_CODE) / SLOG3_CUT) / 1023f
        }
    }

    /** Inverse of [sonySlog3]: S-Log3 signal back to scene-linear. */
    fun invSonySlog3(y: Float): Float {
        val cutY = sonySlog3(SLOG3_CUT)
        val blackY = SLOG3_BLACK_CODE / 1023f
        return if (y >= cutY) {
            (0.19 * 10.0.pow(((y * 1023.0 - 420.0) / 261.5).toDouble()) - 0.01).toFloat()
                .coerceAtLeast(0f)
        } else {
            ((y - blackY) * SLOG3_CUT / (cutY - blackY)).coerceAtLeast(0f)
        }
    }

    /**
     * BT.709 OETF (ITU-R BT.709), mirroring the record GLSL. Golden for
     * [DirectLogProfile.BT709]. Note the published constants meet with a
     * slight kink at 0.018 (0.0810 vs ~0.0813); both branches are used
     * verbatim, matching encoder/decoder convention.
     */
    fun bt709Oetf(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c < 0.018f) 4.5f * c
        else (1.099 * c.toDouble().pow(0.45)).toFloat() - 0.099f
    }

    /** HLG OETF constants (ITU-R BT.2100 Table 5). */
    const val HLG_A = 0.17883277
    const val HLG_B = 0.28466892
    const val HLG_C = 0.55991073

    /**
     * HLG OETF (ITU-R BT.2100), mirroring the record GLSL. Golden for
     * [DirectLogProfile.HLG] (applied after [linearSrgbToBt2020]).
     * Output coerced to 0..1: the rounded published constants overshoot
     * 1.0 by ~8e-5 at E = 1.
     */
    fun hlgOetf(e: Float): Float {
        val c = e.coerceIn(0f, 1f)
        val v = if (c <= 1f / 12f) sqrt(3f * c)
        else (HLG_A * ln(12.0 * c - HLG_B) + HLG_C).toFloat()
        return v.coerceIn(0f, 1f)
    }

    /**
     * Linear sRGB (D65) -> linear BT.2020 (D65), row-major (both D65, so a
     * pure 3x3 with no adaptation; BT.2020/BT.2087 coefficients).
     * White-preserving by construction (rows sum to 1).
     */
    val SRGB_TO_BT2020 = floatArrayOf(
        0.6274040f, 0.3292820f, 0.0433136f,
        0.0690970f, 0.9195400f, 0.0113612f,
        0.0163916f, 0.0880132f, 0.8955950f
    )

    /** Linear sRGB triple through [SRGB_TO_BT2020]. */
    fun linearSrgbToBt2020(rgb: FloatArray): FloatArray {
        require(rgb.size == 3)
        val m = SRGB_TO_BT2020
        return floatArrayOf(
            m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2],
            m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2],
            m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]
        )
    }

    /**
     * CPU goldens for the direct-encode record path (record shaders apply
     * no warp and no saturation): developed linear in, encoded signal
     * out; the shader then packs through the profile's YUV matrix.
     * @param developed WB/CCM/exposure-folded linear sRGB.
     */
    fun encodePixel709(developed: FloatArray): FloatArray {
        require(developed.size == 3)
        return FloatArray(3) { bt709Oetf(developed[it]) }
    }

    fun encodePixelHlg(developed: FloatArray): FloatArray {
        require(developed.size == 3)
        val c2020 = linearSrgbToBt2020(developed)
        return FloatArray(3) { hlgOetf(c2020[it]) }
    }

    fun encodePixelSlog3(developed: FloatArray): FloatArray {
        require(developed.size == 3)
        return FloatArray(3) { sonySlog3(developed[it]) }
    }

    /** Daylight-ish probe gains (R, G, B, spare) and identity CCM. */
    val PROBE_GAINS = floatArrayOf(1.9f, 1.0f, 1.55f, 1.0f)
    val IDENTITY_CCM = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    /** Create the grade pipeline from SPIR-V bytes. Idempotent. */
    external fun initGradeNative(spv: ByteArray): Int

    /**
     * Grade [inBuffer] (superpixel export) into [outBuffer] (graded export).
     * @param idims [width, height] of the quad grid.
     * @param fparams 15 floats from [packGrade].
     */
    external fun gradeNative(
        inBuffer: HardwareBuffer,
        outBuffer: HardwareBuffer,
        idims: IntArray,
        fparams: FloatArray,
    ): Int

    /**
     * Wait-free twin of [gradeNative]: submits without `vkQueueWaitIdle`
     * and returns the completion sync fd for [VfEglImport.adoptNativeFence],
     * or a negative code (fall back to [gradeNative]). Same-queue ordering
     * after [VfVulkan.computeSubmitNative] needs no extra sync. [slot]
     * selects the ping-pong command buffer, matching the compute call.
     */
    external fun gradeSubmitNative(
        inBuffer: HardwareBuffer,
        outBuffer: HardwareBuffer,
        idims: IntArray,
        fparams: FloatArray,
        slot: Int,
    ): Int

    /**
     * True-10-bit stage: FP16 source (superpixel export or full-res MHC
     * rgb) + target codec P010 buffer -> grade + BT.709 pack, submitted
     * wait-free. Returns the completion sync fd (>= 0; poll with
     * [VfEglImport.pollSyncFd] before queueInputBuffer, close with
     * [VfEglImport.closeSyncFd]) or a negative code (-20 = codec layout
     * is not tight P010: abort loudly, the UV offset would corrupt).
     *
     * @param idims full-res output [W, H]; @param isrc FP16 source [W, H]
     * (half res per axis in superpixel mode, full crop in full-RGB);
     * @param fparams 18 floats from [packGrade] (the push block carries
     * the WB/exposure fold; slot 17 selects the [DirectLogProfile]);
     * @param istrides [yStrideBytes, uvStrideBytes, width, height] of OUR
     * P010 staging (allocator-described; validated tight natively, -20);
     * @param slot 0..2 ping-pong selector.
     * @param fullRgb 0 = superpixel quads (Gr/Gb mean), 1 = full-res RGB
     * (MHC output, texel is rgb). Explicit: sizes alone do not identify
     * the mode (encode size differs from crop size in production).
     */
    external fun gradeYuvSubmitNative(
        expBuffer: HardwareBuffer,
        codecBuffer: HardwareBuffer,
        idims: IntArray,
        isrc: IntArray,
        fparams: FloatArray,
        istrides: IntArray,
        slot: Int,
        fullRgb: Int,
    ): Int

    /** Upload a baked warp LUT (129 RGBA half words); required before any grade submit. */
    external fun gradeLutUploadNative(halfBits: ShortArray): Int

    /** Create the grade-YUV pipeline from SPIR-V bytes. Idempotent. */
    external fun initGradeYuvNative(spv: ByteArray): Int

    /** Create the fused MHC+grade pipeline from SPIR-V bytes. Idempotent. */
    external fun initFusedYuvNative(spv: ByteArray): Int

    /**
     * Fused record stage (see `vf_mhcyuv.comp`): CFA mosaic straight to
     * graded P010 in one dispatch — no RGB round trip, one queue slot.
     * Params reuse [VfMhc.packMhc] + [packGrade] (same semantics) via a
     * per-slot SSBO. Returns the completion sync fd (>= 0; poll with
     * [VfEglImport.pollSyncFd], close with [VfEglImport.closeSyncFd]) or
     * a negative code (-20 = P010 staging is not tight: abort loudly).
     *
     * @param mhcIparams 10 ints + @param mhcFparams 8 floats from
     * [VfMhc.packMhc] (crop domain; mode must be 0).
     * @param gradeIdims output [W, H]; @param gradeFparams 18 floats from
     * [packGrade] (bypass must be 0: fused never bypasses; slot 17
     * selects the [DirectLogProfile]).
     * @param istrides [yStrideBytes, uvStrideBytes, width, height] of OUR
     * P010 staging (allocator-described; validated tight natively, -20);
     * @param slot 0..2 ping-pong selector.
     * @param shadeDims null (unshaded) or [rows, cols, l, t, r, b] with
     * @param shadeGains the Camera2-order HAL map (rows*cols*4 fp16 bits):
     * post-MHC RGB gains, pre-WB (demosaiced green takes the Gr/Gb mean),
     * sampled by the GPU with hardware bilinear.
     */
    external fun fusedYuvSubmitNative(
        cfaBuffer: HardwareBuffer,
        p010Buffer: HardwareBuffer,
        mhcIparams: IntArray,
        mhcFparams: FloatArray,
        gradeIdims: IntArray,
        gradeFparams: FloatArray,
        istrides: IntArray,
        slot: Int,
        shadeDims: IntArray?,
        shadeGains: ShortArray?,
    ): Int
}
