// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Per-pixel (Bayer quad) RAW statistics in Magic Lantern's projections:
 * a histogram of log2 levels of max(R, G, B) (GRAY_PROJECTION_MAX_RGB) in
 * [BINS_PER_EV] bins per EV over [-[EV_RANGE], 0], and the count of quads whose
 * average RGB is at least [OVEREXPOSED_LEVEL] (raw_hist_get_overexposure_percentage).
 */
class EttrQuadStats(
    val evHistogram: IntArray,
    val total: Int,
    val overexposed: Int,
    /** Owner F15 extension: min(R, Gr, Gb, B), used only for overshoot gradients. */
    val minChannelHistogram: IntArray? = null
) {
    companion object {
        const val BINS_PER_EV = 64
        const val EV_RANGE = 16
        const val BINS = BINS_PER_EV * EV_RANGE + 1
        /** ML: "white level * 80 / 100" tolerance for clipped pixels. */
        const val OVEREXPOSED_LEVEL = 0.8f

        /** Bin of a normalized 0..1 level (0 EV = white, last bin). */
        fun binOf(level: Float): Int {
            if (level <= 0f) return 0
            val ev = ln(level.toDouble()) / ln(2.0)
            return (floor((ev + EV_RANGE) * BINS_PER_EV)).toInt().coerceIn(0, BINS - 1)
        }
    }

    /** raw_hist_get_percentile_levels for one per-mille percentile, as EV below white. */
    fun percentileEv(permille: Int, histogram: IntArray = evHistogram): Double {
        if (total <= 0) return -EV_RANGE.toDouble()
        val target = (permille.toLong() * total) / 1000
        var cumulative = 0L
        for (b in histogram.indices) {
            cumulative += histogram[b]
            if (cumulative > target) return min(0.0, b.toDouble() / BINS_PER_EV - EV_RANGE)
        }
        return 0.0
    }

    val overexposedFraction: Double get() = if (total > 0) overexposed.toDouble() / total else 0.0
}

/**
 * F15 audit: docs/refactor/ml-focus-ettr-audit.md. Minimum-channel overshoot
 * gradients are an owner-requested extension, not an ML projection. Highlight
 * target and SNR statistics still use ML's MAX_RGB projection.
 *
 * Auto ETTR correction ported from Magic Lantern `modules/ettr/ettr.c`
 * (auto_ettr_get_correction @ 142472e, GPL), single-ISO path:
 *
 * - highlight level = the (1000 - ignore) per-mille percentile of max(RGB),
 *   ignore = 1 (99.9 %); target = min(level, -0.5 EV), default level -1 EV;
 *   correction = target - highlight EV, applied in full.
 * - Overexposed frame (highlight >= -0.1 EV): extrapolate the true highlight
 *   from the 12 lower percentiles remembered from the last good frame
 *   (weights 13 - k); if the weight sum is < 3, they spread by more than 1 EV,
 *   or the result is above -1 EV, fall back to -log2(1 + p^2) for p % of
 *   clipped quads, never below -10 EV.
 * - SNR limits (midtone 6 EV, shadow 2 EV): with SNR = dynamic range + EV
 *   of the median / 5th percentile, raise the correction so they are met
 *   (accepting clipping rather than noisy midtones); an overexposed frame
 *   first steps 2 EV further down. Skipped without a dynamic range.
 */
internal object MagicLanternEttr {
    val PERCENTILES = intArrayOf(999, 950, 900, 800, 750, 700, 600, 500, 300, 200, 150, 100, 50)
    const val TARGET_CEILING_EV = -0.5
    const val MIDTONE_SNR_EV = 6.0
    const val SHADOW_SNR_EV = 2.0

    /** Per-percentile memory of the last non-overexposed frame (diff_from_lower_percentiles). */
    class Memory {
        val diffFromLower = DoubleArray(PERCENTILES.size - 1)
        val diffFromMin = DoubleArray(PERCENTILES.size - 1)
        var hasMinGradient = false

