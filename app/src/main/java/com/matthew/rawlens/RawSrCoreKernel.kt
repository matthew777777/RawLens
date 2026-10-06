// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.exp

/**
 * Shared SR core: kernel tap weights + merge finalizer (CPU donors
 * [RawSrBayerMerge]/[MosaicSrReconstructor] delegate here; the Vulkan
 * shaders transcribe the same formulas in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of the CPU merge twins, so CPU behavior is
 * identical by construction. No Android/GL dependencies; no allocation
 * (tap terms land in caller scratch).
 */
object RawSrCoreKernel {
    /**
     * Zero-support gate (reference `utils.divide` parity): the divide reads
     * 0 only at exactly-zero denominators. Stays `0.0`; the donors keep
     * their own equally-valued constants for API stability.
     */
    const val EPS = 0.0

    /**
     * Precision-domain scale for R/B taps (sigma x s <=> z / s^2); green
     * taps always use the unscaled z (bitwise reference path).
     */
    fun chromaZScale(chromaSigmaMpy: Double): Double = 1.0 / (chromaSigmaMpy * chromaSigmaMpy)

    /**
     * Tap exponent `z = d^T P d` over raw-unit distances ([cross] is the
     * hoisted `P01 + P10` sum: same operands, same sum, bitwise-identical
     * exponent).
     */
    fun tapZ(p00: Double, cross: Double, p11: Double, distX: Double, distY: Double): Double =
        p00 * distX * distX + cross * distX * distY + p11 * distY * distY

    /**
     * Chroma latch guard: R/B taps see the widened kernel (`z / s^2`);
     * green keeps the unscaled z, so the luma lane is bitwise-identical
     * with or without the guard.
     */
    fun scaleChromaZ(z: Double, chromaZScale: Double, isGreen: Boolean): Double =
        if (isGreen) z else z * chromaZScale

    /** Gaussian tap weight `w = exp(-0.5 max(z, 0))`, no additive floor. */
    fun tapWeight(zc: Double): Double {
        // Fast paths (bitwise-identical): exp(0) is exactly 1, and
        // exp(-1000) underflows to exactly 0 on every JVM/ART, so taps at
        // or beyond z=2000 skip the libm call with no value change. The
        // slow path keeps the maxOf(zc, 0.0) spelling (a no-op for zc > 0)
        // so the shared-formula tripwire still finds it here.
        if (zc <= 0.0) return 1.0
        if (zc >= 2000.0) return 0.0
        return exp(-0.5 * maxOf(zc, 0.0))
    }

    /**
     * One tap's accumulation terms: `out[0] = w*r*sample`,
     * `out[1] = w*r` ([out] is caller scratch, size >= 2). Returns false
     * when the tap must be skipped (non-finite scaled exponent or weight),
     * exactly like the donor tap loops' `continue` guards.
     */
    fun accumulateTap(
        z: Double,
        r: Double,
        sample: Double,
        chromaZScale: Double,
        isGreen: Boolean,
        out: DoubleArray
    ): Boolean {
        val zc = scaleChromaZ(z, chromaZScale, isGreen)
        if (!zc.isFinite()) return false
        val weight = tapWeight(zc)
        if (!weight.isFinite()) return false
        val weighted = weight * r
        out[0] = weighted * sample
        out[1] = weighted
        return true
    }

    /**
     * Reference divide (`utils.divide`): plain per-channel quotient, 0 at
     * zero support (the reference NaN blacked downstream), non-finite
     * quotients read 0. Twin flag in [divideFallback].
     */
    fun divide(num: Double, den: Double): Double {
        val value = if (den > EPS) {
            num / maxOf(den, EPS)
        } else {
            0.0
        }
        return if (value.isFinite()) value else 0.0
    }

    /**
     * Fallback flag twin of [divide]: true where the donor finalizers mark
     * the pixel/site fallen back (zero support, or a non-finite quotient).
     */
    fun divideFallback(num: Double, den: Double): Boolean {
        if (!(den > EPS)) return true
        val value = num / maxOf(den, EPS)
        return !value.isFinite()
    }

    /** Non-finite accumulator lanes read 0 on store. */
    fun sanitize(value: Float): Float = if (value.isFinite()) value else 0f
}
