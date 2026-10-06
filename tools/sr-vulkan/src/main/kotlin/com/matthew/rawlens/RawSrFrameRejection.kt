// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Shared super-resolution frame-rejection policy (phone + desktop, linear +
 * mosaic). One tuning surface so Android (`app/`) and desktop
 * (`tools/sr-vulkan`, `linear-sr-desktop`, `mosaic-desktop`) reject identical
 * frames for identical bursts.
 *
 * Sources:
 * - Hasinoff et al. 2016 "Burst photography for high dynamic range and
 *   low-light imaging" (HDR+): §5 robust temporal merge, N=2..8 frames
 *   preferred; tile-RMS note (high-contrast tiles filter harder); tuning
 *   factor c≈8 on the Wiener noise term (our [HdrTileDeghost] `strength`
 *   default 8f is that c-factor).
 * - Wronski et al. 2019 "Handheld multi-frame super-resolution" (Pixel 3):
 *   per-tile robustness R with 3x3 flow-span motion prior M vs threshold Mth
 *   (s1 motion scale vs s2 static scale), 5x5 local-minimum support.
 * - Liba et al. 2019 (Night Sight): spatially-varying temporal merge,
 *   mismatch-gated static/motion adaptation.
 *
 * The SR path stays constant-exposure by design ([RawSrBurstPlanner]
 * MAX_EXPOSURE_RATIO 1.10 gate documents the HDR+ constant-exposure capture
 * policy). Multi-EV brackets route to the HDR path ([HdrRawMerge] +
 * [HdrTileDeghost] + [HdrBracketAligner]); see [HdrBracketConfig].
 *
 * Rejection runs in two stages, both shared by the linear and mosaic outputs
 * (they consume the same [RawSrMergeJob] chain, so gates live once in
 * [RawSrBurstPlanner.plan] and [RawSrMergeJob.buildMovingFrame]):
 *  1. Planner (pre-merge, CPU preview): saturation, displacement,
 *     registration, exposure, unsharp (relative sharpness), overflow (N-cap).
 *  2. Merge (per-frame, post-align): reliable-tile fraction, mean robustness
 *     R, support fraction (Wronski 5x5-min R^ support), median flow-span veto
 *     for global handshake/rolling-shutter.
 */
object RawSrFrameRejection {
    /**
     * HDR+ preferred burst size (Hasinoff §5: N=2..8). The planner keeps the
     * reference plus the (N-1) lowest-travel frames and rejects the overflow
     * as OVERFLOW. Input bursts up to 30 still validate; only the merge set
     * is capped. Default only: the phone SR path overrides the cap with the
     * ZSL-selected burst size, and desktop caps only via --max-frames.
     */
    const val MAX_MERGE_FRAMES = 8

    /**
     * Relative sharpness gate: a frame whose sampled sharpness falls below
     * this fraction of the reference sharpness rejects as UNSHARP. Absolute
     * sharpness varies wildly by scene (0.4 in flat light vs 1.0 on texture),
     * so the gate is relative — matching the raymerge log's Unsharp verdict
     * but anchored to the burst's own reference instead of a fixed floor.
     */
    const val MIN_SHARPNESS_RATIO = 0.25

    /**
     * Minimum fraction of alignment tiles marked reliable. Below this the
     * frame contributes almost no trusted warp and merges as smear; reject.
     */
    const val MIN_RELIABLE_FRACTION = 0.05

    /**
     * Minimum mean robustness R over the quad grid (Wronski Alg. 6 output
     * after the 5x5 local minimum). A frame with tiny mean R is almost fully
     * photo-conflicted or out-of-bounds; keeping it only adds noise.
     */
    const val MIN_MEAN_ROBUSTNESS = 0.02

    /**
     * Minimum fraction of quads with R above [SUPPORT_THRESHOLD] (Wronski 5x5
     * local-minimum support). Distinct from the mean: a frame with a small
     * sharp island (high R on few quads, zero elsewhere) passes the mean gate
     * but fails support — and vice versa for a uniformly misted frame.
     */
    const val MIN_SUPPORT_FRACTION = 0.05
    const val SUPPORT_THRESHOLD = 0.05f

    /**
     * Frame-level motion-prior veto (median 3x3 flow-span in raw pixels).
     * Per-quad s1/s2 scaling already handles local motion inside
     * [RawSrRobustness]; this veto catches GLOBAL handshake/rolling-shutter
     * where the median tile span dwarfs Mth (default Mth 0.8 raw px).
     * Conservative: 8 raw px — only extreme bursts trip it.
     */
    const val MAX_MEDIAN_FLOW_SPAN_PX = 8.0f

    /** Tunable copy of the defaults above (CLI flags / A/B). */
    data class Policy(
        val maxMergeFrames: Int = MAX_MERGE_FRAMES,
        val minSharpnessRatio: Double = MIN_SHARPNESS_RATIO,
        val minReliableFraction: Double = MIN_RELIABLE_FRACTION,
        val minMeanRobustness: Double = MIN_MEAN_ROBUSTNESS,
        val minSupportFraction: Double = MIN_SUPPORT_FRACTION,
        val supportThreshold: Float = SUPPORT_THRESHOLD,
        val maxMedianFlowSpanPx: Float = MAX_MEDIAN_FLOW_SPAN_PX
    ) {
        init {
            require(maxMergeFrames in 2..30) { "maxMergeFrames must be 2..30" }
            require(minSharpnessRatio in 0.0..1.0) { "minSharpnessRatio must be 0..1" }
            require(minReliableFraction in 0.0..1.0) { "minReliableFraction must be 0..1" }
            require(minMeanRobustness in 0.0..1.0) { "minMeanRobustness must be 0..1" }
            require(minSupportFraction in 0.0..1.0) { "minSupportFraction must be 0..1" }
            require(maxMedianFlowSpanPx.isFinite() && maxMedianFlowSpanPx > 0f) {
                "maxMedianFlowSpanPx must be finite and positive"
            }
        }
    }

