// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Phase-0 harness for [RawWhiteBalanceFallback]: synthetic Bayer scenes with
 * known illuminant (shading, texture, speculars, signal-dependent noise),
 * scored by angular error against the pre-2026-11-13 estimator kept below as
 * [estimateLegacy]. Identity XYZ->camera matrices, so the locus is the
 * blackbody itself. Real-capture scoring (logical-route HAL AWB as label)
 * goes through the same [angularError].
 */
class RawWhiteBalanceBenchmarkTest {
    private val identity = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    private val locus = requireNotNull(RawWhiteBalanceFallback.Locus.from(identity, identity, null, null, 21, 17))
    private val black = FloatArray(4) { 64f }
    private val white = 4095f
    private val w = 512
    private val h = 384

    private fun neutralAt(k: Double, tint: Double = 0.0): DoubleArray {
        val xyz = RawWhiteBalanceFallback.Locus.planckXyz(k)
        // tint > 0 is green (fluorescent): R/G and B/G both drop.
        return doubleArrayOf(xyz.x / xyz.y * exp(-tint), 1.0, xyz.z / xyz.y * exp(-tint))
    }

    class Patch(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val albedo: DoubleArray)

    /** Patches painted in order over a [base] albedo; later patches win. */
    private fun render(
        illum: DoubleArray, base: DoubleArray, patches: List<Patch>, exposure: Double,
        noiseA: Double, speculars: Int = 0, seed: Int = 1
    ): ByteBuffer {
        val rnd = Random(seed)
        val pattern = BayerPattern.fromCamera2(0)
        val spots = List(speculars) { doubleArrayOf(rnd.nextDouble() * w, rnd.nextDouble() * h) }
        val texture = DoubleArray((w / 8 + 1) * (h / 8 + 1)) { 0.9 + 0.2 * rnd.nextDouble() }
        val buf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x.toDouble() / w; val fy = y.toDouble() / h
            val a = patches.lastOrNull { fx >= it.x0 && fx < it.x1 && fy >= it.y0 && fy < it.y1 }?.albedo ?: base
            val c = when (pattern.colorAt(x, y)) { CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2 }
            val shade = (0.6 + 0.4 * fx) * texture[(y / 8) * (w / 8 + 1) + x / 8]
            var level = a[c] * illum[c] * exposure * shade
            for (s in spots) {
                val d2 = (x - s[0]) * (x - s[0]) + (y - s[1]) * (y - s[1])
                if (d2 < 36) level += 0.5 * illum[c] * exp(-d2 / 12)
            }
            level += rnd.nextGaussian() * sqrt(noiseA * level.coerceAtLeast(0.0) + 1e-7)
            val code = (64 + level.coerceIn(0.0, 1.0) * (4095 - 64)).toInt()
            buf.putShort((y * w + x) * 2, code.toShort())
        }
        return buf
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }

    private fun angularError(a: DoubleArray?, b: DoubleArray): Double {
        if (a == null) return 45.0
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a.sumOf { it * it }); val nb = sqrt(b.sumOf { it * it })
        return Math.toDegrees(acos((dot / (na * nb)).coerceIn(-1.0, 1.0)))
    }

    class Scene(val name: String, val truth: DoubleArray, val buf: ByteBuffer, val ev100: Double?)

    private val beige = doubleArrayOf(0.85, 0.70, 0.50)
    private val skin = doubleArrayOf(0.70, 0.47, 0.37)
    private val wood = doubleArrayOf(0.50, 0.32, 0.18)
    private fun gray(l: Double) = doubleArrayOf(l, l, l)

    private fun chart(seed: Int): List<Patch> {
        val rnd = Random(seed)
        return List(24) { i ->
            val cx = (i % 6) / 6.0; val cy = (i / 6) / 4.0
            val alb = if (i % 4 == 0) gray(0.2 + 0.15 * (i / 4)) else
                DoubleArray(3) { 0.05 + 0.8 * rnd.nextDouble() }
            Patch(cx, cy, cx + 1 / 6.0, cy + 0.25, alb)
        }
    }

    private fun scenes(): List<Scene> {
        val out = ArrayList<Scene>()
        val n5000 = neutralAt(5000.0)
        out += Scene("beige wall 5000K", n5000, render(n5000, beige, listOf(
            Patch(0.10, 0.60, 0.18, 0.80, gray(0.85)), Patch(0.70, 0.10, 0.95, 0.15, gray(0.08)),
            Patch(0.40, 0.70, 0.60, 0.95, wood), Patch(0.80, 0.60, 0.86, 0.72, gray(0.5))),
            exposure = 0.7, noiseA = 0.0005, speculars = 6), 10.0)
        val n4500 = neutralAt(4500.0)
        out += Scene("skin portrait 4500K", n4500, render(n4500, beige, listOf(
            Patch(0.30, 0.10, 0.70, 0.70, skin), Patch(0.25, 0.70, 0.75, 1.0, gray(0.35)),
            Patch(0.35, 0.05, 0.65, 0.18, gray(0.05))),
            exposure = 0.8, noiseA = 0.0005, speculars = 4, seed = 2), 9.0)
        val fl = neutralAt(4000.0, tint = 0.06)
        out += Scene("fluorescent chart 4000K+g", fl, render(fl, gray(0.3), chart(3),
            exposure = 0.9, noiseA = 0.0005, seed = 3), 8.0)
        val n3000 = neutralAt(3000.0)
        out += Scene("low light chart 3000K", n3000, render(n3000, gray(0.3), chart(4),
            exposure = 0.08, noiseA = 0.004, seed = 4), 4.0)
        val n6000 = neutralAt(6000.0)
        out += Scene("outdoor grass/sky 6000K", n6000, render(n6000, doubleArrayOf(0.08, 0.35, 0.05), listOf(
            Patch(0.0, 0.0, 1.0, 0.35, doubleArrayOf(0.25, 0.45, 0.9)), Patch(0.3, 0.55, 0.7, 0.8, gray(0.4))),
            exposure = 0.9, noiseA = 0.0003, seed = 5), 14.5)
        val n2800 = neutralAt(2800.0)
        out += Scene("tungsten beige room 2800K", n2800, render(n2800, beige, listOf(
            Patch(0.0, 0.0, 1.0, 0.12, gray(0.85)), Patch(0.5, 0.5, 0.7, 0.9, wood)),
            exposure = 0.9, noiseA = 0.001, speculars = 3, seed = 6), 6.0)
        return out
    }

    @Test fun `new estimator beats the legacy one on the synthetic set`() {
        var newSum = 0.0; var oldSum = 0.0
        val rows = StringBuilder("scene                         legacy    new\n")
        val errs = HashMap<String, Double>()
        for (s in scenes()) {
            val stride = w * 2
            val t0 = System.nanoTime()
            val n = RawWhiteBalanceFallback.estimate(s.buf, stride, 2, w, h, 0, black, white, locus, s.ev100)
            val ms = (System.nanoTime() - t0) / 1e6
            val o = estimateLegacy(s.buf, stride, 2, w, h, 0, black, white, locus)
            val en = angularError(n, s.truth); val eo = angularError(o, s.truth)
            errs[s.name] = en
            newSum += en; oldSum += eo
            rows.append(String.format("%-28s %6.2f° %6.2f°  (%.1f ms)\n", s.name, eo, en, ms))
        }
        val count = scenes().size
        rows.append(String.format("%-28s %6.2f° %6.2f°", "mean", oldSum / count, newSum / count))
        println(rows)
        assertTrue(rows.toString(), newSum < oldSum)
        for ((name, e) in errs) assertTrue("$name: $e\n$rows", e < 1.5)
    }

    // ---- legacy estimator (verbatim from before 2026-11-13) for comparison ----
    private val GRID_X = 64
    private val GRID_Y = 48
    private val CLIP = 0.92
    private val DARK = 0.01
    private val MIN_QUADS = 64
    private val MIN_GRAY_QUADS = 24
    private val GRAY_TOLERANCE = 0.12
    private val NEUTRAL_MIN = 0.1
    private val NEUTRAL_MAX = 2.0
    private val DAYLIGHT_K = 5500.0

