// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Prompt 4D CPU oracle: Bayer-direct merge (Wronski/IPOL Alg. 4 accumulation
 * geometry: 3x3 support, kernel splats, reference-anchored flow).
 * Implements [docs/raw-sr-merge.md]; the document is normative.
 *
 * Attribution: the accumulation geometry is Jamy-L Alg. 4; the fallback
 * cascade below (reference-only quotient, saturation guard, reference-kernel
 * before burst-nearest, MIN_SUPPORT overwrite) is Stacker/SkyKing-derived,
 * not Jamy-L — Jamy-L leaves zero-support pixels undefined (NaN, later
 * blacked). Do not cite Algs. 4/11 for fallback behavior.
 *
 * Deliberate structure, each pinned by tests:
 * - Ordinary linear camera-RGB numerators with independent per-channel
 *   denominators (no opponent transform or baked white balance). Final
 *   highlight rolloff resolves censored chroma toward the camera neutral.
 * - Native 1x grid; reference-anchored flow in quad pixels converted with x2
 *   and looked up at the nearest tile ([RawSrAlignmentField.flowAt]).
 * - Source-anchored bilinear precision interpolation; quad-unit exponent
 *   `z = d_quad^T P d_quad`, `w = exp(-0.5 z)` with no additive floor.
 * - 3x3 RAW support with per-tap sensor-coordinate CFA routing via
 *   [BayerPattern.colorAt] (all four phases; never an `rggb` hardcode).
 * - Opt-in Sabre-style green-guided chroma deweight ([ChromaParams]): R/B taps
 *   scale by `exp(-0.5 d^2)` with `d = (localGreen - targetGreen) / sigma`,
 *   `sigma = max(2.5 sqrt(max(S*signal + O, 0)), 1/160)`. Null (the default)
 *   preserves legacy behavior bitwise. Green taps, the reference frame, and
 *   burst-nearest accumulation never deweight.
 * - Reference-last order with `r_ref = 1`; reference-only A/B mode runs the
 *   same path with moving frames skipped. Fallback at `den <= eps` takes the
 *   reference-only value; additionally, any quad whose accumulated robustness
 *   is below [MIN_SUPPORT] (less than one frame-equivalent of support) is
 *   overwritten with the reference-only value, mirroring Stacker's
 *   accumulated-robustness overwrite. `Rc` folds through
 *   [RawSrRobustness.accumulate] unchanged over the effective
 *   (finite-sanitized) weights.
 * - Bounded support (Sabre `maximumSupport` analogue): Rc sums [0, 1]
 *   robustness weights, so no quad can exceed one frame-equivalent per
 *   moving frame — the excess is clamped, and per-channel moving-frame
 *   support is exposed as [MergeResult.channelEvidence] for denoising
 *   control (Sabre `support.g/b` analogue). Unbounded Double accumulators
 *   need no cap for normalization itself (quotients, not sums); the cap
 *   guards only the reported support and the downstream noise model.
 * - The merge takes precomputed kernel precision fields and consumes no
 *   tuning: there is no per-scene covariance parameter to tune.
 * - Unclamped signal policy: negative samples are preserved, never clipped.
 * - Scalar Double accumulation over flat arrays; no object is allocated per
 *   pixel and peak working memory is independent of the frame count.
 */
object RawSrBayerMerge {
    /** Per-channel normalization epsilon (merge contract §6). */
    const val EPS = 1e-8

    /**
     * Motion-edge stop (water / occlusion boundaries) in quad pixels:
     * neighbouring tiles disagreeing by more than this mean the bilinear
     * flow blends two motions, so every kernel splat misregisters. Pixels
     * past the stop are skipped like r == 0 (accumulators keep the
     * reference-only value downstream) with no OOB bump — the source
     * exists, the motion does not agree. Frozen: a sensor-geometry rail
     * like SATURATED_REF_GUARD, not tuning; the robustness mTh gate (0.4)
     * already handles sub-pixel irregularity, this catches true
     * discontinuities only.
     */
    const val MOTION_EDGE_QUAD = 1.0f

