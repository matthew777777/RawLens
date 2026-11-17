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
        /** Picture-fitted rate map accepted (see [StabPlanner.Calibration.rateMatrix]). */
        val matrixAccepted: Boolean = false,
        /** Held-out RMS (px) of the gyro model vs the fitted map. */
        val cvGyroPx: Double = Double.NaN,
        val cvFitPx: Double = Double.NaN,
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
        var rmsAfter = rms(r)
        // Picture-fitted rate map: meas = A·[ix, iy, iz, dt] per image axis.
        // Accepted only when it beats the gyro model on held-out frames in
        // both folds of a block cross-validation (no tuned threshold).
        val lowRates = lowBand(take.gyro)
        val lowTable = RateIntegral(LongArray(take.gyro.size) { take.gyro[it].timestampNanos }, lowRates)
        val ints = Array(pairs.size) { k ->
            val p = pairs[k]
            val a = tc[p.frame] + tau
            val b = tc[p.frame + 1] + tau
            val (ix, iy, iz) = table.between(a, b)
            val (lx, ly, lz) = lowTable.between(a, b)
            // [low x,y,z (bias-corrected), high x,y,z, dt]; index 0/1 stay the
            // full-band bias-corrected integrals for the gyro-model baseline.
            doubleArrayOf(lx - bx * dts[k], ly - by * dts[k], lz, ix - lx, iy - ly, iz - lz, dts[k])
        }
        val mx = DoubleArray(pairs.size) { pairs[it].dx }
        val my = DoubleArray(pairs.size) { pairs[it].dy }
        val wts = DoubleArray(pairs.size) { pairs[it].weight }
        val fold = IntArray(pairs.size) { (pairs[it].frame / CV_BLOCK) % 2 }
        var matrixOk = true
        var cvGyro = 0.0
        var cvFit = 0.0
        for (f in 0..1) {
            val ax = fitRows(ints, mx, wts) { fold[it] != f }
            val ay = fitRows(ints, my, wts) { fold[it] != f }
            if (ax == null || ay == null) { matrixOk = false; break }
            var eg = 0.0; var ef = 0.0; var c = 0
            for (k in pairs.indices) if (fold[k] == f) {
                val gx = mx[k] - fx * (ints[k][1] + ints[k][4])
                val gy = my[k] + fy * (ints[k][0] + ints[k][3])
                val fxr = mx[k] - dotN(ax, ints[k])
                val fyr = my[k] - dotN(ay, ints[k])
                eg += gx * gx + gy * gy; ef += fxr * fxr + fyr * fyr; c++
            }
            if (c < MIN_PAIRS / 2 || ef >= eg) matrixOk = false
            cvGyro += eg; cvFit += ef
        }
        var rateMatrix: DoubleArray? = null
        var rateOffset: DoubleArray? = null
        var rateMatrixHigh: DoubleArray? = null
        if (matrixOk) {
            val ax = fitRows(ints, mx, wts) { true }!!
            val ay = fitRows(ints, my, wts) { true }!!
            // dx = fx·iy', dy = −fy·ix'  ⇒  row y = A_x/fx, row x = −A_y/fy.
            rateMatrix = doubleArrayOf(
                -ay[0] / fy, -ay[1] / fy, -ay[2] / fy,
                ax[0] / fx, ax[1] / fx, ax[2] / fx,
                0.0, 0.0, 1.0,
            )
            rateMatrixHigh = doubleArrayOf(
                -ay[3] / fy, -ay[4] / fy, -ay[5] / fy,
                ax[3] / fx, ax[4] / fx, ax[5] / fx,
                0.0, 0.0, 1.0,
            )
            rateOffset = doubleArrayOf(-ay[6] / fy, ax[6] / fx, 0.0)
            var ss = 0.0
            for (k in pairs.indices) {
                val ex = mx[k] - dotN(ax, ints[k]); val ey = my[k] - dotN(ay, ints[k])
                ss += ex * ex + ey * ey
            }
            rmsAfter = sqrt(ss / (2 * pairs.size))
        }
        val lastCv = doubleArrayOf(sqrt(cvGyro / (2 * pairs.size)), sqrt(cvFit / (2 * pairs.size)))
        // τ maps frame time → gyro clock; the planner shifts gyro stamps onto
        // the frame clock, so it takes the opposite sign.
        val cal = StabPlanner.Calibration(
            gyroOffsetNs = -tau, biasX = bx, biasY = by,
            rateMatrix = rateMatrix, rateOffset = rateOffset, rateMatrixHigh = rateMatrixHigh,
        )
        return Result(cal, offsetOk, se, bx != 0.0 || by != 0.0, pairs.size, rmsBefore, rmsAfter, gainX, gainY,
            matrixAccepted = rateMatrix != null, cvGyroPx = lastCv[0], cvFitPx = lastCv[1])
    }

    private const val MIN_PAIRS = 30

    /**
     * Two-band split of the gyro: slow motion (hand sway, lever-arm
     * translation at close range) and fast shake (partly absorbed by OIS on
     * device) map to the image with different gains — measured 3500-5250 vs
     * ~1000 px/rad for yaw on device. Zero-phase boxcar of this width.
     */
    const val BAND_WINDOW_NS = 300_000_000L

    /** Low-band rates (3 per sample, sidecar units) of [gyro]; high band = raw − low. */
    fun lowBand(gyro: List<GyroSample>, windowNs: Long = BAND_WINDOW_NS): DoubleArray {
        val ri = RateIntegral(gyro)
        val half = windowNs / 2
        return DoubleArray(gyro.size * 3) { k -> ri.mean(k % 3, gyro[k / 3].timestampNanos, half) }
    }
    /** Cross-validation block length (frames): longer than the shake period. */
    private const val CV_BLOCK = 30

    private fun dotN(a: DoubleArray, x: DoubleArray): Double { var v = 0.0; for (i in a.indices) v += a[i] * x[i]; return v }

    /** Weighted least squares y ≈ a·x over rows where [use]; null if singular. */
    internal fun fitRows(x: Array<DoubleArray>, y: DoubleArray, w: DoubleArray, use: (Int) -> Boolean): DoubleArray? {
        val q = x.firstOrNull()?.size ?: return null
        val ata = Array(q) { DoubleArray(q) }
        val atb = DoubleArray(q)
        var n = 0
        for (k in x.indices) if (use(k)) {
            n++
            for (i in 0 until q) {
                atb[i] += w[k] * x[k][i] * y[k]
                for (j in 0 until q) ata[i][j] += w[k] * x[k][i] * x[k][j]
            }
        }
        if (n < MIN_PAIRS / 2) return null
        // Tiny ridge: an unexcited regressor (no roll in the take) gets a
        // zero coefficient instead of a singular solve.
        var diag = 0.0
        for (i in 0 until q) diag = max(diag, ata[i][i])
        if (diag <= 0) return null
        for (i in 0 until q) ata[i][i] += 1e-9 * diag + 1e-300
        val m = Array(q) { i -> DoubleArray(q + 1) { j -> if (j < q) ata[i][j] else atb[i] } }
        for (c in 0 until q) {
            var p = c
            for (r in c + 1 until q) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-300) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until q) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (j in c..q) m[r][j] -= f * m[c][j]
            }
        }
        return DoubleArray(q) { m[it][q] / m[it][it] }
    }

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
    internal class RateIntegral(private val t: LongArray, rates: DoubleArray) {
        constructor(gyro: List<GyroSample>) : this(
            LongArray(gyro.size) { gyro[it].timestampNanos },
            DoubleArray(gyro.size * 3) { k ->
                val g = gyro[k / 3]
                when (k % 3) { 0 -> g.xRadiansPerSecond.toDouble(); 1 -> g.yRadiansPerSecond.toDouble(); else -> g.zRadiansPerSecond.toDouble() }
            },
        )
        private val c = Array(3) { DoubleArray(t.size) }

        init {
            for (i in 1 until t.size) {
                val dt = (t[i] - t[i - 1]) * 1e-9
                for (a in 0 until 3) c[a][i] = c[a][i - 1] + 0.5 * (rates[3 * (i - 1) + a] + rates[3 * i + a]) * dt
            }
        }

        /** Mean rate over [time − half, time + half] (zero-phase boxcar), per axis. */
        fun mean(axis: Int, time: Long, half: Long): Double = (at(axis, time + half) - at(axis, time - half)) / (2 * half * 1e-9)

        fun between(a: Long, b: Long): Triple<Double, Double, Double> =
            Triple(at(0, b) - at(0, a), at(1, b) - at(1, a), at(2, b) - at(2, a))

        fun at(axis: Int, time: Long): Double {
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