private fun estimateLegacy(
    buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
    cfa: Int, black: FloatArray, white: Float, locus: RawWhiteBalanceFallback.Locus? = null
): DoubleArray? {
    if (pixelStride != 2 || rowStride <= 0 || width < 4 || height < 4) return null
    if (black.size != 4 || !white.isFinite()) return null
    val pattern = runCatching { BayerPattern.fromCamera2(cfa) }.getOrNull() ?: return null
    val range = DoubleArray(4) { (white - black[it]).toDouble() }
    if (range.any { it <= 1.0 }) return null
    val lastReadable = buffer.limit() - 2
    val stepX = maxOf(2, (width / GRID_X) and 1.inv())
    val stepY = maxOf(2, (height / GRID_Y) and 1.inv())
    // Whole-frame sums (gray-world) and locus-gray sums side by side.
    var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
    var grayR = 0.0; var grayG = 0.0; var grayB = 0.0
    var quads = 0
    var grayQuads = 0
    val v = DoubleArray(4)
    var y = 0
    while (y + 1 < height) {
        var x = 0
        while (x + 1 < width) {
            var usable = true
            for (i in 0..3) {
                val offset = (y + (i shr 1)).toLong() * rowStride + (x + (i and 1)).toLong() * 2
                if (offset > lastReadable) { usable = false; break }
                val code = buffer.getShort(offset.toInt()).toInt() and 0xffff
                val n = (code - black[i]) / range[i]
                if (n >= CLIP) { usable = false; break }
                v[i] = n.coerceAtLeast(0.0)
            }
            if (usable) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (i in 0..3) when (pattern.colorAt(i and 1, i shr 1)) {
                    CfaColor.RED -> r += v[i]
                    CfaColor.GREEN -> g += v[i] * 0.5
                    CfaColor.BLUE -> b += v[i]
                }
                if (g >= DARK) {
                    sumR += r; sumG += g; sumB += b
                    quads++
                    if (locus != null && r > 0.0 && b > 0.0) {
                        val d = locus.project(ln(r / g), ln(b / g))[2]
                        if (d < GRAY_TOLERANCE) {
                            // Brighter, closer-to-locus quads carry the vote.
                            val w = g * (1.0 - d / GRAY_TOLERANCE)
                            grayR += r * w; grayG += g * w; grayB += b * w
                            grayQuads++
                        }
                    }
                }
            }
            x += stepX
        }
        y += stepY
    }
    if (quads < MIN_QUADS || sumG <= 0.0) return null
    val neutral = if (locus == null) {
        doubleArrayOf(sumR / sumG, 1.0, sumB / sumG)
    } else if (grayQuads >= MIN_GRAY_QUADS && grayG > 0.0 && grayR > 0.0 && grayB > 0.0) {
        val p = locus.project(ln(grayR / grayG), ln(grayB / grayG))
        doubleArrayOf(exp(p[0]), 1.0, exp(p[1]))
    } else if (sumR > 0.0 && sumB > 0.0) {
        // No near-neutral evidence (one-colour frame): the whole-frame
        // mean only picks a direction along the locus, halved toward daylight.
        val p = locus.project(ln(sumR / sumG), ln(sumB / sumG))
        val q = locus.at((p[3] + locus.indexOf(DAYLIGHT_K)) / 2)
        doubleArrayOf(exp(q[0]), 1.0, exp(q[1]))
    } else return null
    return neutral.takeIf { n -> n.all { it.isFinite() } &&
        n[0] in NEUTRAL_MIN..NEUTRAL_MAX && n[2] in NEUTRAL_MIN..NEUTRAL_MAX }
}
}
