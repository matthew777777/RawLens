// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * CPU oracle: Bayer-direct linear merge, Jamy-L Alg. 4 (`merge.py`:
 * `accumulate`) verbatim, plus the plain `num/den` normalization
 * (`utils.divide`, NaN-at-zero-support blacked by the caller).
 *
 * Reference structure, in reference order per output pixel:
 * - Native 1x grid; reference-anchored flow in RAW pixels applied directly
 *   (`lr_mov = lr + flow`), bilinearly blended at the raw source
 *   ([RawSrAlignmentField.flowAtSmoothInto], Sabre-style dense gather —
 *   deliberate deviation from the reference tile snap: the warp is
 *   C0-continuous, so no tile tears can form; mistakes are rejected
 *   per-pixel by r, not by flow vetoes).
 * - Robustness from the nearest quad with the reference one-quad shift
 *   (`min(int(lr//2-0.5))`, `r_ref = 1`); `r == 0` skips accumulation
 *   (adding zeros, like the reference accumulating `w*0`).
 * - Source-anchored bilinear COVARIANCE interpolation at
 *   `g = source*(guide/raw) - 0.5`, inverted per pixel to the precision the
 *   exponent consumes (`z = d_raw^T P d_raw`, raw-unit distances,
 *   `w = exp(-0.5 max(z, 0))`, no additive floor).
 * - 3x3 RAW support with per-tap sensor-coordinate CFA routing via
 *   [BayerPattern.colorAt] (pattern-aware; identical to the reference `rggb`
 *   hardcode on RGGB): `num_c += w*r*c`, `den_c += w*r`, with independent
 *   R/G/B denominators. Ordinary linear camera-RGB numerators (no opponent
 *   transform or baked white balance); unclamped signal policy (negatives
 *   preserved, never clipped); every finite sample merges.
 * - Plain per-channel divide with `den <= eps` reading 0, matching the
 *   reference NaN-at-zero-support blacked downstream (an empty denominator
 *   implies an empty numerator — the same weights feed both — so the only
 *   undefined quotient is 0/0).
 * - Chroma latch guard (deliberate deviation, [CHROMA_SIGMA_MPY]): R/B taps
 *   merge with a 2x-wider kernel than green, Nyquist-matched to their 2px
 *   lattices; the reference reuses the razor kernel for all channels and
 *   latches R/B onto single taps (chroma zipper). Green is bitwise
 *   reference-verbatim; `chromaSigmaMpy = 1.0` restores the full reference
 *   path (parity tests pin that spelling).
 * - `Rc` folds through [RawSrRobustness.accumulate] over the effective
 *   (finite-sanitized) weights, like the reference accumulated-robustness
 *   debug map; per-channel moving-frame support is exposed as
 *   [MergeResult.channelEvidence] for denoising control.
 * - The merge takes precomputed kernel covariance fields and consumes no
 *   tuning: there is no per-scene covariance parameter to tune.
 * - Opt-in green-guided chroma deweight ([ChromaParams]) stays available for
 *   A/B; null (the default) runs the reference path. Likewise
 *   [accumulateNearest] (Stacker delta-kernel rule) stays as a tested helper
 *   but no longer backs any fallback: the base path has no fallback cascade,
 *   no highlight rolloff, and no support overwrite — the reference defines
 *   none of them.
 * - Scalar Double accumulation over flat arrays; no object is allocated per
 *   pixel and peak working memory is independent of the frame count.
 */
object RawSrBayerMerge {
    /**
     * Zero-support gate (reference `utils.divide` parity): the divide reads 0
     * only at exactly-zero denominators (0/0 NaN blacked downstream); tiny
     * but nonzero support (1e-10 from razor kernels) divides to its finite
     * weighted mean like the reference, instead of gating to 0 at 1e-8 and
     * punching single-lane dead dots the inpaint pass must then guess.
     */
    const val EPS = 0.0

