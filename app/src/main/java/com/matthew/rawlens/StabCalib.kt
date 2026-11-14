// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage-3 self-calibration: estimates the gyro↔frame time offset and gyro
 * bias from the take's own video (after Karpenko et al. 2011 and Gyroflow
 * sync). VidUltra assumes both are zero. A few ms of offset shows as wobble
 * that the path planner cannot remove.
 *
 * Input: per consecutive frame pair, the global image translation of the
 * frame centre region measured on low-res luma ([GlobalShift]), in ENCODE
 * pixels. Model, using the desktop content law ([GyroCameraFrameMapper]):
 *   dx = fx·∫ry dt,  dy = −fy·∫rx dt   over [tc_i + τ, tc_{i+1} + τ],
 * with the rate integrals read from a cumulative table, so a τ evaluation is
 * O(pairs).
 *
 * - Offset: Huber cost J(τ) on a 0.5 ms grid over ±[MAX_OFFSET_NS], then a
 *   parabolic refinement. Accepted only when the statistical standard error
 *   from the curvature, se = sqrt(2σ²/J''), is below a tenth of a frame
 *   interval, and the minimum is interior to the search range. Both limits
 *   are relative to measured quantities, not tuned constants.
 * - Bias: with τ fixed, per-pair residual / (focal·Δt) gives a bias sample
 *   per axis. The median is accepted when it exceeds 3× its robust standard
 *   error (1.4826·MAD/√N). Roll bias is not observable from translation and
 *   stays 0.
 *
 * Caveat: OIS that cannot be read back removes part of the image motion
 * the gyro sees. That shrinks the measured shifts by a gain, which does not
 * move the optimum τ, but it does bias residuals. So a pair is weighted by
 * its measurement confidence, and bias is only accepted when significant.
 *
 * Pure JVM.
 */
internal object StabCalib {
    const val MAX_OFFSET_NS = 60_000_000L
    private const val GRID_NS = 500_000L

    /** Measured centre translation from frame [frame] to [frame]+1 (encode px). */
    data class PairShift(val frame: Int, val dx: Double, val dy: Double, val weight: Double)

    data class Result(
        val calibration: StabPlanner.Calibration,
        val offsetAccepted: Boolean,
        val offsetStdErrNs: Double,
        val biasAccepted: Boolean,
        val pairsUsed: Int,
        val rmsBeforePx: Double,
        val rmsAfterPx: Double,
        /** Fitted image/gyro gain per axis (x = yaw→dx, y = pitch→dy): ≈1 ok, ≈0 OIS/no texture, < 0 axis/sign error. */
        val gainX: Double = Double.NaN,
        val gainY: Double = Double.NaN,
    )

