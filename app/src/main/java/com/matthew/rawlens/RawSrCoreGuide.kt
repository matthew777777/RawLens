// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.sqrt

/**
 * Shared SR core: covariance guide (CPU donor [RawSrCovarianceGuide]; the
 * Vulkan shaders transcribe the same formulas in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of the donor (per-phase GAT stabilization, quad
 * averaging, noise-table resolve), so CPU behavior is identical by
 * construction. The [RawSrCovarianceGuide.NoiseTables] /
 * [RawSrCovarianceGuide.ModelClass] types stay on the donor for API
 * stability; only the computation moves here. No Android/GL dependencies;
 * no allocation.
 */
object RawSrCoreGuide {
    /**
     * Black/white normalization of one sensor code:
     * `v = (code - b) / (W - b)`.
     */
    fun normalize(code: Int, black: Double, white: Double): Double =
        (code - black) / (white - black)

    /**
     * Generalized Anscombe transform of one normalized observation
     * (Jamy-L `gat`: `VST = max(0, a*x + 3/8*a^2 + b)`, output
     * `(2/a)*sqrt(VST)`); zero shot noise takes the continuous affine
     * limit `v/sqrt(b)`.
     */
    fun stabilize(observed: Double, alpha: Double, beta: Double): Double =
        if (alpha == 0.0) observed / sqrt(beta)
        else (2.0 / alpha) * sqrt(maxOf(0.0, alpha * observed + 0.375 * alpha * alpha + beta))

    /** Quad reduction: the mean of the four stabilized samples. */
    fun quadMean(sum: Double): Double = sum * 0.25

    /**
     * Per-phase noise tables in crop-local phase order (matches preprocess
     * black-level order). Alpha/beta are normalized-domain (`variance = a*v +
     * b` at normalized v); slope/offset are the code-domain equivalents
     * (`variance = slope*code + offset`) for the sigma-in-codes consumers.
     * Eight-coefficient Camera2 profiles are code-domain and converted;
     * six-coefficient DNG profiles are already normalized-domain (DNG
     * NoiseProfile convention) and pass through to alpha/beta, with
     * slope/offset derived as the exact code-domain image. Sensor
     * coordinates select profile entries, so odd origins stay exact.
     */
    internal fun noiseTables(
        input: GpuRawAmazeInput,
        profile: ImmutableDoubleValues?
    ): RawSrCovarianceGuide.NoiseTables {
        val empty = DoubleArray(4)
        if (profile == null) return RawSrCovarianceGuide.NoiseTables(
            empty, empty, empty, empty, RawSrCovarianceGuide.ModelClass.MISSING)
        val coefficients = profile.toDoubleArray()
        if (coefficients.size != 6 && coefficients.size != 8)
            return RawSrCovarianceGuide.NoiseTables(
                empty, empty, empty, empty, RawSrCovarianceGuide.ModelClass.INVALID)
        if (coefficients.any { !it.isFinite() || it < 0.0 })
            return RawSrCovarianceGuide.NoiseTables(
                empty, empty, empty, empty, RawSrCovarianceGuide.ModelClass.INVALID)
        val alpha = DoubleArray(4)
        val beta = DoubleArray(4)
        val slope = DoubleArray(4)
        val offset = DoubleArray(4)
        // DNG six-coefficient profiles already live in the normalized domain;
        // only Camera2 eight-coefficient profiles need code-domain conversion.
        val dngNormalized = coefficients.size == 6
        for (phase in 0..3) {
            val sx = input.sensorCropLeft + (phase and 1)
            val sy = input.sensorCropTop + ((phase shr 1) and 1)
            val parsedSlope: Double
            val parsedOffset: Double
            if (coefficients.size == 8) {
                val raster = ((sy and 1) shl 1) or (sx and 1)
                parsedSlope = coefficients[raster * 2]
                parsedOffset = coefficients[raster * 2 + 1]
            } else {
                val channel = when (input.normalization.sensorPattern.colorAt(sx, sy)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
                parsedSlope = coefficients[channel * 2]
                parsedOffset = coefficients[channel * 2 + 1]
            }
            val black = input.normalization.blackAt(sx, sy).toDouble()
            val white = input.normalization.whiteLevel.toDouble()
            if (dngNormalized) {
                alpha[phase] = parsedSlope
                beta[phase] = parsedOffset
                // Exact code-domain image of S*v + O with v = (code-b)/(W-b):
                // slope*code + offset for the sigma-in-codes consumers. The
                // offset legitimately goes negative (affine extrapolation
                // below black); variance stays positive at/above black.
                slope[phase] = parsedSlope * (white - black)
                offset[phase] = parsedOffset * (white - black) * (white - black) -
                    parsedSlope * (white - black) * black
            } else {
                slope[phase] = parsedSlope
                offset[phase] = parsedOffset
                alpha[phase] = parsedSlope / (white - black)
                beta[phase] = (parsedSlope * black + parsedOffset) / ((white - black) * (white - black))
            }
        }
        val degenerate = BooleanArray(4) { alpha[it] == 0.0 && beta[it] == 0.0 }
        if (degenerate.all { it }) return RawSrCovarianceGuide.NoiseTables(
            alpha, beta, slope, offset, RawSrCovarianceGuide.ModelClass.ZERO_NOISE)
        // A zero-noise phase beside a noisy one describes no physical sensor.
        if (degenerate.any { it }) return RawSrCovarianceGuide.NoiseTables(
            empty, empty, empty, empty, RawSrCovarianceGuide.ModelClass.INVALID)
        val modelClass = if (alpha.all { it == 0.0 }) RawSrCovarianceGuide.ModelClass.ZERO_SHOT
        else RawSrCovarianceGuide.ModelClass.VALID
        return RawSrCovarianceGuide.NoiseTables(alpha, beta, slope, offset, modelClass)
    }
}
