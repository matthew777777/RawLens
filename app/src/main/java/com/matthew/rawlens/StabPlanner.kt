// SPDX-License-Identifier: GPL-3.0-or-later
// Rolling-shutter band model, virtual-camera projection and the border
// "fits" test follow VidUltra's Stabilizer.java (Pixelgamer4k/VidUltra,
// GPL-3.0); the path planning is a whole-take L1 optimization (see
// StabPathSolver) instead of VidUltra's causal easing.
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stabilization planner v2: sidecar → per-frame virtual camera + per-row-band
 * source rotations, with rolling shutter and lens distortion.
 *
 * Model (all on the SENSOR_TIMESTAMP clock):
 * - The real camera has orientation C(t) (camera→world), integrated from
 *   gyro with optional [Calibration] (time offset, bias).
 * - Sensor row y of frame i was exposed at t(y) = ts + exp/2 + skew·y/H
 *   (top-to-bottom readout of the stored RAW buffer).
 * - A smooth virtual camera V_i renders the output. It is an ideal
 *   rectilinear pinhole with focal fo = zoom·fx_enc, centred in the frame.
 *   An output pixel's ray n maps to the real camera ray C(t(y))ᵀ·V_i·n, then
 *   through the lens (intrinsics + Brown distortion, Camera2 convention) to
 *   a RAW pixel, then to an encode pixel through the record crop.
 * - The virtual path is planned for the whole take by [StabPathSolver] on
 *   rotation vectors, with each frame's correction box derived from the
 *   crop margin. Every frame is then checked exactly against the encode
 *   bounds (16 border points, row-band iteration). Failing frames get their
 *   box shrunk and the take is re-solved; anything still outside after that
 *   is pulled toward the real camera by bisection. So no border is ever
 *   exposed.
 *
 * Camera frame: stored RAW axes, X right, Y down, Z into the scene. Sidecar
 * gyro rates are in the desktop camera frame ([GyroCameraFrameMapper]),
 * whose content law is shift ∝ (+fx·ry, −fy·rx) with +rz turning content
 * clockwise. The physical camera angular velocity in this frame is −r
 * ([cameraRate]); `StabPlannerTest` pins the sign against
 * [StabTrajectory]'s law.
 *
 * Pure JVM, no Android dependencies.
 */
internal object StabPlanner {
    const val BANDS = 32
    private const val AXIS_LIMIT_RAD = 0.5
    private const val BISECT_STEPS = 10
    private const val BISECT_STEPS_WARM = 5
    private const val BOX_STRIDE = 4

    data class Options(
        /** Crop margin per side as a fraction of the encode frame (sets the zoom). */
        val cropMargin: Double = 0.08,
        /** Share of the smaller margin reserved for roll in the planner's box. */
        val rollShare: Double = 0.3,
        /** Keep sampling this many encode pixels inside the frame. */
        val borderPx: Double = 2.0,
        val weights: StabPathSolver.Weights? = null,
        /** Box-shrink rounds for frames that fail the exact border test. */
        val refineRounds: Int = 4,
        val maxIterations: Int = 3000,
        /**
         * Level the horizon (roll) from the accelerometer, to the nearest 90°,
         * within the roll margin. Needs the accel↔camera axis signs to be
         * identifiable from the take ([HorizonFit]); otherwise it stays off.
         */
        val horizonLock: Boolean = false,
        /**
         * Follow the real camera more in frames whose own exposure smears the
         * image: λ·(1 + (blur/[blurRefPx])²). A still path through blurred
         * frames looks worse than a little residual motion. Tunable.
         */
        val blurEase: Boolean = true,
        val blurRefPx: Double = 2.0,
    )

    /** Stage-3 self-calibration output (identity by default). */
    data class Calibration(
        val gyroOffsetNs: Long = 0L,
        /** Bias in sidecar (desktop) rate units, subtracted before use. */
        val biasX: Double = 0.0,
        val biasY: Double = 0.0,
        val biasZ: Double = 0.0,
        /** Overrides the per-frame skew when > 0 (calibrated readout). */
        val readoutNs: Long = 0L,
    )

    /** Lens in RAW-buffer pixel coordinates. */
    data class Lens(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double, val skew: Double,
        val k1: Double, val k2: Double, val k3: Double, val p1: Double, val p2: Double,
        /** True when intrinsics came from LENS_INTRINSIC_CALIBRATION. */
        val calibrated: Boolean,
    ) {
        val hasDistortion: Boolean get() = k1 != 0.0 || k2 != 0.0 || k3 != 0.0 || p1 != 0.0 || p2 != 0.0
    }