    fun calibrate(take: StabSidecar.Take, shifts: List<PairShift>): Result? {
        val h = take.header
        val n = take.frameTimestampsNs.size
        val lens = StabPlanner.lensFor(take) ?: return null
        val fx = lens.fx * h.encodeW / h.cropW
        val fy = lens.fy * h.encodeH / h.cropH
        val ex = take.extras
        val tc = LongArray(n) {
            val e = ex?.exposureNs?.getOrNull(it)?.takeIf { v -> v > 0 } ?: 0L
            val s = ex?.rollingShutterSkewNs?.getOrNull(it)?.takeIf { v -> v > 0 } ?: 0L
            take.frameTimestampsNs[it] + e / 2 + s / 2
        }
        val table = RateIntegral(take.gyro)
        val pairs = shifts.filter { it.frame in 0 until n - 1 && it.weight > 0 && it.dx.isFinite() && it.dy.isFinite() }
        if (pairs.size < MIN_PAIRS) return null

        fun residuals(tau: Long, bx: Double, by: Double, out: DoubleArray) {
            for ((k, p) in pairs.withIndex()) {
                val a = tc[p.frame] + tau
                val b = tc[p.frame + 1] + tau
                val dt = (b - a) * 1e-9
                val (ix, iy, _) = table.between(a, b)
                out[2 * k] = p.dx - fx * (iy - by * dt)
                out[2 * k + 1] = p.dy + fy * (ix - bx * dt)
            }
        }

        val r = DoubleArray(pairs.size * 2)
        // Huber scale from the zero-offset residuals (robust to outliers).
        residuals(0L, 0.0, 0.0, r)
        val delta = max(0.25, 1.4826 * mad(r))
        fun cost(tau: Long): Double {
            residuals(tau, 0.0, 0.0, r)
            var j = 0.0
            for ((k, p) in pairs.withIndex()) {
                j += p.weight * (huber(r[2 * k], delta) + huber(r[2 * k + 1], delta))
            }
            return j
        }
        val steps = (MAX_OFFSET_NS / GRID_NS).toInt()
        val costs = DoubleArray(2 * steps + 1) { cost((it - steps) * GRID_NS) }
        var best = 0
        for (i in costs.indices) if (costs[i] < costs[best]) best = i
        val rmsBefore = rms(r.also { residuals(0L, 0.0, 0.0, it) })

        var tau = 0L
        var se = Double.POSITIVE_INFINITY
        var offsetOk = false
        if (best in 1 until costs.size - 1) {
            val c0 = costs[best - 1]
            val c1 = costs[best]
            val c2 = costs[best + 1]
            val curv = (c0 - 2 * c1 + c2) / (GRID_NS.toDouble() * GRID_NS)
            val frac = if (c0 - 2 * c1 + c2 > 0) 0.5 * (c0 - c2) / (c0 - 2 * c1 + c2) else 0.0
            val tauD = ((best - steps) + frac) * GRID_NS
            val dof = max(1, 2 * pairs.size - 1)
            // Huber cost ≈ ½r² in the core: σ² from the minimum cost.
            val sigma2 = 2 * c1 / dof
            if (curv > 0) se = sqrt(2 * sigma2 / curv)
            val period = median(LongArray(n - 1) { tc[it + 1] - tc[it] }.map { it.toDouble() }.toDoubleArray())
            offsetOk = se < 0.1 * period
            if (offsetOk) tau = tauD.toLong()
        }

        // Bias per axis with τ fixed, fitted jointly with an image/gyro gain:
        // meas = g·pred + c·dt. Hidden OIS (no OIS samples) shrinks image
        // motion by a gain; fitting bias alone turns that into fake bias on
        // a pan (seen on device: 0.037 rad/s). One robust refit drops >3σ
        // outliers; c is accepted only when |c| > 3·SE(c) and g > 0.
        residuals(tau, 0.0, 0.0, r)
        val px = DoubleArray(pairs.size)
        val py = DoubleArray(pairs.size)
        val dts = DoubleArray(pairs.size)
        for ((k, p) in pairs.withIndex()) {
            dts[k] = (tc[p.frame + 1] - tc[p.frame]) * 1e-9
            px[k] = p.dx - r[2 * k]          // prediction = meas − residual
            py[k] = p.dy - r[2 * k + 1]
        }
        val gainX = gainOnly(DoubleArray(pairs.size) { pairs[it].dx }, px)
        val gainY = gainOnly(DoubleArray(pairs.size) { pairs[it].dy }, py)
        var by = gainBias(DoubleArray(pairs.size) { pairs[it].dx }, px, dts)?.let { (g, c) -> -c / (g * fx) } ?: 0.0
        var bx = gainBias(DoubleArray(pairs.size) { pairs[it].dy }, py, dts)?.let { (g, c) -> c / (g * fy) } ?: 0.0
        // The planner applies bias with gain 1. A small fitted gain inflates
        // c/g (device: 0.154 rad/s made the residual 6.3 → 10.5 px), so keep
        // a bias only if it lowers that axis's robust residual as applied.
        fun axisErr(bxT: Double, byT: Double, axis: Int): Double {
            residuals(tau, bxT, byT, r)
            return median(DoubleArray(pairs.size) { abs(r[2 * it + axis]) })
        }
        if (by != 0.0 && axisErr(0.0, by, 0) >= axisErr(0.0, 0.0, 0)) by = 0.0
        if (bx != 0.0 && axisErr(bx, 0.0, 1) >= axisErr(0.0, 0.0, 1)) bx = 0.0
        residuals(tau, bx, by, r)
        val rmsAfter = rms(r)
        // τ maps frame time → gyro clock; the planner shifts gyro stamps onto
        // the frame clock, so it takes the opposite sign.
        val cal = StabPlanner.Calibration(gyroOffsetNs = -tau, biasX = bx, biasY = by)
        return Result(cal, offsetOk, se, bx != 0.0 || by != 0.0, pairs.size, rmsBefore, rmsAfter, gainX, gainY)
    }

    private const val MIN_PAIRS = 30

    /**
     * Least squares meas = g·pred + c·dt with one 3σ (robust) outlier pass.
     * Returns (g, c) when g > 0 and c is significant (> 3 SE), else null.
     */
    /** Least-squares gain meas ≈ g·pred (diagnostic). */
    internal fun gainOnly(meas: DoubleArray, pred: DoubleArray): Double {
        var smp = 0.0
        var spp = 0.0
        for (i in meas.indices) { smp += meas[i] * pred[i]; spp += pred[i] * pred[i] }
        return if (spp > 0) smp / spp else Double.NaN
    }

