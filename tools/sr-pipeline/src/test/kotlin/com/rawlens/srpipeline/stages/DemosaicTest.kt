package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Demosaic].
 *
 * The Menon expectations below are cross-checked against
 * `demosaic_to_grey` (`demosaicing_CFA_Bayer_Menon2007`, RGGB, refining step
 * on) from `handheld_super_resolution/utils_image.py`, run under the
 * project venv (read-only; the venv itself is never modified):
 *
 * ```
 * import numpy as np, sys
 * sys.path.insert(0, "<handheld-super-resolution>")
 * from handheld_super_resolution.utils_image import demosaic_to_grey
 * ```
 *
 * Tolerance policy: bit-exactness with the Python path is not demanded
 * (except for constant images, see below) because the port rounds the
 * float64 pipeline once to float32 exactly like the reference, but the
 * 5-tap/3-tap FIR accumulation order and the 5x5 decision convolution
 * summation order of `scipy.ndimage` are implementation-defined; the
 * residual is a few float32 ulps, so golden comparisons use `1e-6f`. The
 * `demosaicWithinEnvelope` test additionally asserts the recorded
 * `stages.demosaic.max_abs_diff` envelope (`0.0076806508004665375`, copied
 * verbatim from `parity/envelopes.json`, itself the GPU-vs-CPU bound): the
 * CPU-vs-CPU port must lie far inside it.
 */
class DemosaicTest {

    companion object {
        /** Recorded envelope: `stages.demosaic.max_abs_diff` in `parity/envelopes.json`. */
        const val DEMOSAIC_ENVELOPE_MAX_ABS = 0.0076806508004665375

        /** Menon2007 grey of the shared 4x4 [GreyTest.INPUT] fixture (venv cross-check). */
        val EXPECTED_MENON_4X4 = arrayOf(
            floatArrayOf(0.64699072f, 0.24739583f, 0.89699072f, 0.49739584f),
            floatArrayOf(0.62369794f, 0.22974537f, 0.87369794f, 0.47974536f),
            floatArrayOf(0.58275461f, 0.18880208f, 0.83275461f, 0.43880209f),
            floatArrayOf(0.56510419f, 0.16550925f, 0.81510419f, 0.41550925f),
        )

        /** Menon2007 grey of `[[0.1, 0.2], [0.3, 0.4]]` (venv cross-check). */
        val EXPECTED_MENON_2X2 = arrayOf(
            floatArrayOf(0.2f, 0.2f),
            floatArrayOf(0.3f, 0.3f),
        )

        /** Deterministic 8x8 CFA input, bit-identical in Python and Kotlin (see [formulaInput]). */
        fun formulaInput(h: Int, w: Int): Array<FloatArray> =
            Array(h) { y -> FloatArray(w) { x -> ((y * 31 + x * 17) % 23).toFloat() / 23f } }

        /** Menon2007 grey of [formulaInput]`(8, 8)` (venv cross-check). */
        val EXPECTED_MENON_8X8 = arrayOf(
            floatArrayOf(-0.11272142f, 0.55012077f, 0.62318838f, 0.09903382f, 0.77898550f, 0.69565219f, 0.25040257f, 0.21678744f),
            floatArrayOf(0.21437199f, 0.25986314f, 0.61684781f, 0.69987923f, 0.21890096f, 0.17371176f, 0.70893717f, 0.52435589f),
            floatArrayOf(0.76811594f, 0.38466182f, 0.30605876f, 0.83303142f, 0.83343399f, 0.28592995f, 0.35628018f, 0.75634056f),
            floatArrayOf(0.21557972f, 0.59208935f, 0.63888890f, 0.08353462f, 0.08001208f, 0.60698473f, 0.52838165f, 0.14492753f),
            floatArrayOf(0.21376812f, 0.33031401f, 0.74939609f, 0.78562802f, 0.24275362f, 0.27536231f, 0.67632848f, 0.76811594f),
            floatArrayOf(0.78623188f, 0.37801933f, 0.27898550f, 0.80927938f, 0.78955317f, 0.25634056f, 0.32820049f, 0.69243157f),
            floatArrayOf(0.30756843f, 0.67179948f, 0.74365938f, 0.21044685f, 0.19072060f, 0.72101450f, 0.62198067f, 0.21376811f),
            floatArrayOf(0.17632850f, 0.28663447f, 0.71074879f, 0.75493157f, 0.22131643f, 0.27143720f, 0.70440817f, 0.82326889f),
        )

        private fun maxAbsDiff(a: Array<FloatArray>, b: Array<FloatArray>): Double {
            var m = 0.0
            for (y in a.indices) for (x in a[y].indices) {
                val d = kotlin.math.abs(a[y][x] - b[y][x]).toDouble()
                if (d > m) m = d
            }
            return m
        }
    }

