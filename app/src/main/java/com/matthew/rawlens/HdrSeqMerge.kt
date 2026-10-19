// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight sequential-DCG merge for the 0, -2, 0 trio at fixed ISO.
 *
 * Two 0 EV frames give ~3 dB shadow averaging; the -2 EV frame adds ~2
 * stops of highlight headroom. Deliberately local and light, in contrast
 * to the production bracket path (global translation + FlowNet dense
 * refinement + frequency-domain Wiener deghost in [HdrRawMerge]):
 *
 * - The sharper 0 EV frame is the geometric reference.
 * - Coarse per-tile motion comes from downsampled green-channel SAD; a
 *   single global shift cannot cover moving subjects or handheld parallax.
 * - Warps reuse [HdrRawMerge.sampleSmoothAt] (same-colour bilinear), so
 *   Bayer channels are never mixed.
 * - The two 0 EV frames average only where their difference fits the
 *   expected Poisson-Gaussian noise ([CfaNoiseModel]); elsewhere the
 *   reference is retained (noise-aware rejection mask).
 * - The short frame replaces clipped reference samples only where its
 *   tile alignment is trustworthy.
 *
 * Output is normalized Bayer (reference normalization, highlight headroom
 * above 1.0) for the existing 16-bit DNG writers. [Timings] feeds the
 * benchmark log; no single-frame speed is promised until measured.
 */
object HdrSeqMerge {
    /** Green-plane tile edge in green pixels (2x2 Bayer quads). */
    const val TILE_GREEN = 16
    /** Per-tile search radius in green pixels. */
    const val SEARCH_GREEN = 8
    /** Noise gate for 0 EV averaging, in difference sigmas. */
    const val REJECT_K = 3.0f
    /** Reference level at/above which a sample counts as clipped. */
    const val CLIP_LEVEL = 0.98f
    /** Short-frame level below which a rescue sample counts as unsaturated. */
    const val SHORT_CLIP = 0.98f
    /** Alignment-trust gate for rescue tiles, in noise sigmas per pixel. */
    const val TRUST_K = 3.0f
    /** Minimum valid-pixel fraction for a rescue tile to be trusted. */
    const val TRUST_VALID_FRAC = 0.25f
    /** Nominal -2 EV exposure ratio (short-to-long). */
    const val SHORT_RATIO = 0.25

    data class TileShift(val dx: Float, val dy: Float, val costPerPixel: Float, val validFrac: Float)

    data class GreenImage(val width: Int, val height: Int, val values: FloatArray)

    data class Timings(val alignMs: Long, val mergeMs: Long, val rescueMs: Long)

    data class Result(
        val cfa: UnpackedRawCfa,
        /** Index into the input list of the chosen 0 EV reference. */
        val refIndex: Int,
        /** Fraction of 0 EV pixels that passed the noise gate. */
        val keptFraction: Double,
        /** Fraction of clipped pixels rescued by the short frame. */
        val rescuedFraction: Double,
        val timings: Timings
    )

    /**
     * True for a same-ISO trio of two matching exposures plus one short
     * frame near quarter exposure, in any order. Ratio gates are wide
     * (±10% on the longs, 0.2..0.35 on the short) so shutter clamping and
     * rounding cannot silently reroute a bracket here.
     */
    fun isTrio(frames: List<HdrMergeFrame>): Boolean {
        if (frames.size != 3) return false
        if (frames.any { it.sensitivityIso != frames[0].sensitivityIso }) return false
        val e = frames.map { it.exposureTimeNanos.toDouble() }
        if (e.any { it <= 0.0 }) return false
        for (long in 0..2) {
            val shorts = (0..2).filter { it != long }
            val a = e[shorts[0]]
            val b = e[shorts[1]]
            val s = e[long]
            if (abs(a - b) / max(a, b) > 0.10) continue
            val ratio = s / ((a + b) / 2.0)
            if (ratio in 0.20..0.35) return true
        }
        return false
    }

    /** Green gradient energy (same-colour taps only) as a sharpness score. */
    fun sharpness(cfa: UnpackedRawCfa): Double {
        require(cfa.width % 2 == 0 && cfa.height % 2 == 0)
        var acc = 0.0
        var n = 0
        for (y in 0 until cfa.height) for (x in 0 until cfa.width) {
            if (cfa.pattern.colorAt(x, y) != CfaColor.GREEN) continue
            val c = cfa.values[y * cfa.width + x].toDouble()
            val r2 = cfa.values[y * cfa.width + min(x + 2, cfa.width - 1 - ((cfa.width - 1 - x) and 1))].toDouble()
            val d2 = cfa.values[min(y + 2, cfa.height - 1 - ((cfa.height - 1 - y) and 1)) * cfa.width + x].toDouble()
            acc += abs(c - r2) + abs(c - d2)
            n++
        }
        return if (n == 0) 0.0 else acc / n
    }

