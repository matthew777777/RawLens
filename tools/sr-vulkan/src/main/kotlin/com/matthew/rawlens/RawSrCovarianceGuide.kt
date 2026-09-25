// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Prompt 4B.1 covariance guide: variance-stabilized Bayer-quad grayscale that feeds
 * kernel estimation. The guide applies the documented generalized Anscombe transform
 * (GAT) to normalized Bayer observations before quad reduction, using the captured
 * per-phase noise model expressed in the same normalization domain.
 *
 * Algorithm reference: Jamy Lafenetre's MIT-licensed IPOL implementation
 * (`handheld_super_resolution/utils_image.py::gat`: `VST = max(0, a*x + 3/8*a^2 + b)`,
 * output `(2/a)*sqrt(VST)`, asserted `a > 0`) followed by `decimating` 2x2 averaging.
 * No upstream code is copied. Deliberate deviations, each with explicit behavior:
 * - The transform runs on black/white-normalized, lens-shading-free observations
 *   (the domain where the captured `S*code + O` model holds), not on raw sensor codes.
 *   Per-phase normalized coefficients are exact, not fitted: with `v = (code-b)/(W-b)`
 *   and code variance `S*code + O`, `var(v) = a'*v + b'` for `a' = S/(W-b)` and
 *   `b' = (S*b + O)/(W-b)^2`.
 * - Zero shot noise (`S == 0`, `O > 0`) uses the continuous affine limit `v/sqrt(b')`.
 *   Jamy-L asserts `alpha > 0` and refuses these profiles; RawLens stabilizes them.
 * - Missing, invalid, inconsistent, or exactly-zero-noise profiles fall back to the
 *   plain normalized quad average (the Prompt 4B guide), each with its own status.
 *   The fallback is never silently relabeled as stabilized.
 * - Camera2 eight-coefficient profiles are indexed by sensor raster phase
 *   (`((sy&1)<<1)|(sx&1)`), consistent with [RawNormalization.blackLevels] order;
 *   six-coefficient DNG profiles are indexed by sensor CFA color, mirroring
 *   [RawSrTuning.estimate]. Eight-coefficient profiles carry no pattern of their own,
 *   so the frame's sensor pattern is authoritative for color-indexed profiles only.
 *
 * The guide never touches fusion samples, saved RAW, or the alignment pyramid: it is
 * consumed exclusively by kernel covariance estimation, for the reference and every
 * moving frame alike.
 *
 * ## Coordinate contract (RAW vs quad)
 *
 * - RAW grid: integer sensor-pixel coordinates. Sample `(x, y)` integrates the cell
 *   `[x,x+1) x [y,y+1)` and, after GAT, carries variance-stabilized units
 *   (dimensionless, unit noise variance where the model holds).
 * - Quad grid: quad `(qx, qy)` covers raw `[2qx,2qx+2) x [2qy,2qy+2)`. Its guide value
 *   is the mean of its four GAT samples, spatially located at the cell center
 *   `(2qx+1, 2qy+1)` in raw pixel-center coordinates.
 * - Gradients ([RawSrKernelCovariance] input): gradient `(gx, gy)` spans quads
 *   `(gx..gx+1, gy..gy+1)`; valid for `0 <= gx <= W-2`. The structure tensor at quad
 *   `(qx, qy)` sums the 2x2 gradient window `{(qx-1..qx, qy-1..qy)}`.
 * - Storage vs units: covariance/precision matrices are STORED per quad pixel, but
 *   their entries are expressed in quad-pixel spatial units (quad-pixel^2 and
 *   quad-pixel^-2). A matrix stored at quad `(qx, qy)` describes offsets measured in
 *   quad pixels from that quad's center.
 * - Cross-grid conversion: raw offsets are twice quad offsets (`d_raw = 2*d_quad`),
 *   so a raw-domain precision is `P_raw = P_quad/4` and the kernel exponent `d^T P d`
 *   is invariant. Future fusion code sampling raw pixels MUST scale packed precision
 *   by 1/4; the exponent-invariance test pins this factor.
 *
 * Scalar Double arithmetic over flat arrays; no object is allocated per pixel.
 */
object RawSrCovarianceGuide {
    /** Every profile case has an explicit, tested outcome. */
    enum class Status {
        /** Valid model; every phase stabilized with the GAT formula. */
        STABILIZED,
        /** All shot-noise slopes are zero with positive offsets; affine limit applied. */
        STABILIZED_ZERO_SHOT,
        /** Null profile: plain normalized quad average, exactly the 4B guide. */
        UNSTABILIZED_MISSING_PROFILE,
        /** Wrong size, non-finite, negative, or phase-inconsistent coefficients. */
        UNSTABILIZED_INVALID_PROFILE,
        /** Every phase reports exactly zero noise; nothing to stabilize. */
        UNSTABILIZED_ZERO_NOISE
    }

    data class Guide(val gray: RawSrGrayImage, val status: Status)

    /**
     * Per-frame GPU parameters. Alpha/beta are normalized-domain coefficients in
     * crop-local phase order (matching `raw/preprocess.glsl` black-level order), so
     * the shader needs no pattern or origin arithmetic. Black/white ride along so the
     * plain path normalizes exactly like the CPU fallback.
     */
    data class FrameGuide(
        val stabilize: Boolean,
        val alpha: FloatArray,
        val beta: FloatArray,
        val black: FloatArray,
        val white: Float
    ) {
        init {
            require(alpha.size == 4 && beta.size == 4 && black.size == 4)
            require(white.isFinite())
        }
    }