    @Test
    fun `demosaicToGrey matches Menon2007 on the shared 4x4 fixture`() {
        val out = Demosaic.demosaicToGrey(GreyTest.INPUT)
        assertEquals(4, out.size)
        assertEquals(4, out[0].size)
        for (y in EXPECTED_MENON_4X4.indices) {
            assertArrayEquals(EXPECTED_MENON_4X4[y], out[y], 1e-6f)
        }
    }

    @Test
    fun `demosaicToGrey matches Menon2007 on a 2x2 input`() {
        val inp = arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f))
        val out = Demosaic.demosaicToGrey(inp)
        for (y in EXPECTED_MENON_2X2.indices) {
            assertArrayEquals(EXPECTED_MENON_2X2[y], out[y], 1e-6f)
        }
    }

    @Test
    fun `demosaicToGrey preserves a constant image exactly`() {
        // Every Menon2007 interpolation of a constant is that constant
        // (kernels sum to 1, mirror/constant edges only reuse the same
        // value), and 0.5 is exact in float32 and float64, so exactness is
        // semantically guaranteed here, unlike for general inputs.
        val img = Array(4) { FloatArray(4) { 0.5f } }
        val out = Demosaic.demosaicToGrey(img)
        for (row in out) assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), row, 0.0f)
    }

    @Test
    fun `demosaicToGrey matches Menon2007 within the recorded envelope`() {
        val inp = formulaInput(8, 8)
        val out = Demosaic.demosaicToGrey(inp)
        assertEquals(8, out.size)
        assertEquals(8, out[0].size)
        for (y in EXPECTED_MENON_8X8.indices) {
            assertArrayEquals(EXPECTED_MENON_8X8[y], out[y], 1e-6f)
        }
        assertTrue(
            "max abs diff ${maxAbsDiff(EXPECTED_MENON_8X8, out)} exceeds envelope $DEMOSAIC_ENVELOPE_MAX_ABS",
            maxAbsDiff(EXPECTED_MENON_8X8, out) <= DEMOSAIC_ENVELOPE_MAX_ABS,
        )
    }

    @Test
    fun `demosaicToGrey keeps shape and finite outputs on odd sizes`() {
        for ((h, w) in listOf(5 to 3, 3 to 5, 1 to 1, 7 to 7)) {
            val out = Demosaic.demosaicToGrey(formulaInput(h, w))
            assertEquals(h, out.size)
            assertEquals(w, out[0].size)
            for (row in out) for (v in row) assertTrue("expected finite, got $v", v.isFinite())
        }
    }

    @Test
    fun `demosaicToGrey isolates a single NaN pixel via the bilinear fallback`() {
        // NaN inputs take the private bilinear fallback (Menon convolutions
        // would smear NaN across the neighbourhood), so the previous
        // NaN-isolating expectations still hold: (1, 1) averages the finite
        // neighbours of the missing R/B channels, (0, 1) has no finite B
        // neighbour and averages R and G only.
        val img = GreyTest.INPUT.map { it.clone() }.toTypedArray()
        img[1][1] = Float.NaN
        val out = Demosaic.demosaicToGrey(img)
        for (row in out) {
            for (v in row) assertTrue("expected finite, got $v", v.isFinite())
        }
        assertEquals(0.3515625f, out[1][1], 1e-6f)
        assertEquals(0.25f, out[0][1], 1e-6f)
    }

    @Test
    fun `demosaicToGrey yields NaN only when every channel is missing`() {
        val out = Demosaic.demosaicToGrey(Array(2) { FloatArray(2) { Float.NaN } })
        for (row in out) {
            for (v in row) assertTrue("expected NaN, got $v", v.isNaN())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `demosaicToGrey rejects ragged input`() {
        Demosaic.demosaicToGrey(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f)))
    }
}