    class Plan(
        val frameCount: Int,
        val encodeW: Int,
        val encodeH: Int,
        /** Virtual focal length in encode pixels (output principal point is the centre). */
        val virtualFocal: Double,
        val lens: Lens,
        /** RAW pixel → encode pixel: u = (x − cropLeft)·sx. */
        val cropLeft: Double, val cropTop: Double, val sx: Double, val sy: Double,
        val sensorH: Int,
        /** Per frame [BANDS] row-major 3x3 (virtual ray → real camera ray), `frames·BANDS·9`. */
        val bandMatrices: FloatArray,
        /** Per frame virtual orientation quaternion (w,x,y,z), `frames·4`. */
        val virtual: DoubleArray,
        /** Per frame [0,1]: 1 = planned path used, < 1 = pulled toward the real camera. */
        val alpha: DoubleArray,
        val solverIterations: Int,
        val solverConverged: Boolean,
        val refinedFrames: Int,
        val usedRollingShutter: Boolean,
        val usedDistortion: Boolean,
        /** Horizon lock outcome: "off", "applied", or why it was refused. */
        val horizon: String = "off",
        /** Frames whose λ was raised by blur easing (blur > blurRefPx). */
        val blurEasedFrames: Int = 0,
    ) {
        /**
         * Output encode pixel → source encode pixel for [frame]. CPU
         * reference for the S4 shader. Band matrices are interpolated
         * linearly by source row, so the mapping is continuous.
         */
        fun sourcePoint(frame: Int, ox: Double, oy: Double, out: DoubleArray) {
            projectWith(this, frame, ox, oy, out)
        }

        /**
         * Pinhole-only, centre-band homography (output px → source encode px,
         * row-major, normalised so h[8] = 1). Ignores rolling shutter and distortion.
         */
        fun centreHomography(frame: Int): FloatArray = homographyAtBand(frame, BANDS / 2 - 0.5)

        /**
         * Rolling-shutter warp for the two-matrix shader (`vf_stabwarp.comp`):
         * [0..8] = homography for output row 0 and [9..17] = for the last
         * output row. Each uses the band matrix at the source row those output
         * rows actually read, and the shader interpolates linearly by output
         * row. Pinhole only: lens distortion needs the per-pixel S4 shader.
         */
        fun rowHomographies(frame: Int): FloatArray {
            val out = FloatArray(18)
            val p = DoubleArray(2)
            for ((k, oy) in doubleArrayOf(0.0, encodeH - 1.0).withIndex()) {
                sourcePoint(frame, encodeW * 0.5, oy, p)
                val rawY = cropTop + p[1] / sy
                val fb = (rawY / sensorH * BANDS - 0.5).coerceIn(0.0, BANDS - 1.0)
                homographyAtBand(frame, fb).copyInto(out, k * 9)
            }
            return out
        }

        private fun homographyAtBand(frame: Int, fb: Double): FloatArray {
            val b0 = fb.toInt().coerceIn(0, BANDS - 2)
            val t = fb - b0
            val m = DoubleArray(9) {
                val a = bandMatrices[(frame * BANDS + b0) * 9 + it].toDouble()
                val c = bandMatrices[(frame * BANDS + b0 + 1) * 9 + it].toDouble()
                a + (c - a) * t
            }
            val fxe = lens.fx * sx
            val fye = lens.fy * sy
            val cxe = (lens.cx - cropLeft) * sx
            val cye = (lens.cy - cropTop) * sy
            val kin = doubleArrayOf(
                1 / virtualFocal, 0.0, -encodeW * 0.5 / virtualFocal,
                0.0, 1 / virtualFocal, -encodeH * 0.5 / virtualFocal,
                0.0, 0.0, 1.0,
            )
            val k = doubleArrayOf(fxe, lens.skew * sx, cxe, 0.0, fye, cye, 0.0, 0.0, 1.0)
            val h = mul3(k, mul3(m, kin))
            val s = h[8]
            return FloatArray(9) { (h[it] / s).toFloat() }
        }
    }

    /** Physical camera angular velocity (stored camera frame) from sidecar desktop rates. */
    fun cameraRate(rx: Double, ry: Double, rz: Double): DoubleArray = doubleArrayOf(-rx, -ry, -rz)

