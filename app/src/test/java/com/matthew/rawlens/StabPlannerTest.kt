// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class StabPlannerTest {
    // 8000x6000 sensor, 16:9 crop, 4K encode; fx = 6 mm · 8000 px / 8 mm = 6000 raw px = 2880 encode px.
    private fun header(fps: Int = 30) = StabSidecar.Header(
        fps = fps, encodeW = 3840, encodeH = 2160, timeOriginNs = 0L,
        sensorOrientationDeg = 90, frontFacing = false,
        focalMm = 6.0f, sensorWMm = 8.0f, sensorHMm = 6.0f,
        sensorW = 8000, sensorH = 6000,
        cropLeft = 0, cropTop = 750, cropW = 8000, cropH = 4500,
        mapperVersion = GyroCameraFrameMapper.MAPPER_VERSION,
    )
    private val fxEnc = 2880.0

    private fun take(
        frames: Int,
        fps: Int = 30,
        extras: ((Int) -> StabSidecar.Extras)? = null,
        rate: (tSec: Double) -> Triple<Double, Double, Double>,
    ): StabSidecar.Take {
        val dt = 1.0 / fps
        val frameTs = LongArray(frames) { (it * dt * 1e9).toLong() }
        val gyro = ArrayList<GyroSample>()
        var t = -0.1
        val end = frames * dt + 0.1
        while (t < end) {
            val (x, y, z) = rate(t)
            gyro.add(GyroSample((t * 1e9).toLong(), x.toFloat(), y.toFloat(), z.toFloat()))
            t += 0.0025 // 400 Hz
        }
        return StabSidecar.Take(header(fps), frameTs, gyro, extras?.invoke(frames))
    }

    private fun centre(plan: StabPlanner.Plan, i: Int): DoubleArray =
        DoubleArray(2).also { plan.sourcePoint(i, 1920.0, 1080.0, it) }

    private fun assertNoBorder(plan: StabPlanner.Plan) {
        val out = DoubleArray(2)
        for (i in 0 until plan.frameCount) {
            for ((x, y) in listOf(0.0 to 0.0, 3840.0 to 0.0, 0.0 to 2160.0, 3840.0 to 2160.0, 1920.0 to 0.0, 0.0 to 1080.0)) {
                plan.sourcePoint(i, x, y, out)
                assertTrue("frame $i ($x,$y) -> ${out[0]},${out[1]}", out[0] in 0.0..3840.0 && out[1] in 0.0..2160.0)
            }
        }
    }

    @Test fun `static take is an exact centred crop`() {
        val plan = StabPlanner.plan(take(30) { Triple(0.0, 0.0, 0.0) })!!
        val m = StabPlanner.Options().cropMargin
        val out = DoubleArray(2)
        plan.sourcePoint(5, 0.0, 0.0, out)
        assertEquals(m * 3840, out[0], 0.5)
        assertEquals(m * 2160, out[1], 0.5)
        val c = centre(plan, 5)
        assertEquals(1920.0, c[0], 1e-3)
        assertEquals(1080.0, c[1], 1e-3)
        assertEquals(fxEnc / (1 - 2 * m), plan.virtualFocal, 1e-6)
    }

    @Test fun `v1 take without extras uses the fallback pinhole`() {
        val plan = StabPlanner.plan(take(10) { Triple(0.0, 0.0, 0.0) })!!
        assertTrue(!plan.lens.calibrated)
        assertTrue(!plan.usedRollingShutter)
        assertTrue(!plan.usedDistortion)
    }

    /**
     * Content law pin (matches StabTrajectory / GyroCameraFrameMapper): for a
     * stabilized (still) virtual camera, the source point of a world-fixed
     * output pixel moves by +fx·∫ry in x and −fy·∫rx in y.
     */
    @Test fun `source motion follows the desktop content law`() {
        val amp = 0.004 // rad
        val f = 2.0
        val wRate = amp * 2 * PI * f
        val yaw = StabPlanner.plan(take(90) { t -> Triple(0.0, wRate * cos(2 * PI * f * t), 0.0) })!!
        val pitch = StabPlanner.plan(take(90) { t -> Triple(wRate * cos(2 * PI * f * t), 0.0, 0.0) })!!
        var cx = 0.0
        var cy = 0.0
        for (i in 10 until 80) {
            val angle = amp * sin(2 * PI * f * i / 30.0)
            cx += (centre(yaw, i)[0] - 1920.0) * angle
            cy += (centre(pitch, i)[1] - 1080.0) * angle
        }
        assertTrue("yaw correlation $cx", cx > 0)
        assertTrue("pitch correlation $cy", cy < 0)
        // Magnitude: the still path absorbs the whole shake.
        val i = 22 // sin peak near frame 22.5 at 2 Hz
        val expect = fxEnc * amp * sin(2 * PI * f * i / 30.0)
        assertEquals(expect, centre(yaw, i)[0] - 1920.0, abs(expect) * 0.2 + 2.0)
    }

    @Test fun `positive rz rotates content clockwise`() {
        val amp = 0.01
        val f = 1.5
        val plan = StabPlanner.plan(take(90) { t -> Triple(0.0, 0.0, amp * 2 * PI * f * cos(2 * PI * f * t)) })!!
        // At a frame where the roll angle is positive, a point right of centre
        // samples from lower in the source (clockwise content in Y-down).
        val i = 5 // angle = amp·sin(2π·1.5·5/30) > 0
        val out = DoubleArray(2)
        plan.sourcePoint(i, 1920.0 + 800.0, 1080.0, out)
        assertTrue("y ${out[1]}", out[1] > 1080.0 + 1.0)
    }

    @Test fun `hand shake is removed and pans are kept without lag`() {
        val pan = 0.15 // rad/s
        val plan = StabPlanner.plan(take(150) { t ->
            Triple(0.02 * cos(2 * PI * 6 * t), pan + 0.03 * cos(2 * PI * 7 * t), 0.0)
        })!!
        assertNoBorder(plan)
        // Virtual path angular velocity: steady pan, shake gone.
        val v = plan.virtual
        val q = StabPlanner.Quat
        var maxDev = 0.0
        for (i in 20 until 130) {
            val a = doubleArrayOf(v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3])
            val b = doubleArrayOf(v[i * 4 + 4], v[i * 4 + 5], v[i * 4 + 6], v[i * 4 + 7])
            val r = q.log(q.mul(q.conj(a), b))
            // camera rate is −r_desktop: yaw step = −pan/30 per frame
            maxDev = maxOf(maxDev, abs(r[1] + pan / 30), abs(r[0]))
        }
        assertTrue("virtual rate deviation $maxDev rad/frame", maxDev < 2e-4)
    }

    @Test fun `violent shake never exposes a border`() {
        val plan = StabPlanner.plan(take(120) { t ->
            Triple(1.2 * sin(2 * PI * 3 * t), 2.0 * sin(2 * PI * 2.3 * t), 0.6 * sin(2 * PI * 1.1 * t))
        })!!
        assertNoBorder(plan)
        assertTrue(plan.alpha.all { it in 0.0..1.0 })
    }

    @Test fun `rolling shutter skew is corrected per row`() {
        val pan = 0.5 // rad/s, desktop ry
        val skewNs = 30_000_000L
        val ex = { n: Int -> StabSidecar.Extras(
            exposureNs = LongArray(n) { 5_000_000L },
            rollingShutterSkewNs = LongArray(n) { skewNs },
        ) }
        val plan = StabPlanner.plan(take(90, extras = ex) { Triple(0.0, pan, 0.0) })!!
        assertTrue(plan.usedRollingShutter)
        assertNoBorder(plan)
        val top = DoubleArray(2)
        val bot = DoubleArray(2)
        val i = 45
        plan.sourcePoint(i, 1920.0, 300.0, top)
        plan.sourcePoint(i, 1920.0, 1860.0, bot)
        // Bottom rows are read later, the camera has turned further, so the
        // world-fixed column sits further +x (content law) in the source.
        val rowsRaw = (bot[1] - top[1]) / plan.sy
        val expect = fxEnc * pan * skewNs * 1e-9 * rowsRaw / 6000.0
        assertEquals(expect, bot[0] - top[0], abs(expect) * 0.15)
    }

    @Test fun `calibrated lens with distortion still fits`() {
        val ex = { n: Int -> StabSidecar.Extras(
            lensIntrinsics = floatArrayOf(6000f, 6000f, 4000f, 3000f, 0f),
            lensDistortion = floatArrayOf(-0.05f, 0.01f, 0f, 0f, 0f),
            preCorrW = 8000, preCorrH = 6000,
            exposureNs = LongArray(n) { 8_000_000L },
            rollingShutterSkewNs = LongArray(n) { 20_000_000L },
        ) }
        val plan = StabPlanner.plan(take(60, extras = ex) { t -> Triple(0.05 * sin(9 * t), 0.08 * sin(7 * t), 0.0) })!!
        assertTrue(plan.lens.calibrated && plan.usedDistortion)
        assertNoBorder(plan)
    }

    @Test fun `gyro offset calibration shifts the timeline`() {
        val base = take(60) { t -> Triple(0.0, 0.2 * sin(2 * PI * 2 * t), 0.0) }
        val a = StabPlanner.plan(base)!!
        val b = StabPlanner.plan(base, calibration = StabPlanner.Calibration(gyroOffsetNs = 20_000_000L))!!
        assertTrue(abs(centre(a, 30)[0] - centre(b, 30)[0]) > 0.5)
    }

    @Test fun `refuses thin gyro and implausible geometry`() {
        val t = take(30) { Triple(0.0, 0.0, 0.0) }
        assertNull(StabPlanner.plan(t.copy(gyro = t.gyro.take(5))))
        assertNull(StabPlanner.plan(t.copy(header = t.header.copy(focalMm = 0.5f))))
        assertNull(StabPlanner.plan(t.copy(header = t.header.copy(cropW = 9000))))
        assertNull(StabPlanner.plan(t, StabPlanner.Options(cropMargin = 0.0)))
        assertNotNull(StabPlanner.plan(t))
    }

    @Test fun `exact box means refinement is rarely needed`() {
        val plan = StabPlanner.plan(take(150) { t ->
            Triple(0.02 * cos(2 * PI * 6 * t), 0.15 + 0.03 * cos(2 * PI * 7 * t), 0.0)
        })!!
        assertTrue("refined ${plan.refinedFrames}", plan.refinedFrames <= 3)
    }

    /** 10-minute 30 fps walk: prints timing; asserts it finishes and fits. */
    @Test fun `ten minute take benchmark`() {
        val frames = 30 * 600
        val t0 = System.nanoTime()
        val plan = StabPlanner.plan(take(frames) { t ->
            Triple(
                0.05 * sin(2 * PI * 1.8 * t) + 0.02 * sin(2 * PI * 7 * t),
                0.1 * sin(2 * PI * 0.05 * t) + 0.04 * sin(2 * PI * 0.9 * t + 1),
                0.03 * sin(2 * PI * 1.8 * t + 0.5),
            )
        })!!
        val ms = (System.nanoTime() - t0) / 1e6
        println("BENCH frames=$frames ms=${"%.0f".format(ms)} iterations=${plan.solverIterations} " +
            "converged=${plan.solverConverged} refined=${plan.refinedFrames} minAlpha=${plan.alpha.min()}")
        val out = DoubleArray(2)
        for (i in 0 until frames step 97) {
            plan.sourcePoint(i, 0.0, 0.0, out)
            assertTrue(out[0] >= 0 && out[1] >= 0)
        }
    }

    /** The shader's row-interpolated homography pair reproduces the exact mapping (pinhole lens). */
    @Test fun `row homographies match the exact mapping`() {
        val ex = { n: Int -> StabSidecar.Extras(
            exposureNs = LongArray(n) { 5_000_000L },
            rollingShutterSkewNs = LongArray(n) { 25_000_000L },
        ) }
        val plan = StabPlanner.plan(take(60, extras = ex) { t -> Triple(0.3 * sin(5 * t), 0.4 + 0.2 * cos(6 * t), 0.1 * sin(3 * t)) })!!
        val exact = DoubleArray(2)
        var worst = 0.0
        for (i in listOf(5, 30, 55)) {
            val m = plan.rowHomographies(i)
            for (oy in listOf(0.0, 540.0, 1080.0, 1620.0, 2159.0)) for (ox in listOf(0.0, 1920.0, 3839.0)) {
                val t = oy / 2159.0
                val h = DoubleArray(9) { m[it] + (m[9 + it] - m[it]) * t }
                val z = h[6] * ox + h[7] * oy + h[8]
                val sx = (h[0] * ox + h[1] * oy + h[2]) / z
                val sy = (h[3] * ox + h[4] * oy + h[5]) / z
                plan.sourcePoint(i, ox, oy, exact)
                worst = maxOf(worst, kotlin.math.hypot(sx - exact[0], sy - exact[1]))
            }
        }
        assertTrue("worst $worst px", worst < 1.0)
    }

    /** Accel generated from the gyro-integrated orientation and a tilted world gravity. */
    private fun withAccel(t: StabSidecar.Take, tiltRad: Double, flipX: Boolean = false): StabSidecar.Take {
        val track = StabPlanner.OrientationTrack.build(t.gyro, StabPlanner.Calibration())!!
        val q = StabPlanner.Quat
        val gw = doubleArrayOf(sin(tiltRad), cos(tiltRad), 0.0)
        val m = GyroCameraFrameMapper.matrix(t.header.sensorOrientationDeg, t.header.frontFacing)
        val accel = t.gyro.map { gs ->
            val c = q.toMat(track.at(gs.timestampNanos))
            // camera-frame gravity Cᵀ·gw, then device axes Mᵀ·g (M orthogonal)
            val g = DoubleArray(3) { k -> c[k] * gw[0] + c[3 + k] * gw[1] + c[6 + k] * gw[2] }
            if (flipX) g[0] = -g[0]
            val d = DoubleArray(3) { k -> m[k] * g[0] + m[3 + k] * g[1] + m[6 + k] * g[2] }
            GyroSample(gs.timestampNanos, (9.8 * d[0]).toFloat(), (9.8 * d[1]).toFloat(), (9.8 * d[2]).toFloat())
        }
        return t.copy(extras = StabSidecar.Extras(accel = accel))
    }

    private fun virtualTilt(plan: StabPlanner.Plan, take: StabSidecar.Take, i: Int, tiltRad: Double): Double {
        val q = StabPlanner.Quat
        val v = q.toMat(doubleArrayOf(plan.virtual[i * 4], plan.virtual[i * 4 + 1], plan.virtual[i * 4 + 2], plan.virtual[i * 4 + 3]))
        val gw = doubleArrayOf(sin(tiltRad), cos(tiltRad), 0.0)
        val g = DoubleArray(3) { k -> v[k] * gw[0] + v[3 + k] * gw[1] + v[6 + k] * gw[2] }
        return StabPlanner.HorizonFit.quadrantResidual(g)
    }

    @Test fun `horizon lock levels a tilted take`() {
        val tilt = 0.02
        val base = take(150) { t -> Triple(0.25 * sin(2 * PI * 1.1 * t), 0.3 * cos(2 * PI * 0.8 * t), 0.15 * sin(2 * PI * 0.6 * t)) }
        val t = withAccel(base, tilt)
        // Leveling needs roll room: a 12 % margin with half of it for roll.
        val room = StabPlanner.Options(cropMargin = 0.12, rollShare = 0.5)
        val off = StabPlanner.plan(t, room)!!
        val on = StabPlanner.plan(t, room.copy(horizonLock = true))!!
        assertTrue(on.horizon, on.horizon.startsWith("applied"))
        var errOn = 0.0
        var errOff = 0.0
        for (i in 30 until 120) {
            errOn = maxOf(errOn, abs(virtualTilt(on, t, i, tilt)))
            errOff = maxOf(errOff, abs(virtualTilt(off, t, i, tilt)))
        }
        assertTrue("on $errOn off $errOff", errOn < 0.01 && errOn < errOff * 0.3)
        assertNoBorder(on)
    }

    @Test fun `horizon lock refuses without rotation to identify axes`() {
        val t = withAccel(take(90) { Triple(0.0, 0.0, 0.0) }, 0.02)
        val plan = StabPlanner.plan(t, StabPlanner.Options(horizonLock = true))!!
        assertTrue(plan.horizon, plan.horizon.startsWith("axes ambiguous"))
    }

    @Test fun `blur easing follows the camera through smeared frames`() {
        val ex = { n: Int -> StabSidecar.Extras(exposureNs = LongArray(n) { 30_000_000L }) }
        val shake = { tt: Double -> Triple(0.0, 0.6 * sin(2 * PI * 3 * tt), 0.0) }
        val eased = StabPlanner.plan(take(90, extras = ex, rate = shake))!!
        val stiff = StabPlanner.plan(take(90, extras = ex, rate = shake), StabPlanner.Options(blurEase = false))!!
        assertTrue(eased.blurEasedFrames > 10)
        var ce = 0.0
        var cs = 0.0
        for (i in 10 until 80) {
            ce += abs(centre(eased, i)[0] - 1920.0)
            cs += abs(centre(stiff, i)[0] - 1920.0)
        }
        assertTrue("eased $ce stiff $cs", ce < cs * 0.8)
    }
}
