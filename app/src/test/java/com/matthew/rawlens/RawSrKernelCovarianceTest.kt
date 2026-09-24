// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Prompt 4B CPU oracle tests: no GLES required. */
class RawSrKernelCovarianceTest {
    private fun flat(width: Int = 16, height: Int = 12, value: Float = 0.4f) =
        RawSrGrayImage(width, height, FloatArray(width * height) { value })

    @Test fun flatFieldIsIsotropicDenoiseAndLowSnrWidensKernel() {
        val gray = flat()
        val mid = RawSrTuning.forSnr(18.0)
        val expectedK = 0.29f * 4.0f
        val precision = RawSrKernelCovariance.precision(gray, mid)
        val covariance = RawSrKernelCovariance.covariance(gray, mid)
        for (y in 0 until gray.height) for (x in 0 until gray.width) {
            assertEquals(1f / (expectedK * expectedK), precision.get(x, y, 0), 1e-6f)
            assertEquals(0f, precision.get(x, y, 1), 1e-6f)
            assertEquals(0f, precision.get(x, y, 2), 1e-6f)
            assertEquals(1f / (expectedK * expectedK), precision.get(x, y, 3), 1e-6f)
            assertEquals(expectedK * expectedK, covariance.get(x, y, 0), 1e-6f)
            assertEquals(expectedK * expectedK, covariance.get(x, y, 3), 1e-6f)
        }
        // Wider radial kernels at low SNR: precision falls as the denoise radius grows.
        val low = RawSrKernelCovariance.precision(gray, RawSrTuning.forSnr(6.0)).get(8, 6, 0)
        val high = RawSrKernelCovariance.precision(gray, RawSrTuning.forSnr(30.0)).get(8, 6, 0)
        assertTrue("low=$low mid=${1f / (expectedK * expectedK)} high=$high", low < 1f / (expectedK * expectedK))
        assertTrue("low=$low high=$high", high > 1f / (expectedK * expectedK))
    }