    /**
     * R/B tap kernel sigma multiplier relative to green (default
     * [merge] behavior). The reference merges every CFA channel with the
     * same razor kernels, but R/B taps live on 2px lattices while green is
     * quincunx-dense: at across-edge radii (~0.13 raw px) each output
     * pixel's R/B quotient latches onto a single tap, and the R/B latch
     * phases disagree along edges (comb R/G-B/G, chroma zipper teeth that
     * survive the CFL finishing pass). Widening only the R/B kernel
     * (~Nyquist-matched to their sparser lattice) kills the teeth while
     * the green lane — and all luma detail — stays bitwise-identical.
     * Pass `chromaSigmaMpy = 1.0` for the reference-verbatim path.
     */
    const val CHROMA_SIGMA_MPY = 2.0

    /**
     * Dormant A/B constants, not consumed by the reference-parity base path:
     * [MIN_SUPPORT] (accumulated-robustness overwrite), [SATURATED_REF_GUARD]
     * (tap censor boundary). The reference defines none of these gates;
     * every finite sample merges and zero support divides to 0.
     */
    const val MIN_SUPPORT = 0.5f
    const val SATURATED_REF_GUARD = 0.99

    data class MergeFrame(
        val width: Int,
        val height: Int,
        /** Black-subtracted, white-normalized, lens-shaded linear samples, unclamped. */
        val samples: FloatArray,
        /**
         * Bayer phase at samples[0]: the sensor pattern already shifted by
         * the crop origin (exactly [UnpackedRawCfa.pattern]). CFA routing
         * below is crop-relative (`sensorPattern.colorAt(tx, ty)`); adding
         * the origin again double-folds the phase and swaps R/B on odd
         * origins — the GPU shader and this oracle must agree, so the
         * shifted form is the only valid input.
         */
        val sensorPattern: BayerPattern,
        /** Full-sensor coordinates of samples[0]; informational only, never folded into routing. */
        val sensorLeft: Int,
        val sensorTop: Int,
        /**
         * Owning frame's kernel covariance field on its quad grid
         * (quad-pixel²). The merge interpolates covariances and inverts per
         * pixel (reference Alg. 4); it never consumes a pre-inverted field.
         */
        val covariance: RawSrKernelCovariance.MatrixField,
        /** Reference-anchored flow on the raw lattice (raw-unit vectors); ignored for the reference frame. */
        val flow: RawSrAlignmentField?,
        /** Reference-anchored robustness on the quad grid; ignored for the reference frame. */
        val robustness: RawSrRobustness.FrameRobustness?,
        val highlightNeutral: FloatArray = floatArrayOf(1f, 1f, 1f),
        /**
         * Opt-in green-guided chroma deweight (Sabre `direct_rgb_accumulate`
         * `chromaWeight` analogue, adapted to plain linear RGB: no opponent
         * transform, no highlight reconstruction). Null (the default)
         * disables the deweight and preserves legacy behavior bitwise.
         *
         * Coefficients are normalized-domain SINGLE-sample green noise
         * (mean of the two green phases from
         * [RawSrCovarianceGuide.noiseTables], NOT the x0.25 green-mean pair
         * in [RawSrRobustness.gpuParams], whose quarter scaling describes
         * the variance of the 2-sample green mean used by the photo term).
         */
        val chroma: ChromaParams? = null
    )

    /**
     * Green noise model for [MergeFrame.chroma]: normalized-domain slope and
     * offset such that `variance = S * signal + O` at single-sample green
     * levels. Mirrors Sabre `greenNoiseS/greenNoiseO` uniforms.
     */
    data class ChromaParams(
        val greenNoiseS: Double,
        val greenNoiseO: Double
    )

    data class MergeResult(
        val width: Int,
        val height: Int,
        val rgb: FloatArray,
        val numerator: FloatArray,
        val denominator: FloatArray,
        val rc: RawSrRobustness.RcField,
        /** 1 + Rc per quad: robustness-based support estimate (contract §11). */
        val support: FloatArray,
        /** Per-pixel out-of-bounds diagnostic counter (contract §7). */
        val oobCount: IntArray,
        /** True where any channel fell back to the reference-only value. */
        val fallback: BooleanArray,
        /**
         * Reserved reference-quotient lane (pixels * 3), zeros in the base
         * path: the reference-parity finalizer is a plain divide with no
         * reference-only fallback branch. Kept for result-shape stability.
         */
        val refQuotient: FloatArray = FloatArray(0),
        /**
         * Per-channel moving-frame support evidence, one byte per pixel:
         * bit `c` is set when channel `c`'s moving-frame denominator exceeds
         * [EPS] (bit 0 = R, 1 = G, 2 = B). Sabre `support.g/b` analogue:
         * per-color validity for denoising control. Reference-only pixels
         * (and reference-only mode) read 0 — the reference contribution is
         * not evidence of multi-frame support.
         */
        val channelEvidence: ByteArray = ByteArray(0)
    )