    /**
     * Plans the take, or null when it cannot be stabilized (thin gyro,
     * no usable intrinsics, implausible geometry). Never throws on bad data.
     */
    fun plan(take: StabSidecar.Take, options: Options = Options(), calibration: Calibration = Calibration()): Plan? =
        try {
            planOrNull(take, options, calibration)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    // ------------------------------------------------------------------ core

    private fun planOrNull(take: StabSidecar.Take, o: Options, cal: Calibration): Plan? {
        val h = take.header
        val n = take.frameTimestampsNs.size
        if (n == 0 || take.gyro.size < n) return null
        if (o.cropMargin <= 0.0 || o.cropMargin >= 0.3) return null
        val lens = lensFor(take) ?: return null
        val track = OrientationTrack.build(take.gyro, cal) ?: return null
        val ex = take.extras
        val w = h.encodeW.toDouble()
        val ht = h.encodeH.toDouble()
        val sx = w / h.cropW
        val sy = ht / h.cropH
        val zoom = 1.0 / (1.0 - 2.0 * o.cropMargin)
        val fo = lens.fx * sx * zoom

        // Per-frame timing.
        val ts = take.frameTimestampsNs
        val exp = LongArray(n) { ex?.exposureNs?.getOrNull(it)?.takeIf { v -> v > 0 } ?: 0L }
        val skew = LongArray(n) {
            if (cal.readoutNs > 0) cal.readoutNs
            else ex?.rollingShutterSkewNs?.getOrNull(it)?.takeIf { v -> v > 0 } ?: 0L
        }
        val usedRs = skew.any { it > 0 }
        val tc = LongArray(n) { ts[it] + exp[it] / 2 + skew[it] / 2 }
        if (!track.covers(tc[0], tc[n - 1])) return null

        // Real orientation per frame centre and the raw path (body increments).
        val cq = Array(n) { track.at(tc[it]) }
        val p = Array(3) { DoubleArray(n) }
        for (i in 1 until n) {
            val d = Quat.log(Quat.mul(Quat.conj(cq[i - 1]), cq[i]))
            for (a in 0 until 3) p[a][i] = p[a][i - 1] + d[a]
        }

        // Exact correction box per frame: single-axis limits by bisection on
        // the real border test (RS bands + distortion), then a joint scale so
        // every corner of the box fits. The analytic estimate used before let
        // ~20 % of frames fail on a plain pan and cost extra solves.
        val geom = Geometry(lens, h.cropLeft.toDouble(), h.cropTop.toDouble(), sx, sy, w, ht, fo, h.sensorH, o.borderPx)
        if ((w - w / zoom) * 0.5 <= o.borderPx || (ht - ht / zoom) * 0.5 <= o.borderPx) return null
        if (!geom.fits(track, -1, ts[0], exp[0], skew[0], cq[0])) return null // even no correction leaves the frame
        // Exact boxes on every BOX_STRIDE-th frame (coarse 16-point test,
        // bisection bracketed around the previous result); frames in between
        // take the tighter neighbour. The exact 32-point check after the
        // solve catches anything this misses.
        val boxes = Array(3) { DoubleArray(n) }
        val unit = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val prevLim = DoubleArray(3) { -1.0 }
        val keys = (0 until n step BOX_STRIDE).toMutableList().also { if (it.last() != n - 1) it.add(n - 1) }
        for (i in keys) {
            fun fitsD(d: DoubleArray) = geom.fits(track, i, ts[i], exp[i], skew[i], Quat.mul(cq[i], Quat.exp(d)), coarse = true)
            val lim = DoubleArray(3)
            for (a in 0 until 3) {
                var best = Double.MAX_VALUE
                for (sign in doubleArrayOf(1.0, -1.0)) {
                    fun ok(m: Double) = fitsD(DoubleArray(3) { unit[a][it] * sign * m })
                    var lo = 0.0
                    var hi = AXIS_LIMIT_RAD
                    var steps = BISECT_STEPS
                    val g = prevLim[a]
                    if (g > 0) {
                        // bracket [0.8g, 1.25g] (neighbouring frames are similar)
                        val a0 = 0.8 * g
                        val a1 = min(AXIS_LIMIT_RAD, 1.25 * g)
                        if (ok(a0)) { lo = a0; if (!ok(a1)) { hi = a1; steps = BISECT_STEPS_WARM } else lo = a1 }
                        else hi = a0
                    }
                    repeat(steps) {
                        val mid = 0.5 * (lo + hi)
                        if (ok(mid)) lo = mid else hi = mid
                    }
                    best = min(best, lo)
                }
                lim[a] = best
                prevLim[a] = best
            }
            lim[2] *= o.rollShare
            var lo = 0.0
            var hi = 1.0
            fun cornersFit(sc: Double): Boolean {
                for (c in 0 until 8) {
                    val d = DoubleArray(3) { a -> lim[a] * sc * (if ((c shr a) and 1 == 0) 1.0 else -1.0) }
                    if (!fitsD(d)) return false
                }
                return true
            }
            if (cornersFit(1.0)) lo = 1.0 else repeat(BISECT_STEPS) {
                val mid = 0.5 * (lo + hi)
                if (cornersFit(mid)) lo = mid else hi = mid
            }
            for (a in 0 until 3) boxes[a][i] = lim[a] * lo
        }
        for (k in 0 until keys.size - 1) {
            val i0 = keys[k]
            val i1 = keys[k + 1]
            for (i in i0 + 1 until i1) for (a in 0 until 3) boxes[a][i] = min(boxes[a][i0], boxes[a][i1])
        }

        val weights = o.weights ?: StabPathSolver.weightsForFps(h.fps)

        // Blur easing: expected smear per frame from the rotation during its exposure.
        val fxe = lens.fx * sx
        val halfDiag = 0.5 * kotlin.math.hypot(w, ht)
        var blurEased = 0
        val lamScale = if (!o.blurEase) null else DoubleArray(n) { i ->
            if (exp[i] <= 0) 1.0 else {
                val r = Quat.log(Quat.mul(Quat.conj(track.at(ts[i] + skew[i] / 2)), track.at(ts[i] + skew[i] / 2 + exp[i])))
                val blur = fxe * kotlin.math.hypot(r[0], r[1]) + abs(r[2]) * halfDiag
                if (blur > o.blurRefPx) blurEased++
                1.0 + (blur / o.blurRefPx) * (blur / o.blurRefPx)
            }
        }

        // Horizon lock: shift the roll target by the per-frame leveling angle.
        var horizon = "off"
        val level = if (!o.horizonLock) null else {
            val fit = HorizonFit.levelAngles(take, track, tc, cq)
            horizon = fit.status
            fit.angles
        }
        val shrink = DoubleArray(n) { 1.0 }
        val v = Array(n) { cq[it] }
        var iterations = 0
        var converged = true
        var refined = 0
        val bandMats = FloatArray(n * BANDS * 9)
        val alpha = DoubleArray(n) { 1.0 }
        val warm = arrayOfNulls<StabPathSolver.State>(3)
        val scales = DoubleArray(3) { a -> median(boxes[a]).takeIf { it > 0 } ?: 1e-3 }
        for (round in 0..o.refineRounds) {
            val s = Array(3) { a ->
                val lo = DoubleArray(n) { -boxes[a][it] * shrink[it] }
                val hi = DoubleArray(n) { boxes[a][it] * shrink[it] }
                val lv = if (a == 2) level else null
                val target = if (lv == null) p[a] else DoubleArray(n) { p[a][it] + lv[it] }
                if (lv != null) for (i in 0 until n) { lo[i] -= lv[i]; hi[i] -= lv[i] }
                val r = StabPathSolver.solve(target, lo, hi, weights, scales[a], o.maxIterations, warm = warm[a], lambdaScale = lamScale)
                warm[a] = r.state
                iterations += r.iterations
                converged = converged && r.converged
                r.path
            }
            for (i in 0 until n) {
                val d = doubleArrayOf(s[0][i] - p[0][i], s[1][i] - p[1][i], s[2][i] - p[2][i])
                v[i] = Quat.mul(cq[i], Quat.exp(d))
            }
            var failing = 0
            for (i in 0 until n) {
                if (!geom.fits(track, i, ts[i], exp[i], skew[i], v[i])) {
                    failing++
                    shrink[i] *= 0.75
                }
            }
            if (failing == 0) break
            if (round == o.refineRounds) break
            refined += failing
        }
        // Last resort: per frame, the largest blend toward the plan that fits.
        for (i in 0 until n) {
            if (geom.fits(track, i, ts[i], exp[i], skew[i], v[i])) continue
            var lo = 0.0
            var hi = 1.0
            repeat(12) {
                val mid = 0.5 * (lo + hi)
                if (geom.fits(track, i, ts[i], exp[i], skew[i], Quat.slerp(cq[i], v[i], mid))) lo = mid else hi = mid
            }
            alpha[i] = lo
            v[i] = Quat.slerp(cq[i], v[i], lo)
        }
        val virtualOut = DoubleArray(n * 4)
        for (i in 0 until n) {
            geom.bandMatrices(track, i, ts[i], exp[i], skew[i], v[i], bandMats, i * BANDS * 9)
            for (k in 0 until 4) virtualOut[i * 4 + k] = v[i][k]
        }
        return Plan(
            n, h.encodeW, h.encodeH, fo, lens, h.cropLeft.toDouble(), h.cropTop.toDouble(), sx, sy, h.sensorH,
            bandMats, virtualOut, alpha, iterations, converged, refined, usedRs, lens.hasDistortion,
            horizon, blurEased,
        )
    }

    /**
     * Lens for the take in RAW-buffer pixels. Prefers LENS_INTRINSIC_CALIBRATION
     * (+ LENS_DISTORTION) mapped from pre-correction array coordinates; falls
     * back to focal-length / physical-size pinhole at the crop centre.
     */
    fun lensFor(take: StabSidecar.Take): Lens? {
        val h = take.header
        if (h.sensorW <= 0 || h.sensorH <= 0 || h.cropW <= 0 || h.cropH <= 0) return null
        if (h.cropLeft < 0 || h.cropTop < 0 || h.cropLeft + h.cropW > h.sensorW || h.cropTop + h.cropH > h.sensorH) return null
        if (h.encodeW <= 0 || h.encodeH <= 0) return null
        val e = take.extras
        val intr = e?.lensIntrinsics?.takeIf { a -> a.size == 5 && a.all { it.isFinite() } && a[0] > 0 && a[1] > 0 }
        if (intr != null) {
            // Intrinsics are in pre-correction active array pixels. The RAW
            // buffer is either that array (same size → offset 0) or the full
            // pixel array, which the pre-correction rect is positioned in.
            val (ox, oy) = if (e.preCorrW == h.sensorW && e.preCorrH == h.sensorH) 0.0 to 0.0
            else -e.preCorrLeft.toDouble() to -e.preCorrTop.toDouble()
            val d = e.lensDistortion?.takeIf { a -> a.size == 5 && a.all { it.isFinite() } }
            val lens = Lens(
                intr[0].toDouble(), intr[1].toDouble(), intr[2] - ox, intr[3] - oy, intr[4].toDouble(),
                d?.get(0)?.toDouble() ?: 0.0, d?.get(1)?.toDouble() ?: 0.0, d?.get(2)?.toDouble() ?: 0.0,
                d?.get(3)?.toDouble() ?: 0.0, d?.get(4)?.toDouble() ?: 0.0, true,
            )
            if (plausible(lens, h)) return lens
        }
        if (h.sensorWMm <= 0f || h.sensorHMm <= 0f || h.focalMm <= 0f) return null
        val fx = h.focalMm.toDouble() * h.sensorW / h.sensorWMm
        val fy = h.focalMm.toDouble() * h.sensorH / h.sensorHMm
        val lens = Lens(
            fx, fy, h.cropLeft + h.cropW * 0.5, h.cropTop + h.cropH * 0.5, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, false,
        )
        return lens.takeIf { plausible(it, h) }
    }

    private fun plausible(l: Lens, h: StabSidecar.Header): Boolean {
        if (!l.fx.isFinite() || !l.fy.isFinite() || l.fx <= 0 || l.fy <= 0) return false
        // Wider than ~120° across the crop smells like a unit mix-up.
        if (l.fx < h.cropW * 0.29 || l.fy < h.cropH * 0.29) return false
        if (l.fx / l.fy !in 0.8..1.25) return false
        return l.cx in 0.0..h.sensorW.toDouble() && l.cy in 0.0..h.sensorH.toDouble()
    }

    // -------------------------------------------------------------- geometry

    private class Geometry(
        val lens: Lens,
        val cropLeft: Double, val cropTop: Double, val sx: Double, val sy: Double,
        val w: Double, val h: Double, val fo: Double, val sensorH: Int, val border: Double,
    ) {
        private val bandCache = Array(BANDS) { DoubleArray(9) }
        /** Real-camera band rotations for [camFrame] (independent of V, so cached). */
        private val camBands = Array(BANDS) { DoubleArray(9) }
        private var camFrame = Int.MIN_VALUE
        private val tmp = DoubleArray(2)

        /** Fills per-band C(t_b)ᵀ·V for frame timing into [out] at [offset]. */
        fun bandMatrices(track: OrientationTrack, frame: Int, ts: Long, exp: Long, skew: Long, v: DoubleArray, out: FloatArray, offset: Int) {
            fill(track, frame, ts, exp, skew, v)
            for (b in 0 until BANDS) for (k in 0 until 9) out[offset + b * 9 + k] = bandCache[b][k].toFloat()
        }

        /** [frame] < 0 disables the cache. */
        private fun fill(track: OrientationTrack, frame: Int, ts: Long, exp: Long, skew: Long, v: DoubleArray) {
            if (frame < 0 || frame != camFrame) {
                for (b in 0 until BANDS) {
                    val t = ts + exp / 2 + (skew * ((b + 0.5) / BANDS)).toLong()
                    Quat.toMat(track.at(t)).copyInto(camBands[b])
                }
                camFrame = if (frame < 0) Int.MIN_VALUE else frame
            }
            val vm = Quat.toMat(v)
            for (b in 0 until BANDS) {
                val cm = camBands[b]
                val m = bandCache[b]
                for (r in 0 until 3) for (c in 0 until 3) {
                    m[r * 3 + c] = cm[r] * vm[c] + cm[3 + r] * vm[3 + c] + cm[6 + r] * vm[6 + c]
                }
            }
        }

        /** Border test: 32 points (exact) or 16 points (coarse, for box search). */
        fun fits(track: OrientationTrack, frame: Int, ts: Long, exp: Long, skew: Long, v: DoubleArray, coarse: Boolean = false): Boolean {
            fill(track, frame, ts, exp, skew, v)
            val per = if (coarse) 4 else 8
            for (i in 0 until 4 * per) {
                val side = i / per
                val f = (i % per) / per.toDouble()
                val ox: Double
                val oy: Double
                when (side) {
                    0 -> { ox = f * w; oy = 0.0 }
                    1 -> { ox = w; oy = f * h }
                    2 -> { ox = (1 - f) * w; oy = h }
                    else -> { ox = 0.0; oy = (1 - f) * h }
                }
                if (!project(bandCache, ox, oy, tmp)) return false
                if (tmp[0] < border || tmp[0] > w - border || tmp[1] < border || tmp[1] > h - border) return false
            }
            return true
        }

        fun project(bands: Array<DoubleArray>, ox: Double, oy: Double, out: DoubleArray): Boolean =
            projectBands(bands, lens, fo, w, h, cropLeft, cropTop, sx, sy, sensorH, ox, oy, out)
    }

    private fun projectWith(plan: Plan, frame: Int, ox: Double, oy: Double, out: DoubleArray) {
        require(frame in 0 until plan.frameCount) { "frame $frame out of range" }
        val bands = Array(BANDS) { b -> DoubleArray(9) { plan.bandMatrices[(frame * BANDS + b) * 9 + it].toDouble() } }
        projectBands(
            bands, plan.lens, plan.virtualFocal, plan.encodeW.toDouble(), plan.encodeH.toDouble(),
            plan.cropLeft, plan.cropTop, plan.sx, plan.sy, plan.sensorH, ox, oy, out,
        )
    }

    /**
     * Output encode px → source encode px. The band is picked from the
     * source row, refined twice (the row depends on the band), with linear
     * interpolation between band centres.
     */
    private fun projectBands(
        bands: Array<DoubleArray>, lens: Lens, fo: Double, w: Double, h: Double,
        cropLeft: Double, cropTop: Double, sx: Double, sy: Double, sensorH: Int,
        ox: Double, oy: Double, out: DoubleArray,
    ): Boolean {
        val nx = (ox - w * 0.5) / fo
        val ny = (oy - h * 0.5) / fo
        var rawY = cropTop + oy / sy
        val m = DoubleArray(9)
        repeat(3) {
            val fb = (rawY / sensorH * BANDS - 0.5).coerceIn(0.0, BANDS - 1.0)
            val b0 = fb.toInt().coerceAtMost(BANDS - 2)
            val t = fb - b0
            val a = bands[b0]
            val c = bands[b0 + 1]
            for (k in 0 until 9) m[k] = a[k] + (c[k] - a[k]) * t
            val rx = m[0] * nx + m[1] * ny + m[2]
            val ry = m[3] * nx + m[4] * ny + m[5]
            val rz = m[6] * nx + m[7] * ny + m[8]
            if (rz <= 1e-6) return false
            val x = rx / rz
            val y = ry / rz
            val r2 = x * x + y * y
            val rad = 1 + lens.k1 * r2 + lens.k2 * r2 * r2 + lens.k3 * r2 * r2 * r2
            val xd = x * rad + 2 * lens.p1 * x * y + lens.p2 * (r2 + 2 * x * x)
            val yd = y * rad + lens.p1 * (r2 + 2 * y * y) + 2 * lens.p2 * x * y
            val px = lens.fx * xd + lens.skew * yd + lens.cx
            val py = lens.fy * yd + lens.cy
            rawY = py
            out[0] = (px - cropLeft) * sx
            out[1] = (py - cropTop) * sy
        }
        return true
    }

    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray()
        return s[s.size / 2]
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return o
    }