    @Test fun verticalLineElongatesAlongLine() {
        // Full-contrast one-quad-wide vertical line: both step columns fall inside the
        // 2x2 structure window, producing a dominant x gradient (t00 = 1).
        val width = 32
        val height = 24
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width)
            values[y * width + x] = if (x == 16) 1f else 0f
        val tuning = RawSrTuning.forSnr(30.0)
        val precision = RawSrKernelCovariance.precision(RawSrGrayImage(width, height, values), tuning)
        val p00 = precision.get(16, 12, 0)
        val p01 = precision.get(16, 12, 1)
        val p11 = precision.get(16, 12, 3)
        // Analytic expectation: l1=1, l2=0, A=2, D=0.71, k1=0.56875, k2=0.8225.
        assertEquals(1f / (0.56875f * 0.56875f), p00, 1e-4f)
        assertEquals(0f, p01, 1e-5f)
        assertEquals(1f / (0.8225f * 0.8225f), p11, 1e-4f)
        assertTrue("across-line precision must exceed along-line: $p00 vs $p11", p00 / p11 > 1.5f)
    }

    @Test fun horizontalLineElongatesAlongLine() {
        val width = 32
        val height = 24
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width)
            values[y * width + x] = if (y == 12) 1f else 0f
        val tuning = RawSrTuning.forSnr(30.0)
        val precision = RawSrKernelCovariance.precision(RawSrGrayImage(width, height, values), tuning)
        val p00 = precision.get(16, 12, 0)
        val p11 = precision.get(16, 12, 3)
        assertEquals(precision.get(16, 12, 1), 0f, 1e-5f)
        assertTrue("along-line precision must exceed across-line: $p11 vs $p00", p11 / p00 > 1.5f)
    }

    @Test fun isolatedDiagonalAndCornerFallBackToDenoiseKernel() {
        // Without GAT preprocessing (out of scope for 4B), an isolated diagonal line
        // or quadrant corner concentrates too little gradient energy in the 2x2 window
        // to open the detail gate: sqrt(l1) ~= 0.5-0.61 stays below Dth*Dtr = 0.71 at
        // SNR 30, so D saturates to 1 and both patterns resolve exactly to the flat
        // isotropic denoise kernel. This pins the gate; it does not weaken it.
        val tuning = RawSrTuning.forSnr(30.0)
        val expected = 1f / (0.75f * 0.75f)
        val diagSize = 32
        val diagValues = FloatArray(diagSize * diagSize)
        for (y in 0 until diagSize) for (x in 0 until diagSize)
            diagValues[y * diagSize + x] = if (x == y) 1f else 0f
        val diagonal = RawSrKernelCovariance.precision(RawSrGrayImage(diagSize, diagSize, diagValues), tuning)
        assertEquals(expected, diagonal.get(16, 16, 0), 1e-5f)
        assertEquals(0f, diagonal.get(16, 16, 1), 1e-6f)
        assertEquals(expected, diagonal.get(16, 16, 3), 1e-5f)
        val cornerSize = 24
        val cornerValues = FloatArray(cornerSize * cornerSize)
        for (y in 0 until cornerSize) for (x in 0 until cornerSize)
            cornerValues[y * cornerSize + x] = if (x >= 12 && y >= 12) 1f else 0f
        val corner = RawSrKernelCovariance.precision(RawSrGrayImage(cornerSize, cornerSize, cornerValues), tuning)
        assertEquals(expected, corner.get(12, 12, 0), 1e-5f)
        assertEquals(0f, corner.get(12, 12, 1), 1e-6f)
        assertEquals(expected, corner.get(12, 12, 3), 1e-5f)
    }

    @Test fun denseStripesAreNarrowerAcrossThanFlat() {
        // Full-contrast period-2 vertical stripes fill every interior structure window
        // (t00 = 1): the detail gate opens (D = 0.71 at SNR 30) exactly as in the
        // single-line case, so every interior pixel shares its analytic precision and
        // is strictly narrower across the stripes than the flat field. A pixel-wise
        // checkerboard would alias to zero under the 2x2 gradient operator instead.
        val width = 28
        val height = 22
        val values = FloatArray(width * height) { i -> if ((i % width) % 2 == 0) 1f else 0f }
        val tuning = RawSrTuning.forSnr(30.0)
        val field = RawSrKernelCovariance.precision(RawSrGrayImage(width, height, values), tuning)
        val flat = RawSrKernelCovariance.precision(flat(width, height), tuning).get(4, 4, 0)
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            assertEquals(1f / (0.56875f * 0.56875f), field.get(x, y, 0), 1e-4f)
            assertEquals(0f, field.get(x, y, 1), 1e-5f)
        }
        assertTrue(field.get(10, 10, 0) / flat > 1.5f)
    }

    @Test fun covarianceIsPositiveDefiniteAndInvertsPrecision() {
        val width = 20
        val height = 14
        val values = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            0.35f + 0.25f * ((x * 79 + y * 43) % 17) / 17f + 0.1f * ((x * x + 3 * y) % 13) / 13f
        }
        val gray = RawSrGrayImage(width, height, values)
        for (snr in listOf(6.0, 18.0, 30.0)) {
            val tuning = RawSrTuning.forSnr(snr)
            val covariance = RawSrKernelCovariance.covariance(gray, tuning)
            val precision = RawSrKernelCovariance.precision(gray, tuning)
            for (y in 0 until height) for (x in 0 until width) {
                val c00 = covariance.get(x, y, 0)
                val c01 = covariance.get(x, y, 1)
                val c11 = covariance.get(x, y, 3)
                // Symmetry and positive-definiteness of the covariance.
                assertEquals(c01, covariance.get(x, y, 2), 0f)
                assertTrue("snr=$snr ($x,$y) c00=$c00", c00 > 0f)
                assertTrue("snr=$snr ($x,$y) det=${c00 * c11 - c01 * c01}", c00 * c11 - c01 * c01 > 0f)
                // Precision is exactly the matrix inverse.
                val p00 = precision.get(x, y, 0)
                val p01 = precision.get(x, y, 1)
                val p11 = precision.get(x, y, 3)
                assertEquals(1f, c00 * p00 + c01 * p01, 1e-3f)
                assertEquals(0f, c00 * p01 + c01 * p11, 1e-3f)
                assertEquals(0f, c01 * p00 + c11 * p01, 1e-3f)
                assertEquals(1f, c01 * p01 + c11 * p11, 1e-3f)
            }
        }
    }

    @Test fun nonFiniteInputStillYieldsFiniteCoefficients() {
        val gray = flat(10, 8)
        gray.values[3 * gray.width + 4] = Float.NaN
        gray.values[6 * gray.width + 7] = Float.POSITIVE_INFINITY
        val tuning = RawSrTuning.forSnr(18.0)
        val precision = RawSrKernelCovariance.precision(gray, tuning)
        val covariance = RawSrKernelCovariance.covariance(gray, tuning)
        assertTrue(precision.values.all { it.isFinite() })
        assertTrue(covariance.values.all { it.isFinite() })
    }

    @Test fun degenerateSinglePixelIsIsotropic() {
        val tuning = RawSrTuning.forSnr(18.0)
        val precision = RawSrKernelCovariance.precision(RawSrGrayImage(1, 1, floatArrayOf(0.5f)), tuning)
        val expected = 1f / ((0.29f * 4.0f) * (0.29f * 4.0f))
        assertEquals(expected, precision.get(0, 0, 0), 1e-6f)
        assertEquals(0f, precision.get(0, 0, 1), 0f)
        assertEquals(expected, precision.get(0, 0, 3), 1e-6f)
    }

    @Test fun resultsAreDeterministic() {
        val width = 20
        val height = 14
        val values = FloatArray(width * height) { i -> 0.2f + 0.6f * ((i * 2654435761L ushr 8) % 1024) / 1024f }
        val gray = RawSrGrayImage(width, height, values)
        val tuning = RawSrTuning.forSnr(22.0)
        assertArrayEquals(
            RawSrKernelCovariance.precision(gray, tuning).values,
            RawSrKernelCovariance.precision(gray, tuning).values, 0f)
        assertArrayEquals(
            RawSrKernelCovariance.covariance(gray, tuning).values,
            RawSrKernelCovariance.covariance(gray, tuning).values, 0f)
    }

    @Test fun eigenDecompositionMatchesReferenceCases() {
        val identity = RawSrKernelCovariance.eigenDecomposition(1f, 0f, 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 1f, 1f), identity, 0f)
        val diagonal = RawSrKernelCovariance.eigenDecomposition(4f, 0f, 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 4f, 1f), diagonal, 1e-6f)
        val coupled = RawSrKernelCovariance.eigenDecomposition(2f, 1f, 2f)
        val s = kotlin.math.sqrt(0.5f)
        assertArrayEquals(floatArrayOf(s, s, -s, s, 3f, 1f), coupled, 1e-6f)
        // Orthonormality and reconstruction for a general tensor.
        val general = RawSrKernelCovariance.eigenDecomposition(0.9f, 0.3f, 0.4f)
        val e1x = general[0]
        val e1y = general[1]
        val e2x = general[2]
        val e2y = general[3]
        assertEquals(1f, e1x * e1x + e1y * e1y, 1e-6f)
        assertEquals(1f, e2x * e2x + e2y * e2y, 1e-6f)
        assertEquals(0f, e1x * e2x + e1y * e2y, 1e-6f)
        assertEquals(0.9f, general[4] * e1x * e1x + general[5] * e2x * e2x, 1e-5f)
        assertEquals(0.3f, general[4] * e1x * e1y + general[5] * e2x * e2y, 1e-5f)
    }

    @Test fun eigenDecompositionIsStableAtNearCollinearTensors() {
        // Sea fixture tensor: gradients collinear to 0.2%, so the legacy
        // (T - l2I)(1,1) residual is rounding noise and 1-ulp input changes
        // rotate the kernel by degrees. The stable solve must hold the ~135-degree
        // major axis steady under +-10-ulp perturbations of every component.
        // Compared modulo PI: kernels consume the axis LINE (outer products),
        // so opposite directions of the same line are the same kernel. The 4E
        // row-selection form reports this line as -PI/4 rather than 3*PI/4.
        val base = floatArrayOf(277.4251f, -276.1415f, 277.428f)
        fun lineAngle(t00: Float, t01: Float, t11: Float): Double {
            val e = RawSrKernelCovariance.eigenDecomposition(t00, t01, t11)
            val a = kotlin.math.atan2(e[1].toDouble(), e[0].toDouble())
            return ((a % kotlin.math.PI) + kotlin.math.PI) % kotlin.math.PI
        }
        val expected = kotlin.math.PI * 3.0 / 4.0
        assertEquals(expected, lineAngle(base[0], base[1], base[2]), 2e-3)
        for (i in 0..2) for (dir in listOf(-10, 10)) {
            val p = base.copyOf()
            var v = p[i]
            repeat(kotlin.math.abs(dir)) { v = if (dir > 0) Math.nextUp(v) else Math.nextDown(v) }
            p[i] = v
            assertEquals("component $i dir $dir", expected, lineAngle(p[0], p[1], p[2]), 2e-3)
        }
    }

    @Test fun rejectsMismatchedPacking() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSrKernelCovariance.MatrixField(4, 4, FloatArray(4 * 4 * 3))
        }
    }

    @Test fun defaultSelectionLawIsHard() {
        val width = 20
        val height = 14
        val values = FloatArray(width * height) { i -> 0.2f + 0.6f * ((i * 2654435761L ushr 8) % 1024) / 1024f }
        val gray = RawSrGrayImage(width, height, values)
        val tuning = RawSrTuning.forSnr(18.0)
        assertArrayEquals(
            RawSrKernelCovariance.precision(
                gray, tuning, RawSrKernelCovariance.KernelType.STEERABLE,
                RawSrKernelCovariance.SelectionLaw.HARD).values,
            RawSrKernelCovariance.precision(gray, tuning).values, 0f)
        assertArrayEquals(
            RawSrKernelCovariance.covariance(
                gray, tuning, RawSrKernelCovariance.KernelType.STEERABLE,
                RawSrKernelCovariance.SelectionLaw.HARD).values,
            RawSrKernelCovariance.covariance(gray, tuning).values, 0f)
    }

    @Test fun selectionAxesPinsHardGateBoundary() {
        val hard = RawSrKernelCovariance.SelectionLaw.HARD
        val linear = RawSrKernelCovariance.SelectionLaw.LINEAR
        // Strict gate: exactly 1.95 stays isotropic, one ulp above stretches.
        assertEquals(1f to 1f, RawSrKernelCovariance.selectionAxes(1.95f, 2f, 4f, hard))
        assertEquals(0.5f to 4f, RawSrKernelCovariance.selectionAxes(Math.nextUp(1.95f), 2f, 4f, hard))
        assertEquals(1f to 1f, RawSrKernelCovariance.selectionAxes(1f, 2f, 4f, hard))
        assertEquals(0.5f to 4f, RawSrKernelCovariance.selectionAxes(2f, 2f, 4f, hard))
        // NaN anisotropy (0/0 tensor) takes the isotropic branch, like the reference.
        assertEquals(1f to 1f, RawSrKernelCovariance.selectionAxes(Float.NaN, 2f, 4f, hard))
        // Linear law blends progressively: A=1.5 gives (0.75, 2.5).
        val (axis1, axis2) = RawSrKernelCovariance.selectionAxes(1.5f, 2f, 4f, linear)
        assertEquals(0.75f, axis1, 1e-6f)
        assertEquals(2.5f, axis2, 1e-6f)
    }

    @Test fun isoIgnoresTextureAndDenoiseWidth() {
        val width = 20
        val height = 14
        val values = FloatArray(width * height) { i -> 0.2f + 0.6f * ((i * 2654435761L ushr 8) % 1024) / 1024f }
        val texture = RawSrGrayImage(width, height, values)
        for (snr in listOf(6.0, 30.0)) {
            val tuning = RawSrTuning.forSnr(snr)
            val expectedCov = tuning.kDetail.toFloat()
            val expectedPrec = 1f / expectedCov
            for (gray in listOf(texture, flat(width, height))) {
                val precision = RawSrKernelCovariance.precision(
                    gray, tuning, RawSrKernelCovariance.KernelType.ISO)
                val covariance = RawSrKernelCovariance.covariance(
                    gray, tuning, RawSrKernelCovariance.KernelType.ISO)
                for (y in 0 until height) for (x in 0 until width) {
                    assertEquals(expectedPrec, precision.get(x, y, 0), 1e-6f)
                    assertEquals(0f, precision.get(x, y, 1), 0f)
                    assertEquals(0f, precision.get(x, y, 2), 0f)
                    assertEquals(expectedPrec, precision.get(x, y, 3), 1e-6f)
                    assertEquals(expectedCov, covariance.get(x, y, 0), 1e-6f)
                    assertEquals(0f, covariance.get(x, y, 1), 0f)
                    assertEquals(expectedCov, covariance.get(x, y, 3), 1e-6f)
                }
            }
        }
    }

    @Test fun hardLawMatchesLinearOnFlatLineAndSaturatedGate() {
        // A=1 (flat), A=2 (full-contrast line), and D=1 (saturated denoise gate)
        // are fixed points shared by both laws.
        val tuning = RawSrTuning.forSnr(30.0)
        val hard = RawSrKernelCovariance.SelectionLaw.HARD
        val linear = RawSrKernelCovariance.SelectionLaw.LINEAR
        val lineValues = FloatArray(32 * 24)
        for (y in 0 until 24) for (x in 0 until 32)
            lineValues[y * 32 + x] = if (x == 16) 1f else 0f
        for (gray in listOf(flat(32, 24), RawSrGrayImage(32, 24, lineValues))) {
            assertArrayEquals(
                RawSrKernelCovariance.precision(gray, tuning,
                    RawSrKernelCovariance.KernelType.STEERABLE, linear).values,
                RawSrKernelCovariance.precision(gray, tuning,
                    RawSrKernelCovariance.KernelType.STEERABLE, hard).values, 0f)
        }
    }

    @Test fun hardLawStaysIsotropicOnMidAnisotropyTexture() {
        // Asymmetric cross: a strong vertical bar (1.5) over a weaker horizontal
        // bar (0.8). The crossing holds two large perpendicular gradient
        // energies in one 2x2 window (l1 ~= 0.56, l2 ~= 0.16): the detail gate
        // opens (sqrt(l1) > Dth*Dtr = 0.71 at SNR 30) while A ~= 1.75 stays
        // below the hard gate, so the linear law stretches progressively and
        // the hard law must stay isotropic (anti-oil-painting). Guide values
        // above 1 are legitimate GAT-domain inputs, not clamped codes.
        val width = 32
        val height = 24
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width)
            values[y * width + x] = maxOf(
                if (x == 16) 1.5f else 0f,
                if (y == 12) 0.8f else 0f)
        val gray = RawSrGrayImage(width, height, values)
        val tuning = RawSrTuning.forSnr(30.0)
        val hard = RawSrKernelCovariance.precision(gray, tuning,
            RawSrKernelCovariance.KernelType.STEERABLE, RawSrKernelCovariance.SelectionLaw.HARD)
        val linear = RawSrKernelCovariance.precision(gray, tuning,
            RawSrKernelCovariance.KernelType.STEERABLE, RawSrKernelCovariance.SelectionLaw.LINEAR)
        var differ = 0
        var hardIsotropic = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (hard.get(x, y, 0) != linear.get(x, y, 0) || hard.get(x, y, 1) != linear.get(x, y, 1)) differ++
            // Isotropic hard pixels have ~zero off-diagonal and ~equal diagonal
            // (float outer products of the eigenbasis, so tolerance, not exact).
            val d0 = hard.get(x, y, 0)
            val off = hard.get(x, y, 1)
            val d3 = hard.get(x, y, 3)
            if (abs(off) <= 1e-5f * d0 && abs(d0 - d3) <= 1e-5f * d0) hardIsotropic++
            assertTrue(d0.isFinite())
            assertTrue(d0 > 0f)
        }
        assertTrue("hard and linear laws must differ at the cross", differ > 0)
        assertTrue("hard law must keep the crossing isotropic", hardIsotropic > 0)
        // The crossing itself: hard is isotropic, linear stretches.
        val h0 = hard.get(16, 12, 0)
        assertEquals(h0, hard.get(16, 12, 3), 1e-5f * h0)
        assertEquals(0f, hard.get(16, 12, 1), 1e-5f * h0)
        assertTrue(abs(linear.get(16, 12, 0) - linear.get(16, 12, 3)) > 1e-3f)
    }
}
