// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * PROGRAM auto-exposure core: a port of the Raspberry Pi AGC in libcamera
 * (`src/ipa/rpi/controller/rpi/agc_channel.cpp` and `ipa/libipa/histogram.cpp`
 * @ 0bf3fc5, BSD-2-Clause) with its default tuning (`vc4/data/imx477.json`):
 *
 * - [computeGain]: gain that brings the metered mean to the Y target, iterated
 *   with the saturation of the scene modelled (computeGain + computeInitialY),
 *   then the histogram constraints on the inter-quantile mean (constraint
 *   modes; "normal" = lower bound, top 2 % at least 0.3).
 * - [filter]: one filter on the total exposure (filterExposure): speed 0.2
 *   per frame, sqrt(speed) within +-20 % of the target, no change inside
 *   +-2 %, instant during the first 10 frames; plus the x0.4 desaturate step
 *   (applyDigitalGain) when the target cannot be reached by lowering gain.
 * - [roundForFlicker]: exposure time rounded down to whole flicker periods
 *   with gain making up the difference (divideUpExposure).
 *
 * RawLens adaptations, all stated: the statistic is the RAW pooled-green
 * mean in the selected metering zone (the HAL gives no Y region sums), the
 * saturation model uses the frame's green histogram, and the per-frame
 * filter is applied for the number of sensor frames elapsed between
 * updates. The ISO / shutter split stays PhotonCamera's handheld curve
 * (AutoExposureBalance); RAW never takes digital gain.
 */
internal object ProgramAgc {
    /** y_target (imx477 default, lowest-lux point; lux is not measured here). */
    const val Y_TARGET = 0.16
    /** EvGainYTargetLimit: targets above this cannot be monitored. */
    const val Y_TARGET_LIMIT = 0.9
    const val SPEED = 0.2
    const val STARTUP_FRAMES = 10
    const val STABLE_REGION = 0.02
    const val FAST_REDUCE_THRESHOLD = 0.4
    private const val GAIN_ITERATIONS = 8
    private const val MAX_EXTRA_GAIN = 10.0

    enum class Bound { LOWER, UPPER }

    data class Constraint(val bound: Bound, val qLo: Double, val qHi: Double, val yTarget: Double)

    /** imx477 constraint_modes ("normal" is libcamera's default mode). */
    enum class ConstraintMode(val constraints: List<Constraint>) {
        NORMAL(listOf(Constraint(Bound.LOWER, 0.98, 1.0, 0.3))),
        HIGHLIGHT(listOf(
            Constraint(Bound.LOWER, 0.98, 1.0, 0.3),
            Constraint(Bound.UPPER, 0.98, 1.0, 0.8),
        )),
    }

    /** Cumulative histogram as in libipa Histogram (cumulative[0] = 0). */
    class Histogram(bins: IntArray) {
        val bins = bins.size
        val cumulative = LongArray(bins.size + 1).also { c ->
            for (i in bins.indices) c[i + 1] = c[i] + bins[i]
        }
        val total: Long get() = cumulative[bins]

        /** Histogram::quantile: fractional bin index below which [q] of the samples lie. */
        fun quantile(q: Double, firstIn: Int = 0, lastIn: Int = bins - 1): Double {
            var first = firstIn
            var last = lastIn
            val item = (q * total).toLong()
            while (first < last) {
                val middle = (first + last) / 2
                if (cumulative[middle + 1] > item) last = middle else first = middle + 1
            }
            val frac = if (cumulative[first + 1] == cumulative[first]) 0.0
            else (q * total - cumulative[first]) / (cumulative[first + 1] - cumulative[first]).toDouble()
            return first + frac
        }

        /** Histogram::interQuantileMean, in bin units. */
        fun interQuantileMean(lowQuantile: Double, highQuantile: Double): Double {
            val lowPoint = quantile(lowQuantile)
            val highPoint = quantile(highQuantile, lowPoint.toInt())
            var sumBinFreq = 0.0
            var cumulFreq = 0.0
            var bin = floor(lowPoint).toInt()
            while (bin < ceil(highPoint)) {
                val lowBound = maxOf(bin.toDouble(), lowPoint)
                val highBound = minOf(bin + 1.0, highPoint)
                val freq = (cumulative[bin + 1] - cumulative[bin]) * (highBound - lowBound)
                sumBinFreq += (highBound + lowBound) / 2 * freq
                cumulFreq += freq
                bin++
            }
            return sumBinFreq / cumulFreq
        }
    }