    /** Guide for one burst frame, with its explicit stabilization status. */
    fun guide(frame: RawSrPackedFrame): Guide {
        require(frame.width % 2 == 0 && frame.height % 2 == 0) {
            "Guide quad reduction requires complete Bayer quads"
        }
        val input = frame.uploadInput()
        val resolved = resolve(input, frame.noiseProfile)
        val source = input.buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = source.position()
        val crop = input.crop
        val width = crop.width
        val height = crop.height
        val outWidth = width / 2
        val outHeight = height / 2
        val values = FloatArray(outWidth * outHeight)
        RawSrWorkers.forEachShard(outHeight) { y0, y1 ->
            for (qy in y0 until y1) for (qx in 0 until outWidth) {
                var sum = 0.0
                for (i in 0..1) for (j in 0..1) {
                    val x = qx * 2 + j
                    val y = qy * 2 + i
                    val sx = input.sensorCropLeft + x
                    val sy = input.sensorCropTop + y
                    val offset = base + (crop.top + y) * input.layout.rowStride + (crop.left + x) * 2
                    val code = source.getShort(offset).toInt() and 65535
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val black = input.normalization.blackAt(sx, sy).toDouble()
                    val white = input.normalization.whiteLevel.toDouble()
                    val observed = (code - black) / (white - black)
                    sum += if (!resolved.stabilize) {
                        observed
                    } else {
                        val a = resolved.alpha[phase]
                        val b = resolved.beta[phase]
                        if (a == 0.0) observed / sqrt(b) else (2.0 / a) * sqrt(maxOf(0.0, a * observed + 0.375 * a * a + b))
                    }
                }
                values[qy * outWidth + qx] = (sum * 0.25).toFloat()
            }
        }
        return Guide(RawSrGrayImage(outWidth, outHeight, values), resolved.status)
    }

    /** GPU parameters for one burst frame; plain-path fields are always populated. */
    fun gpuParams(frame: RawSrPackedFrame): FrameGuide {
        val input = frame.uploadInput()
        val resolved = resolve(input, frame.noiseProfile)
        val black = FloatArray(4) { input.normalization.blackAt(input.sensorCropLeft + (it and 1), input.sensorCropTop + (it shr 1)) }
        return FrameGuide(
            resolved.stabilize,
            FloatArray(4) { resolved.alpha[it].toFloat() },
            FloatArray(4) { resolved.beta[it].toFloat() },
            black,
            input.normalization.whiteLevel
        )
    }

    /** Adapter-path parameters: no codes exist, so the covariance pass reuses quad gray. */
    fun bypass(): FrameGuide = FrameGuide(false, FloatArray(4), FloatArray(4), FloatArray(4), 1f)

    /** Model classification shared with the robustness stage. */
    internal enum class ModelClass { VALID, ZERO_SHOT, MISSING, INVALID, ZERO_NOISE }

    /**
     * Per-phase noise tables in crop-local phase order (matches preprocess
     * black-level order). Alpha/beta are normalized-domain; slope/offset are the
     * code-domain captured pairs. Sensor coordinates select profile entries, so
     * odd origins stay exact.
     */
    internal data class NoiseTables(
        val alpha: DoubleArray,
        val beta: DoubleArray,
        val slope: DoubleArray,
        val offset: DoubleArray,
        val modelClass: ModelClass
    )

    internal fun noiseTables(input: GpuRawAmazeInput, profile: ImmutableDoubleValues?): NoiseTables {
        val empty = DoubleArray(4)
        if (profile == null) return NoiseTables(empty, empty, empty, empty, ModelClass.MISSING)
        val coefficients = profile.toDoubleArray()
        if (coefficients.size != 6 && coefficients.size != 8)
            return NoiseTables(empty, empty, empty, empty, ModelClass.INVALID)
        if (coefficients.any { !it.isFinite() || it < 0.0 })
            return NoiseTables(empty, empty, empty, empty, ModelClass.INVALID)
        val alpha = DoubleArray(4)
        val beta = DoubleArray(4)
        val slope = DoubleArray(4)
        val offset = DoubleArray(4)
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
            slope[phase] = parsedSlope
            offset[phase] = parsedOffset
            alpha[phase] = parsedSlope / (white - black)
            beta[phase] = (parsedSlope * black + parsedOffset) / ((white - black) * (white - black))
        }
        val degenerate = BooleanArray(4) { alpha[it] == 0.0 && beta[it] == 0.0 }
        if (degenerate.all { it }) return NoiseTables(alpha, beta, slope, offset, ModelClass.ZERO_NOISE)
        // A zero-noise phase beside a noisy one describes no physical sensor.
        if (degenerate.any { it }) return NoiseTables(empty, empty, empty, empty, ModelClass.INVALID)
        val modelClass = if (alpha.all { it == 0.0 }) ModelClass.ZERO_SHOT else ModelClass.VALID
        return NoiseTables(alpha, beta, slope, offset, modelClass)
    }

    private data class Resolved(val stabilize: Boolean, val status: Status, val alpha: DoubleArray, val beta: DoubleArray)

    private fun fallback(status: Status) = Resolved(false, status, DoubleArray(4), DoubleArray(4))

    private fun resolve(input: GpuRawAmazeInput, profile: ImmutableDoubleValues?): Resolved {
        val tables = noiseTables(input, profile)
        return when (tables.modelClass) {
            ModelClass.VALID -> Resolved(true, Status.STABILIZED, tables.alpha, tables.beta)
            ModelClass.ZERO_SHOT -> Resolved(true, Status.STABILIZED_ZERO_SHOT, tables.alpha, tables.beta)
            ModelClass.MISSING -> fallback(Status.UNSTABILIZED_MISSING_PROFILE)
            ModelClass.INVALID -> fallback(Status.UNSTABILIZED_INVALID_PROFILE)
            ModelClass.ZERO_NOISE -> fallback(Status.UNSTABILIZED_ZERO_NOISE)
        }
    }
}
