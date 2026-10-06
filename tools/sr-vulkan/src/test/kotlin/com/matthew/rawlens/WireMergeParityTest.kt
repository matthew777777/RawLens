// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.max

/**
 * Wire-regime merge parity: [RawSrBayerMerge] must reproduce
 * `merge.py::accumulate` (Alg. 4) bit-near on thin sub-guide structures where
 * bead/zipper energy lives — the regime the smooth-scene goldens in
 * [JamyCoreParityTest] do not cover.
 *
 * Phantom: 64x48 RGGB, bright field with 1-2px dark wires (vertical,
 * horizontal, diagonal), ref + 2 constant-subpixel-shift frames, constant
 * isotropic covariance (sigma 0.766, the blind-estimator antenna regime),
 * constant flow per frame (bilinear == nearest exactly, so the sanctioned
 * flow deviation is moot), constant r = 1 (bilinear == nearest exactly, so
 * the FULL frame compares tight with no border margin), chromaSigmaMpy = 1.
 *
 * The in-test oracle transcribes `accumulate` op-for-op in double precision,
 * including the negative-frac covariance extrapolation (`math.modf` +
 * `max(int(kmap), 0)`), which the 12px-margin golden test never pins.
 * Reference-first (oracle) vs reference-last (ours) summation accounts for
 * 1-ulp differences; the bound below is 1000x looser than that and 4x
 * tighter than the golden merge bound.
 */
class WireMergeParityTest {
    private val rawW = 64
    private val rawH = 48
    private val quadsW = 32
    private val quadsH = 24
    private val tileSize = 16
    private val covIso = 0.5871653f // 0.766^2: blind-estimator isotropic regime

    private fun sceneAt(x: Int, y: Int): Double {
        if (x == 20) return 0.08 // 1px vertical wire
        if (y == 30) return 0.08 // 1px horizontal wire
        if (x - y == 8) return 0.08 // 1px diagonal wire
        if (x == 44 || x == 45) return 0.08 // 2px vertical wire
        return 0.8 // bright field
    }

    private fun frameSamples(dx: Double, dy: Double): FloatArray {
        val out = FloatArray(rawW * rawH)
        for (y in 0 until rawH) for (x in 0 until rawW) {
            // Nearest scene evaluation: wires stay razor sharp (worst case).
            val sx = (x - dx + 0.5).toInt().coerceIn(0, rawW - 1)
            val sy = (y - dy + 0.5).toInt().coerceIn(0, rawH - 1)
            out[y * rawW + x] = sceneAt(sx, sy).toFloat()
        }
        return out
    }

    private fun constCov(): RawSrKernelCovariance.MatrixField {
        val values = FloatArray(quadsW * quadsH * 4)
        for (i in 0 until quadsW * quadsH) {
            values[i * 4] = covIso
            values[i * 4 + 1] = 0f
            values[i * 4 + 2] = 0f
            values[i * 4 + 3] = covIso
        }
        return RawSrKernelCovariance.MatrixField(quadsW, quadsH, values)
    }

    private fun constFlow(dx: Double, dy: Double): RawSrAlignmentField {
        val cols = rawW / tileSize
        val rows = rawH / tileSize
        val tiles = List(cols * rows) {
            RawSrTileFlow(0f, 0f, dx.toFloat(), dy.toFloat(), 0f, true)
        }
        return RawSrAlignmentField(rawW, rawH, tileSize, cols, rows, tiles)
    }

    private fun constRobust(): RawSrRobustness.FrameRobustness =
        RawSrRobustness.FrameRobustness(
            quadsW, quadsH,
            FloatArray(quadsW * quadsH) { 1f },
            IntArray(quadsW * quadsH)
        )

