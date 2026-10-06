// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

class StabTrajectoryTest {
    private fun header(fps: Int = 30) = StabSidecar.Header(
        fps = fps, encodeW = 3840, encodeH = 2160, timeOriginNs = 0L,
        sensorOrientationDeg = 90, frontFacing = false,
        focalMm = 6.0f, sensorWMm = 8.0f, sensorHMm = 6.0f,
        sensorW = 8000, sensorH = 6000,
        cropLeft = 0, cropTop = 750, cropW = 8000, cropH = 4500,
        mapperVersion = GyroCameraFrameMapper.MAPPER_VERSION
    )

    /** 200 Hz gyro fixture over [frames] frames at 30 fps. */
    private fun take(
        frames: Int,
        rate: (tSec: Double) -> Triple<Double, Double, Double>,
    ): StabSidecar.Take {
        val dt = 1.0 / 30
        val frameTs = LongArray(frames) { (it * dt * 1e9).toLong() }
        val gyro = ArrayList<GyroSample>()
        var t = -0.05
        val end = frames * dt + 0.05
        while (t < end) {
            val (x, y, z) = rate(t)
            gyro.add(GyroSample((t * 1e9).toLong(), x.toFloat(), y.toFloat(), z.toFloat()))
            t += 0.005
        }
        return StabSidecar.Take(header(), frameTs, gyro)
    }

    @Test fun `intrinsics derive from the crop window`() {
        val k = StabTrajectory.intrinsicsForEncode(header())
        assertNotNull(k)
        // 6mm * 3840 / 8mm = 2880; 6mm * 2160 / 4.5mm = 2880.
        assertEquals(2880.0, k!!.fxPixels, 1e-9)
        assertEquals(2880.0, k.fyPixels, 1e-9)
        assertEquals(1920.0, k.cxPixels, 1e-9)
        assertEquals(1080.0, k.cyPixels, 1e-9)
    }

    @Test fun `intrinsics refuse implausible geometry`() {
        assertNull(StabTrajectory.intrinsicsForEncode(header().copy(focalMm = 0f)))
        assertNull(StabTrajectory.intrinsicsForEncode(header().copy(cropW = 9000)))
        // Absurdly wide "focal length" smells like a unit mixup.
        assertNull(StabTrajectory.intrinsicsForEncode(header().copy(focalMm = 0.5f)))
    }

    @Test fun `zero gyro yields pure crop-scale on every frame`() {
        val plan = StabTrajectory.plan(take(30) { Triple(0.0, 0.0, 0.0) })
        assertNotNull(plan)
        val s = 1.0 - 2.0 * StabTrajectory.CROP_MARGIN
        for (f in 0 until 30) {
            val m = plan!!.matrixFor(f)
            assertEquals(s.toFloat(), m[0], 1e-6f)
            assertEquals(0f, m[1], 1e-6f)
            assertEquals(0.05f * 3840, m[2], 1e-3f)
            assertEquals(0f, m[3], 1e-6f)
            assertEquals(s.toFloat(), m[4], 1e-6f)
            assertEquals(0.05f * 2160, m[5], 1e-3f)
            assertEquals(0f, m[6], 1e-9f)
            assertEquals(0f, m[7], 1e-9f)
            assertEquals(1f, m[8], 1e-9f)
        }
    }

    @Test fun `constant pan passes through with bounded onset transient`() {
        // 0.1 rad/s yaw for 3 s: centered smoothing tracks the ramp, so
        // interior corrections vanish and the pan survives stabilization.
        val plan = StabTrajectory.plan(take(90) { Triple(0.0, 0.1, 0.0) })
        assertNotNull(plan)
        fun tx(f: Int): Float = plan!!.matrixFor(f)[2] - 0.05f * 3840
        fun ty(f: Int): Float = plan!!.matrixFor(f)[5] - 0.05f * 2160
        // Interior: ramp minus its centered average is ~0 (pan preserved).
        assertTrue(tx(45) in -2f..2f)
        assertTrue(ty(45) in -2f..2f)
        // Onset: clamped-edge smoothing looks ahead, correction leads by
        // ~1.87 frames of rotation: -2880 * 1.867 * (0.1/30) ≈ -17.9px.
        assertTrue("onset tx=${tx(0)}", tx(0) in -25f..-10f)
        assertTrue(ty(0) in -2f..2f)
    }

    @Test fun `fast shake is inverted at full scale`() {
        // 4 Hz yaw shake, 0.02 rad amplitude: the 15-frame window spans
        // exactly 2 periods, so smoothing vanishes and corr = raw.
        val amp = 0.02
        val freq = 4.0
        val omega = amp * 2 * PI * freq
        val plan = StabTrajectory.plan(take(90) { t -> Triple(0.0, omega * kotlin.math.cos(2 * PI * freq * t), 0.0) })
        assertNotNull(plan)
        // Content amplitude 2880 * 0.02 = 57.6px; expect full inversion
        // within integration lag (≈2.5ms hold ≈ 3.6px worst case).
        for (f in 20..40) {
            val tNext = (f + 1) / 30.0
            val expected = (2880.0 * amp * sin(2 * PI * freq * tNext)).toFloat()
            val actual = plan!!.matrixFor(f)[2] - 0.05f * 3840
            assertTrue("frame $f: tx=$actual expected=$expected", kotlin.math.abs(actual - expected) < 8f)
        }
    }