    /** Merge-time verdict with the measured support numbers for logging. */
    data class Verdict(
        val reliableFraction: Double,
        val meanRobustness: Double,
        val supportFraction: Double,
        val medianFlowSpanPx: Float,
        /** Null means keep; non-null names the rejection gate. */
        val rejectReason: String?
    ) {
        val keep: Boolean get() = rejectReason == null
    }

    fun reliableFraction(field: RawSrAlignmentField): Double {
        val direct = field.tiles as? RawSrDirectFlowTiles
        var reliable = 0
        if (direct != null) {
            for (i in 0 until field.tiles.size) if (direct.directReliable(i)) reliable++
        } else {
            for (tile in field.tiles) if (tile.reliable) reliable++
        }
        return if (field.tiles.isEmpty()) 0.0 else reliable.toDouble() / field.tiles.size
    }

    fun meanRobustness(r: FloatArray): Double {
        if (r.isEmpty()) return 0.0
        var sum = 0.0
        for (v in r) sum += if (v.isFinite()) v.toDouble().coerceIn(0.0, 1.0) else 0.0
        return sum / r.size
    }

    fun supportFraction(r: FloatArray, threshold: Float = SUPPORT_THRESHOLD): Double {
        if (r.isEmpty()) return 0.0
        var n = 0
        for (v in r) if (v.isFinite() && v >= threshold) n++
        return n.toDouble() / r.size
    }

    /**
     * Median 3x3 flow-span over reliable tiles (max - min of dx and dy in the
     * tile's 3x3 neighborhood, span = max(dxSpan, dySpan)). Unreliable tiles
     * contribute nothing; with no reliable tile the span is +Inf (the
     * reliable-fraction gate already rejects that frame).
     */
    fun medianFlowSpanPx(field: RawSrAlignmentField): Float {
        val cols = field.columns
        val rows = field.rows
        if (cols <= 0 || rows <= 0) return Float.POSITIVE_INFINITY
        val direct = field.tiles as? RawSrDirectFlowTiles
        fun dx(i: Int): Float = direct?.directDx(i) ?: field.tiles[i].dx
        fun dy(i: Int): Float = direct?.directDy(i) ?: field.tiles[i].dy
        fun rel(i: Int): Boolean = direct?.directReliable(i) ?: field.tiles[i].reliable
        val spans = ArrayList<Float>()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val center = ty * cols + tx
            if (!rel(center)) continue
            var minDx = Float.MAX_VALUE
            var maxDx = -Float.MAX_VALUE
            var minDy = Float.MAX_VALUE
            var maxDy = -Float.MAX_VALUE
            for (oy in -1..1) for (ox in -1..1) {
                val nx = (tx + ox).coerceIn(0, cols - 1)
                val ny = (ty + oy).coerceIn(0, rows - 1)
                val i = ny * cols + nx
                val vx = dx(i)
                val vy = dy(i)
                if (!vx.isFinite() || !vy.isFinite()) continue
                if (vx < minDx) minDx = vx
                if (vx > maxDx) maxDx = vx
                if (vy < minDy) minDy = vy
                if (vy > maxDy) maxDy = vy
            }
            if (minDx <= maxDx && minDy <= maxDy) {
                spans.add(maxOf(maxDx - minDx, maxDy - minDy))
            }
        }
        if (spans.isEmpty()) return Float.POSITIVE_INFINITY
        spans.sort()
        return if (spans.size % 2 == 1) spans[spans.size / 2]
        else (spans[spans.size / 2 - 1] + spans[spans.size / 2]) * 0.5f
    }

    /**
     * Merge-time gate shared by [RawSrMergeJob.mosaicChain] and
     * [RawSrMergeJob.mosaicStream] (both call it via `buildMovingFrame`, so
     * linear and mosaic outputs inherit identical rejection).
     */
    fun judge(
        field: RawSrAlignmentField,
        robustness: RawSrRobustness.FrameRobustness,
        policy: Policy = Policy()
    ): Verdict {
        val rel = reliableFraction(field)
        val mean = meanRobustness(robustness.r)
        val support = supportFraction(robustness.r, policy.supportThreshold)
        val span = medianFlowSpanPx(field)
        val reason = when {
            rel < policy.minReliableFraction -> "low-reliable-frac"
            mean < policy.minMeanRobustness -> "low-mean-r"
            support < policy.minSupportFraction -> "low-support"
            span.isFinite() && span > policy.maxMedianFlowSpanPx -> "global-motion"
            else -> null
        }
        return Verdict(rel, mean, support, span, reason)
    }

    /** One-line provenance log matching the raymerge F-list style. */
    fun logLine(label: String, ev: String, verdict: Verdict, kept: Boolean): String =
        "$label EV=$ev: ${if (kept) "Accepted" else "REJECTED (${verdict.rejectReason})"} " +
            "rel=${"%.3f".format(verdict.reliableFraction)} " +
            "meanR=${"%.3f".format(verdict.meanRobustness)} " +
            "support=${"%.3f".format(verdict.supportFraction)} " +
            "span=${"%.2f".format(verdict.medianFlowSpanPx)}px"
}