    /** Green-only half-res plane: each 2x2 quad contributes its two greens. */
    fun greenPlane(cfa: UnpackedRawCfa): GreenImage {
        require(cfa.width % 2 == 0 && cfa.height % 2 == 0)
        val gw = cfa.width / 2
        val gh = cfa.height / 2
        val out = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var sum = 0f
            var n = 0
            for (k in 0..3) {
                val x = gx * 2 + (k and 1)
                val y = gy * 2 + (k shr 1)
                if (cfa.pattern.colorAt(x, y) == CfaColor.GREEN) {
                    sum += cfa.values[y * cfa.width + x]
                    n++
                }
            }
            out[gy * gw + gx] = if (n == 0) 0f else sum / n
        }
        return GreenImage(gw, gh, out)
    }

    /**
     * Per-tile shift mapping [moving] green onto [reference] green.
     * Gain-matches [moving] by [gain] for comparison only; pixels where
     * either side exceeds [clip] after matching are skipped so clipped
     * highlights cannot drag the match. Shifts are full-res pixels.
     */
    fun estimateTileShifts(
        reference: GreenImage,
        moving: GreenImage,
        gain: Float = 1f,
        clip: Float = Float.POSITIVE_INFINITY
    ): Array<Array<TileShift>> {
        require(reference.width == moving.width && reference.height == moving.height)
        require(gain.isFinite() && gain > 0f)
        val tw = (reference.width + TILE_GREEN - 1) / TILE_GREEN
        val th = (reference.height + TILE_GREEN - 1) / TILE_GREEN
        val grid = Array(th) { Array(tw) { TileShift(0f, 0f, Float.MAX_VALUE, 0f) } }
        HdrPools.runStriped(th, 4) { y0, y1 ->
            for (ty in y0 until y1) for (tx in 0 until tw) {
                grid[ty][tx] = bestTileShift(reference, moving, tx, ty, gain, clip)
            }
        }
        return grid
    }

    private fun bestTileShift(
        ref: GreenImage, mov: GreenImage, tx: Int, ty: Int, gain: Float, clip: Float
    ): TileShift {
        val x0 = tx * TILE_GREEN
        val y0 = ty * TILE_GREEN
        val x1 = min(x0 + TILE_GREEN, ref.width)
        val y1 = min(y0 + TILE_GREEN, ref.height)
        var bestDx = 0
        var bestDy = 0
        var bestCost = Float.MAX_VALUE
        var bestValid = 0f
        for (dy in -SEARCH_GREEN..SEARCH_GREEN) for (dx in -SEARCH_GREEN..SEARCH_GREEN) {
            var cost = 0f
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val mx = x + dx
                val my = y + dy
                if (mx !in 0 until mov.width || my !in 0 until mov.height) continue
                val r = ref.values[y * ref.width + x]
                val m = mov.values[my * mov.width + mx] * gain
                if (r >= clip || m >= clip) continue
                cost += abs(r - m)
                n++
            }
            if (n == 0) continue
            val per = cost / n
            if (per < bestCost) {
                bestCost = per
                bestDx = dx
                bestDy = dy
                bestValid = n.toFloat() / ((x1 - x0) * (y1 - y0))
            }
        }
        return if (bestCost == Float.MAX_VALUE) TileShift(0f, 0f, Float.MAX_VALUE, 0f)
        else TileShift(bestDx * 2f, bestDy * 2f, bestCost, bestValid)
    }

    internal fun shiftFor(
        grid: Array<Array<TileShift>>, gx: Int, gy: Int, tile: Int = TILE_GREEN
    ): TileShift {
        val th = grid.size
        val tw = if (th == 0) 0 else grid[0].size
        if (th == 0 || tw == 0) return TileShift(0f, 0f, Float.MAX_VALUE, 0f)
        val tx = (gx / tile).coerceIn(0, tw - 1)
        val ty = (gy / tile).coerceIn(0, th - 1)
        return grid[ty][tx]
    }

    private fun noiseSigma(
        model: CfaNoiseModel?, ref: Float, mov: Float
    ): Float {
        val scale = model?.averageScale ?: CfaNoiseModel.FALLBACK_SCALE
        val offset = model?.averageOffset ?: CfaNoiseModel.FALLBACK_OFFSET
        val vr = scale * max(ref, 0f) + offset
        val vm = scale * max(mov, 0f) + offset
        return sqrt(max(vr + vm, 1e-12f))
    }

    /**
     * Stage 1: average two same-exposure frames. [reference] stays fixed;
     * [alternate] is tile-warped onto it, then per-pixel averaged only
     * where the warped difference fits [REJECT_K] noise sigmas.
     */
    fun mergeZeroPair(
        reference: HdrMergeFrame,
        alternate: HdrMergeFrame,
        refIndex: Int = 0
    ): Result {
        require(reference.cfa.width == alternate.cfa.width &&
            reference.cfa.height == alternate.cfa.height) { "Frame geometry must match" }
        require(reference.cfa.pattern == alternate.cfa.pattern) { "Frames must share the CFA pattern" }
        val w = reference.cfa.width
        val h = reference.cfa.height
        var t = System.nanoTime()
        val shifts = estimateTileShifts(greenPlane(reference.cfa), greenPlane(alternate.cfa))
        val alignMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val out = FloatArray(w * h)
        var kept = 0L
        HdrPools.runStriped(h, 8) { y0, y1 ->
            var localKept = 0L
            for (y in y0 until y1) for (x in 0 until w) {
                val r = reference.cfa.values[y * w + x]
                val s = shiftFor(shifts, x / 2, y / 2)
                val m = HdrRawMerge.sampleSmoothAt(alternate.cfa, s.dx, s.dy, x, y)
                val sigma = noiseSigma(reference.noiseModel, r, m)
                if (abs(m - r) <= REJECT_K * sigma) {
                    out[y * w + x] = (r + m) / 2f
                    localKept++
                } else {
                    out[y * w + x] = r
                }
            }
            synchronized(this) { kept += localKept }
        }
        val mergeMs = (System.nanoTime() - t) / 1_000_000
        val cfa = reference.cfa.copy(values = out)
        return Result(cfa, refIndex, kept.toDouble() / (w * h), 0.0, Timings(alignMs, mergeMs, 0))
    }

    /**
     * Full trio: stage-1 average of the two 0 EV frames, then rescue of
     * clipped samples from the gain-mapped short frame where the short
     * tile alignment is trustworthy. Frames may arrive in any order;
     * exactly two must share the long exposure (see [isTrio]).
     */
    fun mergeTrio(frames: List<HdrMergeFrame>): Result {
        require(isTrio(frames)) { "Need a same-ISO trio: two matching exposures plus one ~quarter short" }
        val e = frames.map { it.exposureTimeNanos.toDouble() }
        val longIdx = (0..2).first { i ->
            val others = (0..2).filter { it != i }
            val ratio = e[i] / ((e[others[0]] + e[others[1]]) / 2.0)
            ratio in 0.20..0.35
        }
        val zeroIdx = (0..2).filter { it != longIdx }
        val (z0, z1) = zeroIdx[0] to zeroIdx[1]
        val refLocal = if (sharpness(frames[z1].cfa) > sharpness(frames[z0].cfa)) 1 else 0
        val refIndex = zeroIdx[refLocal]
        val altIndex = zeroIdx[1 - refLocal]
        val reference = frames[refIndex]
        val alternate = frames[altIndex]
        val short = frames[longIdx]
        val gain = (reference.exposureTimeNanos.toDouble() / short.exposureTimeNanos.toDouble()).toFloat()

        val pair = mergeZeroPair(reference, alternate, refIndex)
        var t = System.nanoTime()
        val w = reference.cfa.width
        val h = reference.cfa.height
        val shortShifts = estimateTileShifts(
            greenPlane(reference.cfa), greenPlane(short.cfa), gain, CLIP_LEVEL)
        val trustSigma = run {
            val scale = reference.noiseModel?.averageScale ?: CfaNoiseModel.FALLBACK_SCALE
            val offset = reference.noiseModel?.averageOffset ?: CfaNoiseModel.FALLBACK_OFFSET
            sqrt(max(scale * CLIP_LEVEL + offset, 1e-12f)) * sqrt(2f)
        }
        val merged = pair.cfa.values.copyOf()
        var clipped = 0L
        var rescued = 0L
        HdrPools.runStriped(h, 8) { y0, y1 ->
            var lClip = 0L
            var lResc = 0L
            for (y in y0 until y1) for (x in 0 until w) {
                val idx = y * w + x
                if (merged[idx] < CLIP_LEVEL) continue
                lClip++
                val s = shiftFor(shortShifts, x / 2, y / 2)
                if (s.validFrac < TRUST_VALID_FRAC) continue
                if (s.costPerPixel > TRUST_K * trustSigma) continue
                val raw = HdrRawMerge.sampleSmoothAt(short.cfa, s.dx, s.dy, x, y)
                if (raw >= SHORT_CLIP) continue
                merged[idx] = raw * gain
                lResc++
            }
            synchronized(this) {
                clipped += lClip
                rescued += lResc
            }
        }
        val rescueMs = (System.nanoTime() - t) / 1_000_000
        val cfa = reference.cfa.copy(values = merged)
        return Result(
            cfa, refIndex, pair.keptFraction,
            if (clipped == 0L) 1.0 else rescued.toDouble() / clipped,
            Timings(pair.timings.alignMs, pair.timings.mergeMs, rescueMs)
        )
    }
}
