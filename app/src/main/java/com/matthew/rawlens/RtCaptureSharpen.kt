/*
 * Port of RawTherapee's Capture Sharpening (rtengine/capturesharpening.cc,
 * rtengine/rt_algo.cc buildBlendMask / calcContrastThreshold,
 * rtengine/boxblur.cc compute*kernel2 / gauss*div2 / gauss*mult2).
 *
 *  Copyright (c) 2019 Ingo Weyrich (heckflosse67@gmx.de)
 *  Jacques Desmis 2024 - 2025
 *  Kotlin port for RawLens, 2026.
 *
 *  RawTherapee is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.matthew.rawlens

import java.nio.FloatBuffer
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RawTherapee Capture Sharpening with its default parameters (params/raw.cc:
 * autoRadius, autoContrast, contrast 10, deconvradiusOffset 0, deconviter 20,
 * deconvitercheck true, no pre/post denoise): Richardson-Lucy deconvolution of
 * luminance Y with a Gaussian PSF, blended by a contrast mask (flat areas and
 * clipped highlights stay untouched), applied to RGB as the ratio Ynew / Yold.
 *
 * Differences from RawTherapee, all forced by where it runs:
 * - Input is the merged, demosaiced linear camera RGB (white = 1) instead of
 *   the raw-domain RGB of RawImageSource; white balance gains are applied for
 *   the luminance only (RT's scale_mul includes them), the output ratio is
 *   applied to the unbalanced data, so colour is unchanged.
 * - The automatic radius is measured on the reference frame's CFA
 *   ([autoRadiusBayer], RT calcRadiusBayer), before the merge, then scaled by
 *   the output scale.
 * - The clip mask is built from the merged RGB (any channel >= 0.95 white),
 *   with RT's 5x5 diamond footprint.
 * - The image is processed in row bands with a halo to bound memory
 *   (a 2x output is 50 MP); deconvolution tiles are RT's 32 px tiles.
 */
object RtCaptureSharpen {

    private const val MAXVALF = 65535f
    private const val EPSILON = 216f / 24389f
    private const val KAPPA = 24389f / 27f
    private const val CLIP_LIMIT = 0.95f
    private const val MAX_SIGMA = 2f
    private const val TILE = 32
    private const val MIN_BLEND = 0.01f
    private const val BAND = 256
    private const val HALO = 24 // >= deconv border (8) and blend mask support (2 + 3 x 2.0 blur)

    data class Result(val radius: Float, val contrastThreshold: Float, val millis: Long)

    // ---------------------------------------------------------------- radius

    /**
     * RT calcRadiusBayer on an RGGB-normalized CFA (values 0..1 of white):
     * the largest ratio between diagonal green neighbours that are not near
     * clipping estimates the sharpest edge the lens delivers; sigma =
     * sqrt(1 / ln(maxRatio)), capped at 2 (RT maxSigma). NaN when undefined.
     */
    fun autoRadiusBayer(cfa: Array<FloatArray>): Float {
        val h = cfa.size
        val w = cfa[0].size
        val lowerLimit = 1000f
        val upperLimit = MAXVALF // RT clipVal: green white after scale_mul
        val fc = intArrayOf(0, 1) // FC(0,0) = R, FC(1,0) = G in RGGB
        fun v(r: Int, c: Int) = cfa[r][c] * MAXVALF
        val rowMax = FloatArray(h) { 1f }
        IntStream.range(4, max(4, h - 4)).parallel().forEach { row ->
            var maxRatio = 1f
            var col = 5 + (fc[row and 1] and 1)
            while (col < w - 4) {
                val val00 = v(row, col)
                if (val00 > 0f) {
                    val val1m1 = v(row + 1, col - 1)
                    val val1p1 = v(row + 1, col + 1)
                    val maxVal0 = max(val00, val1m1)
                    if (val1m1 > 0f && maxVal0 > lowerLimit) {
                        val minVal = min(val00, val1m1)
                        if (maxVal0 > maxRatio * minVal) {
                            val clipped = if (maxVal0 == val00) {
                                max(max(v(row - 1, col - 1), v(row - 1, col + 1)), val1p1) >= upperLimit
                            } else {
                                max(max(v(row, col - 2), val00), max(v(row + 2, col - 2), v(row + 2, col))) >= upperLimit
                            }
                            if (!clipped) maxRatio = maxVal0 / minVal
                        }
                    }
                    val maxVal1 = max(val00, val1p1)
                    if (val1p1 > 0f && maxVal1 > lowerLimit) {
                        val minVal = min(val00, val1p1)
                        if (maxVal1 > maxRatio * minVal) {
                            val clipped = if (maxVal1 == val00) {
                                max(max(v(row - 1, col - 1), v(row - 1, col + 1)), val1p1) >= upperLimit
                            } else {
                                max(max(val00, v(row, col + 2)), max(v(row + 2, col), v(row + 2, col + 2))) >= upperLimit
                            }
                            if (!clipped) maxRatio = maxVal1 / minVal
                        }
                    }
                }
                col += 2
            }
            rowMax[row] = maxRatio
        }
        val maxRatio = rowMax.max()
        return min(sqrt(1f / ln(maxRatio)), MAX_SIGMA)
    }

