package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class RawSrFrameNoiseMeterTest {
    private val alphas = doubleArrayOf(2.0e-4, 3.0e-4, 2.5e-4, 3.2e-4)
    private val betas = doubleArrayOf(4.0e-6, 5.0e-6, 4.5e-6, 5.5e-6)

    /** Noisy CFA: per-phase (alpha, beta) noise over a latent [base] signal. */
    private fun noisyCfa(
        width: Int,
        height: Int,
        base: (x: Int, y: Int, phase: Int) -> Double,
        seed: Long = 7L,
        pattern: BayerPattern = BayerPattern.RGGB,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        alpha: DoubleArray = alphas,
        beta: DoubleArray = betas
    ): UnpackedRawCfa {
        val rng = Random(seed)
        val values = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val phase = ((y and 1) shl 1) or (x and 1)
            val m = base(x, y, phase)
            (m + sqrt(alpha[phase] * m + beta[phase]) * rng.nextGaussian()).toFloat()
        }
        return UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height), cropLeft, cropTop)
    }

    /** Horizontal ramp plus per-phase offsets: brightness spread with a mosaic the meter must ignore. */
    private fun ramp(x: Int, y: Int, phase: Int, w: Int = 256): Double {
        val offsets = doubleArrayOf(-0.06, 0.10, 0.02, -0.02)
        return 0.06 + 0.74 * x / (w - 1) + offsets[phase]
    }

    @Test fun recoversKnownNoiseParameters() {
        val fits = RawSrFrameNoiseMeter.measure(noisyCfa(256, 256, { x, y, phase -> ramp(x, y, phase) }))
        assertEquals(4, fits.size)
        for (fit in fits) {
            // Alpha (shot slope) dominates mid-tone variance: tightly recoverable.
            assertEquals(alphas[fit.phase], fit.alpha.toDouble(), 0.20 * alphas[fit.phase])
            // Beta (read intercept) is weakly identified under shot noise: loose band.
            assertEquals(betas[fit.phase], fit.beta.toDouble(), 2.5e-5)
            assertTrue("phase ${fit.phase} bins=${fit.bins}", fit.bins >= 6)
        }
    }

    @Test fun sigmaAtHalfMatchesTruth() {
        // Headline quantity: the ratio denominator/numerator at sigmaFor's operating point.
        val cfa = noisyCfa(256, 256, { x, y, phase -> ramp(x, y, phase) })
        val profile = DoubleArray(8) { if (it % 2 == 0) alphas[(it / 2) % 4] else betas[(it / 2) % 4] }
        val report = RawSrFrameNoiseMeter.compare(cfa, ImmutableDoubleValues(profile))!!
        val truth = sqrt(alphas.average() * 0.5 + betas.average())
        assertEquals(truth, report.measuredSigmaAtHalf.toDouble(), 0.15 * truth)
        assertEquals(1.0f, report.ratio, 0.15f)
        assertTrue(report.logLine("t").contains("ratio="))
    }

    @Test fun ratioDetectsConservativeProfile() {
        val cfa = noisyCfa(256, 256, { x, y, phase -> ramp(x, y, phase) })
        // 4x overstated variance ~= 2x overstated sigma: the oil-painting suspect.
        val conservative = DoubleArray(8) { (if (it % 2 == 0) alphas[(it / 2) % 4] else betas[(it / 2) % 4]) * 4.0 }
        val report = RawSrFrameNoiseMeter.compare(cfa, ImmutableDoubleValues(conservative))!!
        assertEquals(0.5f, report.ratio, 0.15f)
    }

    @Test fun ignoresTextureAndEdges() {
        val cfa = noisyCfa(256, 256, { x, y, _ ->
            0.4 + (if (((x / 32) + (y / 32)) % 2 == 0) 0.25 else -0.25)
        })
        val fits = RawSrFrameNoiseMeter.measure(cfa)
        assertEquals(4, fits.size)
        for (fit in fits) {
            assertEquals(alphas[fit.phase], fit.alpha.toDouble(), 0.25 * alphas[fit.phase])
        }
    }

    @Test fun excludesSaturatedHighlights() {
        val cfa = noisyCfa(256, 256, { x, y, phase ->
            if (y < 128) 1.2 else ramp(x, y, phase)
        })
        val fits = RawSrFrameNoiseMeter.measure(cfa)
        assertEquals(4, fits.size)
        for (fit in fits) {
            assertEquals(alphas[fit.phase], fit.alpha.toDouble(), 0.25 * alphas[fit.phase])
        }
    }

    @Test fun flatSceneReportsUnmeasurable() {
        val cfa = noisyCfa(128, 128, { _, _, _ -> 0.3 })
        assertTrue(RawSrFrameNoiseMeter.measure(cfa).isEmpty())
        val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 2.0e-4 else 4.0e-6 })
        assertNull(RawSrFrameNoiseMeter.compare(cfa, profile))
    }

    @Test fun rejectsInvalidProfiles() {
        val cfa = noisyCfa(256, 256, { x, y, phase -> ramp(x, y, phase) })
        assertNull(RawSrFrameNoiseMeter.compare(cfa, null))
        assertNull(RawSrFrameNoiseMeter.compare(cfa, ImmutableDoubleValues(DoubleArray(7) { 1e-4 })))
        assertNull(RawSrFrameNoiseMeter.compare(cfa, ImmutableDoubleValues(DoubleArray(8) { Double.NaN })))
        assertNull(RawSrFrameNoiseMeter.compare(cfa, ImmutableDoubleValues(DoubleArray(8) { -1e-4 })))
    }

    @Test fun mapsEightCoeffProfilesBySensorRaster() {
        val dummy = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4), 1, 1)
        val profile = DoubleArray(8) { (it + 1).toDouble() }
        // Phase p -> sensor (1+dx, 1+dy) -> raster ((sy&1)<<1)|(sx&1).
        val expectedRaster = intArrayOf(3, 2, 1, 0)
        for (phase in 0..3) {
            val (s, o) = RawSrFrameNoiseMeter.profilePairFor(profile, phase, dummy)
            val r = expectedRaster[phase]
            assertEquals(profile[r * 2], s, 0.0)
            assertEquals(profile[r * 2 + 1], o, 0.0)
        }
    }

    @Test fun mapsSixCoeffProfilesByCfaColor() {
        val even = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val odd = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4), 1, 0)
        val profile = doubleArrayOf(1.0, 0.1, 2.0, 0.2, 3.0, 0.3)
        // Even origin: phases are R,G,G,B. Odd-left origin shifts to G,R,B,G.
        val expectedEven = intArrayOf(0, 1, 1, 2)
        val expectedOdd = intArrayOf(1, 0, 2, 1)
        for (phase in 0..3) {
            val (se, oe) = RawSrFrameNoiseMeter.profilePairFor(profile, phase, even)
            assertEquals(profile[expectedEven[phase] * 2], se, 0.0)
            assertEquals(profile[expectedEven[phase] * 2 + 1], oe, 0.0)
            val (so, oo) = RawSrFrameNoiseMeter.profilePairFor(profile, phase, odd)
            assertEquals(profile[expectedOdd[phase] * 2], so, 0.0)
            assertEquals(profile[expectedOdd[phase] * 2 + 1], oo, 0.0)
        }
    }

    @Test fun fitClampsToNonnegativeLeastSquares() {
        // Negative unconstrained slope -> beta edge (0, mean).
        val (a0, b0) = RawSrFrameNoiseMeter.fitNonNegative(listOf(0.0 to 1.0, 1.0 to 0.1))
        assertEquals(0.0, a0, 0.0)
        assertEquals(0.55, b0, 1e-12)
        // Exact line through the origin stays put.
        val (a, b) = RawSrFrameNoiseMeter.fitNonNegative(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0))
        assertEquals(1.0, a, 1e-12)
        assertEquals(0.0, b, 1e-12)
        // Single point: exact fit on the alpha edge.
        val (a1, b1) = RawSrFrameNoiseMeter.fitNonNegative(listOf(2.0 to 8.0))
        assertEquals(4.0, a1, 1e-12)
        assertEquals(0.0, b1, 1e-12)
    }

    @Test fun nonFinitePixelsAreSkipped() {
        val cfa = noisyCfa(256, 256, { x, y, phase -> ramp(x, y, phase) })
        val rng = Random(3L)
        repeat(3000) { cfa.values[rng.nextInt(cfa.values.size)] = if (rng.nextBoolean()) Float.NaN else Float.POSITIVE_INFINITY }
        val fits = RawSrFrameNoiseMeter.measure(cfa)
        assertEquals(4, fits.size)
        for (fit in fits) {
            assertTrue(fit.alpha.isFinite() && fit.beta.isFinite())
            assertEquals(alphas[fit.phase], fit.alpha.toDouble(), 0.25 * alphas[fit.phase])
        }
    }
}