    /**
     * Minimum accumulated robustness (per quad) for merged output to stand.
     * Quads below half a frame-equivalent of moving-frame support are
     * overwritten with the reference-only value (merge contract §9). Below
     * half a frame the reference already dominates the blend 2:1, so keeps
     * near the threshold stay near the reference and a straddling decision
     * cannot hurt; at the same time the threshold sits clear of the Rc = 1
     * saturation fixed point, where accepted GPU/CPU arithmetic differences
     * would straddle it routinely. This is deliberately NOT matched to
     * Stacker's 1.0: Stacker's pinned overwrite compares a per-pixel
     * accumulated-robustness scalar against a caller max_frame_count whose
     * only 1.0 is a self-test-harness literal with no production caller
     * (Stacker v0.1.5-beta, commit 715d949e) — an incommensurate quantity in
     * an incommensurate context. 0.5 stands on our own threshold A/B
     * (runlog 2026-09-12, supportThresholdABDiscriminatingBandIsGhosty).
     * The rule is inert with no moving frames: reference-only output already
     * equals the reference, and the fallback mask stays clean.
     */
    const val MIN_SUPPORT = 0.5f

    /** Sensor-normalized censor boundary. Censored taps stay out of kernel
     * means; the finalizer uses a shared neighbourhood mask and camera-neutral
     * rolloff to resolve the missing chroma (see [RawSrHighlights]). */
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
        /** Owning frame's precision field on its quad grid (quad-pixel^-2). */
        val precision: RawSrKernelCovariance.MatrixField,
        /** Reference-anchored flow on the quad grid; ignored for the reference frame. */
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
         * Reference-only quotient per pixel and channel (pixels * 3). Valid
         * at fallback pixels, where the finalizer evaluates it for the
         * [SATURATED_REF_GUARD]; zero elsewhere. Lets the 4E Q1 gate excuse
         * the float64/float32 guard-boundary race without re-deriving the
         * quotient from inputs.
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
     * Merge [moving] onto [reference]; the reference merges last with zero
     * shift and unit robustness. With [referenceOnly] the moving frames are
     * skipped on the same path and Rc stays zero. [memory], when supplied,
     * accounts the constant-size working buffers; every allocated byte is
     * released if validation, cancellation, or an unexpected failure aborts
     * the merge. [isCancelled] observes completed scanlines and aborts.
     */
    fun merge(
        reference: MergeFrame,
        moving: List<MergeFrame>,
        referenceOnly: Boolean = false,
        memory: RawSrTextureMemory? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null
    ): MergeResult {
        val frames = if (referenceOnly) emptyList() else moving
        val width = reference.width
        val height = reference.height
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Merge requires an even RAW crop of at least 2x2"
        }
        val quadsW = width / 2
        val quadsH = height / 2
        fun checkFrame(frame: MergeFrame, label: String) {
            require(frame.width == width && frame.height == height) {
                "$label dimensions ${frame.width}x${frame.height} do not match $width x $height"
            }
            require(frame.samples.size == width * height) { "$label samples truncated" }
            require(frame.precision.width == quadsW && frame.precision.height == quadsH) {
                "$label precision grid must match the quad grid"
            }
        }
        checkFrame(reference, "Reference")
        frames.forEachIndexed { index, frame ->
            checkFrame(frame, "Moving $index")
            require(frame.flow != null && frame.robustness != null) { "Moving $index needs flow and robustness" }
            require(frame.flow.imageWidth == quadsW && frame.flow.imageHeight == quadsH) {
                "Moving $index flow grid must match the quad grid"
            }
            require(frame.robustness.width == quadsW && frame.robustness.height == quadsH) {
                "Moving $index robustness grid must match the quad grid"
            }
        }
        val pixels = width * height
        // Accumulator doubles, quad Rc floats, int OOB/fallback lanes, plus
        // the per-pixel channel-evidence byte plane.
        val bytes = pixels * 3L * 8 * 8 + quadsW.toLong() * quadsH * 4 +
            pixels * 4L + pixels + pixels.toLong()
        memory?.allocate(bytes)
        try {
            val num = DoubleArray(pixels * 3)
            val den = DoubleArray(pixels * 3)
            val refNum = DoubleArray(pixels * 3)
            val refDen = DoubleArray(pixels * 3)
            // Burst-nearest accumulators (Stacker-style delta-kernel merge):
            // per-channel robustness-weighted mean of each frame's nearest
            // matching-phase sample. Backs the fallback path below; the
            // kernel accumulators above are untouched.
            val nearNum = DoubleArray(pixels * 3)
            val nearDen = DoubleArray(pixels * 3)
            val nearRefNum = DoubleArray(pixels * 3)
            val nearRefDen = DoubleArray(pixels * 3)
            val oob = IntArray(pixels)
            val rowsDone = java.util.concurrent.atomic.AtomicInteger(0)
            for (frame in frames) {
                val tile = frame.flow!!
                val robust = frame.robustness!!
                accumulateFrame(frame, tile, robust, num, den, nearNum, nearDen,
                    oob, width, height, quadsW, false) {
                    isCancelled?.invoke(rowsDone.incrementAndGet()) == true
                }
                if (isCancelled?.invoke(rowsDone.get()) == true) throw CancellationException("RAW-SR merge cancelled")
            }
            accumulateFrame(reference, null, null, refNum, refDen, nearRefNum, nearRefDen,
                oob, width, height, quadsW, true)
            // Per-channel moving-frame evidence (Sabre support.g/b analogue):
            // read off the moving-only denominators before the reference
            // contribution joins them below. Bit c of pixel p is set when
            // channel c saw moving-frame support above EPS.
            val channelEvidence = ByteArray(pixels)
            RawSrWorkers.forEachShard(num.size) { i0, i1 ->
                for (i in i0 until i1) {
                    if (den[i] > EPS) {
                        val p = i / 3
                        channelEvidence[p] =
                            (channelEvidence[p].toInt() or (1 shl (i % 3))).toByte()
                    }
                    num[i] += refNum[i]
                    den[i] += refDen[i]
                    nearNum[i] += nearRefNum[i]
                    nearDen[i] += nearRefDen[i]
                }
            }
            // Fused sanitize+accumulate: one alloc and one pass per frame
            // (was: sanitized copy plus accumulate's own copy). Same order,
            // same addition — bitwise-identical.
            var rcValues = FloatArray(quadsW * quadsH)
            for (frame in frames) {
                val robust = frame.robustness!!
                val next = rcValues.copyOf()
                val r = robust.r
                for (i in next.indices) {
                    val v = r[i]
                    next[i] += if (v.isFinite()) v else 0f
                }
                rcValues = next
            }
            if (referenceOnly) rcValues = FloatArray(quadsW * quadsH)
            // Bounded support (Sabre maximumSupport analogue): Rc sums
            // [0, 1] robustness weights, so no quad exceeds one
            // frame-equivalent per moving frame. Clamp the excess — reachable
            // only through out-of-contract inputs (production weights never
            // exceed 1), so the MIN_SUPPORT overwrite below (0.5) and the
            // reference-only path (identically zero) are untouched. The
            // support estimate and the noise model downstream never exceed
            // the true burst size, instead of understating noise.
            if (frames.isNotEmpty()) {
                val cap = frames.size.toFloat()
                for (i in rcValues.indices) {
                    if (rcValues[i] > cap) rcValues[i] = cap
                }
            }
            val rc = RawSrRobustness.RcField(quadsW, quadsH, rcValues)
            val rgb = FloatArray(pixels * 3)
            val numerator = FloatArray(pixels * 3)
            val denominator = FloatArray(pixels * 3)
            val fallback = BooleanArray(pixels)
            val refQuotient = FloatArray(pixels * 3)
            // Accumulated-robustness overwrite (§9): a quad with less than one
            // frame-equivalent of moving-frame support cannot carry merged
            // detail, so every pixel it covers takes the reference-only value
            // and joins the fallback set. Skipped with no moving frames, where
            // the output already equals the reference.
            val overwrite = frames.isNotEmpty()
            // Row-sharded finalizer: shards cover disjoint output rows with the
            // serial per-pixel code untouched (bitwise-identical at any worker
            // count). Hoists the per-row quad base out of the pixel loop.
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) {
                    val quadRow = (y / 2) * quadsW
                    for (x in 0 until width) {
                        val p = y * width + x
                        val quad = quadRow + (x / 2)
                        val unsupported = overwrite && rc.values[quad] < MIN_SUPPORT
                        var fellBack = unsupported
                        val highlightPeak = RawSrHighlights.peak(reference.samples, width, height, x, y)
                        if (RawSrHighlights.amount(highlightPeak) > 0.0) fellBack = true
                        // No moving-frame support at all at this quad (every moving
                        // frame rejected or skipped): the burst contributes nothing,
                        // so the reference kernel quotient stands — the shared-weight
                        // mean stays achromatic where per-channel nearest picks would
                        // straddle edges and invent chroma.
                        val movSupported = rc.values[quad].toDouble() > EPS
                        for (c in 0..2) {
                            val o = p * 3 + c
                            val value = if (!unsupported && den[o] > EPS) {
                                num[o] / maxOf(den[o], EPS)
                            } else {
                                if (den[o] <= EPS) fellBack = true
                                val refValue = refNum[o] / maxOf(refDen[o], EPS)
                                refQuotient[o] = refValue.toFloat()
                                // Fallback cascade: clipped reference channels stay
                                // honest (saturation guard); with no moving
                                // support at all the shared-weight reference
                                // quotient stands; otherwise the reference
                                // kernel mean precedes burst-nearest (see
                                // above); nearest is last resort before the
                                // reference value.
                                if (refValue >= SATURATED_REF_GUARD) refValue
                                else if (!movSupported) refValue
                                // Reference-kernel fallback before burst-nearest:
                                // the reference-only quotient is a full 3x3
                                // kernel mean (smooth, honest single-frame
                                // value), while per-channel nearest picks can
                                // straddle high-contrast edges and invent
                                // chroma speckles there (lamp-clip contours).
                                // Nearest survives solely where the reference
                                // kernel itself has no support. Deliberate
                                // deviation from Stacker's nearest-first order,
                                // pinned by partialSupportResolvesReferenceKernel.
                                else if (refDen[o] > EPS) refValue
                                else if (nearDen[o] > EPS) nearNum[o] / maxOf(nearDen[o], EPS)
                                else refValue
                            }
                            val finite = if (value.isFinite()) value else 0.0
                            rgb[o] = RawSrHighlights.resolve(finite, highlightPeak, reference.highlightNeutral[c]).toFloat()
                            numerator[o] = num[o].toFloat().let { if (it.isFinite()) it else 0f }
                            denominator[o] = den[o].toFloat().let { if (it.isFinite()) it else 0f }
                        }
                        fallback[p] = fellBack
                    }
                }
            }
            val support = FloatArray(quadsW * quadsH) { i ->
                val v = (1.0 + rc.values[i]).toFloat()
                if (v.isFinite()) v else 0f
            }
            return MergeResult(width, height, rgb, numerator, denominator, rc, support, oob, fallback,
                refQuotient, channelEvidence)
        } catch (t: Throwable) {
            memory?.release(bytes)
            throw t
        }
    }

    private fun accumulateFrame(
        frame: MergeFrame,
        flow: RawSrAlignmentField?,
        robust: RawSrRobustness.FrameRobustness?,
        num: DoubleArray,
        den: DoubleArray,
        nearNum: DoubleArray,
        nearDen: DoubleArray,
        oob: IntArray,
        width: Int,
        height: Int,
        quadsW: Int,
        isReference: Boolean,
        onRow: (() -> Boolean)? = null
    ) {
        val samples = frame.samples
        val precision = frame.precision.values
        val guideW = frame.precision.width
        val guideH = frame.precision.height
        val scaleX = guideW.toDouble() / width
        val scaleY = guideH.toDouble() / height
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. Per-pixel
        // scratch (precision + flow) is shard-local (was: one DoubleArray and
        // ~6 boxed flows per pixel, a GC hotspot at full res).
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val scratch = DoubleArray(4)
            val flowScratch = FloatArray(4)
            for (y in y0 until y1) {
                if (onRow?.invoke() == true) throw CancellationException("RAW-SR merge cancelled")
                val quadY = y / 2
                for (x in 0 until width) {
                    val quadX = x / 2
                    val p = y * width + x
                    val r = if (isReference) 1.0 else {
                        val raw = robust!!.r[quadY * quadsW + quadX]
                        if (raw.isFinite()) raw.toDouble() else 0.0
                    }
                    if (r == 0.0) continue
                    val dxQuad: Double
                    val dyQuad: Double
                    if (isReference) {
                        dxQuad = 0.0
                        dyQuad = 0.0
                    } else {
                        // Smooth (bilinear) sampling: tile borders must not reach the merge.
                        flow!!.flowAtSmoothInto(quadX.toFloat(), quadY.toFloat(), flowScratch)
                        if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                            oob[p]++
                            continue
                        }
                        // Motion-edge stop: same 3x3 tile-spread test as the
                        // robustness irregularity gate, at discontinuity
                        // scale — mirrored in merge_accumulate.glsl. Only
                        // demonstrated disagreement vetoes (see
                        // flowDisagrees): unknown tiles merge, with the
                        // robustness gates as backstop.
                        if (RawSrRobustness.flowDisagrees(flow, quadX, quadY, MOTION_EDGE_QUAD)) continue
                        dxQuad = flowScratch[0].toDouble()
                        dyQuad = flowScratch[1].toDouble()
                    }
                    val sourceX = x + 0.5 + 2.0 * dxQuad
                    val sourceY = y + 0.5 + 2.0 * dyQuad
                    if (!sourceX.isFinite() || !sourceY.isFinite() ||
                        sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
                    ) {
                        oob[p]++
                        continue
                    }
                    // Burst-nearest accumulation (Stacker delta-kernel rule): per
                    // channel, the nearest finite sample of the channel's colour
                    // in the floor-centered 3x3 window, weighted by robustness.
                    // Nearest by texel-center distance; strictly-less update, so
                    // the oy-outer/ox-inner loop order is the deterministic
                    // tie-break (mirrored in merge_accumulate.glsl).
                    accumulateNearest(frame, sourceX, sourceY, r, p, width, height, nearNum, nearDen)
                    val interpolated = interpolatePrecision(
                        precision, guideW, guideH, sourceX * scaleX - 0.5, sourceY * scaleY - 0.5, scratch)
                    if (interpolated == null) continue
                    val centerX = floor(sourceX).toInt()
                    val centerY = floor(sourceY).toInt()
                    // Chroma target-green pass (Sabre two-loop structure):
                    // the kernel-weighted green mean at the source, using the
                    // same spatial weights x r as the main loop. Null chroma
                    // (or no green support) leaves targetGreen non-finite and
                    // the main loop below runs ungated, bitwise-identical to
                    // legacy. The reference frame never deweights (chroma is
                    // moving-frame only, like Sabre's frameWeight path).
                    val chroma = if (!isReference) frame.chroma else null
                    var targetGreen = Double.NaN
                    if (chroma != null) {
                        var gSum = 0.0
                        var gW = 0.0
                        for (oy in -1..1) for (ox in -1..1) {
                            val tx = centerX + ox
                            val ty = centerY + oy
                            if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                            if (frame.sensorPattern.colorAt(tx, ty) != CfaColor.GREEN) continue
                            val sample = samples[ty * width + tx].toDouble()
                            if (!sample.isFinite() || sample >= SATURATED_REF_GUARD) continue
                            val distX = (tx + 0.5 - sourceX) / 2.0
                            val distY = (ty + 0.5 - sourceY) / 2.0
                            val z = interpolated[0] * distX * distX +
                                (interpolated[1] + interpolated[2]) * distX * distY +
                                interpolated[3] * distY * distY
                            if (!z.isFinite()) continue
                            val weight = exp(-0.5 * maxOf(z, 0.0))
                            if (!weight.isFinite()) continue
                            gSum += weight * r * sample
                            gW += weight * r
                        }
                        if (gW > 0.0) targetGreen = gSum / gW
                    }
                    for (oy in -1..1) for (ox in -1..1) {
                        val tx = centerX + ox
                        val ty = centerY + oy
                        if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                        val sample = samples[ty * width + tx].toDouble()
                        // Censored taps (>= SATURATED_REF_GUARD) carry no
                        // trustworthy signal (SkyKing CENSORED_UNKNOWN_CHROMA):
                        // clipped values must not bleed through kernel means —
                        // not even from the reference at r = 1. The nearest path
                        // below keeps finite-only sampling (Stacker parity).
                        if (!sample.isFinite() || sample >= SATURATED_REF_GUARD) continue
                        val distX = (tx + 0.5 - sourceX) / 2.0
                        val distY = (ty + 0.5 - sourceY) / 2.0
                        val z = interpolated[0] * distX * distX +
                            (interpolated[1] + interpolated[2]) * distX * distY +
                            interpolated[3] * distY * distY
                        if (!z.isFinite()) continue
                        val weight = exp(-0.5 * maxOf(z, 0.0))
                        if (!weight.isFinite()) continue
                        val channel = when (frame.sensorPattern.colorAt(tx, ty)) {
                            CfaColor.RED -> 0
                            CfaColor.GREEN -> 1
                            CfaColor.BLUE -> 2
                        }
                        val o = p * 3 + channel
                        var weighted = weight * r
                        // Green-guided R/B gate (Sabre chromaWeight): taps
                        // whose local green disagrees with the target green
                        // straddle a luma edge, so their chroma would smear —
                        // scale them down. Green taps never gate (channel 1
                        // skips); without targetGreen the factor is exactly 1.
                        if (chroma != null && channel != 1 && targetGreen.isFinite()) {
                            weighted *= chromaFactor(
                                frame, samples, width, height, tx, ty, targetGreen, chroma)
                        }
                        num[o] += weighted * sample
                        den[o] += weighted
                    }
                }
            }
        }
    }

    /**
     * Burst-nearest sample accumulation for one frame at one output pixel:
     * for each of the three channels, the nearest finite UNCENSORED sample
     * whose sensor colour matches the channel, in the floor-centered 3x3
     * window around (sourceX, sourceY), weighted by [r]. Nearest by
     * texel-center distance squared with a strictly-less update, so the
     * oy-outer/ox-inner loop order deterministically breaks ties (Stacker
     * nearest_cfa_sample rule, mirrored in merge_accumulate.glsl, plus the
     * censor skip: Stacker samples finite-only, but a clipped white tap must
     * not become a fallback value — see SATURATED_REF_GUARD).
     * squared with a strictly-less update, so the oy-outer/ox-inner loop
     * order deterministically breaks ties (Stacker nearest_cfa_sample rule,
     * mirrored in merge_accumulate.glsl). Pinned to Stacker v0.1.5-beta
     * (commit 715d949e), app/src/main/cpp/wronski_cpu_merge.cpp:
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
        for (c in 0..2) {
            var best = Double.POSITIVE_INFINITY
            var bestSample = Double.NaN
            for (oy in -1..1) for (ox in -1..1) {
                val tx = centerX + ox
                val ty = centerY + oy
                if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                val channel = when (frame.sensorPattern.colorAt(tx, ty)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
                if (channel != c) continue
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
     * Sabre `chromaWeight` analogue for one R/B tap: `exp(-0.5 d^2)` with
     * `d = (localGreen - targetGreen) / sigma` and
     * `sigma = max(2.5 sqrt(max(S*signal + O, 0)), 1/160)`.
     * `localGreen` is the mean of the finite uncensored green samples in the
     * 3x3 window around the tap (our frames carry no dense Sabre-style
     * chromaGuide, so the guide is estimated from the frame's own green
     * taps); with no green neighbour the factor is exactly 1. Non-finite
     * factors fall back to 1 rather than killing the tap.
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
        for (oy in -1..1) for (ox in -1..1) {
            val gx = tx + ox
            val gy = ty + oy
            if (gx < 0 || gy < 0 || gx >= width || gy >= height) continue
            if (frame.sensorPattern.colorAt(gx, gy) != CfaColor.GREEN) continue
            val s = samples[gy * width + gx].toDouble()
            if (!s.isFinite() || s >= SATURATED_REF_GUARD) continue
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

    private fun interpolatePrecision(
        precision: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double,
        out: DoubleArray
    ): DoubleArray? {
        if (!gx.isFinite() || !gy.isFinite()) return null
        val cx = gx.coerceIn(0.0, (guideW - 1).toDouble())
        val cy = gy.coerceIn(0.0, (guideH - 1).toDouble())
        val x0 = floor(cx).toInt().coerceIn(0, guideW - 1)
        val y0 = floor(cy).toInt().coerceIn(0, guideH - 1)
        val x1 = minOf(x0 + 1, guideW - 1)
        val y1 = minOf(y0 + 1, guideH - 1)
        val fx = cx - x0
        val fy = cy - y0
        for (c in 0..3) {
            val v00 = precision[(y0 * guideW + x0) * 4 + c].toDouble()
            val v10 = precision[(y0 * guideW + x1) * 4 + c].toDouble()
            val v01 = precision[(y1 * guideW + x0) * 4 + c].toDouble()
            val v11 = precision[(y1 * guideW + x1) * 4 + c].toDouble()
            if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) return null
            out[c] = v00 * (1.0 - fx) * (1.0 - fy) + v10 * fx * (1.0 - fy) +
                v01 * (1.0 - fx) * fy + v11 * fx * fy
        }
        return out
    }
}