    data class Gain(val gain: Double, val targetY: Double)

    /**
     * Mean of the metered zone after applying [gain], with saturation from
     * the frame histogram: samples pushed past white clip at 1 (the RAW
     * analogue of computeInitialY's per-region min(sum * gain, max)).
     */
    fun meteredY(meteredMean: Double, histogram: Histogram, gain: Double): Double {
        if (histogram.total == 0L) return meteredMean * gain
        var unclipped = 0.0
        var linear = 0.0
        for (b in 0 until histogram.bins) {
            val n = (histogram.cumulative[b + 1] - histogram.cumulative[b]).toDouble()
            if (n == 0.0) continue
            val level = (b + 0.5) / histogram.bins
            linear += level * n
            unclipped += minOf(level * gain, 1.0) * n
        }
        if (linear <= 0.0) return meteredMean * gain
        return meteredMean * (unclipped / linear)
    }

    /** AgcChannel::computeGain: [evGain] is 2^(EV compensation). */
    fun computeGain(
        meteredMean: Double,
        histogram: Histogram,
        evGain: Double,
        mode: ConstraintMode,
    ): Gain {
        var targetY = minOf(Y_TARGET_LIMIT, Y_TARGET * evGain)
        var gain = 1.0
        for (i in 0 until GAIN_ITERATIONS) {
            val initialY = meteredY(meteredMean, histogram, gain)
            val extraGain = minOf(MAX_EXTRA_GAIN, targetY / (initialY + .001))
            gain *= extraGain
            if (extraGain < 1.01) break
        }
        if (histogram.total > 0L) {
            for (c in mode.constraints) {
                val newTargetY = minOf(Y_TARGET_LIMIT, c.yTarget * evGain)
                val iqm = histogram.interQuantileMean(c.qLo, c.qHi)
                val newGain = newTargetY * histogram.bins / iqm
                if (c.bound == Bound.LOWER && newGain > gain ||
                    c.bound == Bound.UPPER && newGain < gain) {
                    gain = newGain
                    targetY = newTargetY
                }
            }
        }
        return Gain(gain, targetY)
    }

    /**
     * AgcChannel::filterExposure for [frames] sensor frames at a constant
     * [target] (the per-frame filter applied frame by frame). [frameIndex] is
     * the loop's frame count before these frames (startup runs at speed 1).
     */
    fun filter(filtered: Double, target: Double, frames: Int, frameIndex: Int): Double {
        if (filtered <= 0.0) return target
        var f = filtered
        for (k in 1..frames.coerceAtLeast(1)) {
            val startup = frameIndex + k <= STARTUP_FRAMES
            val stable = if (startup) 0.0 else STABLE_REGION
            if (f * (1.0 - stable) < target && f * (1.0 + stable) > target) continue
            var speed = if (startup) 1.0 else SPEED
            if (f < 1.2 * target && f > 0.8 * target) speed = sqrt(speed)
            f = speed * target + f * (1.0 - speed)
        }
        return f
    }

    /** applyDigitalGain's desaturate test: lower exposure at once by [FAST_REDUCE_THRESHOLD]. */
    fun desaturate(gain: Gain): Boolean =
        gain.targetY > FAST_REDUCE_THRESHOLD && gain.gain < sqrt(gain.targetY)

    /**
     * divideUpExposure's flicker step: exposure time rounded down to whole
     * periods when at least one fits, analogue gain scaled to keep the total.
     * Returns (shutterNanos, isoScale).
     */
    fun roundForFlicker(shutterNanos: Long, flickerPeriodNanos: Long): Pair<Long, Double> {
        if (flickerPeriodNanos <= 0L) return shutterNanos to 1.0
        val periods = shutterNanos / flickerPeriodNanos
        if (periods <= 0L) return shutterNanos to 1.0
        val rounded = periods * flickerPeriodNanos
        return rounded to shutterNanos.toDouble() / rounded
    }
}