    @Test fun `roll correction rotates about the center`() {
        val plan = StabTrajectory.plan(take(90) { Triple(0.0, 0.0, 0.05) })
        assertNotNull(plan)
        // Onset lead ≈ 1.867 frames: -(1.867 * 0.05/30) ≈ -3.1e-3 rad.
        val m0 = plan!!.matrixFor(0)
        val angle = atan2(m0[3].toDouble(), m0[0].toDouble())
        assertTrue("roll=$angle", angle in -5e-3..-1.5e-3)
        // No translation rides along with pure roll: strip the crop
        // and center-rotation terms, tx must vanish.
        val s = 1.0 - 2.0 * StabTrajectory.CROP_MARGIN
        val c = m0[0] / s
        val sn = m0[3] / s
        // H[0..1] already carry the crop scale s; divide it back out.
        val m02 = m0[2] - (m0[0] * 0.05f * 3840 + m0[1] * 0.05f * 2160) / s
        val tx = m02 - (1920.0 - (c * 1920.0 - sn * 1080.0))
        assertTrue("tx=$tx", kotlin.math.abs(tx) < 2f)
    }

    @Test fun `violent shake clamps to the margin`() {
        val plan = StabTrajectory.plan(take(60) { Triple(0.0, 5.0, 0.0) })
        assertNotNull(plan)
        val maxTx = 0.05f * 3840 * StabTrajectory.SHIFT_CLAMP_FRACTION
        for (f in 0 until 60) {
            val tx = kotlin.math.abs(plan!!.matrixFor(f)[2] - 0.05f * 3840)
            assertTrue("frame $f: tx=$tx", tx <= maxTx + 1e-3f)
        }
    }

    @Test fun `plan refuses thin gyro and bad calibration`() {
        val thin = take(60) { Triple(0.0, 0.0, 0.0) }
            .copy(gyro = listOf(GyroSample(0L, 0f, 0f, 0f)))
        assertNull(StabTrajectory.plan(thin))
        val badCal = take(10) { Triple(0.0, 0.0, 0.0) }
            .copy(header = header().copy(focalMm = 0.5f))
        assertNull(StabTrajectory.plan(badCal))
        val noFrames = take(10) { Triple(0.0, 0.0, 0.0) }
            .copy(frameTimestampsNs = longArrayOf())
        assertNull(StabTrajectory.plan(noFrames))
    }

    @Test fun `quaternion accumulates a 90-degree pan exactly`() {
        var q = StabTrajectory.Quat.IDENTITY
        repeat(30) { q = q.then(StabTrajectory.Quat.fromRotationVector(0.0, PI / 60, 0.0)) }
        val e = q.toEulerYxz()
        assertEquals(0.0, e[0], 1e-9)
        assertEquals(PI / 2, e[1], 1e-9)
        assertEquals(0.0, e[2], 1e-9)
    }

    @Test fun `small rotation vectors round-trip through euler`() {
        // A rotation vector is one rotation about a tilted axis; its YXZ
        // Euler decomposition agrees only to first order (O(|r|^2) ≈ 5e-4).
        val e = StabTrajectory.Quat.fromRotationVector(0.01, -0.02, 0.003).toEulerYxz()
        assertEquals(0.01, e[0], 1e-3)
        assertEquals(-0.02, e[1], 1e-3)
        assertEquals(0.003, e[2], 1e-3)
    }

    @Test fun `integration holds the last delta across empty windows`() {
        val frames = longArrayOf(0L, 33_333_333L, 66_666_666L)
        val gyro = listOf(
            GyroSample(0L, 1f, 2f, 3f),
            GyroSample(16_000_000L, 1f, 2f, 3f)
        )
        val d = StabTrajectory.integrateDeltas(frames, gyro)
        // Frame 0: 1 rad/s over 1/30 s.
        assertEquals(1.0 / 30, d[0], 1e-6)
        assertEquals(2.0 / 30, d[1], 1e-6)
        assertEquals(3.0 / 30, d[2], 1e-6)
        // Frames 1-2: no samples, hold frame 0's delta.
        assertEquals(d[0], d[3], 0.0)
        assertEquals(d[1], d[4], 0.0)
        assertEquals(d[2], d[5], 0.0)
        assertEquals(d[0], d[6], 0.0)
    }

    @Test fun `unwrap keeps long pans continuous`() {
        val a = doubleArrayOf(0.0, 3.0, -3.0, -2.9)
        StabTrajectory.unwrapInPlace(a)
        assertEquals(0.0, a[0], 1e-12)
        assertEquals(3.0, a[1], 1e-12)
        assertEquals(-3.0 + 2 * PI, a[2], 1e-12)
        assertEquals(-2.9 + 2 * PI, a[3], 1e-12)
    }

    @Test fun `smoothing kills fast shake but keeps the mean`() {
        val n = 90
        val shaky = DoubleArray(n) { 5.0 + sin(2 * PI * 4 * it / 30.0) }
        val s = StabTrajectory.smooth(shaky, 15)
        for (i in 20..70) {
            assertEquals(5.0, s[i], 0.05)
        }
    }
}
