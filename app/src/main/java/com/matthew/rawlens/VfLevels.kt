// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Q6 fixed-point black/white levels shared by every superpixel tier.
 *
 * The whole RAW viewfinder normalizes in sub-fp32 precision: each tier
 * (Vulkan superpixel, EGL zero-copy, NEON CPU fallback) evaluates the same
 * integer formula, so cross-tier bytes agree by construction instead of by
 * float-rounding luck:
 *
 * ```
 * num = code * 64 - blackQ          // code in 0..65535
 * byte = 0    if num <= 0
 * byte = 255  if num >= denQ
 * byte = (num * 255 + denQ / 2) / denQ   // round-half-up, integer division
 * ```
 *
 * [blackQ] resolves black to 1/64 code (~0.016 DN, far below read noise);
 * [denQ] is the clamped white-minus-black range at the same scale, hard-bound
 * to [64, 65535*64] so `num * 255` can never overflow signed 32 bit on any
 * tier (real sensors never approach the cap: white <= 65535). All
 * intermediates fit signed 32 bit (num <= 65535*64, num*255 <= ~1.07e9),
 * so scalar, NEON integer, GLSL `int` and ESSL `highp int` all evaluate it
 * identically — no float anywhere in the per-pixel path.
 *
 * Transport reuses the existing float slots by bit-casting ([toBits]):
 * the native push-constant fill and the old JNI shapes only memcpy the
 * words (see `readSuperpixelParams`), and Q6 values (< 2^23) never form
 * NaN/Inf bit patterns. Shaders recover the ints with `floatBitsToInt`.
 */
internal object VfLevels {
    /** Fixed-point scale: 64ths of a code. */
    const val SCALE = 64

    /** Maximum denominator: 65535 codes at Q6 (overflow backstop, all tiers). */
    const val MAX_DEN_Q = 65535 * SCALE

    /**
     * True when reported levels can normalize: finite, positive white
     * strictly above every black. Multi-mode HALs (Vivo X300 Ultra without
     * its vendor sensor-mode key) report white=0 (dynamic-levels modes) or
     * mode-mismatched pairs (white <= black); both must fall back instead
     * of throwing in [toFixedQ6] (which hides the viewfinder) or clamping
     * every code to 0 (which the tier probe reads as INCONCLUSIVE forever).
     */
    fun isSane(black: FloatArray, white: Float): Boolean {
        if (black.size != 4 || !white.isFinite() || white <= 0f) return false
        var blackMax = Float.NEGATIVE_INFINITY
        for (b in black) {
            if (!b.isFinite()) return false
            if (b > blackMax) blackMax = b
        }
        return white > blackMax
    }

    /**
     * Data-driven fallback from a raw-code sample (min/max over the frame):
     * black sits at the sample floor, white at the sample peak (at least
     * one code above black). Guarantees a visible viewfinder when the HAL's
     * reported levels are unusable; the output always satisfies [isSane].
     */
    fun fallbackFromSample(sampleMin: Int, sampleMax: Int): Pair<FloatArray, Float> {
        val lo = sampleMin.coerceIn(0, 65535).toFloat()
        val white = sampleMax.coerceIn(0, 65535).toFloat().coerceAtLeast(lo + 1f)
        return FloatArray(4) { lo } to white
    }

    /**
     * Last resort when no sample exists either: uniform black 0 with a
     * 10-bit white. Shows 10-bit data correctly; higher bit depths render
     * hot but visible. Always satisfies [isSane].
     */
    fun lastResort(): Pair<FloatArray, Float> = FloatArray(4) { 0f } to 1023f

    /**
     * Q6 levels from float black/white. [black] is Camera2 order (TL, TR,
     * BL, BR); [white] shared. Mirrors the old float contract (range
     * clamped to >= 1 code, black floored at 0).
     */
    fun toFixedQ6(black: FloatArray, white: Float): Pair<IntArray, IntArray> {
        require(black.size == 4) { "black must have 4 entries" }
        require(white.isFinite() && white > 0f && black.all { it.isFinite() }) {
            "Bad levels white=$white"
        }
        val blackQ = IntArray(4) { i -> ((black[i].coerceAtLeast(0f) * SCALE) + 0.5f).toInt() }
        val denQ = IntArray(4) { i ->
            val range = (white - black[i]).coerceAtLeast(1f)
            (((range * SCALE) + 0.5f).toDouble().coerceIn(SCALE.toDouble(), MAX_DEN_Q.toDouble())).toInt()
        }
        return blackQ to denQ
    }

    /**
     * Bit-casts Q6 ints into the legacy float param slots ([VfVulkan.packParams]
     * fparams): [blackQ] into slots 0..3, [denQ] into slots 4..7.
     */
    fun toBits(blackQ: IntArray, denQ: IntArray): FloatArray {
        require(blackQ.size == 4 && denQ.size == 4) { "Q6 levels must have 4 entries" }
        return FloatArray(8) { i ->
            val bits = if (i < 4) blackQ[i] else denQ[i - 4]
            Float.fromBits(bits)
        }
    }

    /** Exact scalar reference of the per-pixel formula (tests + goldens). */
    fun normalizeByte(code: Int, blackQ: Int, denQ: Int): Int {
        require(code in 0..65535) { "code out of range: $code" }
        require(blackQ >= 0 && denQ > 0) { "bad Q6 levels" }
        val num = code * SCALE - blackQ
        if (num <= 0) return 0
        if (num >= denQ) return 255
        return (num * 255 + denQ / 2) / denQ
    }
}