        fun clear() {
            diffFromLower.fill(0.0)
            diffFromMin.fill(0.0)
            hasMinGradient = false
        }
    }

    data class Result(val correctionEv: Double, val highlightEv: Double, val overexposed: Boolean)

    /**
     * [targetLevelEv] is the user level (negative EV below white); [dynamicRangeEv]
     * the sensor's engineering DR at the current gain, or NaN when unknown.
     */
    fun correction(
        stats: EttrQuadStats,
        memory: Memory,
        targetLevelEv: Double,
        dynamicRangeEv: Double,
        midtoneSnrEv: Double = MIDTONE_SNR_EV,
        shadowSnrEv: Double = SHADOW_SNR_EV,
    ): Result {
        val raw = DoubleArray(PERCENTILES.size) { stats.percentileEv(PERCENTILES[it]) }
        var ev = raw[0]
        val highlight = ev
        val evMedian = raw[7]
        val evShadow = raw[12]
        val target = min(targetLevelEv, TARGET_CEILING_EV)
        var correction = target - ev
        var overexposedFraction = 0.0
        val overexposed = ev >= -0.1
        if (!overexposed) {
            for (k in 0 until PERCENTILES.size - 1) memory.diffFromLower[k] = ev - raw[k + 1]
            // Anchor the MIN-channel tail to the MAX highlight of the same good frame.
            // This preserves the clipping target while retaining gradients if G clips first.
            val minHist = stats.minChannelHistogram
            memory.hasMinGradient = minHist != null
            if (minHist != null) for (k in memory.diffFromMin.indices) {
                memory.diffFromMin[k] = ev - stats.percentileEv(PERCENTILES[k + 1], minHist)
            }
        } else {
            overexposedFraction = stats.overexposedFraction
            var num = 0
            var sum = 0.0
            var lo = 100000.0
            var hi = -100000.0
            val minHist = stats.minChannelHistogram.takeIf { memory.hasMinGradient }
            val differences = if (minHist != null) memory.diffFromMin else memory.diffFromLower
            for (k in 0 until PERCENTILES.size - 1) {
                if (differences[k] > 0) {
                    val lowerEv = if (minHist != null)
                        stats.percentileEv(PERCENTILES[k + 1], minHist) else raw[k + 1]
                    if (lowerEv < -0.1) {
                        ev = lowerEv + differences[k]
                        if (target - ev < correction) {
                            val corr = target - ev
                            lo = min(lo, corr)
                            hi = max(hi, corr)
                            sum += corr * (PERCENTILES.size - k)
                            num += PERCENTILES.size - k
                        }
                    }
                }
            }
            correction = sum / num
            if (num < 3 || hi - correction > 1 || correction - lo > 1 || correction > -1 || correction.isNaN()) {
                val p = overexposedFraction * 100.0
                val corr = -ln(1 + p * p) / ln(2.0)
                // C MIN(NaN, corr) yields corr.
                correction = if (correction.isNaN() || !(correction < corr)) corr else correction
                correction = max(correction, -10.0)
            }
        }
        if (dynamicRangeEv.isFinite()) {
            if (overexposedFraction > 0 && (midtoneSnrEv > 0 || shadowSnrEv > 0)) correction -= 2
            val correction0 = correction
            val midtoneExpected = dynamicRangeEv + evMedian + correction0
            if (midtoneSnrEv > 0 && midtoneExpected < midtoneSnrEv) {
                correction = max(correction, correction0 + midtoneSnrEv - midtoneExpected)
            }
            val shadowExpected = dynamicRangeEv + evShadow + correction0
            if (shadowSnrEv > 0 && shadowExpected < shadowSnrEv) {
                correction = max(correction, correction0 + shadowSnrEv - shadowExpected)
            }
        }
        return Result(correction, highlight, overexposed)
    }

    /** Engineering dynamic range (white / read-noise floor) from a normalized noise offset O. */
    fun dynamicRangeEv(noiseOffset: Double): Double =
        if (noiseOffset > 0.0) -0.5 * ln(noiseOffset) / ln(2.0) else Double.NaN
}
