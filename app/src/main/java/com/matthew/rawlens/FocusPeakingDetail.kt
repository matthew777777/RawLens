// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Local RAW detail confidence, not an autofocus verdict. See focus-peaking-rework.md. */
internal object FocusPeakingDetail {
    const val SIDE = 16
    const val CLIP_LEVEL = 0.98f
    private const val MIN_GRADIENT = 0.006f

    class Scratch {
        val clean = FloatArray(SIDE * SIDE)
        val smooth = FloatArray(SIDE * SIDE)
        val detail = FloatArray(SIDE * SIDE)
        val clipIntegral = IntArray((SIDE + 1) * (SIDE + 1))
        val residual = FloatArray(SIDE * SIDE / 4)
    }

    /** Returns the strongest supported edge's index, or -1 when evidence is insufficient.
     * [noiseVariance] is the variance of the averaged green sample, in normalized RAW units.
     * Scratch is private to one sampling call/worker; no cross-frame state or stale marks.
     */
    fun detect(green: FloatArray, clipped: BooleanArray, noiseVariance: Float = 0f,
               scratch: Scratch = Scratch()): Int {
        require(green.size == SIDE * SIDE && clipped.size == green.size)
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (v in green) {
            if (!v.isFinite()) return -1
            low = minOf(low, v); high = maxOf(high, v)
        }
        if (high - low < MIN_GRADIENT) return -1
        // Median absolute diagonal Haar residual: signal-free on an axis-aligned edge.
        // For independent Gaussian noise, median(|a-b-c+d|) = 1.34898 * sigma.
        var k = 0
        for (y in 0 until SIDE step 2) for (x in 0 until SIDE step 2) {
            val i = y * SIDE + x
            scratch.residual[k++] = abs(green[i] - green[i + 1] - green[i + SIDE] + green[i + SIDE + 1])
        }
        java.util.Arrays.sort(scratch.residual)
        val median = (scratch.residual[k / 2 - 1] + scratch.residual[k / 2]) * 0.5f
        val measuredSigma = median / 1.34898f
        val variance = max(if (noiseVariance.isFinite()) noiseVariance.coerceAtLeast(0f) else 0f,
            measuredSigma * measuredSigma)
        val sigma = sqrt(variance)
        val spike = max(0.03f, 6f * sigma)
        green.copyInto(scratch.clean)
        // Remove isolated sensor impulses without smoothing a continuous thin edge.
        for (y in 1 until SIDE - 1) for (x in 1 until SIDE - 1) {
            val i = y * SIDE + x
            val l = green[i - 1]; val r = green[i + 1]
            val u = green[i - SIDE]; val d = green[i + SIDE]
            val mn = minOf(l, r, u, d); val mx = maxOf(l, r, u, d)
            if ((green[i] > mx + spike || green[i] < mn - spike) && mx - mn < spike * 0.5f)
                scratch.clean[i] = (l + r + u + d) * 0.25f
        }
        // 3x3 binomial prefilter. Same support in both axes; no directional preference.
        val p = scratch.clean; val b = scratch.smooth
        for (y in 1 until SIDE - 1) for (x in 1 until SIDE - 1) {
            val i = y * SIDE + x
            b[i] = (p[i - SIDE - 1] + 2f * p[i - SIDE] + p[i - SIDE + 1] +
                2f * p[i - 1] + 4f * p[i] + 2f * p[i + 1] +
                p[i + SIDE - 1] + 2f * p[i + SIDE] + p[i + SIDE + 1]) / 16f
        }
        // Reject the complete support of both gradient scales if any RAW channel clips.
        val integral = scratch.clipIntegral
        integral.fill(0)
        val stride = SIDE + 1
        for (y in 0 until SIDE) {
            var count = 0
            for (x in 0 until SIDE) {
                if (clipped[y * SIDE + x]) count++
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + count
            }
        }
        // The filtered central difference has noise variance 0.234375 * input variance.
        val gradientFloor = max(MIN_GRADIENT, 6f * sqrt(0.234375f * variance))
        val floorSquared = gradientFloor * gradientFloor
        val scores = scratch.detail
        scores.fill(0f)
        for (y in 3 until SIDE - 3) for (x in 3 until SIDE - 3) {
            val left = x - 3; val right = x + 4; val top = y - 3; val bottom = y + 4
            if (integral[bottom * stride + right] - integral[top * stride + right] -
                integral[bottom * stride + left] + integral[top * stride + left] > 0) continue
            val i = y * SIDE + x
            val gx = b[i + 1] - b[i - 1]; val gy = b[i + SIDE] - b[i - SIDE]
            val hx = b[i + 2] - b[i - 2]; val hy = b[i + 2 * SIDE] - b[i - 2 * SIDE]
            val fine = gx * gx + gy * gy
            val coarse = hx * hx + hy * hy
            // A diagonal step is spread across both axes by the square binomial kernel.
            // Correct that lattice bias rather than rejecting sharp slanted boundaries.
            val diagonal = if (fine > 0f) 2f * abs(gx * gy) / fine else 0f
            val ratio = 0.68f - 0.06f * diagonal
            if (fine >= floorSquared && fine >= ratio * ratio * coarse)
                scores[i] = fine
        }
        var best = -1
        var strength = 0f
        for (y in 3 until SIDE - 3) for (x in 3 until SIDE - 3) {
            val i = y * SIDE + x
            if (scores[i] <= strength) continue
            var neighbors = 0
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && scores[i + dy * SIDE + dx] > 0f) neighbors++
            }
            if (neighbors >= 2) { best = i; strength = scores[i] }
        }
        return best
    }
}
