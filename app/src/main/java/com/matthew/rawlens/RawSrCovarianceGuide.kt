// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder

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
 * - The transform runs on black/white-normalized, lens-shading-free observations,
 *   not on raw sensor codes. Eight-coefficient Camera2 profiles are code-domain
 *   (`S*code + O`) and convert exactly: with `v = (code-b)/(W-b)`,
 *   `var(v) = a'*v + b'` for `a' = S/(W-b)` and `b' = (S*b + O)/(W-b)^2`.
 *   Six-coefficient DNG profiles are already normalized-domain (`S*v + O`) and
 *   pass through untouched; dividing them again would understate noise by
 *   ~the white level and collapse every kernel to a razor.
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
 * - Storage vs units: covariance/precision matrices are STORED per quad pixel
 *   (one matrix per Bayer quad), and their entries are the reference
 *   `estimate_kernels` NUMBERS verbatim: the merge interpolates the
 *   covariances and inverts per pixel, then evaluates the exponent over
 *   RAW-pixel distances with NO /4 conversion — exactly like the reference
 *   `merge.py::accumulate`, whose covariance numbers are likewise consumed
 *   raw. (The retired quad-unit reading divided distances by 2, widening
 *   every kernel 2x against the reference; the exponent-invariance identity
 *   itself — `d^T P d` under joint distance/precision scaling — remains
 *   true math, but the merge no longer applies the conversion.)
 * - Grids: raw offsets are twice quad offsets (`d_raw = 2*d_quad`); only the
 *   LOOKUPS (covariance interpolation, flow) convert between grids, never the
 *   matrix entries.
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
        return Guide(
            reduceQuads(input, resolved.alpha, resolved.beta, resolved.stabilize),
            resolved.status
        )
    }

    /**
     * Plain normalized quad-mean gray WITHOUT GAT stabilization: same
     * geometry as [guide] (per-tap black/white normalization averaged per
     * quad), but the linear mean plane. This is the domain the
     * unblocker's `Vn = A·gray + B` noise gate is specified over
     * (normative docs/raw-sr-unblocker.md §1: the inpainted quad-gray
     * field with green *normalized* coefficients). Feeding
     * GAT-stabilized gray instead compares stabilized variance (noise
     * floor ~0.25) against a linear noise estimate and slashes every
     * noisy flat (regression 2026-10-08: robustness mean 0.93 → 0.04).
     */
    fun plainMean(frame: RawSrPackedFrame): RawSrGrayImage {
        require(frame.width % 2 == 0 && frame.height % 2 == 0) {
            "Guide quad reduction requires complete Bayer quads"
        }
        val input = frame.uploadInput()
        val resolved = resolve(input, frame.noiseProfile)
        return reduceQuads(input, resolved.alpha, resolved.beta, false)
    }

    private fun reduceQuads(
        input: GpuRawAmazeInput,
        alpha: DoubleArray,
        beta: DoubleArray,
        stabilize: Boolean
    ): RawSrGrayImage {
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
                    val observed = RawSrCoreGuide.normalize(code, black, white)
                    sum += if (!stabilize) {
                        observed
                    } else {
                        RawSrCoreGuide.stabilize(observed, alpha[phase], beta[phase])
                    }
                }
                values[qy * outWidth + qx] = RawSrCoreGuide.quadMean(sum).toFloat()
            }
        }
        return RawSrGrayImage(outWidth, outHeight, values)
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
     * black-level order). Alpha/beta are normalized-domain (`variance = a*v +
     * b` at normalized v); slope/offset are the code-domain equivalents
     * (`variance = slope*code + offset`) for the sigma-in-codes consumers.
     * Eight-coefficient Camera2 profiles are code-domain and converted;
     * six-coefficient DNG profiles are already normalized-domain (DNG
     * NoiseProfile convention) and pass through to alpha/beta, with
     * slope/offset derived as the exact code-domain image. Sensor
     * coordinates select profile entries, so odd origins stay exact.
     */
    internal data class NoiseTables(
        val alpha: DoubleArray,
        val beta: DoubleArray,
        val slope: DoubleArray,
        val offset: DoubleArray,
        val modelClass: ModelClass
    )

    internal fun noiseTables(input: GpuRawAmazeInput, profile: ImmutableDoubleValues?): NoiseTables =
        RawSrCoreGuide.noiseTables(input, profile)

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
