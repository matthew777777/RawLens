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
 * - Native 1x grid; reference-anchored flow in quad pixels converted with x2
 *   and sampled bilinearly ([RawSrAlignmentField.flowAtSmoothInto]): tile
 *   centers sit at integer lattice points of u = (q + 0.5) / tileSize - 0.5
 *   and the four surrounding tiles blend dx/dy, so tile borders never imprint
 *   the alignment grid on the merge as quilt steps.
 * - Robustness from the quad one up-left of the output pixel (`r_ref = 1`);
 *   `r == 0` skips accumulation. The one-quad shift is the reference
 *   `min(int(lr//2-0.5))` lookup replicated verbatim, not nearest-quad.
 * - Source-anchored bilinear COVARIANCE interpolation at
 *   `g = source*(guide/raw) - 0.5`, inverted per pixel to the precision the
 *   exponent consumes (`z = d_raw^T P d_raw`, raw-unit distances,
 *   `w = exp(-0.5 max(z, 0))`, no additive floor).
 * - 3x3 RAW support with per-tap sensor-coordinate CFA routing via
 *   [BayerPattern.colorAt] (pattern-aware; identical to the reference `rggb`
 *   hardcode on RGGB): `num_c += w*r*c`, `den_c += w*r`, with independent
 *   R/G/B denominators. Ordinary linear camera-RGB numerators (no opponent
 *   transform or baked white balance); unclamped signal policy (negatives
 *   preserved, never clipped); every finite sample merges (no censor skip).
 * - Plain per-channel divide with `den <= eps` reading 0, matching the
 *   reference NaN-at-zero-support blacked downstream (an empty denominator
 *   implies an empty numerator — the same weights feed both — so the only
 *   undefined quotient is 0/0).
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
    /** Per-channel normalization epsilon (merge contract §6). */
    const val EPS = 1e-8

    /**
     * Dormant A/B constants, not consumed by the reference-parity base path:
     * [MOTION_EDGE_QUAD] (merge motion-edge stop), [MIN_SUPPORT]
     * (accumulated-robustness overwrite), [SATURATED_REF_GUARD] (tap censor
     * boundary). The reference defines none of these gates; every finite
     * sample merges and zero support divides to 0.
     */
    const val MOTION_EDGE_QUAD = 1.0f
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
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
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
                    oob, width, height, quadsW, false) {
                    isCancelled?.invoke(rowsDone.incrementAndGet()) == true
                }
                if (isCancelled?.invoke(rowsDone.get()) == true) throw CancellationException("RAW-SR merge cancelled")
            }
            // Per-channel moving-frame evidence: read off the moving-only
            // denominators before the reference contribution joins them below.
            // Bit c of pixel p is set when channel c saw moving-frame support
            // above EPS.
            val channelEvidence = ByteArray(pixels)
            RawSrWorkers.forEachShard(num.size) { i0, i1 ->
                for (i in i0 until i1) {
                    if (den[i] > EPS) {
                        val p = i / 3
                        channelEvidence[p] =
                            (channelEvidence[p].toInt() or (1 shl (i % 3))).toByte()
                    }
                }
            }
            accumulateFrame(reference, null, null, num, den,
                oob, width, height, quadsW, true)
            // Rc over the effective (finite-sanitized) weights, like the
            // reference accumulated-robustness map; identically zero when the
            // moving frames are skipped.
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
            val rc = RawSrRobustness.RcField(quadsW, quadsH, rcValues)
            val rgb = FloatArray(pixels * 3)
            val numerator = FloatArray(pixels * 3)
            val denominator = FloatArray(pixels * 3)
            val fallback = BooleanArray(pixels)
            val refQuotient = FloatArray(pixels * 3)
            // Reference divide (`utils.divide`): plain per-channel quotient,
            // 0 at zero support (the reference NaN blacked downstream).
            // Row-sharded: disjoint rows, bitwise-identical at any count.
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) {
                    for (x in 0 until width) {
                        val p = y * width + x
                        var fellBack = false
                        for (c in 0..2) {
                            val o = p * 3 + c
                            val value = if (den[o] > EPS) {
                                num[o] / maxOf(den[o], EPS)
                            } else {
                                fellBack = true
                                0.0
                            }
                            val finite = if (value.isFinite()) value else 0.0
                            if (!value.isFinite()) fellBack = true
                            rgb[o] = finite.toFloat()
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
        oob: IntArray,
        width: Int,
        height: Int,
        quadsW: Int,
        isReference: Boolean,
        onRow: (() -> Boolean)? = null
    ) {
        val samples = frame.samples
        val covariance = frame.covariance.values
        val guideW = frame.covariance.width
        val guideH = frame.covariance.height
        val scaleX = guideW.toDouble() / width
        val scaleY = guideH.toDouble() / height
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. Per-pixel
        // scratch is shard-local (no per-pixel allocation).
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
                        // Reference bayer lookup verbatim: min(int(lr//2-0.5))
                        // reads the quad one up-left of the output pixel
                        // (max(q-1, 0)), edge-clamped — a genuine off-by-one
                        // in the reference indexing, replicated exactly.
                        val rqX = maxOf(quadX - 1, 0)
                        val rqY = maxOf(quadY - 1, 0)
                        val raw = robust!!.r[rqY * quadsW + rqX]
                        if (raw.isFinite()) raw.toDouble() else 0.0
                    }
                    if (r == 0.0) continue
                    val dxQuad: Double
                    val dyQuad: Double
                    if (isReference) {
                        dxQuad = 0.0
                        dyQuad = 0.0
                    } else {
                        // Bilinear flow sampling: tile centers sit at integer
                        // lattice points of u = (q + 0.5) / tileSize - 0.5 and
                        // the four surrounding tiles blend dx/dy, so tile
                        // borders stay inside alignment and never imprint the
                        // 16px quilt on the merge. Non-finite corners fall
                        // back to the containing (nearest) tile, preserving
                        // invalid-flow propagation.
                        flow!!.flowAtSmoothInto(quadX.toFloat(), quadY.toFloat(), flowScratch)
                        if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                            oob[p]++
                            continue
                        }
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
                    val interpolated = interpolateCovariance(
                        covariance, guideW, guideH, sourceX * scaleX - 0.5, sourceY * scaleY - 0.5, scratch)
                    if (interpolated == null) continue
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
                            if (frame.sensorPattern.colorAt(tx, ty) != CfaColor.GREEN) continue
                            val sample = samples[ty * width + tx].toDouble()
                            if (!sample.isFinite()) continue
                            val distX = tx + 0.5 - sourceX
                            val distY = ty + 0.5 - sourceY
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
                        // Every finite sample merges (no censor skip): the
                        // reference defines none. Non-finite taps are
                        // reference-undefined and skipped.
                        if (!sample.isFinite()) continue
                        val distX = tx + 0.5 - sourceX
                        val distY = ty + 0.5 - sourceY
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
                        // Dormant A/B green-guided R/B gate. Green taps never
                        // gate; without targetGreen the factor is exactly 1.
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
        for (oy in -1..1) for (ox in -1..1) {
            val gx = tx + ox
            val gy = ty + oy
            if (gx < 0 || gy < 0 || gx >= width || gy >= height) continue
            if (frame.sensorPattern.colorAt(gx, gy) != CfaColor.GREEN) continue
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

    /**
     * Reference covariance interpolation + inversion (`merge.py::accumulate`):
     * sign-preserving `modf` fractions, `int()` (truncation) floors clipped
     * at 0, ceilings clipped at the far edge, row-then-column lerp order,
     * then the analytic 2x2 inverse into [out]. Null skips the pixel:
     * non-finite corners and degenerate determinants are reference-undefined
     * (in-domain corners are finite and the interpolation of PD matrices is
     * PD, so the guard never fires on reference-defined inputs).
     */
    private fun interpolateCovariance(
        covariance: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double,
        out: DoubleArray
    ): DoubleArray? {
        if (!gx.isFinite() || !gy.isFinite()) return null
        // Reference modf/int semantics: the fraction keeps the sign (so the
        // sub-center edge extrapolates) and truncation clips at 0.
        // Sign-preserving remainder, exactly like C modf / Python math.modf.
        val fx = gx % 1.0
        val fy = gy % 1.0
        val x0 = (gx - fx).toInt().coerceAtLeast(0)
        val y0 = (gy - fy).toInt().coerceAtLeast(0)
        if (x0 >= guideW || y0 >= guideH) return null
        val x1 = minOf(x0 + 1, guideW - 1)
        val y1 = minOf(y0 + 1, guideH - 1)
        var cxx = 0.0
        var cxy = 0.0
        var cyy = 0.0
        for (c in 0..3) {
            val v00 = covariance[(y0 * guideW + x0) * 4 + c].toDouble()
            val v10 = covariance[(y0 * guideW + x1) * 4 + c].toDouble()
            val v01 = covariance[(y1 * guideW + x0) * 4 + c].toDouble()
            val v11 = covariance[(y1 * guideW + x1) * 4 + c].toDouble()
            if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) return null
            val top = v00 + fx * (v10 - v00)
            val bot = v01 + fx * (v11 - v01)
            val v = top + fy * (bot - top)
            if (c == 0) cxx = v else if (c == 3) cyy = v else if (c == 1) cxy = v
        }
        val det = cxx * cyy - cxy * cxy
        if (!det.isFinite() || det <= 0.0) return null
        out[0] = cyy / det
        out[1] = -cxy / det
        out[2] = -cxy / det
        out[3] = cxx / det
        return out
    }
}
