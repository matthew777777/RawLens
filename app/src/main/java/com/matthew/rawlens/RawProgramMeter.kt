// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.ln
import kotlin.math.pow

/**
 * PROGRAM metering signals from RAW: the metered mean of the selected zone
 * (center-weighted / average / spot, pooled Gr+Gb, robust to WB gains;
 * highlight-weighted uses all CFA channels) and
 * the frame's green histogram for the AGC ([ProgramAgc]). The control law
 * itself (target, constraints, filter) lives in ProgramAgc.
 */
internal object RawProgramMeter {
    /** Pooled-green (Gr+Gb) mean from a sampled frame; the PROGRAM brightness signal. */
    fun brightness(sample: EttrRawSample): Float = brightness(sample, ProgramMetering.CENTER_WEIGHTED)

    /** Metering-selected brightness signal (highlight mode reports its ceiling statistic). */
    fun brightness(sample: EttrRawSample, metering: ProgramMetering): Float = when (metering) {
        ProgramMetering.HIGHLIGHT_WEIGHTED -> highlightBrightness(sample)
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

    /**
     * Brightness-squared votes times sample area: sum(n*x^3)/sum(n*x^2).
     * Evaluate all CFA colours separately and keep the largest: saturated red/blue
     * must not disappear in pooled green. Full-frame bins ignore tap/spot crops.
     * No sorting, quads, or per-frame image allocation is needed.
     */
    fun highlightBrightness(sample: EttrRawSample): Float {
        var hottest = 0.0
        for (bins in sample.frameBins ?: sample.bins) {
            var weight = 0.0
            var weighted = 0.0
            for (b in bins.indices) {
                val x = (b + 0.5) / bins.size
                val w = bins[b].coerceAtLeast(0) * x * x
                weight += w
                weighted += w * x
            }
            if (weight > 0.0) hottest = maxOf(hottest, weighted / weight)
        }
        return hottest.toFloat()
    }

    /** libcamera imx477 highlight constraint target; see exposure-audit.md. */
    private const val HIGHLIGHT_TARGET = 0.8

    /** One ceiling shared by H1 and H2; ordinary metering keeps its own subject target. */
    fun protectedGain(sample: EttrRawSample, gain: Double, lighting: ActiveDLighting): Double {
        val highlight = highlightBrightness(sample).toDouble()
        val scale = Math.pow(2.0, -lighting.protectionEv)
        if (highlight <= 0.0) return gain * scale
        return minOf(gain, HIGHLIGHT_TARGET / highlight) * scale
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

    /** Full-frame pooled-green (Gr+Gb) histogram (frameBins for cropped scans). */
    fun frameGreenBins(sample: EttrRawSample): IntArray {
        val bins = sample.frameBins ?: sample.bins
        return IntArray(RawEttrSampler.BIN_COUNT) { b ->
            bins.getOrElse(1) { IntArray(0) }.getOrElse(b) { 0 } +
                bins.getOrElse(2) { IntArray(0) }.getOrElse(b) { 0 }
        }
    }
}