    // ------------------------------------------------------- orientation

    /** Camera→world orientation integrated from gyro, slerp lookup at any time. */
    internal class OrientationTrack private constructor(
        private val t: LongArray,
        private val q: Array<DoubleArray>,
    ) {
        fun covers(t0: Long, t1: Long): Boolean = t0 >= t.first() - SLACK_NS && t1 <= t.last() + SLACK_NS

        fun at(time: Long): DoubleArray {
            if (time <= t[0]) return q[0]
            if (time >= t[t.size - 1]) return q[t.size - 1]
            var lo = 0
            var hi = t.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (t[mid] <= time) lo = mid else hi = mid
            }
            val f = (time - t[lo]).toDouble() / max(1L, t[hi] - t[lo])
            return Quat.slerp(q[lo], q[hi], f)
        }

        companion object {
            private const val SLACK_NS = 50_000_000L

            fun build(gyro: List<GyroSample>, cal: StabPlanner.Calibration): OrientationTrack? {
                if (gyro.size < 2) return null
                val t = LongArray(gyro.size)
                val q = arrayOfNulls<DoubleArray>(gyro.size)
                var cur = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
                var prev: DoubleArray? = null
                var prevT = 0L
                for ((i, s) in gyro.withIndex()) {
                    val w = cameraRate(
                        s.xRadiansPerSecond - cal.biasX,
                        s.yRadiansPerSecond - cal.biasY,
                        s.zRadiansPerSecond - cal.biasZ,
                    )
                    val ti = s.timestampNanos + cal.gyroOffsetNs
                    if (prev != null) {
                        val dt = (ti - prevT) * 1e-9
                        if (dt < 0) return null
                        if (dt in 1e-6..0.1) {
                            // trapezoid rate, body-frame right multiply
                            val r = DoubleArray(3) { 0.5 * (w[it] + prev!![it]) * dt }
                            cur = Quat.normalize(Quat.mul(cur, Quat.exp(r)))
                        }
                    }
                    t[i] = ti
                    q[i] = cur
                    prev = w
                    prevT = ti
                }
                @Suppress("UNCHECKED_CAST")
                return OrientationTrack(t, q as Array<DoubleArray>)
            }
        }
    }

    /** Unit quaternions [w, x, y, z]. */
    internal object Quat {
        fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
        )

        fun conj(a: DoubleArray) = doubleArrayOf(a[0], -a[1], -a[2], -a[3])

        fun normalize(a: DoubleArray): DoubleArray {
            val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3])
            return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n, a[3] / n)
        }

        fun exp(r: DoubleArray): DoubleArray {
            val th = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            if (th < 1e-12) return normalize(doubleArrayOf(1.0, r[0] * 0.5, r[1] * 0.5, r[2] * 0.5))
            val s = sin(th * 0.5) / th
            return doubleArrayOf(cos(th * 0.5), r[0] * s, r[1] * s, r[2] * s)
        }

        /** Rotation vector, shortest arc. */
        fun log(q0: DoubleArray): DoubleArray {
            val q = if (q0[0] < 0) doubleArrayOf(-q0[0], -q0[1], -q0[2], -q0[3]) else q0
            val v = sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (v < 1e-12) return doubleArrayOf(2 * q[1], 2 * q[2], 2 * q[3])
            val th = 2 * kotlin.math.atan2(v, q[0])
            return doubleArrayOf(q[1] / v * th, q[2] / v * th, q[3] / v * th)
        }

        fun slerp(a: DoubleArray, b0: DoubleArray, t: Double): DoubleArray {
            var d = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
            val b = if (d < 0) { d = -d; doubleArrayOf(-b0[0], -b0[1], -b0[2], -b0[3]) } else b0
            if (d > 0.9995) return normalize(DoubleArray(4) { a[it] + (b[it] - a[it]) * t })
            val th = acos(d.coerceAtMost(1.0))
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return DoubleArray(4) { wa * a[it] + wb * b[it] }
        }

        /** Row-major rotation matrix (camera→world for camera→world q). */
        fun toMat(q: DoubleArray): DoubleArray {
            val (w, x, y, z) = q
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
            )
        }
    }

    /**
     * Gravity from the accelerometer → per-frame roll correction that levels
     * the image to the nearest 90°. The accel axes are raw device axes, and
     * the camera mapping of polar vectors is not pinned by hardware tests, so
     * the axis signs are chosen from the data: the candidate whose
     * gyro-rotated gravity stays most constant in the world wins. It must
     * beat the runner-up clearly, which needs some rotation in the take.
     */
    internal object HorizonFit {
        class Fit(val angles: DoubleArray?, val status: String)

        fun levelAngles(take: StabSidecar.Take, track: OrientationTrack, tc: LongArray, cq: Array<DoubleArray>): Fit {
            val accel = take.extras?.accel.orEmpty()
            val n = tc.size
            if (accel.size < n) return Fit(null, "no accel")
            val m = GyroCameraFrameMapper.matrix(take.header.sensorOrientationDeg, take.header.frontFacing)
            // Mean accel in ±100 ms around each frame centre (low-pass; hand acceleration averages out).
            val at = LongArray(accel.size) { accel[it].timestampNanos }
            val dev = Array(n) { DoubleArray(3) }
            for (i in 0 until n) {
                var lo = java.util.Arrays.binarySearch(at, tc[i] - 100_000_000L).let { if (it < 0) -it - 1 else it }
                val hi = java.util.Arrays.binarySearch(at, tc[i] + 100_000_000L).let { if (it < 0) -it - 1 else it }
                var c = 0
                while (lo < hi && lo < accel.size) {
                    val a = accel[lo]
                    dev[i][0] += a.xRadiansPerSecond; dev[i][1] += a.yRadiansPerSecond; dev[i][2] += a.zRadiansPerSecond
                    c++; lo++
                }
                if (c == 0) return Fit(null, "accel gap")
                for (k in 0 until 3) dev[i][k] /= c
            }
            // Candidates: diag(sx, sy, 1)·M (global sign is irrelevant: leveling is to the nearest 90°).
            data class Cand(val sx: Double, val sy: Double, val spread: Double)
            val cams = arrayOf(1.0 to 1.0, -1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0).map { (sx, sy) ->
                val worlds = Array(n) { i ->
                    val g = camVec(m, dev[i], sx, sy)
                    val r = Quat.toMat(cq[i])
                    DoubleArray(3) { k -> r[k * 3] * g[0] + r[k * 3 + 1] * g[1] + r[k * 3 + 2] * g[2] }
                }
                val mean = DoubleArray(3) { k -> worlds.sumOf { it[k] } / n }
                Cand(sx, sy, worlds.sumOf { wv -> (0 until 3).sumOf { k -> (wv[k] - mean[k]) * (wv[k] - mean[k]) } } / n)
            }.sortedBy { it.spread }
            val best = cams[0]
            if (!(best.spread < 0.25 * cams[1].spread)) {
                return Fit(null, "axes ambiguous (spread ${"%.4f".format(best.spread)} vs ${"%.4f".format(cams[1].spread)}); needs more rotation")
            }
            val angles = DoubleArray(n) { i ->
                val g = camVec(m, dev[i], best.sx, best.sy)
                // Correction about +Z that levels the image: numeric slope keeps the sign honest.
                val phi0 = quadrantResidual(g)
                val eps = 1e-3
                val rz = Quat.toMat(Quat.exp(doubleArrayOf(0.0, 0.0, eps)))
                // gravity in the virtual camera after rolling by eps: Rᵀ·g
                val gv = doubleArrayOf(
                    rz[0] * g[0] + rz[3] * g[1] + rz[6] * g[2],
                    rz[1] * g[0] + rz[4] * g[1] + rz[7] * g[2],
                    rz[2] * g[0] + rz[5] * g[1] + rz[8] * g[2],
                )
                val slope = (quadrantResidual(gv) - phi0) / eps
                if (abs(slope) < 0.5) 0.0 else -phi0 / slope
            }
            return Fit(angles, "applied (axes ${best.sx.toInt()},${best.sy.toInt()})")
        }

        private fun camVec(m: DoubleArray, a: DoubleArray, sx: Double, sy: Double): DoubleArray {
            val v = DoubleArray(3) { r -> m[r * 3] * a[0] + m[r * 3 + 1] * a[1] + m[r * 3 + 2] * a[2] }
            val nrm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9)
            return doubleArrayOf(sx * v[0] / nrm, sy * v[1] / nrm, v[2] / nrm)
        }

        /** Image-plane angle of the vector, minus the nearest multiple of 90°. */
        internal fun quadrantResidual(g: DoubleArray): Double {
            val phi = kotlin.math.atan2(g[0], g[1])
            val q = Math.PI / 2
            return phi - Math.round(phi / q) * q
        }
    }
}
