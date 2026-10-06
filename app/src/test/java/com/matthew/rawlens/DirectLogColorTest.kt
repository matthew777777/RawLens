// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Direct-Log static CCM math (DNG ForwardMatrix interpolation +
 * Bradford D50->D65 + XYZ->sRGB). The realistic vector below uses magnitudes
 * in the range of real sensor calibration; absolute color is verified by the
 * on-device bake log plus eyeball, while these tests pin the invariants that
 * a transposed/unsigned/scale-slipped matrix would break.
 */
class DirectLogColorTest {
    // Realistic calibration-shaped pair (D65-ish + StdA-ish), math row-major.
    private val fmD65 = doubleArrayOf(
        0.6731, 0.1950, 0.0960,
        0.2761, 0.8181, -0.0944,
        0.0387, -0.5759, 1.6069
    )
    private val fmStdA = doubleArrayOf(
        0.5745, 0.1840, 0.2058,
        0.1938, 0.7444, 0.0608,
        -0.0145, -0.5287, 1.3686
    )

    @Test
    fun tailPreservesWhite() {
        // K maps the D50 reference white to (1,1,1): the pipeline must not
        // tint neutrals by construction. Tolerance covers the 7-digit
        // rounded IEC constants; a transposed K errs by ~1.0.
        val w = DirectLogColor.K * Vec3(0.96422, 1.0, 0.82521)
        assertEquals(1.0, w.x, 1e-3)
        assertEquals(1.0, w.y, 1e-3)
        assertEquals(1.0, w.z, 1e-3)
    }

    @Test
    fun bakeIsFiniteAndDeterministic() {
        val a = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17)
        val b = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17)
        assertNotNull(a)
        assertArrayEquals(a!!, b!!, 0f)
        assertEquals(9, a.size)
        a.forEach { assert(it.isFinite()) { "non-finite CCM entry" } }
    }

    @Test
    fun anchorsRecoverSingleMatrixBakes() {
        // At either calibration anchor the blend must equal that anchor's
        // solo bake regardless of the other matrix.
        val atD65 = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 6504.0)!!
        val soloD65 = DirectLogColor.staticCcm(fmD65, fmD65, 21, 21, 6504.0)!!
        assertArrayEquals(soloD65, atD65, 1e-6f)
        val atStdA = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 2856.0)!!
        val soloStdA = DirectLogColor.staticCcm(fmStdA, fmStdA, 17, 17, 2856.0)!!
        assertArrayEquals(soloStdA, atStdA, 1e-6f)
    }

    @Test
    fun midBlendDiffersFromBothAnchors() {
        // 5500K sits between StdA and D65: the bake must genuinely blend.
        val mid = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 5500.0)!!
        val atD65 = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 6504.0)!!
        val atStdA = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 2856.0)!!
        val dD65 = mid.zip(atD65.toList()).sumOf { (a, b) -> ((a - b) * (a - b)).toDouble() }
        val dStdA = mid.zip(atStdA.toList()).sumOf { (a, b) -> ((a - b) * (a - b)).toDouble() }
        assert(dD65 > 1e-10) { "5500K bake collapsed onto D65 anchor" }
        assert(dStdA > 1e-10) { "5500K bake collapsed onto StdA anchor" }
        // And nearer the D65 anchor (5500K is closer to 6504K in 1/CCT).
        assert(dD65 < dStdA) { "blend leans the wrong way" }
    }

    @Test
    fun outOfSpanCctPinsToNearestAnchor() {
        val hot = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 20_000.0)!!
        val atD65 = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 6504.0)!!
        assertArrayEquals(atD65, hot, 0f)
        val cold = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 1_000.0)!!
        val atStdA = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 17, 2856.0)!!
        assertArrayEquals(atStdA, cold, 0f)
    }

    @Test
    fun unknownIlluminantOrBadInputReturnsNull() {
        assertNull(DirectLogColor.staticCcm(fmD65, fmStdA, 0, 17)) // 0 = unknown
        assertNull(DirectLogColor.staticCcm(fmD65, fmStdA, 21, 255))
        assertNull(DirectLogColor.staticCcm(doubleArrayOf(1.0), fmStdA, 21, 17))
        val nan = fmD65.copyOf().also { it[4] = Double.NaN }
        assertNull(DirectLogColor.staticCcm(nan, fmStdA, 21, 17))
        val singular = DoubleArray(9) // all-zero: no usable white
        assertNull(DirectLogColor.staticCcm(singular, fmStdA, 21, 17))
    }

    @Test
    fun forwardPairIngestIsRowMajor() {
        // getElement(column, row) sentinel: col * 10 + row. Row-major index
        // r * 3 + c must hold element (r, c) = c * 10 + r — the transpose
        // baked a wildly wrong static CCM into result-less head frames
        // (first-frame color pop vs the live-CCM remainder).
        val got = DirectLogColor.ingestRowMajor { col, row -> (col * 10 + row).toDouble() }
        assertArrayEquals(
            doubleArrayOf(
                0.0, 10.0, 20.0,
                1.0, 11.0, 21.0,
                2.0, 12.0, 22.0
            ),
            got, 0.0
        )
    }

    @Test
    fun equalAnchorsIgnoresCct() {
        val a = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 21, 3000.0)!!
        val b = DirectLogColor.staticCcm(fmD65, fmStdA, 21, 21, 9000.0)!!
        assertArrayEquals(a, b, 0f)
    }
}
