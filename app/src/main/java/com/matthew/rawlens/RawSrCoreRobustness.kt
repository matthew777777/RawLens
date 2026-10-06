// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.exp

/**
 * Shared SR core: robustness weights (CPU donor [RawSrRobustness];
 * the Vulkan shaders transcribe the same formulas in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of [RawSrRobustness] (Dogson warp, color
 * distance, s1/s2 scaling, threshold, 5x5 local minimum, Rc sums), so CPU
 * behavior is identical by construction. Sharding stays in the caller. No
 * Android/GL dependencies; no allocation (terms land in caller scratch).
 */
object RawSrCoreRobustness {
    /** Reference `dogson_quadratic_kernel` (utils_image.py). */
    fun dogsonQuadratic(x: Double): Double {
        val a = kotlin.math.abs(x)
        return if (a <= 0.5) -2.0 * a * a + 1.0
        else if (a <= 1.5) a * a - 2.5 * a + 1.5
        else 0.0
    }

    /**
     * Reference `cuda_warp_dogson`: the moving 3x3 means resampled at
     * ([centerX], [centerY]) with the separable Dogson biquadratic kernel
     * over the rounded, edge-clamped 3x3 window, normalized by the summed
     * weights. The caller guarantees the center is in bounds.
     */
    fun warpDogson(
        movMean: Array<FloatArray>,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        out: DoubleArray
    ) {
        // Reference `round_half_away` (CUDA round()): halves away from zero.
        val centerQuadX = RawSrCoreAlign.roundHalfAway(centerX)
        val centerQuadY = RawSrCoreAlign.roundHalfAway(centerY)
        var wAcc = 0.0
        out[0] = 0.0
        out[1] = 0.0
        out[2] = 0.0
        for (i in -1..1) {
            val yy = (centerQuadY + i).coerceIn(0, height - 1)
            val wy = dogsonQuadratic(yy - centerY)
            for (j in -1..1) {
                val xx = (centerQuadX + j).coerceIn(0, width - 1)
                val w = wy * dogsonQuadratic(xx - centerX)
                for (c in 0..2) out[c] += movMean[c][yy * width + xx] * w
                wAcc += w
            }
        }
        out[0] /= wAcc
        out[1] /= wAcc
        out[2] /= wAcc
    }

    /**
     * Color distance over the reference variance: `out[0]` is the
     * squared error sum, `out[1]` the reference variance sum ([out] is
     * caller scratch, size >= 2).
     */
    fun distanceAndVariance(
        refMean: Array<FloatArray>,
        refVar: Array<FloatArray>,
        warped: DoubleArray,
        o: Int,
        out: DoubleArray
    ) {
        var distance = 0.0
        var refSum = 0.0
        for (c in 0..2) {
            val error = refMean[c][o] - warped[c]
            distance += error * error
            refSum += refVar[c][o]
        }
        out[0] = distance
        out[1] = refSum
    }

    /**
     * Measured noise correction (null LUT is a no-op): brightness is the
     * mean reference channel mean, the LUT's binning key. A zero sigma bin
     * skips the floor (a no-op like null): flooring a negative sliver at 0
     * would flip it from the reference accept to a reject, breaking
     * zero-LUT/analytic identity. Writes the corrected pair into [out]
     * (caller scratch, size >= 2).
     */
    fun correctNoise(
        distance: Double,
        variance: Double,
        refMean0: Float,
        refMean1: Float,
        refMean2: Float,
        noiseLut: RawSrNoiseLut.Lut?,
        out: DoubleArray
    ) {
        var correctedDistance = distance
        var correctedVariance = variance
        if (noiseLut != null) {
            val brightness = ((refMean0 + refMean1 + refMean2) / 3f).coerceIn(0f, 1f)
            val sample = noiseLut.sample(brightness)
            if (sample.sigmaSq > 0f) {
                correctedVariance = maxOf(variance, sample.sigmaSq.toDouble())
            }
            if (distance > 0.0 && sample.dSq > 0f) {
                val shrink = distance / (distance + sample.dSq.toDouble())
                correctedDistance = distance * shrink * shrink
            }
        }
        out[0] = correctedDistance
        out[1] = correctedVariance
    }

