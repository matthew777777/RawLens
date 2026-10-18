package com.rawlens.srpipeline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FftTest {

    @Test
    fun `forward1D finds single bin`() {
        val n = 8
        val k = 2
        val input = FloatArray(n) { cos(2.0 * PI * k * it / n).toFloat() }
        val spec = Fft.forward1D(input)
        for (i in 0 until n) {
            val mag = kotlin.math.hypot(spec.real[i], spec.imag[i])
            if (i == k || i == n - k) {
                assertEquals(n / 2f, mag, 1e-3f)
            } else {
                assertTrue("bin $i magnitude $mag should be ~0", mag < 1e-3f)
            }
        }
    }

    @Test
    fun `inverse1D inverts forward1D`() {
        val input = floatArrayOf(0.5f, -1.25f, 3f, 0.125f, 2f, -0.75f, 1f, 4f)
        val out = Fft.inverse1D(Fft.forward1D(input))
        for (i in input.indices) {
            assertEquals(input[i], out[i], 1e-4f)
        }
    }

    @Test
    fun `forward2D then inverse2D roundtrips`() {
        val h = 8
        val w = 8
        val input = FloatArray(h * w) { (it % 7 - 3).toFloat() * 0.5f }
        val out = Fft.inverse2D(Fft.forward2D(input, h, w))
        for (i in input.indices) {
            assertEquals(input[i], out[i], 1e-3f)
        }
    }

    @Test
    fun `forward2D DC bin equals sum`() {
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val spec = Fft.forward2D(input, 2, 2)
        val dc = Shifts.ifftShift(Shifts.fftShift(spec))
        assertEquals(10f, dc.real[0], 1e-3f)
        assertEquals(0f, dc.imag[0], 1e-3f)
    }

    @Test
    fun `lowpass of constant frame is identity`() {
        val h = 16
        val w = 16
        val input = FloatArray(h * w) { 0.7f }
        val out = Fft.lowpass(input, h, w)
        for (i in out.indices) {
            assertEquals(0.7f, out[i], 1e-3f)
        }
    }

    @Test
    fun `lowpass attenuates checkerboard`() {
        val h = 16
        val w = 16
        val input = FloatArray(h * w) { i -> if ((i / w + i % w) % 2 == 0) 1f else -1f }
        val out = Fft.lowpass(input, h, w)
        var peak = 0f
        for (v in out) {
            peak = maxOf(peak, kotlin.math.abs(v))
        }
        assertTrue("checkerboard should be attenuated, peak=$peak", peak < 0.1f)
        // Silence unused-import guard for sin.
        assertEquals(0f, sin(0.0).toFloat(), 0f)
    }
}
