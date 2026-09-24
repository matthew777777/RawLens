// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fast translation pre-align for exposure brackets, following PhotonCamera's
 * pyramid tile matcher cost model (`alignment/normalize.glsl` +
 * `alignment/align2.glsl`).
 *
 * Pure Kotlin, no native dependencies: exposure-compensated 4-channel quad
 * mosaics (R/G1/G2/B kept separate, never averaged to gray) under a Gaussian
 * prefilter, with coarse-to-fine noise-normalized L1 search. This always runs
 * (even where FlowNet is unavailable — emulator stubs, failed init, periodic
 * textures) and doubles as the safety net when dense flow is rejected. FlowNet
 * remains the dense refinement on top; see RawCameraController bracket wiring.
 *
 * CFA safety: matching runs on whole quads, so translation is estimated
 * without Bayer phase bias. The resulting shift is applied through
 * [HdrRawMerge.sampleSmooth] (same-colour bilinear), which never mixes Bayer
 * colours for arbitrary sub-pixel shifts — no 2px snap needed.
 */
object HdrBracketAligner {
    data class Shift(val dx: Float, val dy: Float) {
        fun asFlow(): HdrFlowField = TranslationFlow(dx, dy)
    }

    /** Per-stage estimator cost of the last call (any thread; writer-thread use). */
    data class Timings(val proxyMs: Long, val coarseMs: Long, val fineMs: Long)
    @Volatile var lastTimings = Timings(0, 0, 0)
        private set

    /** Gaussian prefilter sigma in quad px (PhotonCamera normalize.glsl). */
    private const val PREFILTER_SIGMA = 1.5
    /** Reference quads darker than this carry no matchable signal. */
    private const val BLACK_FLOOR = 0.001f

    private fun workerCount(): Int = max(1, min(4, Runtime.getRuntime().availableProcessors()))

    /** Row fan-out on the shared pool; disjoint writes keep output deterministic. */
    private fun parRows(height: Int, block: (y0: Int, y1: Int) -> Unit) =
        HdrPools.runStriped(height, workerCount(), block)

    /** Parallel candidate costs in list order; caller argmins sequentially. */
    private fun parEval(offsets: List<Pair<Int, Int>>, fn: (Pair<Int, Int>) -> Float): FloatArray =
        HdrPools.evalEach(offsets, workerCount(), fn)

    private fun argmin(offsets: List<Pair<Int, Int>>, costs: FloatArray): Pair<Int, Int> {
        var bi = 0
        var best = Float.MAX_VALUE
        costs.forEachIndexed { i, s -> if (s < best) { best = s; bi = i } }
        return offsets[bi]
    }

    fun chain(first: HdrFlowField?, second: HdrFlowField?): HdrFlowField? {
        if (first == null) return second
        if (second == null) return first
        if (first is TranslationFlow && second is TranslationFlow)
            return TranslationFlow(first.dx + second.dx, first.dy + second.dy)
        return CombinedFlow(first, second)
    }

    /** Dense residual is measured after the even pre-shift; a rejected residual
     * must fall back to the original measured shift, including its fraction. */
    internal fun refinedFlow(shift: Shift?, residual: HdrFlowField?): HdrFlowField? =
        if (residual == null) shift?.asFlow()
        else chain(shift?.let { snapEven(it).asFlow() }, residual)

