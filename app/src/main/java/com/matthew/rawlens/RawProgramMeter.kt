// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.ln
import kotlin.math.pow

/**
 * PROGRAM custom-AE metering from RAW brightness.
 *
 * Unlike ETTR (99.9th percentile → clipping headroom), PROGRAM targets a mid-tone
 * gray so point-and-shoot frames land correctly without manual ETTR-ing. The loop
 * measures the sensor mosaic itself (pooled-green mean, robust to WB gains) and
 * returns an EV shift that the solver turns directly into sensor ISO + shutter —
 * never digital gain.
 *
 * Stock Android AE + spektra development both run dark in many scenes; [evBias]
 * (default +0.5 EV) bakes a brighter-than-stock target into [targetLevel].
 */
internal object RawProgramMeter {
    /** Linear mid-gray target before bias. */
    const val TARGET_GRAY = 0.18f
    /** Per-update clamp so the closed loop cannot oscillate across frames. */
    const val MAX_STEP_EV = 2.0
    /**
     * Per-measurement clamp for the PROGRAM glide: one sample only ever nudges the
     * target, so exposure moves in small steps even when the scene jumps stops.
     */
    const val PROGRAM_MAX_STEP_EV = 0.5
    /** Residual below this counts as converged (about 1/8 stop). */
    const val CONVERGED_TOLERANCE_EV = 0.12
    /**
     * Brightening never pushes the hottest meaningful channel past this level:
     * daylight mids can rise to target, but highlights are not sacrificed for them.
     * Darkening passes through so an already-clipped frame still pulls down.
     */
    const val HIGHLIGHT_GUARD = 0.9f

    /** Brightness target after bias, clamped away from clipping and black. */
    fun targetLevel(evBias: Float): Double {
        val bias = evBias.coerceIn(
            ProgramAeProfile.MIN_EV_BIAS, ProgramAeProfile.MAX_EV_BIAS
        ).toDouble()
        return (TARGET_GRAY.toDouble() * 2.0.pow(bias)).coerceIn(0.05, 0.5)
    }

    /**
     * EV shift needed to move [measured] (pooled-green mean, 0..1) to the biased
     * target. Positive means brighten. Zero/negative measured means no signal.
     * [maxStepEv] caps one measurement's authority (PROGRAM uses the small glide
     * cap; ETTR keeps the full clamp).
     */
    fun correctionEv(measured: Float, evBias: Float, maxStepEv: Double = MAX_STEP_EV): Double {
        if (!measured.isFinite() || measured <= 0f) return 0.0
        val target = targetLevel(evBias)
        val shift = ln(target / measured.coerceAtLeast(1e-6f)) / ln(2.0)
        if (kotlin.math.abs(shift) < CONVERGED_TOLERANCE_EV) return 0.0
        return shift.coerceIn(-maxStepEv, maxStepEv)
    }

    /**
     * Caps a mid-tone [shift] against the hottest meaningful channel so brightening
     * never pushes highlights past [HIGHLIGHT_GUARD]. Darkening (negative shift)
     * passes through untouched.
     */
    fun guardShiftEv(shift: Double, hottest: Float): Double {
        if (shift <= 0.0 || !hottest.isFinite() || hottest <= 0f) return shift
        val headroom = ln((HIGHLIGHT_GUARD / hottest.coerceAtLeast(1e-6f))) / ln(2.0)
        return minOf(shift, headroom)
    }

    /** Pooled-green (Gr+Gb) mean from a sampled frame; the PROGRAM brightness signal. */
    fun brightness(sample: EttrRawSample): Float = brightness(sample, ProgramMetering.CENTER_WEIGHTED)

    /** Metering-selected brightness signal: center-weighted, full-frame average, or spot. */
    fun brightness(sample: EttrRawSample, metering: ProgramMetering): Float = when (metering) {
        ProgramMetering.AVERAGE -> meanBrightness(sample)
        ProgramMetering.SPOT -> {
            val spot = sample.spotGreen
            if (spot.isFinite()) spot
            else {
                val weighted = sample.centerWeightedGreen
                if (weighted.isFinite()) weighted else meanBrightness(sample)
            }
        }
        ProgramMetering.CENTER_WEIGHTED -> {
            val weighted = sample.centerWeightedGreen
            if (weighted.isFinite()) weighted else meanBrightness(sample)
        }
    }

    /** Unweighted pooled-green mean (fallback + legacy behavior). */
    fun meanBrightness(sample: EttrRawSample): Float {
        val greenBins = IntArray(RawEttrSampler.BIN_COUNT) { b ->
            sample.bins.getOrElse(1) { IntArray(0) }.getOrElse(b) { 0 } +
                sample.bins.getOrElse(2) { IntArray(0) }.getOrElse(b) { 0 }
        }
        val total = sample.totals.getOrElse(1) { 0 } + sample.totals.getOrElse(2) { 0 }
        return meanLevel(greenBins, total)
    }

    /** Mean normalized level 0..1 from 256-bin histogram + sample count. */
    fun meanLevel(bins: IntArray, total: Int): Float {
        if (total <= 0) return 0f
        var sum = 0.0
        for (b in bins.indices) {
            sum += (b + 0.5) / bins.size * bins[b]
        }
        return (sum / total).toFloat().coerceIn(0f, 1f)
    }
}