    /**
     * Jamy-L `merge.py::accumulate` transcribed op-for-op (scale 1, RGGB,
     * double precision). Flow/r pass straight through (constant inputs);
     * tap loops and the reference-first order mirror the reference.
     */
    private fun oracleAccumulate(
        comp: FloatArray,
        dx: Double,
        dy: Double,
        cov: RawSrKernelCovariance.MatrixField,
        num: DoubleArray,
        den: DoubleArray
    ) {
        for (hrI in 0 until rawH) for (hrJ in 0 until rawW) {
            val lrX = (hrJ + 0.5)
            val lrY = (hrI + 0.5)
            val movX = lrX + dx
            val movY = lrY + dy
            if (!(movX >= 0.0 && movX < rawW && movY >= 0.0 && movY < rawH)) continue
            // Covariance sampling with reference modf/negative-frac semantics.
            val kmapJ = movX / 2.0 - 0.5
            val kmapI = movY / 2.0 - 0.5
            val intJ = kmapJ.toInt() // truncate toward zero, like int()
            val intI = kmapI.toInt()
            val fracX = kmapJ - intJ
            val fracY = kmapI - intI
            val floorX = max(intJ, 0)
            val floorY = max(intI, 0)
            val ceilX = minOf(floorX + 1, quadsW - 1)
            val ceilY = minOf(floorY + 1, quadsH - 1)
            fun c(qx: Int, qy: Int, k: Int): Double =
                cov.values[(qy * quadsW + qx) * 4 + k].toDouble()
            val topXx = c(floorX, floorY, 0) + fracX * (c(ceilX, floorY, 0) - c(floorX, floorY, 0))
            val topXy = c(floorX, floorY, 1) + fracX * (c(ceilX, floorY, 1) - c(floorX, floorY, 1))
            val topYy = c(floorX, floorY, 3) + fracX * (c(ceilX, floorY, 3) - c(floorX, floorY, 3))
            val botXx = c(floorX, ceilY, 0) + fracX * (c(ceilX, ceilY, 0) - c(floorX, ceilY, 0))
            val botXy = c(floorX, ceilY, 1) + fracX * (c(ceilX, ceilY, 1) - c(floorX, ceilY, 1))
            val botYy = c(floorX, ceilY, 3) + fracX * (c(ceilX, ceilY, 3) - c(floorX, ceilY, 3))
            val ixx = topXx + fracY * (botXx - topXx)
            val ixy = topXy + fracY * (botXy - topXy)
            val iyy = topYy + fracY * (botYy - topYy)
            val det = ixx * iyy - ixy * ixy
            val invDet = 1.0 / det
            val pxx = invDet * iyy
            val pxy = -invDet * ixy
            val pyy = invDet * ixx
            val centerJ = movX.toInt() // floor for non-negative
            val centerI = movY.toInt()
            val movJ = movX - 0.5
            val movI = movY - 0.5
            val p = hrI * rawW + hrJ
            for (di in -1..1) for (dj in -1..1) {
                val j = centerJ + dj
                val i = centerI + di
                if (!(j in 0 until rawW && i in 0 until rawH)) continue
                val channel = (i and 1) + (j and 1)
                val sample = comp[i * rawW + j].toDouble()
                val distX = j - movJ
                val distY = i - movI
                val z = max(pxx * distX * distX + 2 * pxy * distX * distY + pyy * distY * distY, 0.0)
                val w = exp(-0.5 * z)
                val o = p * 3 + channel
                num[o] += w * sample
                den[o] += w
            }
        }
    }

    @Test fun wireMergeMatchesOracleFullFrame() {
        val shifts = listOf(0.0 to 0.0, 0.35 to -0.25, -0.4 to 0.55)
        val samples = shifts.map { (dx, dy) -> frameSamples(dx, dy) }
        val frames = samples.mapIndexed { i, s ->
            val (dx, dy) = shifts[i]
            RawSrBayerMerge.MergeFrame(
                rawW, rawH, s, BayerPattern.RGGB, 0, 0, constCov(),
                flow = if (i == 0) null else constFlow(dx, dy),
                robustness = if (i == 0) null else constRobust()
            )
        }
        val merged = RawSrBayerMerge.merge(
            frames[0], frames.subList(1, 3),
            scale = RawSrLinearScale.X1, chromaSigmaMpy = 1.0
        )
        // Oracle: identical inputs, reference-first order. Flows enter as
        // the stored float tiles (inputs, not computed values).
        val num = DoubleArray(rawW * rawH * 3)
        val den = DoubleArray(rawW * rawH * 3)
        for (i in samples.indices) {
            val (dx, dy) = shifts[i]
            oracleAccumulate(
                samples[i],
                if (i == 0) 0.0 else dx.toFloat().toDouble(),
                if (i == 0) 0.0 else dy.toFloat().toDouble(),
                constCov(), num, den
            )
        }
        var rgbD = 0.0
        var denD = 0.0
        for (o in 0 until rawW * rawH * 3) {
            val gold = num[o] / den[o]
            rgbD = maxOf(rgbD, kotlin.math.abs(gold - merged.rgb[o].toDouble()))
            denD = maxOf(denD, kotlin.math.abs(den[o] - merged.denominator[o].toDouble()))
        }
        println("wire merge: rgbMaxAbs=$rgbD denMaxAbs=$denD")
        assertTrue("rgb diff $rgbD", rgbD < 5e-6)
        assertTrue("den diff $denD", denD < 5e-6)
        // Regime check: the phantom must actually bead (period-2 energy on
        // wires), or the parity bound above guards nothing.
        fun beadEnergy(rgb: FloatArray): Double {
            var acc = 0.0
            var n = 0
            for (y in 1 until rawH - 1) for (x in 1 until rawW - 1) {
                val g = rgb[(y * rawW + x) * 3 + 1].toDouble()
                if (g < 0.5) {
                    val l = rgb[(y * rawW + x - 1) * 3 + 1].toDouble()
                    val r = rgb[(y * rawW + x + 1) * 3 + 1].toDouble()
                    acc += kotlin.math.abs(l - 2 * g + r)
                    n++
                }
            }
            return acc / max(n, 1)
        }
        val beadGot = beadEnergy(merged.rgb)
        val goldRgb = FloatArray(rawW * rawH * 3) { (num[it] / den[it]).toFloat() }
        val beadGold = beadEnergy(goldRgb)
        println("wire bead: ours=$beadGot oracle=$beadGold")
        assertTrue("phantom does not bead: $beadGot", beadGot > 1e-3)
        assertTrue("bead mismatch $beadGot vs $beadGold",
            kotlin.math.abs(beadGot - beadGold) < 1e-6)
    }
}
