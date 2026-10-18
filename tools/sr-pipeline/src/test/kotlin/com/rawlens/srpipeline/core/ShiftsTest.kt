package com.rawlens.srpipeline.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ShiftsTest {

    @Test
    fun `fftshift then ifftshift roundtrips even grid`() {
        val input = FloatArray(4 * 6) { it.toFloat() }
        val out = Shifts.ifftShift(Shifts.fftShift(input, 4, 6), 4, 6)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `fftshift then ifftshift roundtrips odd grid`() {
        val input = FloatArray(5 * 7) { it.toFloat() }
        val out = Shifts.ifftShift(Shifts.fftShift(input, 5, 7), 5, 7)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `fftshift moves dc to centre`() {
        // Impulse at origin: every spectrum bin is 1; fftshift is a pure roll.
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val shifted = Shifts.fftShift(input, 2, 2)
        assertArrayEquals(floatArrayOf(4f, 3f, 2f, 1f), shifted, 0f)
    }

    @Test
    fun `1d shifts invert each other`() {
        val input = FloatArray(9) { it.toFloat() }
        val out = Shifts.ifftShift(Shifts.fftShift(input))
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `complex shifts move both parts`() {
        val spec = ComplexSpectrum(
            2, 2,
            floatArrayOf(1f, 2f, 3f, 4f),
            floatArrayOf(5f, 6f, 7f, 8f),
        )
        val shifted = Shifts.fftShift(spec)
        assertArrayEquals(floatArrayOf(4f, 3f, 2f, 1f), shifted.real, 0f)
        assertArrayEquals(floatArrayOf(8f, 7f, 6f, 5f), shifted.imag, 0f)
        val back = Shifts.ifftShift(shifted)
        assertArrayEquals(spec.real, back.real, 0f)
        assertArrayEquals(spec.imag, back.imag, 0f)
    }
}
