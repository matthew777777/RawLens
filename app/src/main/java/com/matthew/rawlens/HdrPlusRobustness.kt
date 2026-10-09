// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HDR+ frequency-merge robustness formulas shared by the HDR bracket deghost
 * path ([HdrTileDeghost]) and the uniform-burst HDR+ Vulkan pipeline.
 *
 * Ports the bracket (non-uniform-exposure) branch of hdr-plus-swift
 * `burstphoto/merge/frequency.swift` + `frequency.metal` at 69cb057 — the
 * same upstream our Vulkan HDR+ port matches on its uniform path (see
 * `app/src/main/cpp/hdrplus/PARITY.md`). The Vulkan port intentionally
 * omits the bracket branch (uniform ZSL bursts only); the CPU bracket
 * deghost implements it here so cross-exposure pairs get upstream's
 * exposure-adapted averaging instead of a fixed strength.
 *
 * Upstream bracket model (frequency.swift `align_merge_frequency_domain`):
 * - Each companion's linear exposure factor `f = exposure_comp /
 *   exposure_ref` (from EXIF bias: `2^((bias_comp-bias_ref)/100)`).
 * - Burst-global corrections `corr1 = mean(0.5+0.5/f)`,
 *   `corr2 = mean(min(4,f))` scale the Wiener noise term
 *   (`robustness_norm = corr1/corr2 * 2^(-rev+7.5)`): longer exposures
 *   carry better SNR, so the expected difference noise is recalibrated
 *   for a comparable average denoise.
 * - Per-companion motion ceiling `max_motion(f) = min(4,f) *
 *   sqrt(max_motion_norm)`: longer (cleaner) frames average harder on
 *   static tiles, shorter (noisier) frames stay near the floor.
 * - Per-tile highlight discount (`calculate_highlights_norm_rgba`) for
 *   longer companions (`f > 1.001`): tiles where the companion's own
 *   exposure would clip are forced toward the reference.
 * - Mismatch denominator `sqrt(0.5*n + 0.5*n/f + 1)` scales the
 *   companion noise term by its exposure factor.
 *
 * Bracket adaptations for our DNG-noise pipeline (vs upstream's
 * rms-from-image noise): our difference-bin variance already measures
 * both frames' Poisson-Gaussian profiles (including the gain-squared
 * scaling of the companion term), so the corr1/corr2 ratio is applied
 * as conservative bracket damping only (clamped to <= 1, never
 * boosting): it tempers averaging on cross-exposure pairs without
 * double-counting companion noise the DNG profile already captures.
 * The max-motion adaptation, highlight discount, and mismatch support
 * are ported verbatim (in normalized 0..1 units).
 */
object HdrPlusRobustness {
    /**
     * Linear exposure factor of [moving] relative to [reference]
     * (upstream `exposure_factor = 2^((bias_comp-bias_ref)/100)`).
     * Derived from darktable calibrations (which are inversely
     * proportional to exposure): `f = cal_ref / cal_mov`. 1 = same
     * exposure; > 1 = longer (brighter) companion.
     */
    fun exposureFactor(reference: HdrMergeFrame, moving: HdrMergeFrame): Float {
        val refCal = HdrRawMerge.calibration(reference)
        val movCal = HdrRawMerge.calibration(moving)
        require(refCal.isFinite() && refCal > 0f && movCal.isFinite() && movCal > 0f)
        return (refCal / movCal).coerceIn(1f / 1024f, 1024f)
    }

    /** [exposureFactor] without materialized frames (streaming merges). */
    fun exposureFactorFromCalibrations(referenceCal: Float, movingCal: Float): Float {
        require(referenceCal.isFinite() && referenceCal > 0f)
        require(movingCal.isFinite() && movingCal > 0f)
        return (referenceCal / movingCal).coerceIn(1f / 1024f, 1024f)
    }

    /**
     * Upstream burst corrections over per-frame factors (each frame's
     * [exposureFactor] against the reference; the reference contributes
     * exactly 1). Returns `corr1/corr2` (upstream `robustness_norm`
     * bracket scale): < 1 for brackets with longer companions (calmer
     * averaging), 1 for uniform bursts.
     */
    fun exposureCorrRatio(factors: List<Float>): Float {
        require(factors.isNotEmpty())
        var corr1 = 0.0
        var corr2 = 0.0
        for (f in factors) {
            require(f.isFinite() && f > 0f)
            corr1 += 0.5 + 0.5 / f
            corr2 += min(4.0, f.toDouble())
        }
        corr1 /= factors.size
        corr2 /= factors.size
        return (corr1 / corr2).toFloat().coerceIn(0f, 8f)
    }

    /**
     * Pairwise fallback when only one companion is visible (direct
     * deghost calls, tests): burst of `[1, factor]`.
     */
    fun pairCorrRatio(factor: Float): Float =
        exposureCorrRatio(listOf(1f, factor))

    /**
     * Conservative bracket damping applied to the Wiener strength:
     * [exposureCorrRatio] clamped to <= 1 so cross-exposure pairs
     * average at most as hard as uniform pairs, never harder (the DNG
     * noise model already captures the companion's own noise; boosting
     * past uniform would double-count it and over-keep noisy short
     * frames). Uniform bursts map to exactly 1 (no-op).
     */
    fun strengthDamping(factors: List<Float>): Float =
        min(1f, exposureCorrRatio(factors))

    /** [strengthDamping] for a single companion factor. */
    fun pairStrengthDamping(factor: Float): Float =
        min(1f, pairCorrRatio(factor))

    /**
     * Upstream per-companion motion ceiling (frequency.swift:
     * `max_motion_norm_exposure = uniform ? max_motion_norm :
     * min(4,factor)*sqrt(max_motion_norm)`). Longer companions average
     * harder on static tiles; shorter ones stay near the floor. Always
     * >= 1 (a sub-unity ceiling would invert the motion ramp's clamp).
     *
     * @param baseMax burst-base ceiling (our [HdrTileDeghost] operating
     *   point; upstream derives it from the strength curve).
     * @param uniform true for same-exposure bursts (fusion path):
     *   returns [baseMax] unchanged, like upstream's uniform branch.
     */
    fun maxMotionForFactor(baseMax: Float, factor: Float, uniform: Boolean): Float {
        require(baseMax.isFinite() && baseMax >= 1f)
        require(factor.isFinite() && factor > 0f)
        if (uniform) return baseMax
        return max(1f, min(4f, factor) * sqrt(baseMax))
    }

    /**
     * Upstream highlight discount (`calculate_highlights_norm_rgba`)
     * for longer companions: fraction [frac] of the tile's quads whose
     * companion-domain peak piles past mid-white (ramped 0.5..0.99 like
     * upstream) maps to `(1-frac)^2`, floored so deeply clipped tiles
     * still contribute a little. Returns 1 (no-op) for same/shorter
     * companions, exactly like upstream's `factor > 1.001` gate.
     */
    fun highlightsNorm(factor: Float, frac: Float): Float {
        if (!(factor > 1.001f)) return 1f
        require(frac.isFinite())
        val f = frac.coerceIn(0f, 1f)
        return ((1f - f) * (1f - f)).coerceIn(0.04f / min(factor, 4f), 1f)
    }

    /**
     * Whether a burst counts as uniform exposure (fusion path): every
     * factor within [tolerance] of 1. Upstream threads an explicit
     * bool; we derive it so direct deghost callers need no new flag.
     */
    fun isUniform(factors: List<Float>, tolerance: Float = 0.05f): Boolean =
        factors.isNotEmpty() && factors.all { it.isFinite() && kotlin.math.abs(it - 1f) <= tolerance }

    /** Pairwise [isUniform] for a single companion factor. */
    fun isUniformPair(factor: Float, tolerance: Float = 0.05f): Boolean =
        factor.isFinite() && kotlin.math.abs(factor - 1f) <= tolerance
}