    /**
     * Reference `cpu_robustness_threshold` verbatim
     * (`clamp(S*exp(-d²/σ²)-t)` in float32): negative-variance
     * cancellation slivers from the unfloored Alg. 8 stats take
     * the exp/clamp path like every other variance (exp(-d/s)>=1
     * with s<0, so flats accept at R=1 for denoising), exactly as
     * the Numba kernel computes them. Exactly-zero variance would
     * raise ZeroDivisionError in Numba (Python float semantics);
     * it never occurs on noisy or LUT-floored data, and reads 0
     * here rather than crashing. NaN (0/0 only) clamps to 0 and
     * +Inf overflows clamp to 1, matching Numba min/max.
     */
    fun threshold(
        correctedDistance: Double,
        correctedVariance: Double,
        scale: Float,
        threshold: Float
    ): Float {
        return if (correctedVariance == 0.0) {
            0f
        } else {
            val v = scale * exp(-(correctedDistance / correctedVariance).toFloat()) - threshold
            if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
        }
    }

    /** Local minimum over a 5x5 clamp window (Alg. 9). */
    fun localMin(raw: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
        var minimum = Float.POSITIVE_INFINITY
        for (i in -2..2) for (j in -2..2)
            minimum = minOf(minimum, raw[((y + i).coerceIn(0, height - 1)) * width + (x + j).coerceIn(0, width - 1)])
        return minimum
    }

    /**
     * Reference `cuda_compute_s`: 3x3 tile flow spread (in-bounds tiles,
     * reliability-blind); true when motion is irregular. Non-finite tiles
     * are reference-undefined and skipped as missing data; with no finite
     * tile the verdict is conservatively irregular. ([x], [y] are guide
     * (quad) coordinates; the tile lattice is raw pixels, so the index
     * doubles — reference `x // (tile_size // 2)`.)
     */
    fun flowIrregular(flow: RawSrAlignmentField, x: Int, y: Int, motionThreshold: Float): Boolean {
        val tileX = ((2 * x) / flow.tileSize).coerceIn(0, flow.columns - 1)
        val tileY = ((2 * y) / flow.tileSize).coerceIn(0, flow.rows - 1)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var finite = false
        val direct = flow.tiles as? RawSrDirectFlowTiles
        for (i in -1..1) for (j in -1..1) {
            val tx = tileX + j
            val ty = tileY + i
            if (tx < 0 || ty < 0 || tx >= flow.columns || ty >= flow.rows) continue
            val dx: Float
            val dy: Float
            if (direct != null) {
                val index = ty * flow.columns + tx
                dx = direct.directDx(index)
                dy = direct.directDy(index)
            } else {
                val tile = flow.tiles[ty * flow.columns + tx]
                dx = tile.dx
                dy = tile.dy
            }
            if (!dx.isFinite() || !dy.isFinite()) continue
            finite = true
            minX = minOf(minX, dx)
            minY = minOf(minY, dy)
            maxX = maxOf(maxX, dx)
            maxY = maxOf(maxY, dy)
        }
        if (!finite) return true
        val spreadX = maxX - minX
        val spreadY = maxY - minY
        return spreadX * spreadX + spreadY * spreadY > motionThreshold * motionThreshold
    }

    /** One non-finite-sanitized Rc addend (reference-undefined taps weigh 0). */
    fun sanitizeWeight(v: Float): Float = if (v.isFinite()) v else 0f

    /**
     * Finite-sanitized plain per-quad Rc sums over the effective weights,
     * like the reference accumulated-robustness map. Returns the new plane
     * ([base] is copied, never mutated).
     */
    fun accumulateRc(base: FloatArray, r: FloatArray): FloatArray {
        val next = base.copyOf()
        accumulateRcInPlace(next, r)
        return next
    }

    /**
     * In-place twin of [accumulateRc]: same additions in the same order,
     * bitwise-identical, with no per-frame copy. Use when [base] is a
     * locally-owned accumulator (the merge Rc loops).
     */
    fun accumulateRcInPlace(base: FloatArray, r: FloatArray) {
        for (i in base.indices) {
            val v = r[i]
            base[i] += if (v.isFinite()) v else 0f
        }
    }

    /**
     * Plain per-quad Rc sums with no sanitization (the exact
     * [RawSrRobustness.accumulate] spelling, kept verbatim for that
     * helper's contract; production robustness planes are always finite,
     * so this agrees with [accumulateRc] on every evaluated frame).
     */
    fun accumulateRcPlain(base: FloatArray, r: FloatArray): FloatArray {
        val next = base.copyOf()
        for (i in next.indices) next[i] += r[i]
        return next
    }

    /** Support estimate `1 + Rc`, non-finite reads 0. */
    fun supportValue(rc: Float): Float {
        val v = (1.0 + rc).toFloat()
        return if (v.isFinite()) v else 0f
    }
}
