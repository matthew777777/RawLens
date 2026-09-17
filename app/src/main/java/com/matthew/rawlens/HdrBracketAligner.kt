// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HDR+-style fast translation pre-align for exposure brackets.
 *
 * Pure Kotlin, no native dependencies: exposure-compensated quad-gray proxies with
 * coarse-to-fine SAD search. This always runs (even where FlowNet is unavailable —
 * emulator stubs, failed init, periodic textures) and doubles as the safety net when
 * dense flow is rejected. FlowNet remains the dense refinement on top; see
 * RawCameraController bracket wiring.
 *
 * CFA safety: proxies average each 2x2 quad, so translation is estimated in a
 * colour-blind domain. The resulting shift is applied through [HdrRawMerge.sampleSmooth]
 * (same-colour bilinear), which never mixes Bayer colours for arbitrary sub-pixel
 * shifts — no 2px snap needed.
 */
object HdrBracketAligner {
    data class Shift(val dx: Float, val dy: Float) {
        fun asFlow(): HdrFlowField = TranslationFlow(dx, dy)
    }

    /** Per-stage estimator cost of the last call (any thread; writer-thread use). */
    data class Timings(val proxyMs: Long, val coarseMs: Long, val fineMs: Long)
    @Volatile var lastTimings = Timings(0, 0, 0)
        private set

    private fun workerCount(): Int = max(1, min(4, Runtime.getRuntime().availableProcessors()))

    /** Row fan-out with join; disjoint writes keep output deterministic. */
    private fun parRows(height: Int, block: (y0: Int, y1: Int) -> Unit) {
        val n = workerCount()
        if (n == 1 || height < n * 2) {
            block(0, height)
            return
        }
        val threads = (0 until n).map { t ->
            Thread { block(t * height / n, (t + 1) * height / n) }.also { it.start() }
        }
        threads.forEach { it.join() }
    }

    /** Parallel candidate costs in list order; caller argmins sequentially. */
    private fun parEval(offsets: List<Pair<Int, Int>>, fn: (Pair<Int, Int>) -> Float): FloatArray {
        val out = FloatArray(offsets.size)
        val n = workerCount()
        if (n == 1 || offsets.size <= 1) {
            offsets.forEachIndexed { i, o -> out[i] = fn(o) }
            return out
        }
        val threads = (0 until n).map { t ->
            Thread {
                var i = t
                while (i < offsets.size) {
                    out[i] = fn(offsets[i])
                    i += n
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        return out
    }

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

    /** Warp [cfa] by [shift] with the merge's CFA-safe smooth sampler. */
    fun warpTranslated(cfa: UnpackedRawCfa, shift: Shift): UnpackedRawCfa {
        val flow = shift.asFlow()
        val out = FloatArray(cfa.width * cfa.height) { i ->
            HdrRawMerge.sampleSmooth(cfa, flow, i % cfa.width, i / cfa.width)
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
        val out = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            cfa.values[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
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
        // Exposure-match moving onto reference so EV gaps don't read as motion.
        val scale = exposureScale(reference) / exposureScale(moving)
        val qScaleW = ref.width / 2
        val qScaleH = ref.height / 2
        // Quad-gray proxies at 1/2 res (one value per Bayer cell).
        var t = System.nanoTime()
        val rq = FloatArray(qScaleW * qScaleH)
        val mq = FloatArray(qScaleW * qScaleH)
        parRows(qScaleH) { y0, y1 ->
            for (qy in y0 until y1) for (qx in 0 until qScaleW) {
                rq[qy * qScaleW + qx] = quadGray(ref, qx, qy)
                mq[qy * qScaleW + qx] =
                    (quadGray(mov, qx, qy) * scale).coerceIn(0.0, 1.0).toFloat()
            }
        }
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
        val coarseCosts = parEval(coarseOffsets) { (dx, dy) -> sad(rc, mc, cw, ch, dx, dy) }
        val (bestDx, bestDy) = argmin(coarseOffsets, coarseCosts)
        val coarseMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        // Fine: single ±2 round at quad resolution, then parabola subpixel.
        var qdx = bestDx * step0
        var qdy = bestDy * step0
        run {
            val fineOffsets = ArrayList<Pair<Int, Int>>(25)
            for (dy in -2..2) for (dx in -2..2) fineOffsets += (qdx + dx) to (qdy + dy)
            val fineCosts = parEval(fineOffsets) { (dx, dy) -> sad(rq, mq, qScaleW, qScaleH, dx, dy) }
            val (bx, by) = argmin(fineOffsets, fineCosts)
            qdx = bx
            qdy = by
        }
        // Sub-pixel parabola refine per axis.
        val fx = parabola(sad(rq, mq, qScaleW, qScaleH, qdx - 1, qdy),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy),
            sad(rq, mq, qScaleW, qScaleH, qdx + 1, qdy))
        val fy = parabola(sad(rq, mq, qScaleW, qScaleH, qdx, qdy - 1),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy),
            sad(rq, mq, qScaleW, qScaleH, qdx, qdy + 1))
        val fineMs = (System.nanoTime() - t) / 1_000_000
        lastTimings = Timings(proxyMs, coarseMs, fineMs)
        // Quad units -> full-res pixels.
        return Shift((qdx + fx) * 2f, (qdy + fy) * 2f)
    }

    private fun exposureScale(frame: HdrMergeFrame): Double =
        frame.exposureTimeNanos.toDouble() * frame.sensitivityIso /
            (frame.aperture.toDouble() * frame.aperture.toDouble())

    private fun quadGray(cfa: UnpackedRawCfa, qx: Int, qy: Int): Float {
        var sum = 0f
        var n = 0
        for (dy in 0..1) for (dx in 0..1) {
            val x = qx * 2 + dx
            val y = qy * 2 + dy
            if (x < cfa.width && y < cfa.height) {
                sum += cfa.values[y * cfa.width + x]
                n++
            }
        }
        return if (n == 0) 0f else sum / n
    }

    private fun downsample(src: FloatArray, w: Int, h: Int, step: Int): FloatArray {
        val cw = (w + step - 1) / step
        val ch = (h + step - 1) / step
        val out = FloatArray(cw * ch)
        parRows(ch) { y0, y1 ->
            for (cy in y0 until y1) for (cx in 0 until cw) {
                var sum = 0f
                var n = 0
                for (dy in 0 until step) for (dx in 0 until step) {
                    val x = cx * step + dx
                    val y = cy * step + dy
                    if (x < w && y < h) {
                        sum += src[y * w + x]
                        n++
                    }
                }
                out[cy * cw + cx] = if (n == 0) 0f else sum / n
            }
        }
        return out
    }

    /**
     * Mean absolute residual over valid (mid-tone) reference cells only.
     * Stride-2 sampling with float accumulation: ~4x cheaper, translation
     * fixpoint unchanged (verified by HdrBracketAlignerTest tolerances).
     */
    private fun sad(a: FloatArray, b: FloatArray, w: Int, h: Int, dx: Int, dy: Int): Float {
        var sum = 0f
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val xx = x + dx
                val yy = y + dy
                if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                    val ra = a[y * w + x]
                    if (ra >= 0.02f && ra <= 0.98f) {
                        sum += abs(ra - b[yy * w + xx])
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