    internal fun gainBias(meas: DoubleArray, pred: DoubleArray, dt: DoubleArray): Pair<Double, Double>? {
        var keep = BooleanArray(meas.size) { true }
        var result: DoubleArray? = null
        repeat(2) { pass ->
            var spp = 0.0; var spd = 0.0; var sdd = 0.0; var smp = 0.0; var smd = 0.0; var n = 0
            for (i in meas.indices) if (keep[i]) {
                spp += pred[i] * pred[i]; spd += pred[i] * dt[i]; sdd += dt[i] * dt[i]
                smp += meas[i] * pred[i]; smd += meas[i] * dt[i]; n++
            }
            val det = spp * sdd - spd * spd
            if (n < MIN_PAIRS || det <= 1e-12 * spp * sdd) return null
            val g = (smp * sdd - smd * spd) / det
            val c = (smd * spp - smp * spd) / det
            val res = DoubleArray(meas.size) { meas[it] - g * pred[it] - c * dt[it] }
            var ss = 0.0
            for (i in meas.indices) if (keep[i]) ss += res[i] * res[i]
            val sigma2 = ss / max(1, n - 2)
            val seC = sqrt(sigma2 * spp / det)
            result = doubleArrayOf(g, c, seC)
            if (pass == 0) {
                val kept = res.filterIndexed { i, _ -> keep[i] }.toDoubleArray()
                val lim = 3 * 1.4826 * mad(kept)
                keep = BooleanArray(meas.size) { keep[it] && abs(res[it]) <= lim }
            }
        }
        val (g, c, se) = result ?: return null
        return if (g > 0 && abs(c) > 3 * se) g to c else null
    }

    private fun huber(r: Double, d: Double): Double {
        val a = abs(r)
        return if (a <= d) 0.5 * r * r else d * (a - 0.5 * d)
    }