    // ---------------------------------------------------------------- colour

    /** RT Color::computeXYZ2LabY: CIE L* scaled to 0..32768 from Y in 0..65535. */
    private fun labL(y: Float): Float {
        if (y < 0f) return 327.68f * (KAPPA * y / MAXVALF)
        val t = y / MAXVALF
        val f = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16f) / 116f
        return 327.68f * (116f * f - 16f)
    }

    // ---------------------------------------------------------------- kernels

    /** RT compute{3,5,7,9,13}x{..}kernel2: circularly truncated, normalized Gaussian. */
    private class Kernel(val r: Int, val dy: IntArray, val dx: IntArray, val w: FloatArray)

    private fun kernelFor(sigma: Float): Kernel {
        val (r, cut) = when {
            sigma < 0.6f -> 1 to Double.MAX_VALUE
            sigma <= 0.84f -> 2 to 3.0 * 0.84
            sigma <= 1.15f -> 3 to 3.0 * 1.15
            sigma <= 1.5f -> 4 to 3.0 * 1.5
            else -> 6 to 3.0 * 2.0
        }
        val temp = -2.0 * sigma * sigma
        val dys = ArrayList<Int>(); val dxs = ArrayList<Int>(); val ws = ArrayList<Double>()
        for (i in -r..r) for (j in -r..r) {
            val d2 = (i * i + j * j).toDouble()
            if (d2 <= cut * cut) { dys += i; dxs += j; ws += exp(d2 / temp) }
        }
        val sum = ws.sum()
        return Kernel(r, dys.toIntArray(), dxs.toIntArray(), FloatArray(ws.size) { (ws[it] / sum).toFloat() })
    }

    // ---------------------------------------------------------------- blend mask

    /** RT calcBlendFactor: sigmoid with inflexion (threshold, 0.5). */
    private fun blendFactor(v: Float, threshold: Float): Float {
        val x = -16f + (16f / threshold) * v
        return 0.5f * (1f + x / sqrt(1f + x * x))
    }

    private const val CONTRAST_SCALE = 0.0625f / 327.68f

    private fun contrastAt(l: Array<FloatArray>, j: Int, i: Int): Float {
        val a = l[j][i + 1] - l[j][i - 1]; val b = l[j + 1][i] - l[j - 1][i]
        val c = l[j][i + 2] - l[j][i - 2]; val d = l[j + 2][i] - l[j - 2][i]
        return sqrt(a * a + b * b + c * c + d * d) * CONTRAST_SCALE
    }

    /** RT calcContrastThreshold on an L tile whose top-left is (0, 0). */
    private fun contrastThresholdOf(l: Array<FloatArray>, ts: Int): Float {
        val n = ts - 4
        val contrast = Array(n) { jj -> FloatArray(n) { ii -> contrastAt(l, jj + 2, ii + 2) } }
        val limit = (n * n) / 100f
        var c = 1
        while (c < 100) {
            val thr = c / 100f
            var sum = 0f
            for (row in contrast) for (v in row) sum += blendFactor(v, thr)
            if (sum <= limit) break
            c++
        }
        return (c + 1) / 100f
    }

    // ---------------------------------------------------------------- driver

    /**
     * Sharpens [rgb] (interleaved linear camera RGB, w x h, white = 1) in
     * place. [radius] from [autoRadiusBayer] (already scaled to output pixels);
     * [wbR], [wbB] are the white-balance gains relative to green.
     */
    fun apply(rgb: FloatBuffer, w: Int, h: Int, radius: Float, wbR: Float, wbB: Float,
              iterations: Int = 20, checkIterStop: Boolean = true): Result {
        val t0 = System.nanoTime()
        if (radius.isNaN() || radius <= 0f || w < 2 * HALO || h < 2 * HALO) return Result(radius, 0f, 0)
        val sigma = min(radius, MAX_SIGMA)
        val stride = w * 3
        val xyzR = 0.212671f; val xyzG = 0.715160f; val xyzB = 0.072169f

        fun readRows(y0: Int, y1: Int): FloatArray {
            val out = FloatArray((y1 - y0) * stride)
            val d = rgb.duplicate(); d.position(y0 * stride); d.get(out)
            return out
        }

        /** L (RT RGB2L, sRGB Y row) and Y (RT RGB2Y, Rec.2020) of rows [y0, y1). */
        fun luminance(px: FloatArray, rows: Int, l: Array<FloatArray>?, y: Array<FloatArray>) {
            IntStream.range(0, rows).parallel().forEach { r ->
                val base = r * stride
                val lr = l?.get(r); val yr = y[r]
                for (x in 0 until w) {
                    val rr = px[base + 3 * x] * wbR * MAXVALF
                    val gg = px[base + 3 * x + 1] * MAXVALF
                    val bb = px[base + 3 * x + 2] * wbB * MAXVALF
                    if (lr != null) lr[x] = labL(xyzR * rr + xyzG * gg + xyzB * bb)
                    yr[x] = 0.2627f * max(rr, 0f) + 0.6780f * max(gg, 0f) + 0.0593f * max(bb, 0f)
                }
            }
        }

        // ---- RT buildBlendMask autoContrast: flattest mid-tone tile.
        fun lumaTile(ty: Int, tx: Int, ts: Int): Array<FloatArray> {
            val px = readRows(ty, ty + ts)
            val l = Array(ts) { FloatArray(w) }
            luminance(px, ts, l, Array(ts) { FloatArray(w) })
            return Array(ts) { r -> l[r].copyOfRange(tx, tx + ts) }
        }
        fun tileStats(l: Array<FloatArray>, y0: Int, x0: Int, ts: Int): Float {
            var avg = 0f
            for (y in y0 until y0 + ts) for (x in x0 until x0 + ts) avg += l[y][x]
            avg /= (ts * ts).toFloat()
            if (avg < 2000f || avg > 20000f) return Float.POSITIVE_INFINITY
            var v = 0f
            for (y in y0 until y0 + ts) for (x in x0 until x0 + ts) { val d = l[y][x] - avg; v += d * d }
            v /= ts * ts * avg
            return if (v < 0.5f) Float.POSITIVE_INFINITY else v
        }
        /** Minimum-variance tile on a grid (rows of luma read band by band). */
        fun scan(ts: Int, skip: Int, nTilesW: Int, nTilesH: Int): Triple<Float, Int, Int> {
            val best = Array(max(0, nTilesH)) { Triple(Float.POSITIVE_INFINITY, 0, 0) }
            IntStream.range(0, max(0, nTilesH)).parallel().forEach { i ->
                val ty = i * skip
                val px = readRows(ty, ty + ts)
                val l = Array(ts) { FloatArray(w) }
                luminance(px, ts, l, Array(ts) { FloatArray(w) })
                var m = Float.POSITIVE_INFINITY; var mj = 0
                for (j in 0 until nTilesW) {
                    val v = tileStats(l, 0, j * skip, ts)
                    if (v < m) { m = v; mj = j }
                }
                best[i] = Triple(m, i, mj)
            }
            return best.minByOrNull { it.first } ?: Triple(Float.POSITIVE_INFINITY, 0, 0)
        }
        var contrast = 0.10f
        run {
            val (min0, i0, j0) = scan(80, 80, w / 80, h / 80)
            if (min0 <= 1f) {
                contrast = contrastThresholdOf(lumaTile(80 * i0, 80 * j0, 80), 80)
                return@run
            }
            val (_, i1, j1) = scan(40, 10, w / 10 - 3, h / 10 - 3)
            val minY = 10 * i1; val minX = 10 * j1
            val ys = max(minY - 10, 0); val xs = max(minX - 10, 0)
            val ye = min(minY + 10, h - 40); val xe = min(minX + 10, w - 40)
            val px = readRows(ys, ye + 40)
            val rows = ye + 40 - ys
            val l = Array(rows) { FloatArray(w) }
            luminance(px, rows, l, Array(rows) { FloatArray(w) })
            var mv = Float.POSITIVE_INFINITY; var by = ys; var bx = xs
            for (ty in ys..ye) for (tx in xs..xe) {
                val v = tileStats(l, ty - ys, tx, 40)
                if (v < mv) { mv = v; by = ty; bx = tx }
            }
            contrast = if (mv <= 8f) contrastThresholdOf(Array(40) { r -> l[by - ys + r].copyOfRange(bx, bx + 40) }, 40) else 0f
        }

        val kernel = kernelFor(sigma)
        val border = if (sigma <= 1.15f) (if (iterations <= 30) 5 else 7) else 8
        val fts = TILE + 2 * border
        val blurK = run { // RT gaussianBlur(blend, 2.0): separable Gaussian
            val r = 6
            val k = FloatArray(2 * r + 1) { exp(-0.5f * ((it - r) / 2f) * ((it - r) / 2f)) }
            val s = k.sum(); FloatArray(k.size) { k[it] / s }
        }

        var savedRows: FloatArray? = null // original pixels of the previous band's last HALO rows
        var savedFrom = 0
        var b0 = 0
        while (b0 < h) {
            val b1 = min(h, b0 + BAND)
            val lo = max(0, b0 - HALO)
            val hi = min(h, b1 + HALO)
            val n = hi - lo
            // Original pixels for [lo, hi): rows above b0 come from the snapshot.
            val px = readRows(lo, hi)
            savedRows?.let { s ->
                for (y in lo until b0) {
                    val src = (y - savedFrom) * stride
                    s.copyInto(px, (y - lo) * stride, src, src + stride)
                }
            }
            val l = Array(n) { FloatArray(w) }
            val yOld = Array(n) { FloatArray(w) }
            luminance(px, n, l, yOld)
            // Clip mask (RT buildClipMaskBayer footprint, on merged RGB).
            val blend = Array(n) { FloatArray(w) { 1f } }
            for (r in 0 until n) for (x in 0 until w) {
                val i = r * stride + 3 * x
                if (max(px[i], max(px[i + 1], px[i + 2])) >= CLIP_LIMIT) {
                    for (dy in -2..2) {
                        val rr = r + dy; if (rr < 0 || rr >= n) continue
                        val span = if (abs(dy) == 2) 1 else 2
                        for (dx in -span..span) { val xx = x + dx; if (xx in 0 until w) blend[rr][xx] = 0f }
                    }
                }
            }
            // RT buildBlendMask: contrast sigmoid x clip mask, edges copied, blurred.
            if (contrast == 0f) {
                for (row in blend) row.fill(1f)
            } else {
                val bm = Array(n) { FloatArray(w) }
                IntStream.range(2, n - 2).parallel().forEach { j ->
                    for (i in 2 until w - 2) bm[j][i] = blend[j][i] * blendFactor(contrastAt(l, j, i), contrast)
                }
                for (j in 0 until n) {
                    if (j < 2) { if (lo == 0) bm[2].copyInto(bm[j]) else bm[j].fill(0f) }
                    if (j >= n - 2) { if (hi == h) bm[n - 3].copyInto(bm[j]) else bm[j].fill(0f) }
                    bm[j][0] = bm[j][2]; bm[j][1] = bm[j][2]; bm[j][w - 2] = bm[j][w - 3]; bm[j][w - 1] = bm[j][w - 3]
                }
                val tmp = Array(n) { FloatArray(w) }
                IntStream.range(0, n).parallel().forEach { j ->
                    val src = bm[j]; val dst = tmp[j]
                    for (x in 0 until w) {
                        var a = 0f
                        for (t in -6..6) a += blurK[t + 6] * src[(x + t).coerceIn(0, w - 1)]
                        dst[x] = a
                    }
                }
                IntStream.range(0, n).parallel().forEach { j ->
                    val dst = blend[j]
                    for (x in 0 until w) {
                        var a = 0f
                        for (t in -6..6) a += blurK[t + 6] * tmp[(j + t).coerceIn(0, n - 1)][x]
                        dst[x] = a
                    }
                }
            }
            // RT CaptureDeconvSharpening on the band core (RT never writes
            // the outer `border` pixels of the image).
            val yNew = Array(n) { yOld[it].copyOf() }
            val coreLo = max(b0, border); val coreHi = min(b1, h - border)
            val tilesY = if (coreHi > coreLo) (coreHi - coreLo + TILE - 1) / TILE else 0
            val tilesX = (w - 2 * border + TILE - 1) / TILE
            IntStream.range(0, tilesY * tilesX).parallel().forEach { t ->
                // Tiles ending past the core are shifted inward, as RT's end tiles.
                val ty = min(coreLo + (t / tilesX) * TILE, coreHi - TILE).coerceAtLeast(max(lo + border, border))
                val tx = min(border + (t % tilesX) * TILE, w - border - TILE)
                val oy = ty - border - lo; val ox = tx - border
                var maxBlend = 0f
                for (k in 0 until TILE) for (m in 0 until TILE) maxBlend = max(maxBlend, blend[oy + border + k][ox + border + m])
                if (maxBlend < MIN_BLEND) return@forEach
                val tmpI = FloatArray(fts * fts); val lum = FloatArray(fts * fts); val tmpT = FloatArray(fts * fts) { 1f }
                for (k in 0 until fts) for (m in 0 until fts) { val v = yOld[oy + k][ox + m]; tmpI[k * fts + m] = v; lum[k * fts + m] = v }
                val r = kernel.r
                for (iter in 0 until iterations) {
                    // gauss*div2: tmpT = lum / max(G * tmpI, 1e-5)
                    for (i in r until fts - r) for (j in r until fts - r) {
                        var v = 0f
                        for (q in kernel.w.indices) v += kernel.w[q] * tmpI[(i + kernel.dy[q]) * fts + j + kernel.dx[q]]
                        tmpT[i * fts + j] = lum[i * fts + j] / max(v, 0.00001f)
                    }
                    // gauss*mult2: tmpI *= G * tmpT
                    for (i in r until fts - r) for (j in r until fts - r) {
                        var v = 0f
                        for (q in kernel.w.indices) v += kernel.w[q] * tmpT[(i + kernel.dy[q]) * fts + j + kernel.dx[q]]
                        tmpI[i * fts + j] *= v
                    }
                    if (checkIterStop && iter < iterations - 1) {
                        var stop = false
                        loop@ for (i in border until fts - border) for (j in border until fts - border) {
                            val gy = oy + i; val gx = ox + j
                            if (tmpI[i * fts + j] < yOld[gy][gx] * blend[gy][gx] * 0.5f) { stop = true; break@loop }
                        }
                        if (stop) break
                    }
                }
                for (i in border until fts - border) for (j in border until fts - border) {
                    val gy = oy + i; val gx = ox + j
                    if (gy + lo < coreLo || gy + lo >= coreHi) continue
                    val b = blend[gy][gx]
                    yNew[gy][gx] = b * tmpI[i * fts + j] + (1f - b) * yNew[gy][gx] // RT intp
                }
            }
            // Snapshot this band's last HALO original rows for the next band, then write.
            savedFrom = max(b0, b1 - HALO)
            savedRows = px.copyOfRange((savedFrom - lo) * stride, (b1 - lo) * stride)
            IntStream.range(b0, b1).parallel().forEach { y ->
                val r = y - lo
                val row = px.copyOfRange(r * stride, (r + 1) * stride)
                val yo = yOld[r]; val yn = yNew[r]
                for (x in 0 until w) {
                    val f = yn[x] / max(yo[x], 0.00001f)
                    row[3 * x] *= f; row[3 * x + 1] *= f; row[3 * x + 2] *= f
                }
                val d = rgb.duplicate(); d.position(y * stride); d.put(row)
            }
            b0 = b1
        }
        return Result(sigma, contrast, (System.nanoTime() - t0) / 1_000_000)
    }
}
