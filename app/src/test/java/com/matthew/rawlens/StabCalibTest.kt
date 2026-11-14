// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class StabCalibTest {
    private val header = StabSidecar.Header(
        fps = 30, encodeW = 3840, encodeH = 2160, timeOriginNs = 0L,
        sensorOrientationDeg = 90, frontFacing = false,
        focalMm = 6.0f, sensorWMm = 8.0f, sensorHMm = 6.0f,
        sensorW = 8000, sensorH = 6000,
        cropLeft = 0, cropTop = 750, cropW = 8000, cropH = 4500,
        mapperVersion = GyroCameraFrameMapper.MAPPER_VERSION,
    )
    private val f = 2880.0

    private fun rx(t: Double) = 0.3 * sin(2 * PI * 1.3 * t) + 0.15 * sin(2 * PI * 3.7 * t + 1)
    private fun ry(t: Double) = 0.4 * cos(2 * PI * 0.9 * t) + 0.2 * sin(2 * PI * 4.1 * t)

    /** Gyro reported with a clock error + bias; shifts measured from the true motion. */
    private fun fixture(trueOffsetNs: Long, biasX: Double, biasY: Double, noisePx: Double): Pair<StabSidecar.Take, List<StabCalib.PairShift>> {
        val frames = 240
        val ts = LongArray(frames) { (it * 1e9 / 30).toLong() }
        val gyro = ArrayList<GyroSample>()
        var t = -0.2
        while (t < frames / 30.0 + 0.2) {
            // gyro timestamp = true time − offset, so calibration should find +offset
            val stamp = ((t - trueOffsetNs * 1e-9) * 1e9).toLong()
            gyro.add(GyroSample(stamp, (rx(t) + biasX).toFloat(), (ry(t) + biasY).toFloat(), 0f))
            t += 0.0025
        }
        val rnd = Random(7)
        val shifts = (0 until frames - 1).map { i ->
            // ∫ over [ts_i, ts_i+1] by fine Riemann sum
            var ix = 0.0
            var iy = 0.0
            val a = ts[i] * 1e-9
            val b = ts[i + 1] * 1e-9
            val m = 200
            for (k in 0 until m) {
                val tt = a + (k + 0.5) * (b - a) / m
                ix += rx(tt) * (b - a) / m
                iy += ry(tt) * (b - a) / m
            }
            StabCalib.PairShift(i, f * iy + rnd.nextDouble(-noisePx, noisePx), -f * ix + rnd.nextDouble(-noisePx, noisePx), 1.0)
        }
        return StabSidecar.Take(header, ts, gyro) to shifts
    }

    @Test fun `recovers the gyro time offset`() {
        for (off in listOf(-23_000_000L, 0L, 14_500_000L)) {
            val (take, shifts) = fixture(off, 0.0, 0.0, 0.5)
            val r = StabCalib.calibrate(take, shifts)!!
            assertTrue("accepted for $off", r.offsetAccepted)
            assertEquals("offset", off.toDouble(), r.calibration.gyroOffsetNs.toDouble(), 1_000_000.0)
            assertTrue(r.rmsAfterPx <= r.rmsBeforePx + 1e-9)
        }
    }

    @Test fun `recovers gyro bias`() {
        val (take, shifts) = fixture(5_000_000L, 0.004, -0.006, 0.3)
        val r = StabCalib.calibrate(take, shifts)!!
        assertTrue(r.biasAccepted)
        assertEquals(0.004, r.calibration.biasX, 0.0008)
        assertEquals(-0.006, r.calibration.biasY, 0.0008)
    }

    /** Device case: hidden OIS shrinks image motion (gain 0.6) on a pan; no real bias. */
    @Test fun `hidden OIS gain is not mistaken for bias`() {
        val (take, shifts) = fixture(0L, 0.0, 0.0, 0.3)
        // add a steady pan to the gyro and 60 % of it to the image
        val pan = 0.3
        val gyro = take.gyro.map { GyroSample(it.timestampNanos, it.xRadiansPerSecond, (it.yRadiansPerSecond + pan).toFloat(), 0f) }
        val dt = 1.0 / 30
        val scaled = shifts.map { it.copy(dx = 0.6 * (it.dx + f * pan * dt), dy = 0.6 * it.dy) }
        val r = StabCalib.calibrate(take.copy(gyro = gyro), scaled)!!
        assertEquals(0.0, r.calibration.biasY, 0.002)
        assertEquals(0.0, r.calibration.biasX, 0.002)
    }

    @Test fun `rejects an offset when there is no motion`() {
        val ts = LongArray(120) { (it * 1e9 / 30).toLong() }
        val gyro = (0 until 2000).map { GyroSample((it * 2.5e6).toLong() - 100_000_000L, 0f, 0f, 0f) }
        val rnd = Random(1)
        val shifts = (0 until 119).map { StabCalib.PairShift(it, rnd.nextDouble(-0.3, 0.3), rnd.nextDouble(-0.3, 0.3), 1.0) }
        val r = StabCalib.calibrate(StabSidecar.Take(header, ts, gyro), shifts)!!
        assertTrue(!r.offsetAccepted)
        assertEquals(0L, r.calibration.gyroOffsetNs)
    }

    @Test fun `global shift measures a subpixel translation`() {
        val w = 320
        val h = 180
        val img = { dx: Double, dy: Double ->
            FloatArray(w * h) { i ->
                val x = (i % w) - dx
                val y = (i / w) - dy
                (sin(x * 0.21) * cos(y * 0.17) + 0.5 * sin((x + y) * 0.07) + 0.3 * cos(x * 0.05 - y * 0.11)).toFloat()
            }
        }
        val s = StabCalib.GlobalShift.measure(img(0.0, 0.0), img(3.3, -1.7), w, h)
        assertNotNull(s)
        assertEquals(3.3, s!!.dx, 0.05)
        assertEquals(-1.7, s.dy, 0.05)
        assertTrue(s.confidence > 1.0)
        assertTrue("se ${s.stdErr}", s.stdErr < 0.05)
        val flat = FloatArray(w * h) { 0.5f }
        val none = StabCalib.GlobalShift.measure(flat, flat, w, h)
        assertTrue(none == null || none.confidence < 1.0)
        assertTrue(abs(0.0) == 0.0)
    }
}
