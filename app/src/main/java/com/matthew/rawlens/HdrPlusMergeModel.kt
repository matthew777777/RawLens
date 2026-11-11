// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * What a strength means to the frequency merge, in units of real noise.
 *
 * The merge shrinks each frequency bin with w = D^2 / (D^2 + norm), D being
 * the reference/companion difference. This mirrors how the native code turns
 * the slider into `norm` (RawMergeHdrPlusGpu.h `frequencyNorms`, shaders
 * hdrq_rms / hdrq_mismatch_norm / hdrq_merge) and divides it by the power a
 * noise-only difference has under a measured [HdrPlusNoiseModel]. The ratio
 * ("noise gain") is 1 when the merge assumes exactly the noise the sensor
 * has: below it the merge rejects plain noise, above it the merge accepts
 * differences that many times larger than noise.
 *
 * Every number here restates the native merge; change them together.
 */
object HdrPlusMergeModel {
    /** Merge tile side in RGBA samples (native kFrequencyTile). */
    private const val TILE = 8
    /** hdrq_rms: noise level is this fraction of the tile's RMS signal. */
    private const val RMS_SCALE = 0.25
    /** hdrq_mismatch_norm: tile mismatch is rescaled to this frame mean. */
    private const val MEAN_MISMATCH = 0.12
    /** hdrq_merge motion-norm ramp: full below START, none after SPAN more. */
    private const val MOTION_START = 0.02
    private const val MOTION_SPAN = 0.15

    private val strengths = HdrPlusSettings.MIN_STRENGTH.toInt()..HdrPlusSettings.MAX_STRENGTH.toInt()

    /** Sum of squared raised-cosine window weights (hdrq_forward_dft): the
     * factor between per-pixel variance and per-bin spectral power. */
    private val windowEnergy: Double = run {
        var sum = 0.0
        for (y in 0 until TILE) for (x in 0 until TILE) {
            val w = (0.5 - 0.5 * cos(2.0 * PI * (x + 0.5) / TILE)) *
                (0.5 - 0.5 * cos(2.0 * PI * (y + 0.5) / TILE))
            sum += w * w
        }
        sum
    }

    /**
     * Noise gain of every integer strength (index 0 = MIN_STRENGTH), taken
     * at the median over the model's own signal levels. [codeRange] is
     * white minus black in raw codes: the native norms are in raw codes.
     */
    fun frequencyGains(model: HdrPlusNoiseModel, codeRange: Double): DoubleArray {
        require(codeRange.isFinite() && codeRange > 0.0) { "HDR+ code range must be positive" }
        return DoubleArray(strengths.count()) { index ->
            val rev = 0.5 * (26.5 - (strengths.first + index))
            val robustnessNorm = 2.0.pow(7.5 - rev)
            val readNoise = 2.0.pow(10.0 - rev).pow(1.6)
            val maxMotionNorm = max(1.0, 1.3.pow(11.0 - rev))
            // A typical tile sits at the frame-mean mismatch.
            val motionNorm = (maxMotionNorm - (MEAN_MISMATCH - MOTION_START) *
                (maxMotionNorm - 1.0) / MOTION_SPAN).coerceIn(1.0, maxMotionNorm)
            val ratios = DoubleArray(model.levels.size) { i ->
                val level = model.levels[i]
                val norm = (RMS_SCALE * level * codeRange + readNoise) *
                    TILE * TILE * robustnessNorm * motionNorm
                // Difference of two frames: twice the single-frame variance.
                norm / (2.0 * model.variance(level) * codeRange * codeRange * windowEnergy)
            }
            ratios.sort()
            (ratios[(ratios.size - 1) / 2] + ratios[ratios.size / 2]) / 2.0
        }
    }

    /** Same-color taps of the spatial merge's blur (hdrp_blur BW16): the
     * central 17 coefficients of the order-32 binomial, renormalized. */
    internal val blurWeights: DoubleArray = run {
        val row = DoubleArray(2 * BLUR_RADIUS + 1)
        for (k in 0..BLUR_RADIUS) {
            var c = 1.0
            for (i in 1..(BLUR_ORDER / 2 - k)) c = c * (BLUR_ORDER - (BLUR_ORDER / 2 - k) + i) / i
            row[BLUR_RADIUS + k] = c
            row[BLUR_RADIUS - k] = c
        }
        val sum = row.sum()
        DoubleArray(row.size) { row[it] / sum }
    }
    internal const val BLUR_RADIUS = 8
    private const val BLUR_ORDER = 32
    /** Raw pixels summed per weight cell (hdrp_color_diff: one 2x2 CFA cell). */
    internal const val DIFF_CELL_PIXELS = 4

    /**
     * Noise gain of every integer strength on the spatial ("Fast") merge.
     * There a companion's weight is 1 - diff / maxDiff, with diff the blurred
     * reference/companion difference per CFA cell and maxDiff the reference's
     * own blur residual ([noiseReference], measured by
     * [HdrPlusSceneNoise.spatialNoiseReference]) over the slider's
     * robustness. A noise-only difference of mean d therefore leaks d /
     * maxDiff of the reference; the frequency merge at gain g leaks
     * 1/(g + 1), so the equivalent gain is maxDiff / d - 1.
     * [noiseSigma] is the reference's mean noise sigma, same units as
     * [noiseReference]. Null when either is unusable.
     */
    fun spatialGains(noiseReference: Double, noiseSigma: Double): DoubleArray? {
        if (!noiseReference.isFinite() || !noiseSigma.isFinite() ||
            noiseReference <= 0.0 || noiseSigma <= 0.0) return null
        val row = blurWeights.sumOf { it * it }
        // Blurring both frames scales the difference's sigma by the 2D
        // kernel energy; E|N(0, 2 s^2)| = 2 s / sqrt(pi).
        val noiseDifference = DIFF_CELL_PIXELS * row * 2.0 * noiseSigma / kotlin.math.sqrt(PI)
        return DoubleArray(strengths.count()) { index ->
            val rev = 0.5 * (36.0 - (strengths.first + index))
            val robustness = 0.12 * 1.3.pow(rev) - 0.4529822
            max(noiseReference / (robustness * noiseDifference) - 1.0, Double.MIN_VALUE)
        }
    }

    /** Continuous strength whose noise gain is [target] (log-interpolated),
     * clamped to the slider range. */
    fun strengthFor(gains: DoubleArray, target: Double): Float {
        if (!(target > gains.first())) return HdrPlusSettings.MIN_STRENGTH
        if (!(target < gains.last())) return HdrPlusSettings.MAX_STRENGTH
        var hi = 1
        while (gains[hi] < target) hi++
        val lo = ln(gains[hi - 1])
        val t = (ln(target) - lo) / (ln(gains[hi]) - lo)
        return (strengths.first + hi - 1 + t).toFloat()
    }

    /**
     * Noise gain a burst deserves. A still [frames]-frame burst merges at a
     * gain equal to its frame count: the Wiener weight then leaks about
     * 1/(frames+1) of the reference's own noise per companion, which costs
     * less than one frame's worth of averaging, so more gain cannot buy
     * anything visible while it would admit larger ghosts. [motionShare]
     * (0..1, fraction of the measured frame difference that noise does not
     * explain) slides the gain down geometrically to 1, where the merge
     * assumes exactly the measured noise and nothing more.
     */
    fun targetGain(frames: Int, motionShare: Double): Double =
        exp(ln(frames.toDouble()) * (1.0 - motionShare.coerceIn(0.0, 1.0)))
}