    /**
     * Even output grid at [scale], via the shared SR planner: the linear
     * SR grid is pixel-identical to the mosaic SR grid. Returns
     * (width, height).
     */
    fun planTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        scale: RawSrLinearScale = RawSrLinearScale.X1
    ): Pair<Int, Int> = planSrOutputDims(sourceWidth, sourceHeight, scale.factor)

    /**
     * Merge [moving] onto [reference]; the reference merges last with zero
     * shift and unit robustness. With [referenceOnly] the moving frames are
     * skipped on the same path and Rc stays zero. [memory], when supplied,
     * accounts the constant-size working buffers; every allocated byte is
     * released if validation, cancellation, or an unexpected failure aborts
     * the merge. [isCancelled] observes completed scanlines and aborts.
     * [scale] sets the output grid: X1 resolves at sensor resolution, SR
     * resolves the shared √2 grid (2x area, ~25 MP, same lattice as mosaic
     * SR). Kernels and robustness stay source-anchored on the quad grid and
     * the flow on the raw lattice (raw-unit distances everywhere); only the
     * output lattice changes, so 1x output is bitwise-identical with or
     * without the argument.
     */
    fun merge(
        reference: MergeFrame,
        moving: List<MergeFrame>,
        referenceOnly: Boolean = false,
        memory: RawSrTextureMemory? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        chromaSigmaMpy: Double = CHROMA_SIGMA_MPY
    ): MergeResult {
        require(chromaSigmaMpy.isFinite() && chromaSigmaMpy > 0.0) {
            "chromaSigmaMpy must be finite and positive"
        }
        // Precision-domain scale for R/B taps (sigma x s <=> z / s^2);
        // green taps always use the unscaled z (bitwise reference path).
        val chromaZScale = RawSrCoreKernel.chromaZScale(chromaSigmaMpy)
        val frames = if (referenceOnly) emptyList() else moving
        val width = reference.width
        val height = reference.height
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Merge requires an even RAW crop of at least 2x2"
        }
        val (outW, outH) = planTarget(width, height, scale)
        val factor = scale.factor
        val quadsW = width / 2
        val quadsH = height / 2
        fun checkFrame(frame: MergeFrame, label: String) {
            require(frame.width == width && frame.height == height) {
                "$label dimensions ${frame.width}x${frame.height} do not match $width x $height"
            }
            require(frame.samples.size == width * height) { "$label samples truncated" }
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
            }
        }
        checkFrame(reference, "Reference")
        frames.forEachIndexed { index, frame ->
            checkFrame(frame, "Moving $index")
            require(frame.flow != null && frame.robustness != null) { "Moving $index needs flow and robustness" }
            require(frame.flow.coversRaw(width, height)) {
                "Moving $index flow must cover the raw lattice"
            }
            require(frame.robustness.width == quadsW && frame.robustness.height == quadsH) {
                "Moving $index robustness grid must match the quad grid"
            }
        }
        val pixels = outW * outH
        // One numerator/denominator double pair, quad Rc floats, int OOB
        // lanes, the fallback mask, and the channel-evidence byte plane.
        val bytes = pixels * 3L * 8 * 2 + quadsW.toLong() * quadsH * 4 +
            pixels * 4L + pixels + pixels.toLong()
        memory?.allocate(bytes)
        try {
            val num = DoubleArray(pixels * 3)
            val den = DoubleArray(pixels * 3)
            val oob = IntArray(pixels)
            val rowsDone = java.util.concurrent.atomic.AtomicInteger(0)
            for (frame in frames) {
                val tile = frame.flow!!
                val robust = frame.robustness!!
                accumulateFrame(frame, tile, robust, num, den,
                    oob, width, height, outW, outH, factor, false, chromaZScale) {
                    isCancelled?.invoke(rowsDone.incrementAndGet()) == true
                }
                if (isCancelled?.invoke(rowsDone.get()) == true) throw CancellationException("RAW-SR merge cancelled")
            }
            // Per-channel moving-frame evidence: read off the moving-only
            // denominators before the reference contribution joins them below.
            // Bit c of pixel p is set when channel c saw moving-frame support
            // above EPS.
            val channelEvidence = computeChannelEvidence(den, pixels)
            accumulateFrame(reference, null, null, num, den,
                oob, width, height, outW, outH, factor, true, chromaZScale)
            // Rc over the effective (finite-sanitized) weights, like the
            // reference accumulated-robustness map; identically zero when the
            // moving frames are skipped.
            val rcValues = FloatArray(quadsW * quadsH)
            for (frame in frames) {
                RawSrCoreRobustness.accumulateRcInPlace(rcValues, frame.robustness!!.r)
            }
            if (referenceOnly) rcValues.fill(0f)
            val rc = RawSrRobustness.RcField(quadsW, quadsH, rcValues)
            val rgb = FloatArray(pixels * 3)
            val numerator = FloatArray(pixels * 3)
            val denominator = FloatArray(pixels * 3)
            val fallback = BooleanArray(pixels)
            val refQuotient = FloatArray(pixels * 3)
            // Reference divide (`utils.divide`): plain per-channel quotient,
            // 0 at zero support (the reference NaN blacked downstream).
            // Row-sharded: disjoint rows, bitwise-identical at any count.
            RawSrWorkers.forEachShard(outH) { y0, y1 ->
                for (y in y0 until y1) {
                    for (x in 0 until outW) {
                        val p = y * outW + x
                        var fellBack = false
                        for (c in 0..2) {
                            val o = p * 3 + c
                            val dNum = num[o]
                            val dDen = den[o]
                            rgb[o] = RawSrCoreKernel.divide(dNum, dDen).toFloat()
                            if (RawSrCoreKernel.divideFallback(dNum, dDen)) fellBack = true
                            numerator[o] = RawSrCoreKernel.sanitize(num[o].toFloat())
                            denominator[o] = RawSrCoreKernel.sanitize(den[o].toFloat())
                        }
                        fallback[p] = fellBack
                    }
                }
            }
            val support = FloatArray(quadsW * quadsH) { i ->
                RawSrCoreRobustness.supportValue(rc.values[i])
            }
            return MergeResult(outW, outH, rgb, numerator, denominator, rc, support, oob, fallback,
                refQuotient, channelEvidence)
        } catch (t: Throwable) {
            memory?.release(bytes)
            throw t
        }
    }

    /**
     * Per-pixel channel-evidence bytes over the moving-only denominators:
     * bit `c` of pixel `p` is set when `den[p * 3 + c]` exceeds [EPS]
     * (bit 0 = R, 1 = G, 2 = B). Sharded over pixels with a single store
     * per pixel: one writer touches each byte, so the result is
     * deterministic at any worker count. A channel sharding must NOT be
     * used here — a pixel's three read-modify-write updates can then split
     * across two shards (whenever the shard span is not a multiple of 3)
     * and lose a bit to a lost-update race.
     */
    internal fun computeChannelEvidence(den: DoubleArray, pixels: Int): ByteArray {
        require(den.size == pixels * 3) { "Denominator must hold three channels per pixel" }
        val evidence = ByteArray(pixels)
        RawSrWorkers.forEachShard(pixels) { p0, p1 ->
            for (p in p0 until p1) {
                val base = p * 3
                var bits = 0
                if (den[base] > EPS) bits = bits or 0b001
                if (den[base + 1] > EPS) bits = bits or 0b010
                if (den[base + 2] > EPS) bits = bits or 0b100
                evidence[p] = bits.toByte()
            }
        }
        return evidence
    }

    private fun accumulateFrame(
        frame: MergeFrame,
        flow: RawSrAlignmentField?,
        robust: RawSrRobustness.FrameRobustness?,
        num: DoubleArray,
        den: DoubleArray,
        oob: IntArray,
        width: Int,
        height: Int,
        outW: Int,
        outH: Int,
        factor: Double,
        isReference: Boolean,
        chromaZScale: Double,
        onRow: (() -> Boolean)? = null
    ) {
        val samples = frame.samples
        val covariance = frame.covariance.values
        val guideW = frame.covariance.width
        val guideH = frame.covariance.height
        val scaleX = RawSrCoreSampling.guideScale(guideW, width)
        val scaleY = RawSrCoreSampling.guideScale(guideH, height)
        // Hoisted CFA phase table (RED=0, GREEN=1, BLUE=2 — exactly the
        // channel indices): per-tap routing indexes it directly instead of
        // dispatching colorAt per tap. Identical routing, no enum traffic.
        val tapPhase = frame.sensorPattern.cellOrdinals
        // Per-axis coordinate LUTs: sourceCenter/robustnessSamplePos are pure
        // functions of (out, factor), so precomputing once per frame replaces
        // outW*outH divisions with outW+outH loads. Same functions, same
        // args, same doubles — bitwise-identical. flowLookupPos aliases
        // sourceCenter, so the flow lookup reuses the source LUT directly.
        val srcX = DoubleArray(outW) { x -> RawSrCoreSampling.sourceCenter(x, factor) }
        val srcY = DoubleArray(outH) { y -> RawSrCoreSampling.sourceCenter(y, factor) }
        val robX = DoubleArray(outW) { x -> RawSrCoreSampling.robustnessSamplePos(x, factor) }
        val robY = DoubleArray(outH) { y -> RawSrCoreSampling.robustnessSamplePos(y, factor) }
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. Per-pixel
        // scratch is shard-local (no per-pixel allocation).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val scratch = DoubleArray(4)
            val flowScratch = FloatArray(4)
            val tapScratch = DoubleArray(2)
            for (y in y0 until y1) {
                if (onRow?.invoke() == true) throw CancellationException("RAW-SR merge cancelled")
                // Output pixel (x, y) centers on raw source ((x+0.5)/factor),
                // like the reference lr = (hr + 0.5) / scale.
                val sourceBaseY = srcY[y]
                val robBaseY = robY[y]
                for (x in 0 until outW) {
                    val sourceBaseX = srcX[x]
                    val p = y * outW + x
                    val r = if (isReference) 1.0 else {
                        // Reference `cpu_accumulate` robustness fetch
                        // verbatim: nearest quad with the one-quad shift
                        // (min(int(lr//2-0.5))), sampled at the source pixel
                        // center s = (p + 0.5) / factor / 2 - 1.
                        RawSrCoreSampling.sampleRobustness(
                            robust!!.r, robust.width, robust.height,
                            robX[x], robBaseY)
                    }
                    if (r == 0.0) continue
                    val dx: Double
                    val dy: Double
                    if (isReference) {
                        dx = 0.0
                        dy = 0.0
                    } else {
                        // Bilinear flow lookup (Sabre-style dense gather,
                        // beyond the reference `cpu_accumulate` tile snap):
                        // the four surrounding tiles blend with bilinear
                        // weights at the raw source position, so the warp
                        // is C0-continuous and no tile tears can form.
                        // Mistakes are rejected per-pixel by r (computed
                        // from the same bilinear warp), not by flow
                        // vetoes. Non-finite corners fall back to the
                        // containing tile; a non-finite result skips the
                        // pixel, preserving invalid-flow propagation.
                        flow!!.flowAtSmoothInto(
                            sourceBaseX.toFloat(), sourceBaseY.toFloat(), flowScratch)
                        if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                            oob[p]++
                            continue
                        }
                        dx = flowScratch[0].toDouble()
                        dy = flowScratch[1].toDouble()
                    }
                    // Reference lr_mov = lr + flow verbatim: raw-unit flow.
                    val sourceX = sourceBaseX + dx
                    val sourceY = sourceBaseY + dy
                    if (!sourceX.isFinite() || !sourceY.isFinite() ||
                        sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
                    ) {
                        oob[p]++
                        continue
                    }
                    val interpolated = RawSrCoreSampling.interpolateCovariance(
                        covariance, guideW, guideH,
                        RawSrCoreSampling.covarianceGuideCoord(sourceX, scaleX),
                        RawSrCoreSampling.covarianceGuideCoord(sourceY, scaleY),
                        scratch)
                    if (interpolated == null) continue
                    // One cross term per pixel, not per tap: same operands,
                    // same sum, bitwise-identical exponent in the tap loops.
                    val cross = interpolated[1] + interpolated[2]
                    val centerX = floor(sourceX).toInt()
                    val centerY = floor(sourceY).toInt()
                    // Dormant A/B chroma target-green pass (null in the base
                    // path): the kernel-weighted green mean at the source,
                    // using the same spatial weights x r as the main loop.
                    // The reference frame never deweights.
                    val chroma = if (!isReference) frame.chroma else null
                    var targetGreen = Double.NaN
                    if (chroma != null) {
                        var gSum = 0.0
                        var gW = 0.0
                        for (oy in -1..1) for (ox in -1..1) {
                            val tx = centerX + ox
                            val ty = centerY + oy
                            if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                            if (tapPhase[((ty and 1) shl 1) or (tx and 1)] != 1) continue
                            val sample = samples[ty * width + tx].toDouble()
                            if (!sample.isFinite()) continue
                            val distX = tx + 0.5 - sourceX
                            val distY = ty + 0.5 - sourceY
                            val z = RawSrCoreKernel.tapZ(
                                interpolated[0], cross, interpolated[3], distX, distY)
                            if (!RawSrCoreKernel.accumulateTap(
                                    z, r, sample, chromaZScale, true, tapScratch)
                            ) continue
                            gSum += tapScratch[0]
                            gW += tapScratch[1]
                        }
                        if (gW > 0.0) targetGreen = gSum / gW
                    }
                    for (oy in -1..1) for (ox in -1..1) {
                        val tx = centerX + ox
                        val ty = centerY + oy
                        if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                        val sample = samples[ty * width + tx].toDouble()
                        // Every finite sample merges (no censor skip): the
                        // reference defines none. Non-finite taps are
                        // reference-undefined and skipped.
                        if (!sample.isFinite()) continue
                        val distX = tx + 0.5 - sourceX
                        val distY = ty + 0.5 - sourceY
                        val z = RawSrCoreKernel.tapZ(
                            interpolated[0], cross, interpolated[3], distX, distY)
                        // Phase ordinals ARE the channel indices (R=0 G=1 B=2).
                        val channel = tapPhase[((ty and 1) shl 1) or (tx and 1)]
                        // Chroma latch guard: R/B taps see the widened kernel
                        // (z / s^2); green keeps the unscaled z, so the luma
                        // lane is bitwise-identical with or without the guard.
                        if (!RawSrCoreKernel.accumulateTap(
                                z, r, sample, chromaZScale, channel == 1, tapScratch)
                        ) continue
                        val o = p * 3 + channel
                        var numAdd = tapScratch[0]
                        var denAdd = tapScratch[1]
                        // Dormant A/B green-guided R/B gate. Green taps never
                        // gate; without targetGreen the factor is exactly 1.
                        if (chroma != null && channel != 1 && targetGreen.isFinite()) {
                            denAdd *= chromaFactor(
                                frame, samples, width, height, tx, ty, targetGreen, chroma)
                            numAdd = denAdd * sample
                        }
                        num[o] += numAdd
                        den[o] += denAdd
                    }
                }
            }
        }
    }

    /**
     * Burst-nearest sample accumulation for one frame at one output pixel
     * (dormant A/B helper; the reference-parity base path has no
     * burst-nearest fallback): for each of the three channels, the nearest
     * finite UNCENSORED sample whose sensor colour matches the channel, in
     * the floor-centered 3x3 window around (sourceX, sourceY), weighted by
     * [r]. Nearest by texel-center distance squared with a strictly-less
     * update, so the oy-outer/ox-inner loop order deterministically breaks
     * ties (Stacker nearest_cfa_sample rule, plus the censor skip: Stacker
     * samples finite-only, but a clipped white tap must not become a
     * fallback value — see SATURATED_REF_GUARD). Pinned to Stacker
     * v0.1.5-beta (commit 715d949e), app/src/main/cpp/wronski_cpu_merge.cpp:
     * nearest_cfa_sample lines 48-87, merge_cpu_bayer_nearest_burst lines
     * 539-617; parity pinned by StackerNearestParityTest. Internal for that
     * test; not a call-site API.
     */
    internal fun accumulateNearest(
        frame: MergeFrame,
        sourceX: Double,
        sourceY: Double,
        r: Double,
        p: Int,
        width: Int,
        height: Int,
        nearNum: DoubleArray,
        nearDen: DoubleArray
    ) {
        val centerX = floor(sourceX).toInt()
        val centerY = floor(sourceY).toInt()
        val tapPhase = frame.sensorPattern.cellOrdinals
        for (c in 0..2) {
            var best = Double.POSITIVE_INFINITY
            var bestSample = Double.NaN
            for (oy in -1..1) for (ox in -1..1) {
                val tx = centerX + ox
                val ty = centerY + oy
                if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                if (tapPhase[((ty and 1) shl 1) or (tx and 1)] != c) continue
                val sample = frame.samples[ty * width + tx].toDouble()
                // Censored taps carry no trustworthy signal here either: a
                // clipped white tap must not become the fallback value.
                if (!sample.isFinite() || sample >= SATURATED_REF_GUARD) continue
                val dx = tx + 0.5 - sourceX
                val dy = ty + 0.5 - sourceY
                val d2 = dx * dx + dy * dy
                if (d2 < best) {
                    best = d2
                    bestSample = sample
                }
            }
            if (bestSample.isFinite()) {
                val o = p * 3 + c
                nearNum[o] += r * bestSample
                nearDen[o] += r
            }
        }
    }

    /**
     * Dormant A/B `chromaWeight` analogue for one R/B tap: `exp(-0.5 d^2)`
     * with `d = (localGreen - targetGreen) / sigma` and
     * `sigma = max(2.5 sqrt(max(S*signal + O, 0)), 1/160)`.
     * `localGreen` is the mean of the finite green samples in the 3x3 window
     * around the tap (no dense chromaGuide exists on this path, so the guide
     * is estimated from the frame's own green taps); with no green neighbour
     * the factor is exactly 1. Non-finite factors fall back to 1 rather than
     * killing the tap.
     */
    private fun chromaFactor(
        frame: MergeFrame,
        samples: FloatArray,
        width: Int,
        height: Int,
        tx: Int,
        ty: Int,
        targetGreen: Double,
        chroma: ChromaParams
    ): Double {
        var sum = 0.0
        var n = 0
        val tapPhase = frame.sensorPattern.cellOrdinals
        for (oy in -1..1) for (ox in -1..1) {
            val gx = tx + ox
            val gy = ty + oy
            if (gx < 0 || gy < 0 || gx >= width || gy >= height) continue
            if (tapPhase[((gy and 1) shl 1) or (gx and 1)] != 1) continue
            val s = samples[gy * width + gx].toDouble()
            if (!s.isFinite()) continue
            sum += s
            n++
        }
        if (n == 0) return 1.0
        val localGreen = sum / n
        val signal = maxOf(maxOf(localGreen, targetGreen), 0.0)
        val variance = maxOf(chroma.greenNoiseS * signal + chroma.greenNoiseO, 0.0)
        val sigma = maxOf(2.5 * sqrt(variance), 1.0 / 160.0)
        val d = (localGreen - targetGreen) / sigma
        val f = exp(-0.5 * d * d)
        return if (f.isFinite()) f else 1.0
    }

    // Sampling (robustness fetch, covariance interpolation), tap weights,
    // and the finalizer live in [RawSrCoreSampling]/[RawSrCoreKernel]; this
    // file keeps orchestration, sharding, and the dormant A/B helpers.
}