    private fun rms(r: DoubleArray): Double {
        var s = 0.0
        for (v in r) s += v * v
        return sqrt(s / max(1, r.size))
    }

    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
    }

    private fun mad(a: DoubleArray): Double {
        val m = median(a)
        return median(DoubleArray(a.size) { abs(a[it] - m) })
    }

    /** Cumulative trapezoid integral of desktop rates, linear between samples. */
    private class RateIntegral(gyro: List<GyroSample>) {
        private val t = LongArray(gyro.size) { gyro[it].timestampNanos }
        private val c = Array(3) { DoubleArray(gyro.size) }

        init {
            for (i in 1 until gyro.size) {
                val dt = (t[i] - t[i - 1]) * 1e-9
                val a = gyro[i - 1]
                val b = gyro[i]
                c[0][i] = c[0][i - 1] + 0.5 * (a.xRadiansPerSecond + b.xRadiansPerSecond) * dt
                c[1][i] = c[1][i - 1] + 0.5 * (a.yRadiansPerSecond + b.yRadiansPerSecond) * dt
                c[2][i] = c[2][i - 1] + 0.5 * (a.zRadiansPerSecond + b.zRadiansPerSecond) * dt
            }
        }

        fun between(a: Long, b: Long): Triple<Double, Double, Double> =
            Triple(at(0, b) - at(0, a), at(1, b) - at(1, a), at(2, b) - at(2, a))

        private fun at(axis: Int, time: Long): Double {
            if (t.isEmpty()) return 0.0
            if (time <= t[0]) return c[axis][0]
            if (time >= t[t.size - 1]) return c[axis][t.size - 1]
            var lo = 0
            var hi = t.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (t[mid] <= time) lo = mid else hi = mid
            }
            val f = (time - t[lo]).toDouble() / max(1L, t[hi] - t[lo])
            // linear rate inside the interval → quadratic integral; linear is
            // within 0.1 % at 200+ Hz and keeps J(τ) smooth.
            return c[axis][lo] + (c[axis][hi] - c[axis][lo]) * f
        }
    }

    /**
     * Global translation between two low-res luma images by coarse-to-fine
     * Lucas–Kanade on the central region. Returns null when the image has
     * too little texture: the structure tensor's smaller eigenvalue is
     * compared against the image's own residual noise.
     */
    object GlobalShift {
        /**
         * [confidence] = texture vs residual (legacy diagnostic); [stdErr] =
         * standard error of the shift along its weakest direction,
         * sqrt(σ²/λmin) in luma px. That is the acceptance measure: it stays
         * meaningful on noisy log footage, where the residual never gets small.
         */
        data class Shift(val dx: Double, val dy: Double, val confidence: Double, val stdErr: Double = Double.NaN)

        fun measure(prev: FloatArray, cur: FloatArray, w: Int, h: Int): Shift? {
            require(prev.size == w * h && cur.size == w * h) { "size" }
            val levels = ArrayList<Triple<FloatArray, FloatArray, IntArray>>()
            var a = prev
            var b = cur
            var lw = w
            var lh = h
            levels.add(Triple(a, b, intArrayOf(lw, lh)))
            while (min(lw, lh) >= 64 && levels.size < 4) {
                val nw = lw / 2
                val nh = lh / 2
                a = half(a, lw, lh)
                b = half(b, lw, lh)
                lw = nw
                lh = nh
                levels.add(Triple(a, b, intArrayOf(lw, lh)))
            }
            var dx = 0.0
            var dy = 0.0
            var conf = 0.0
            var se = Double.NaN
            for (li in levels.indices.reversed()) {
                val (p, c, d) = levels[li]
                if (li != levels.size - 1) {
                    dx *= 2
                    dy *= 2
                }
                repeat(6) {
                    val step = lkStep(p, c, d[0], d[1], dx, dy) ?: return null
                    dx += step[0]
                    dy += step[1]
                    conf = step[2]
                    se = step[3]
                    if (abs(step[0]) + abs(step[1]) < 0.01) return@repeat
                }
            }
            return if (conf > 0) Shift(dx, dy, conf, se) else null
        }

        private fun half(src: FloatArray, w: Int, h: Int): FloatArray {
            val nw = w / 2
            val nh = h / 2
            return FloatArray(nw * nh) { i ->
                val x = (i % nw) * 2
                val y = (i / nw) * 2
                0.25f * (src[y * w + x] + src[y * w + x + 1] + src[(y + 1) * w + x] + src[(y + 1) * w + x + 1])
            }
        }

        /** One LK step on the central 80 % (cur sampled at +d). [ddx, ddy, confidence]. */
        private fun lkStep(p: FloatArray, c: FloatArray, w: Int, h: Int, dx: Double, dy: Double): DoubleArray? {
            val x0 = (w * 0.1).toInt() + 1
            val x1 = (w * 0.9).toInt() - 1
            val y0 = (h * 0.1).toInt() + 1
            val y1 = (h * 0.9).toInt() - 1
            var gxx = 0.0
            var gxy = 0.0
            var gyy = 0.0
            var bx = 0.0
            var by = 0.0
            var ee = 0.0
            var cnt = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val sx = x + dx
                val sy = y + dy
                if (sx < 0 || sy < 0 || sx >= w - 1 || sy >= h - 1) continue
                val gx = 0.5 * (p[y * w + x + 1] - p[y * w + x - 1])
                val gy = 0.5 * (p[(y + 1) * w + x] - p[(y - 1) * w + x])
                val e = bilinear(c, w, sx, sy) - p[y * w + x]
                gxx += gx * gx
                gxy += gx * gy
                gyy += gy * gy
                bx += gx * e
                by += gy * e
                ee += e * e
                cnt++
            }
            if (cnt < 64) return null
            val det = gxx * gyy - gxy * gxy
            if (det <= 0) return null
            val tr = gxx + gyy
            val lmin = 0.5 * (tr - sqrt(max(0.0, tr * tr - 4 * det)))
            // Texture must dominate the residual: λmin per pixel vs mean e².
            val conf = (lmin / cnt) / (ee / cnt + 1e-6)
            // σ² of the per-pixel residual; shift covariance σ²·G⁻¹.
            val sigma2 = ee / max(1, cnt - 2)
            val se = if (lmin > 0) sqrt(sigma2 / lmin) else Double.POSITIVE_INFINITY
            val ddx = -(gyy * bx - gxy * by) / det
            val ddy = -(gxx * by - gxy * bx) / det
            return doubleArrayOf(ddx, ddy, conf, se)
        }

        private fun bilinear(img: FloatArray, w: Int, x: Double, y: Double): Double {
            val ix = x.toInt()
            val iy = y.toInt()
            val fx = x - ix
            val fy = y - iy
            val i = iy * w + ix
            val a = img[i] + (img[i + 1] - img[i]) * fx
            val b = img[i + w] + (img[i + w + 1] - img[i + w]) * fx
            return a + (b - a) * fy
        }
    }
}
