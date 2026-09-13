// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.floor

/**
 * Prompt 4D CPU oracle: Bayer-direct merge (Wronski/IPOL Alg. 4 accumulation).
 * Implements [docs/raw-sr-merge.md]; the document is normative.
 *
 * Deliberate structure, each pinned by tests:
 * - Ordinary linear camera-RGB numerators with independent per-channel
 *   denominators (no opponent transform, no white balance, no highlight
 *   reconstruction in v1).
 * - Native 1x grid; reference-anchored flow in quad pixels converted with x2
 *   and looked up at the nearest tile ([RawSrAlignmentField.flowAt]).
 * - Source-anchored bilinear precision interpolation; quad-unit exponent
 *   `z = d_quad^T P d_quad`, `w = exp(-0.5 z)` with no additive floor.
 * - 3x3 RAW support with per-tap sensor-coordinate CFA routing via
 *   [BayerPattern.colorAt] (all four phases; never an `rggb` hardcode).
 * - Reference-last order with `r_ref = 1`; reference-only A/B mode runs the
 *   same path with moving frames skipped. Fallback at `den <= eps` takes the
 *   reference-only value; additionally, any quad whose accumulated robustness
 *   is below [MIN_SUPPORT] (less than one frame-equivalent of support) is
 *   overwritten with the reference-only value, mirroring Stacker's
 *   accumulated-robustness overwrite. `Rc` folds through
 *   [RawSrRobustness.accumulate] unchanged over the effective
 *   (finite-sanitized) weights.
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

    /**
     * Saturation censor level (SkyKing CENSORED_UNKNOWN_CHROMA): a normalised
     * tap at or above this level carries no trustworthy signal. Three duties:
     * censored taps never enter kernel means (any frame, including the
     * reference at r = 1 — reference self-bleed painted the lamp halo);
     * a censored reference site outputs its measured clip for all channels
     * (highlight desaturation to white, exact); and the fallback path keeps
     * the reference value instead of the burst-nearest blend there.
     * Robustness already forces r == 0 at railed quads; without the kernel
     * exclusion the unattenuated means smear misregistered neighbours into
     * highlights (device-measured deviations up to ~1.2 with dark speckle
     * down to 0.19). Deliberate, documented deviation from pure Stacker
     * parity (whose nearest rule stays finite-only); mirrored in
     * merge_finalize.glsl and MosaicSrReconstructor. Matches the 4E Q6
     * saturation level. Not tuning: a sensor-physics rail, frozen.
     */
    const val SATURATED_REF_GUARD = 0.99

    data class MergeFrame(
        val width: Int,
        val height: Int,
        /** Black-subtracted, white-normalized, lens-shaded linear samples, unclamped. */
        val samples: FloatArray,
        /** Full-sensor Bayer pattern; local routing folds the crop origin below. */
        val sensorPattern: BayerPattern,
        /** Full-sensor coordinates of samples[0]. */
        val sensorLeft: Int,
        val sensorTop: Int,
        /** Owning frame's precision field on its quad grid (quad-pixel^-2). */
        val precision: RawSrKernelCovariance.MatrixField,
        /** Reference-anchored flow on the quad grid; ignored for the reference frame. */
        val flow: RawSrAlignmentField?,
        /** Reference-anchored robustness on the quad grid; ignored for the reference frame. */
        val robustness: RawSrRobustness.FrameRobustness?
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
        val refQuotient: FloatArray = FloatArray(0)
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
        val bytes = pixels * 3L * 8 * 8 + quadsW.toLong() * quadsH * 4 +
            pixels * 4L + pixels
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
            var rowsDone = 0
            for (frame in frames) {
                val tile = frame.flow!!
                val robust = frame.robustness!!
                accumulateFrame(frame, tile, robust, num, den, nearNum, nearDen,
                    oob, width, height, quadsW, false) {
                    rowsDone++
                    isCancelled?.invoke(rowsDone) == true
                }
                if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("RAW-SR merge cancelled")
            }
            accumulateFrame(reference, null, null, refNum, refDen, nearRefNum, nearRefDen,
                oob, width, height, quadsW, true)
            for (i in num.indices) {
                num[i] += refNum[i]
                den[i] += refDen[i]
                nearNum[i] += nearRefNum[i]
                nearDen[i] += nearRefDen[i]
            }
            var rc = RawSrRobustness.RcField(quadsW, quadsH, FloatArray(quadsW * quadsH))
            for (frame in frames) {
                val robust = frame.robustness!!
                val sanitized = FloatArray(robust.r.size) { i ->
                    val v = robust.r[i]
                    if (v.isFinite()) v else 0f
                }
                rc = RawSrRobustness.accumulate(
                    rc, RawSrRobustness.FrameRobustness(robust.width, robust.height, sanitized, robust.flags))
            }
            if (referenceOnly) rc = RawSrRobustness.RcField(quadsW, quadsH, FloatArray(quadsW * quadsH))
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
            for (p in 0 until pixels) {
                val quad = (p / width / 2) * quadsW + (p % width / 2)
                val unsupported = overwrite && rc.values[quad] < MIN_SUPPORT
                var fellBack = unsupported
                // Censored-site white: the reference tap at this site is
                // clipped (>= SATURATED_REF_GUARD), so its true value is
                // unknown-but-bright; the honest output is the measured clip
                // for all three channels (highlight desaturation to white),
                // never a kernel mean polluted by neighbours nor a nearest
                // blend mixing sides. Takes precedence over every path below
                // and joins the fallback set (it is a reference-direct
                // override, not a kernel blend).
                val siteDirect = reference.samples[p].toDouble()
                val siteWhite = siteDirect >= SATURATED_REF_GUARD
                if (siteWhite) fellBack = true
                // No moving-frame support at all at this quad (every moving
                // frame rejected or skipped): the burst contributes nothing,
                // so the reference kernel quotient stands — the shared-weight
                // mean stays achromatic where per-channel nearest picks would
                // straddle edges and invent chroma.
                val movSupported = rc.values[quad].toDouble() > EPS
                for (c in 0..2) {
                    val o = p * 3 + c
                    val value = if (siteWhite) {
                        siteDirect
                    } else if (!unsupported && den[o] > EPS) {
                        num[o] / maxOf(den[o], EPS)
                    } else {
                        if (den[o] <= EPS) fellBack = true
                        val refValue = refNum[o] / maxOf(refDen[o], EPS)
                        refQuotient[o] = refValue.toFloat()
                        // Burst-nearest fallback (Stacker): where the kernel
                        // merge is untrustworthy, average the nearest
                        // matching-phase samples across the burst instead of
                        // smearing kernels — no covariance, no
                        // interpolation. Nested ref-only fallback where even
                        // the nearest path has no support, where the
                        // reference channel is clipped (SATURATED_REF_GUARD),
                        // or where the burst carries no moving support at all.
                        if (refValue >= SATURATED_REF_GUARD) refValue
                        else if (!movSupported) refValue
                        else if (nearDen[o] > EPS) nearNum[o] / maxOf(nearDen[o], EPS)
                        else refValue
                    }
                    val finite = if (value.isFinite()) value else 0.0
                    rgb[o] = finite.toFloat()
                    numerator[o] = num[o].toFloat().let { if (it.isFinite()) it else 0f }
                    denominator[o] = den[o].toFloat().let { if (it.isFinite()) it else 0f }
                }
                fallback[p] = fellBack
            }
            val support = FloatArray(quadsW * quadsH) { i ->
                val v = (1.0 + rc.values[i]).toFloat()
                if (v.isFinite()) v else 0f
            }
            return MergeResult(width, height, rgb, numerator, denominator, rc, support, oob, fallback,
                refQuotient)
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
        for (y in 0 until height) {
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
                    val tile = flow!!.flowAtSmooth(quadX.toFloat(), quadY.toFloat())
                    if (!tile.dx.isFinite() || !tile.dy.isFinite()) {
                        oob[p]++
                        continue
                    }
                    dxQuad = tile.dx.toDouble()
                    dyQuad = tile.dy.toDouble()
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
                    precision, guideW, guideH, sourceX * scaleX - 0.5, sourceY * scaleY - 0.5)
                if (interpolated == null) continue
                val centerX = floor(sourceX).toInt()
                val centerY = floor(sourceY).toInt()
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
                    val channel = when (frame.sensorPattern.colorAt(
                        frame.sensorLeft + tx, frame.sensorTop + ty)) {
                        CfaColor.RED -> 0
                        CfaColor.GREEN -> 1
                        CfaColor.BLUE -> 2
                    }
                    val o = p * 3 + channel
                    val weighted = weight * r
                    num[o] += weighted * sample
                    den[o] += weighted
                }
            }
        }
    }

    /**
     * Burst-nearest sample accumulation for one frame at one output pixel:
     * for each of the three channels, the nearest finite sample whose sensor
     * colour matches the channel, in the floor-centered 3x3 window around
     * (sourceX, sourceY), weighted by [r]. Nearest by texel-center distance
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
                val channel = when (frame.sensorPattern.colorAt(
                    frame.sensorLeft + tx, frame.sensorTop + ty)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
                if (channel != c) continue
                val sample = frame.samples[ty * width + tx].toDouble()
                if (!sample.isFinite()) continue
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

    private fun interpolatePrecision(
        precision: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double
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
        val out = DoubleArray(4)
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
