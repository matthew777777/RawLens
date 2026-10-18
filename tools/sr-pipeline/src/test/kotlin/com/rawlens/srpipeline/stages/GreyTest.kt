package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Unit tests for [Grey].
 *
 * The 4x4 expectations are numpy cross-checks: the same input run through
 * `fft_lowpass` from `handheld_super_resolution/utils_image.py` (torch
 * backend) and through an equivalent numpy `fft2/fftshift/zero/ifftshift/
 * ifft2` implementation, which agree. The constant-image cases are
 * implementation-independent (DC survives the low-pass, so output must equal
 * input) and additionally cover the naive-DFT fallback on non-power-of-two
 * sizes.
 */
class GreyTest {

    companion object {
        // Shared 4x4 fixture (also used by the other stage tests).
        val INPUT = arrayOf(
            floatArrayOf(0.125f, 0.25f, 0.375f, 0.5f),
            floatArrayOf(0.625f, 0.75f, 0.875f, 1.0f),
            floatArrayOf(0.0625f, 0.1875f, 0.3125f, 0.4375f),
            floatArrayOf(0.5625f, 0.6875f, 0.8125f, 0.9375f),
        )
        val EXPECTED_LOWAPS = arrayOf(
            floatArrayOf(0.484375f, 0.484375f, 0.609375f, 0.609375f),
            floatArrayOf(0.484375f, 0.484375f, 0.609375f, 0.609375f),
            floatArrayOf(0.453125f, 0.453125f, 0.578125f, 0.578125f),
            floatArrayOf(0.453125f, 0.453125f, 0.578125f, 0.578125f),
        )
    }

    private fun assertMatrixEquals(expected: Array<FloatArray>, actual: Array<FloatArray>, delta: Float) {
        assertArrayEquals(
            expected.flatMap { it.toList() }.toFloatArray(),
            actual.flatMap { it.toList() }.toFloatArray(),
            delta,
        )
    }

    @Test
    fun `fftLowpass matches numpy cross-checked values`() {
        assertMatrixEquals(EXPECTED_LOWAPS, Grey.fftLowpass(INPUT), 1e-5f)
    }

    @Test
    fun `fftLowpass preserves constant image on power-of-two size`() {
        val img = Array(8) { FloatArray(8) { 0.37f } }
        assertMatrixEquals(img, Grey.fftLowpass(img), 1e-6f)
    }

    @Test
    fun `fftLowpass preserves constant image on non-power-of-two sizes`() {
        val img6 = Array(6) { FloatArray(6) { 0.42f } }
        assertMatrixEquals(img6, Grey.fftLowpass(img6), 1e-6f)
        val img5 = Array(5) { FloatArray(5) { 0.42f } }
        assertMatrixEquals(img5, Grey.fftLowpass(img5), 1e-6f)
    }

    @Test
    fun `computeGreyImages FFT dispatch matches fftLowpass`() {
        assertMatrixEquals(EXPECTED_LOWAPS, Grey.computeGreyImages(INPUT, "FFT"), 1e-5f)
    }

    @Test
    fun `computeGreyImages decimating dispatch halves each dimension`() {
        val out = Grey.computeGreyImages(INPUT, "decimating")
        assertMatrixEquals(
            arrayOf(floatArrayOf(0.4375f, 0.6875f), floatArrayOf(0.375f, 0.625f)),
            out,
            0.0f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `computeGreyImages rejects unknown method`() {
        Grey.computeGreyImages(INPUT, "median")
    }
}