    /** Warp [cfa] by [shift] with the merge's CFA-safe smooth sampler. */
    fun warpTranslated(cfa: UnpackedRawCfa, shift: Shift): UnpackedRawCfa {
        val flow = shift.asFlow()
        val w = cfa.width
        val h = cfa.height
        val out = FloatArray(w * h)
        // Same per-pixel values as the old single-threaded init lambda, striped
        // over rows (nested loops also avoid per-pixel div/mod).
        parRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                out[y * w + x] = HdrRawMerge.sampleSmooth(cfa, flow, x, y)
            }
        }
        return cfa.copy(values = out)
    }

    /**
     * Even-integer part of [shift]. An even pixel offset is a pure reindex —
     * provably lossless and CFA-phase-preserving — so the FlowNet coarse stage
     * pre-shifts with this and the merge performs the only interpolating
     * resample. Keeps full HF energy (nearest warps measured lossless vs ~19%
     * HF loss per bilinear pass on synthetic charts and ~25% Nyquist power
     * loss on the 2026-09-16 bracket set).
     */
    fun snapEven(shift: Shift): Shift {
        fun even(v: Float): Float {
            require(v.isFinite())
            return (kotlin.math.round(v / 2f) * 2f)
        }
        return Shift(even(shift.dx), even(shift.dy))
    }

    /** Lossless integer pre-shift: no interpolation, border-replicated. */
    fun warpShiftedEven(cfa: UnpackedRawCfa, shift: Shift): UnpackedRawCfa {
        val dx = shift.dx.toInt()
        val dy = shift.dy.toInt()
        require(dx % 2 == 0 && dy % 2 == 0) { "Pre-shift must be even (got $dx,$dy)" }
        val w = cfa.width
        val h = cfa.height
        val src = cfa.values
        val out = FloatArray(w * h)
        // Same values as the old single-threaded init lambda; row strips keep
        // output identical while using all cores (this is a full-res pass).
        parRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                out[y * w + x] = src[(y + dy).coerceIn(y and 1, h - 2 + (y and 1)) * w +
                    (x + dx).coerceIn(x and 1, w - 2 + (x and 1))]
            }
        }
        return cfa.copy(values = out)
    }

    fun estimateShift(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        maxShiftPx: Int = 64
    ): Shift {
        val ref = reference.cfa
        val mov = moving.cfa
        require(ref.width == mov.width && ref.height == mov.height)
        require(ref.pattern == mov.pattern) { "Bracket frames must share the CFA pattern" }
        // Exposure-match moving onto reference so EV gaps don't read as motion.
        val scale = exposureScale(reference) / exposureScale(moving)
        val qScaleW = ref.width / 2
        val qScaleH = ref.height / 2
        // 4-channel quad mosaics at 1/2 res (R/G1/G2/B planes, never averaged:
        // gray averaging destroys the texture the matcher runs on) under a
        // Gaussian prefilter (PhotonCamera normalize.glsl: sigma 1.5 quads).
        var t = System.nanoTime()
        val rq = packQuads(ref)
        val mq = packQuads(mov)
        // One reusable scratch for both passes (same size); halves the ~24MB
        // transient on 12MP frames. Filtered values are identical.
        val prefilterTmp = FloatArray(rq.size)
        prefilter(rq, qScaleW, qScaleH, prefilterTmp)
        prefilter(mq, qScaleW, qScaleH, prefilterTmp)
        val noise = reference.noiseModel
        val proxyMs = (System.nanoTime() - t) / 1_000_000
        // Two-level search (logcat 2026-09-16: full-SAD cost 37s align on 12MP;
        // stride-2 SAD + float + threads target low single digits — a coarser
        // grid was tried and mislocks on periodic texture, so the grid stays).
        // Steps adapt to proxy size so small fixtures keep full resolution.
        val step0 = when {
            min(qScaleW, qScaleH) >= 96 -> 4
            min(qScaleW, qScaleH) >= 48 -> 2
            else -> 1
        }
        t = System.nanoTime()
        val cw = (qScaleW + step0 - 1) / step0
        val ch = (qScaleH + step0 - 1) / step0
        val rc = downsample(rq, qScaleW, qScaleH, step0)
        val mc = downsample(mq, qScaleW, qScaleH, step0)
        val coarseRange = maxShiftPx / 2 / step0 + 1
        val coarseOffsets = ArrayList<Pair<Int, Int>>((2 * coarseRange + 1) * (2 * coarseRange + 1))
        for (dy in -coarseRange..coarseRange) for (dx in -coarseRange..coarseRange)
            coarseOffsets += dx to dy
        val coarseCosts = parEval(coarseOffsets) { (dx, dy) -> sad(rc, mc, cw, ch, dx, dy, scale, noise) }
        val (bestDx, bestDy) = argmin(coarseOffsets, coarseCosts)
        val coarseMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        // Fine: single ±2 round at quad resolution, then parabola subpixel.
        var qdx = bestDx * step0
        var qdy = bestDy * step0
        run {
            val fineOffsets = ArrayList<Pair<Int, Int>>(25)
            for (dy in -2..2) for (dx in -2..2) fineOffsets += (qdx + dx) to (qdy + dy)
            val fineCosts = parEval(fineOffsets) { (dx, dy) -> sad(rq, mq, qScaleW, qScaleH, dx, dy, scale, noise) }
            val (bx, by) = argmin(fineOffsets, fineCosts)
            qdx = bx
            qdy = by
        }
        // Sub-pixel parabola refine per axis.
        val fx = parabola(sad(rq, mq, qScaleW, qScaleH, qdx - 1, qdy, scale, noise),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy, scale, noise),
            sad(rq, mq, qScaleW, qScaleH, qdx + 1, qdy, scale, noise))
        val fy = parabola(sad(rq, mq, qScaleW, qScaleH, qdx, qdy - 1, scale, noise),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy, scale, noise),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy + 1, scale, noise))
        val fineMs = (System.nanoTime() - t) / 1_000_000
        lastTimings = Timings(proxyMs, coarseMs, fineMs)
        // Quad units -> full-res pixels.
        return Shift((qdx + fx) * 2f, (qdy + fy) * 2f)
    }

    private fun exposureScale(frame: HdrMergeFrame): Double =
        frame.exposureTimeNanos.toDouble() * frame.sensitivityIso /
            (frame.aperture.toDouble() * frame.aperture.toDouble())

    /**
     * Packs a CFA frame into a 4-channel quad mosaic (channel-last
     * R/G1/G2/B per quad, top-green first) at half resolution.
     */
    private fun packQuads(cfa: UnpackedRawCfa): FloatArray {
        val qw = cfa.width / 2
        val qh = cfa.height / 2
        val out = FloatArray(qw * qh * 4)
        parRows(qh) { y0, y1 ->
            for (qy in y0 until y1) for (qx in 0 until qw) {
                val base = (qy * qw + qx) * 4
                for (k in 0..3) {
                    val x = qx * 2 + (k and 1)
                    val y = qy * 2 + (k shr 1)
                    val slot = when (cfa.pattern.colorAt(x, y)) {
                        CfaColor.RED -> 0
                        CfaColor.BLUE -> 3
                        CfaColor.GREEN -> if ((k shr 1) == 0) 1 else 2
                    }
                    out[base + slot] = cfa.values[y * cfa.width + x]
                }
            }
        }
        return out
    }

    /**
     * In-place separable 5-tap Gaussian (sigma 1.5 quads) over packed
     * channels, matching PhotonCamera's alignment prefilter: hot pixels and
     * noise average out while edges survive for the block matcher.
     */
    private fun prefilter(packed: FloatArray, w: Int, h: Int, tmp: FloatArray) {
        require(tmp.size == packed.size)
        val s2 = 2.0 * PREFILTER_SIGMA * PREFILTER_SIGMA
        val k = DoubleArray(5) { i ->
            val d = (i - 2).toDouble()
            kotlin.math.exp(-d * d / s2)
        }
        val sum = k.sum()
        val w0 = (k[0] / sum).toFloat()
        val w1 = (k[1] / sum).toFloat()
        val w2 = (k[2] / sum).toFloat()
        parRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) for (c in 0..3) {
                var acc = 0f
                for (t in -2..2) {
                    val xx = (x + t).coerceIn(0, w - 1)
                    val wt = when (t) {
                        0 -> w2
                        -1, 1 -> w1
                        else -> w0
                    }
                    acc += wt * packed[(y * w + xx) * 4 + c]
                }
                tmp[(y * w + x) * 4 + c] = acc
            }
        }
        parRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) for (c in 0..3) {
                var acc = 0f
                for (t in -2..2) {
                    val yy = (y + t).coerceIn(0, h - 1)
                    val wt = when (t) {
                        0 -> w2
                        -1, 1 -> w1
                        else -> w0
                    }
                    acc += wt * tmp[(yy * w + x) * 4 + c]
                }
                packed[(y * w + x) * 4 + c] = acc
            }
        }
    }

    private fun downsample(src: FloatArray, w: Int, h: Int, step: Int): FloatArray {
        val cw = (w + step - 1) / step
        val ch = (h + step - 1) / step
        val out = FloatArray(cw * ch * 4)
        parRows(ch) { y0, y1 ->
            for (cy in y0 until y1) for (cx in 0 until cw) for (c in 0..3) {
                var sum = 0f
                var n = 0
                for (dy in 0 until step) for (dx in 0 until step) {
                    val x = cx * step + dx
                    val y = cy * step + dy
                    if (x < w && y < h) {
                        sum += src[(y * w + x) * 4 + c]
                        n++
                    }
                }
                out[(cy * cw + cx) * 4 + c] = if (n == 0) 0f else sum / n
            }
        }
        return out
    }

    /**
     * Noise-normalized L1 over valid reference quads (PhotonCamera
     * `align2.glsl` cost without truncation): per-channel absolute residual
     * against the exposure-matched moving quad, divided by the reference
     * noise sigma. Quads below the black floor or above the moving frame's
     * matchable range (clipped there) contribute nothing instead of locking
     * onto garbage. Stride-2 sampling: ~4x cheaper, translation fixpoint
     * unchanged (verified by HdrBracketAlignerTest tolerances).
     */
    private fun sad(
        a: FloatArray, b: FloatArray, w: Int, h: Int, dx: Int, dy: Int,
        scale: Double, noise: CfaNoiseModel?
    ): Float {
        // Moving values saturate at 1 -> scale after matching; brighter
        // reference quads have no correspondence in this frame.
        val matchCeil = scale.toFloat()
        val avgScale = noise?.averageScale
        val avgOffset = noise?.averageOffset
        var sum = 0f
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val xx = x + dx
                val yy = y + dy
                if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                    val ai = (y * w + x) * 4
                    val brightness =
                        (a[ai] + a[ai + 1] + a[ai + 2] + a[ai + 3]) * 0.25f
                    if (brightness >= BLACK_FLOOR && brightness <= matchCeil) {
                        val bi = (yy * w + xx) * 4
                        var d = 0f
                        for (c in 0..3) {
                            val matched = (b[bi + c] * scale).coerceIn(0.0, 1.0).toFloat()
                            d += abs(a[ai + c] - matched)
                        }
                        d *= 0.25f
                        val sigma = if (avgScale != null && avgOffset != null) {
                            sqrt(max(avgScale * max(brightness, 0f) + avgOffset, 1e-10f))
                        } else 1f
                        sum += d / max(sigma, 1e-5f)
                        n++
                    }
                }
                x += 2
            }
            y += 2
        }
        return if (n < 16) Float.MAX_VALUE else sum / n
    }

    private fun parabola(m1: Float, c: Float, p1: Float): Float {
        if (!m1.isFinite() || !c.isFinite() || !p1.isFinite()) return 0f
        val denom = m1 - 2 * c + p1
        if (abs(denom) < 1e-6f) return 0f
        return ((m1 - p1) / (2 * denom)).coerceIn(-1f, 1f)
    }
}
