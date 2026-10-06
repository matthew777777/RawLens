// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins the KernelNet s1/s2/rho -> precision law (PhotonCamera
 * `mergeCombineWeight` convention: s1 is the y-sigma, s2 the x-sigma, with
 * the exp(-0.5 z) bridge scale x2), the quad-mean luma input domain, and the
 * quarter-to-quad resampling. If the axis convention drifts, every
 * anisotropic kernel rotates 90 degrees and the merge zippers along edges.
 */
class RawSrKernelNetAnisoTest {
    private var savedKernelMpy = 1.0f
    private var savedMajorMpy = 1.0f
    private var savedMinSigma = 0f
    private var savedZipperGates = false

    // The verbatim-law tests below pin the conversion at multiplier 1.0
    // with the support floor off; the production defaults are
    // DEFAULT_KERNEL_SIGMA_MPY (0.5), DEFAULT_KERNEL_SIGMA_MAJOR_MPY
    // (1.0) and DEFAULT_MIN_SIGMA (0.45), covered by
    // kernelSigmaMultiplierDefaultsToHalf,
    // kernelSigmaMajorDefaultsToVerbatim and minSigmaDefaultsToSupport.
    // Pinning the major multiplier to 1.0 keeps the verbatim tests on the
    // symmetric law exactly (major == minor collapses the blend).
    @Before fun pinVerbatimMultiplier() {
        savedKernelMpy = RawSrKernelNetAniso.kernelSigmaMpy
        savedMajorMpy = RawSrKernelNetAniso.kernelSigmaMajorMpy
        savedMinSigma = RawSrKernelNetAniso.minSigma
        savedZipperGates = RawSrKernelNetAniso.zipperGates
        RawSrKernelNetAniso.kernelSigmaMpy = 1.0f
        RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
        RawSrKernelNetAniso.minSigma = 0f
        RawSrKernelNetAniso.zipperGates = false
    }

    @After fun restoreMultiplier() {
        RawSrKernelNetAniso.kernelSigmaMpy = savedKernelMpy
        RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajorMpy
        RawSrKernelNetAniso.minSigma = savedMinSigma
        RawSrKernelNetAniso.zipperGates = savedZipperGates
    }

    // The verbatim-law tests above run with the gates off (production
    // default); the zipper-gate tests below enable them per test. The
    // pinned 1.0/1.0/0 multipliers make the scaling law an exact no-op,
    // so the gate expectations match the consumer-guard spec bit-for-bit.
    private fun <T> withZipperGates(block: () -> T): T {
        val prev = RawSrKernelNetAniso.zipperGates
        RawSrKernelNetAniso.zipperGates = true
        try {
            return block()
        } finally {
            RawSrKernelNetAniso.zipperGates = prev
        }
    }

    @Test fun kernelSigmaMultiplierDefaultsToHalf() {
        // eszdman recommendation, confirmed by wall-crop A/B (+27%
        // Laplacian energy over verbatim with the floor in place).
        assertEquals(0.5f, RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MPY, 0f)
    }

    @Test fun minSigmaDefaultsToSupport() {
        // Support floor: sub-lattice kernels maze (wall crop worst-checker
        // 0.122 unclamped vs 0.075 at 0.45, reference 8-frame burst).
        assertEquals(0.45f, RawSrKernelNetAniso.DEFAULT_MIN_SIGMA, 0f)
    }

    @Test fun isotropicTripleGivesScaledIdentity() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1f, 1f, 0f, out, 0))
        // Verbatim upstream law: P = 2/s^2 I.
        assertEquals(2.0f, out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(2.0f, out[3], 1e-5f)
    }

    @Test fun quadScaleFollowsInverseSquareLaw() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0f, out, 0))
        // Below the cap, above the floor: exact 2/s^2 law.
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[3], 1e-5f)
    }

    @Test fun saturatedTripleConvertsVerbatim() {
        // (2,2,0): upstream consumes the saturated wide end as-is: P = 2/4 I.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(2f, 2f, 0f, out, 0))
        assertEquals(0.5f, out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-5f)
    }

    @Test fun anisotropyRatioPreservedVerbatim() {
        // (1.5,0.6,0): direct law, the 2.5 ratio survives exactly.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1.5f, 0.6f, 0f, out, 0))
        assertEquals((2.0 / (0.6 * 0.6)).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (1.5 * 1.5)).toFloat(), out[3], 1e-5f)
        // σy/σx = 1.5/0.6 = 2.5 before and after (s1 is y).
        val sx = 1.0 / kotlin.math.sqrt(out[0].toDouble())
        val sy = 1.0 / kotlin.math.sqrt(out[3].toDouble())
        assertEquals(2.5, sy / sx, 1e-6)
    }

    @Test fun midRangeTripleConvertsVerbatim() {
        // No guards in the direct port: exact 2/s^2 law at any range.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.71f, 0.639f, 0f, out, 0))
        assertEquals((2.0 / (0.639 * 0.639)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.71 * 0.71)).toFloat(), out[3], 1e-6f)
    }

    @Test fun correlationTiltsOffDiagonal() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0.5f, out, 0))
        // det = 0.75: p00 = 2/(c^2*det), p01 = -2*rho/(c^2*det).
        val det = 1.0 - 0.5 * 0.5
        assertEquals((2.0 / (0.7 * 0.7 * det)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 * -0.5 / (0.7 * 0.7 * det)).toFloat(), out[1], 1e-5f)
        assertEquals(out[1], out[2], 0f)
        assertEquals((2.0 / (0.7 * 0.7 * det)).toFloat(), out[3], 1e-5f)
    }

    @Test fun smallS1NarrowsYNotX() {
        // Zipper regression: s1 is the y-sigma (upstream a = 1/(s1^2*det)
        // weights dy^2). A small s1 must raise p11, leaving p00 wide; the
        // transposed law narrowed x instead and starved the along-edge taps.
        // Direct law, exact.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.6f, 0.7f, 0f, out, 0))
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals((2.0 / (0.6 * 0.6)).toFloat(), out[3], 1e-6f)
        assertTrue("narrow-y kernel must satisfy p11 > p00", out[3] > out[0])
    }

    @Test fun anisotropicCorrelatedTripleKeepsAxes() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.5f, 1f, 0.5f, out, 0))
        // Direct law, det = 0.75:
        // p00 = 2/(c2^2*det), p11 = 2/(c1^2*det), p01 = -2*rho/(c1*c2*det).
        val det = 1.0 - 0.5 * 0.5
        assertEquals((2.0 / (1.0 * 1.0 * det)).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 * -0.5 / (0.5 * 1.0 * det)).toFloat(), out[1], 1e-5f)
        assertEquals(out[1], out[2], 0f)
        assertEquals((2.0 / (0.5 * 0.5 * det)).toFloat(), out[3], 1e-4f)
    }

    @Test fun degenerateTriplesRejected() {
        val out = FloatArray(4) { Float.NaN }
        assertFalse(RawSrKernelNetAniso.precisionOf(0f, 1f, 0f, out, 0))
        assertFalse(RawSrKernelNetAniso.precisionOf(1f, -1f, 0f, out, 0))
        assertFalse(RawSrKernelNetAniso.precisionOf(Float.NaN, 1f, 0f, out, 0))
        assertFalse(RawSrKernelNetAniso.precisionOf(1f, 1f, Float.POSITIVE_INFINITY, out, 0))
    }

    @Test fun extremeCorrelationStaysFinite() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1f, 1f, 1f, out, 0))
        assertTrue(out.all { it.isFinite() })
        // rho clamps to 0.999, det floors: p00 = 2/(c^2*det), verbatim.
        val r = 0.999f
        val det = 1.0 - r * r
        assertEquals((2.0 / (1.0 * 1.0 * det)).toFloat(), out[0], out[0] * 1e-4f)
    }

    @Test fun sharpTextureTripleConvertsVerbatim() {
        // Isotropic-sharp triple (texture-like): direct law, no widening.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.23f, 0.25f, 0f, out, 0))
        assertEquals((2.0 / (0.25 * 0.25)).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (0.23 * 0.23)).toFloat(), out[3], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(out[1], out[2], 0f)
    }

    @Test fun midTextureTripleConvertsVerbatim() {
        // Direct unfloored law at any range.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0f, out, 0))
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[3], 1e-5f)
    }

    @Test fun edgeTripleKeepsAnisotropyVerbatim() {
        // Strong-edge triple converts with its narrow-across/wide-along
        // ratio intact — exactly what upstream consumes.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1f, 0f, out, 0))
        assertEquals((2.0 / (1.0 * 1.0)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.2 * 0.2)).toFloat(), out[3], 1e-4f)
        assertTrue("edge must stay narrow across (y)", out[3] > out[0])
    }

    @Test fun precisionScaleIsTwo() {
        assertEquals(2.0, RawSrKernelNetAniso.QUAD_PRECISION_SCALE, 0.0)
    }

    @Test fun classifyTripleReportsUsableOrRejected() {
        val R = RawSrKernelNetAniso.KernelTripleFlags.REJECTED
        // Every finite, positive triple converts verbatim: flags 0.
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(2f, 2f, 0f))
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(0.23f, 0.25f, 0f))
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(1.5f, 0.6f, 0f))
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(0.7f, 0.7f, 0f))
        // Unusable triples reject.
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(0f, 1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(1f, -1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(Float.NaN, 1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(1f, 1f, Float.POSITIVE_INFINITY))
    }

    @Test fun kernelStatsCensusMergesAcrossShards() {
        val a = RawSrKernelNetAniso.KernelStats()
        a.add(2f, 2f, 0)
        a.add(0.23f, 0.25f, 0)
        a.add(0f, 1f, RawSrKernelNetAniso.KernelTripleFlags.REJECTED)
        val b = RawSrKernelNetAniso.KernelStats()
        b.add(0.7f, 0.7f, 0)
        a.merge(b)
        assertEquals(4, a.total)
        assertEquals(1, a.rejected)
        assertEquals(0.25f, a.peakMin, 0f)
        assertEquals(2f, a.peakMax, 0f)
        assertEquals((2.0 + 0.25 + 0.7) / 3, a.peakSum / (a.total - a.rejected), 1e-6)
        val line = a.logLine("test")
        assertTrue(line, line.contains("quads=4") && line.contains("rejected=1"))
    }
    @Test fun directLawKeepsEdgeRatioOnSharpEdge() {
        // (0.2,1.5,0): verbatim conversion; ratio preserved end to end.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, out, 0))
        assertEquals((2.0 / (1.5 * 1.5)).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (0.2 * 0.2)).toFloat(), out[3], 1e-3f)
        assertTrue("edge must stay narrow across (y)", out[3] > out[0])
    }

    @Test fun sigmaAtMidBrightness() {
        // Upstream kernelSigma: sqrt(S*0.5+O) over the averaged model.
        val profile = ImmutableDoubleValues(
            doubleArrayOf(4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6)
        )
        assertEquals(kotlin.math.sqrt(4e-4 * 0.5 + 1e-6).toFloat(),
            RawSrKernelNetAniso.sigmaFor(profile), 1e-6f)
    }

    @Test fun sigmaFallsBackWithoutProfile() {
        // Fallback model constants (2.5e-4 / 2.5e-6), still at 0.5.
        assertEquals(kotlin.math.sqrt(2.5e-4 * 0.5 + 2.5e-6).toFloat(),
            RawSrKernelNetAniso.sigmaFor(null), 1e-6f)
    }

    @Test fun autoSigmaCapsUpwardCorrection() {
        // Measured/Profile 3.9 (reference burst) caps at 2x: the meter reads
        // high on texture, and 2x was the visual sweet spot there.
        val profile = ImmutableDoubleValues(
            doubleArrayOf(4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6)
        )
        val base = kotlin.math.sqrt(4e-4 * 0.5 + 1e-6).toFloat()
        assertEquals(base * 2f, RawSrKernelNetAniso.sigmaFor(profile, 3.93f), 1e-6f)
        assertEquals(base * 1.5f, RawSrKernelNetAniso.sigmaFor(profile, 1.5f), 1e-6f)
    }

    @Test fun autoSigmaNeverCorrectsDownward() {
        // Ratio < 1 (profile overstates noise) leaves sigma alone:
        // underestimating noise invents maze, the worse failure mode.
        val profile = ImmutableDoubleValues(
            doubleArrayOf(4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6)
        )
        val base = kotlin.math.sqrt(4e-4 * 0.5 + 1e-6).toFloat()
        assertEquals(base, RawSrKernelNetAniso.sigmaFor(profile, 0.5f), 1e-6f)
        assertEquals(base, RawSrKernelNetAniso.sigmaFor(profile, Float.NaN), 1e-6f)
        assertEquals(base, RawSrKernelNetAniso.sigmaFor(profile, -2f), 1e-6f)
        assertEquals(base, RawSrKernelNetAniso.sigmaFor(profile, null), 1e-6f)
    }

    @Test fun autoSigmaStacksWithManualMultiplier() {
        val profile = ImmutableDoubleValues(
            doubleArrayOf(4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6, 4e-4, 1e-6)
        )
        val base = kotlin.math.sqrt(4e-4 * 0.5 + 1e-6).toFloat()
        val saved = RawSrKernelNetAniso.sigmaMpy
        try {
            RawSrKernelNetAniso.sigmaMpy = 2f
            assertEquals(base * 4f, RawSrKernelNetAniso.sigmaFor(profile, 3.93f), 1e-5f)
        } finally {
            RawSrKernelNetAniso.sigmaMpy = saved
        }
    }

    @Test fun lumaPlaneIsQuadMeanSqrt() {
        // One quad: mean of the four sites, clamped, then sqrt.
        val cfa = UnpackedRawCfa(
            2, 2, BayerPattern.RGGB, floatArrayOf(0.16f, 0.36f, 0.64f, 1.0f),
            RawCrop(0, 0, 2, 2), 0, 0
        )
        val luma = RawSrKernelNetAniso.lumaPlane(cfa)
        assertEquals(1, luma.capacity())
        assertEquals(kotlin.math.sqrt(0.54).toFloat(), luma.get(0), 1e-6f)
    }

    @Test fun lumaPlaneClampsAndDropsNonFinite() {
        // Clamp to [0,1], non-finite treated as 0: mean = (0+0.25+1+0)/4.
        val cfa = UnpackedRawCfa(
            2, 2, BayerPattern.RGGB, floatArrayOf(-0.5f, 0.25f, 4f, Float.NaN),
            RawCrop(0, 0, 2, 2), 0, 0
        )
        val luma = RawSrKernelNetAniso.lumaPlane(cfa)
        assertEquals(kotlin.math.sqrt(0.3125).toFloat(), luma.get(0), 1e-6f)
    }

    @Test fun lumaPlaneTilesQuadsInRowMajor() {
        // 4x4 CFA -> 2x2 luma; each output is its own 2x2 mean, sqrt'd.
        val cfa = UnpackedRawCfa(
            4, 4, BayerPattern.RGGB, floatArrayOf(
                0.01f, 0.01f, 0.04f, 0.04f,
                0.01f, 0.01f, 0.04f, 0.04f,
                0.09f, 0.09f, 0.16f, 0.16f,
                0.09f, 0.09f, 0.16f, 0.16f
            ),
            RawCrop(0, 0, 4, 4), 0, 0
        )
        val luma = RawSrKernelNetAniso.lumaPlane(cfa)
        assertEquals(4, luma.capacity())
        assertEquals(0.1f, luma.get(0), 1e-6f)
        assertEquals(0.2f, luma.get(1), 1e-6f)
        assertEquals(0.3f, luma.get(2), 1e-6f)
        assertEquals(0.4f, luma.get(3), 1e-6f)
    }

    @Test fun lumaPlaneReusesScratchFromBase() {
        val cfa = UnpackedRawCfa(
            2, 2, BayerPattern.RGGB, floatArrayOf(0f, 0.25f, 1f, 0.5f),
            RawCrop(0, 0, 2, 2), 0, 0
        )
        val scratch = java.nio.ByteBuffer.allocateDirect(1 * 4 + 64)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until scratch.capacity()) scratch.put(i, 7f)
        val luma = RawSrKernelNetAniso.lumaPlane(cfa, scratch)
        // Same buffer, filled from index 0, rewound for JNI.
        assertTrue(luma === scratch)
        assertEquals(0, luma.position())
        assertEquals(kotlin.math.sqrt(0.4375).toFloat(), luma.get(0), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lumaPlaneRejectsOddCrop() {
        val cfa = UnpackedRawCfa(
            3, 2, BayerPattern.RGGB, FloatArray(6) { 0.1f },
            RawCrop(0, 0, 3, 2), 0, 0
        )
        RawSrKernelNetAniso.lumaPlane(cfa)
    }

    @Test fun samplePlaneBilinearCentersAndClamps() {
        // 2x2 plane [0,1,2,3] resampled onto a 4x4 quad grid.
        val planes = java.nio.ByteBuffer.allocateDirect(3 * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        planes.put(floatArrayOf(0f, 1f, 2f, 3f, 10f, 11f, 12f, 13f, 20f, 21f, 22f, 23f))
        fun at(plane: Int, qx: Int, qy: Int) =
            RawSrKernelNetAniso.samplePlane(planes, plane, 2, 2, qx, qy, 4, 4)
        // Corners clamp to the edge texels.
        assertEquals(0f, at(0, 0, 0), 1e-6f)
        assertEquals(3f, at(0, 3, 3), 1e-6f)
        // (1,1) lands exactly on texel (0,0); (2,2) on the four-texel mean.
        assertEquals(0f, at(0, 1, 1), 1e-6f)
        assertEquals(1.5f, at(0, 2, 2), 1e-6f)
        // Edge midpoint blends the two edge texels.
        assertEquals(0.5f, at(0, 2, 0), 1e-6f)
        // Plane selection offsets into the channel-major planes.
        assertEquals(11.5f, at(1, 2, 2), 1e-6f)
        assertEquals(23f, at(2, 3, 3), 1e-6f)
    }

    @Test fun samplePlaneRejectsNonFiniteTaps() {
        val planes = java.nio.ByteBuffer.allocateDirect(3 * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        planes.put(floatArrayOf(0f, 1f, 2f, Float.NaN, 10f, 11f, 12f, 13f, 20f, 21f, 22f, 23f))
        // Strict like the merge's precision interpolation: any non-finite
        // tap in the 2x2 window poisons the sample (caller falls back).
        assertTrue(RawSrKernelNetAniso.samplePlane(planes, 0, 2, 2, 2, 2, 4, 4).isNaN())
        // Clean planes are unaffected.
        assertEquals(11.5f, RawSrKernelNetAniso.samplePlane(planes, 1, 2, 2, 2, 2, 4, 4), 1e-6f)
    }

    @Test fun kernelNetIsEnabledByDefault() {
        // KernelNet + auto-sigma is the default merge path; --no-kernelnet
        // opts out. (Every test that flips the flag restores it, so the
        // default is observable here.)
        assertTrue(RawSrKernelNetAniso.enabled)
    }

    @Test fun disabledReturnsAnalyticSameInstance() {
        val cfa = UnpackedRawCfa(
            2, 2, BayerPattern.RGGB, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
            RawCrop(0, 0, 2, 2), 0, 0
        )
        val analytic = RawSrKernelCovariance.MatrixField(1, 1, floatArrayOf(1f, 0f, 0f, 1f))
        RawSrKernelNetAniso.enabled = false
        try {
            // No model touch, no allocation: the exact fallback contract the
            // OOM path depends on.
            assertTrue(RawSrKernelNetAniso.precisionFor(cfa, analytic, 0.01f) === analytic)
            assertTrue(RawSrKernelNetAniso.precisionForKernelOnly(cfa, 0.01f) == null)
        } finally {
            RawSrKernelNetAniso.enabled = true
        }
    }

    @Test fun kernelNetFieldConvertsEdgeVerbatim() {
        // Strong-edge triple (0.2,1.5,0) converts with its narrow-across /
        // wide-along ratio intact — exactly what upstream consumes. No
        // narrow-axis clamp in the direct port.
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, raw, 0))
        // Constant 1x1 model plane resampled onto a 2x2 quad grid: every
        // quad samples the edge triple exactly (clamped-edge resampling).
        val planes = floatArrayOf(0.2f, 1.5f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        assertEquals(2, field.width)
        assertEquals(2, field.height)
        for (i in 0 until 4) {
            val o = i * 4
            // The produced field holds covariances (the merge inverts per
            // pixel).
            val c00 = field.values[o].toDouble()
            val c01 = field.values[o + 1].toDouble()
            val c11 = field.values[o + 3].toDouble()
            // Axis-aligned triple stays axis-aligned.
            assertEquals(0.0, c01, 1e-6)
            // Wide (x) axis is the exact reciprocal of the precision lane.
            assertEquals(1.0 / raw[0].toDouble(), c00, 1e-4)
            // Narrow (y) axis converts verbatim too: 0.2^2/2 = 0.02.
            assertEquals(0.02, c11, 1e-4)
        }
    }

    @Test fun kernelNetFieldConvertsSharpIsotropicVerbatim() {
        // Isotropic-sharp triple (0.23,0.25,0): direct law, no widening.
        // Covariances read s^2/2 per axis.
        val planes = floatArrayOf(0.23f, 0.25f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        for (i in 0 until 4) {
            val o = i * 4
            assertEquals(0.25 * 0.25 / 2.0, field.values[o].toDouble(), 1e-6)
            assertEquals(0.23 * 0.23 / 2.0, field.values[o + 3].toDouble(), 1e-6)
            assertEquals(0.0, field.values[o + 1].toDouble(), 1e-6)
        }
    }

    @Test fun kernelNetFieldConvertsWideTripleVerbatim() {
        // Wide triple (1,1,0): covariance reads exactly s^2/2 = 0.5.
        val planes = floatArrayOf(1f, 1f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        for (i in 0 until 4) {
            val o = i * 4
            assertEquals(0.5f, field.values[o], 1e-5f)
            assertEquals(0f, field.values[o + 1], 1e-6f)
            assertEquals(0.5f, field.values[o + 3], 1e-5f)
        }
    }

    @Test fun kernelSigmaMultiplierSharpensPrecisionQuadratically() {
        // eszdman recommendation for the SR-merge context: predicted sigmas
        // ×0.5 (precision ×4). Applied post-model, after the usability gate.
        val saved = RawSrKernelNetAniso.kernelSigmaMpy
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(1f, 1f, 0f, out, 0))
            assertEquals(8.0f, out[0], 1e-5f)
            assertEquals(8.0f, out[3], 1e-5f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = saved
        }
    }

    @Test fun kernelSigmaMultiplierGateSeesRawTriple() {
        // Usability is judged on the raw model triple: a 0.5 scaling must
        // not reject a usable kernel via the SIGMA_MIN floor (the sharpened
        // kernel converts, it does not fall back to analytic).
        val saved = RawSrKernelNetAniso.kernelSigmaMpy
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.0015f, 0.0015f, 0f, out, 0))
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = saved
        }
    }

    @Test fun minSigmaClampsStarvedAxes() {
        // (0.55,0.45,0) is the maze-hotspot triple (near-isotropic, narrow):
        // ×0.5 alone lands at (0.275,0.225) — below the sample lattice —
        // so the 0.45 floor clamps both axes: P = 2/0.45² I.
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.minSigma = 0.45f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.55f, 0.45f, 0f, out, 0))
            assertEquals((2.0 / (0.45 * 0.45)).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (0.45 * 0.45)).toFloat(), out[3], 1e-4f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun minSigmaKeepsAnisotropyAboveFloor() {
        // Edge triple under the production asymmetric law: the across-edge
        // axis (0.35→0.175) clamps to the floor while the along-edge axis
        // (1.2, native aniso 3.43) keeps its verbatim width — orientation
        // survives with full along-edge support, so the green quotient no
        // longer latches onto 1-2 Bayer-phase taps (zipper fix).
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            RawSrKernelNetAniso.minSigma = 0.45f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.35f, 1.2f, 0f, out, 0))
            val cLong = 1.2f.toDouble()
            val cFloor = 0.45f.toDouble()
            assertEquals((2.0 / (cLong * cLong)).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (cFloor * cFloor)).toFloat(), out[3], 1e-4f)
            assertTrue("along-edge x-precision must stay narrower than across", out[0] < out[3])
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun minSigmaZeroDisablesFloor() {
        // 0 restores the unclamped ×mpy law exactly (the maze-repro escape
        // hatch for A/B runs). Pinned symmetric (major == minor) so the
        // expectation tests the floor escape, not the asymmetric law.
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 0.5f
            RawSrKernelNetAniso.minSigma = 0f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.55f, 0.45f, 0f, out, 0))
            assertEquals((2.0 / (0.225 * 0.225)).toFloat(), out[0], 1e-3f)
            assertEquals((2.0 / (0.275 * 0.275)).toFloat(), out[3], 1e-3f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun kernelSigmaMajorDefaultsToVerbatim() {
        // The longer model axis keeps its verbatim width by default: the
        // symmetric x0.5 collapsed edge kernels to near-isotropic floor
        // blobs (slat crop median aniso 1.11 vs analytic 2.49) and zippered.
        assertEquals(1.0f, RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MAJOR_MPY, 0f)
    }

    @Test fun majorAxisKeepsVerbatimWidthYLonger() {
        // Mirror of minSigmaKeepsAnisotropyAboveFloor with the longer axis
        // on s1 (y): the s1>=s2 branch must take the major multiplier.
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            RawSrKernelNetAniso.minSigma = 0.45f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(1.2f, 0.35f, 0f, out, 0))
            val cLong = 1.2f.toDouble()
            val cFloor = 0.45f.toDouble()
            assertEquals((2.0 / (cFloor * cFloor)).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (cLong * cLong)).toFloat(), out[3], 1e-4f)
            assertTrue("along-edge y-precision must stay narrower than across", out[3] < out[0])
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun symmetricLawRestoredBitExactlyWhenMajorEqualsMinor() {
        // major == minor must collapse the blend exactly, even for a triple
        // deep in the asymmetric window (aniso 2.0) with correlation: the
        // symmetric law reproduces bit-exactly (m + (m-m)*t == m).
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 0.5f
            RawSrKernelNetAniso.minSigma = 0f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(1.5f, 0.75f, 0.3f, out, 0))
            // Mirror the implementation's op sequence exactly (r*r stays
            // Float there, so it stays Float here).
            val c1 = 1.5f.toDouble() * 0.5
            val c2 = 0.75f.toDouble() * 0.5
            val r = 0.3f
            val det = maxOf(1.0 - r * r, 1e-4)
            assertEquals((2.0 / (c2 * c2 * det)).toFloat(), out[0], 0f)
            assertEquals((2.0 * -r / (c1 * c2 * det)).toFloat(), out[1], 0f)
            assertEquals(out[1], out[2], 0f)
            assertEquals((2.0 / (c1 * c1 * det)).toFloat(), out[3], 0f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun belowGateMajorMultiplierHasNoEffect() {
        // Below MAJOR_ANISO_LO the blend factor is exact 0, so the major
        // multiplier is dead input: two runs differing only in majorMpy
        // must agree bitwise (flats never invent an orientation).
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.minSigma = 0f
            val a = FloatArray(4)
            val b = FloatArray(4)
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            assertTrue(RawSrKernelNetAniso.precisionOf(1.1f, 1.0f, 0.2f, a, 0))
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 0.5f
            assertTrue(RawSrKernelNetAniso.precisionOf(1.1f, 1.0f, 0.2f, b, 0))
            for (c in 0..3) assertEquals(b[c], a[c], 0f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun nearIsotropicFlatStaysSymmetric() {
        // Flat prediction (1.9,1.85): aniso 1.027 sits below the gate, so
        // both axes take the sharpening multiplier (current flats behavior,
        // no fake orientation from sigma noise).
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            RawSrKernelNetAniso.minSigma = 0.45f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(1.9f, 1.85f, 0f, out, 0))
            val c1 = 1.9f.toDouble() * 0.5
            val c2 = 1.85f.toDouble() * 0.5
            assertEquals((2.0 / (c2 * c2)).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (c1 * c1)).toFloat(), out[3], 1e-4f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun gateRampsAcrossAnisoWindow() {
        // Mid-window triple (1.35,1.0,0): the longer axis scales by ~0.75,
        // halfway between the sharpening and verbatim multipliers.
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            RawSrKernelNetAniso.minSigma = 0f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(1.35f, 1.0f, 0f, out, 0))
            val cLong = 1.35f.toDouble() * 0.75
            val cShort = 1.0 * 0.5
            assertEquals((2.0 / (cShort * cShort)).toFloat(), out[0], 1e-5f)
            assertEquals((2.0 / (cLong * cLong)).toFloat(), out[3], 1e-2f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun gateSeesRawTripleNotFloored() {
        // (0.8,0.5,0): raw aniso 1.6 clears the gate, so the longer axis
        // converts at 0.8 even though symmetric x0.5 would floor both axes
        // (a floored-value gate would see aniso 1.0 and clamp to 0.45).
        val savedMpy = RawSrKernelNetAniso.kernelSigmaMpy
        val savedMajor = RawSrKernelNetAniso.kernelSigmaMajorMpy
        val savedFloor = RawSrKernelNetAniso.minSigma
        try {
            RawSrKernelNetAniso.kernelSigmaMpy = 0.5f
            RawSrKernelNetAniso.kernelSigmaMajorMpy = 1.0f
            RawSrKernelNetAniso.minSigma = 0.45f
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.8f, 0.5f, 0f, out, 0))
            val cLong = 0.8f.toDouble()
            val cFloor = 0.45f.toDouble()
            assertEquals((2.0 / (cFloor * cFloor)).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (cLong * cLong)).toFloat(), out[3], 1e-3f)
        } finally {
            RawSrKernelNetAniso.kernelSigmaMpy = savedMpy
            RawSrKernelNetAniso.kernelSigmaMajorMpy = savedMajor
            RawSrKernelNetAniso.minSigma = savedFloor
        }
    }

    @Test fun kernelOnlyNullWithoutModel() {
        // Unit JVM has no ncnn library: isReady() is false, so the GPU path
        // must resolve to null (analytic shader) without throwing.
        val cfa = UnpackedRawCfa(
            2, 2, BayerPattern.RGGB, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
            RawCrop(0, 0, 2, 2), 0, 0
        )
        assertTrue(RawSrKernelNetAniso.precisionForKernelOnly(cfa, 0.01f) == null)
    }

    @Test fun widthCapTrimsSaturatedIsotropic() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(2f, 2f, 0f, out, 0))
        // Capped to 0.71 joint: P = 2/0.71^2 I (area 0.252 stays above
        // the floor, so only the cap binds).
        val p = (2.0 / (0.71 * 0.71)).toFloat()
        assertEquals(p, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(p, out[3], 1e-4f)
    }

    @Test fun widthCapPreservesAnisotropyRatio() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1.5f, 0.6f, 0f, out, 0))
        // Joint scale kc = 0.71/1.5: (0.71, 0.284); ratio 2.5 preserved.
        // Capped area 0.71*0.284/2 = 0.101 < 0.2, so the floor scales P by
        // k = 0.101/0.2 after the cap (composition, not either/or).
        val kc = 0.71 / 1.5
        val c1 = 1.5f * kc
        val c2 = 0.6f * kc
        val k = c1 * c2 * sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * 1.0) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (c1 * c1 * 1.0) * k).toFloat(), out[3], 1e-4f)
    }

    @Test fun widthCapStrictBoundaryUntouched() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.71f, 0.71f, 0f, out, 0))
        // Exactly 0.71: strict > keeps it verbatim (cap binds above only).
        // Area 0.71^2/2 = 0.252 clears the floor too.
        val p = (2.0 / (0.71 * 0.71)).toFloat()
        assertEquals(p, out[0], 1e-5f)
        assertEquals(p, out[3], 1e-5f)
    }

    @Test fun kernelAreaFloorWidensTextureKernels() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.23f, 0.25f, 0f, out, 0))
        // Area 0.23*0.25/2 = 0.0288 < 0.2: P scales by k = 0.144 (uniform
        // widening, ratio preserved).
        val k = 0.23 * 0.25 * sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (0.25 * 0.25) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (0.23 * 0.23) * k).toFloat(), out[3], 1e-4f)
    }

    @Test fun kernelAreaFloorLeavesWideKernelsAlone() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0f, out, 0))
        // Area 0.245 clears the floor: exact 2/s^2 law, no cap either.
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[3], 1e-5f)
    }

    @Test fun kernelAreaFloorKeepsEdgeAnisotropy() = withZipperGates {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, out, 0))
        // (0.2,1.5): capped to (0.0947,0.71), then floored (area 0.0336):
        // the 7.5x ratio survives both guards (floor scales P uniformly).
        val kc = 0.71 / 1.5
        val c1 = 0.2f * kc
        val c2 = 1.5f * kc
        val k = c1 * c2 * sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * 1.0) * k).toFloat(), out[0], 1e-3f)
        assertEquals((2.0 / (c1 * c1 * 1.0) * k).toFloat(), out[3], 1e-3f)
        assertTrue("edge must stay narrow across (y)", out[3] > out[0])
    }

    @Test fun areaFloorOverrideNarrowsTextureKernels() = withZipperGates {
        // Lowering the floor to 0.05 narrows the same texture triple 4x
        // (P scales by area/0.05 instead of area/0.2): the override is a
        // live A/B knob, not a dead constant.
        val prev = RawSrKernelNetAniso.kernelAreaFloor
        RawSrKernelNetAniso.kernelAreaFloor = 0.05
        try {
            val out = FloatArray(4)
            assertTrue(RawSrKernelNetAniso.precisionOf(0.23f, 0.25f, 0f, out, 0))
            val k = 0.23 * 0.25 * sqrt(1.0) / 2.0 / 0.05
            assertEquals((2.0 / (0.25 * 0.25) * k).toFloat(), out[0], 1e-4f)
            assertEquals((2.0 / (0.23 * 0.23) * k).toFloat(), out[3], 1e-4f)
        } finally {
            RawSrKernelNetAniso.kernelAreaFloor = prev
        }
    }

    @Test fun classifyTripleReportsRegimes() = withZipperGates {
        val c = RawSrKernelNetAniso.KernelTripleFlags.CAPPED
        val f = RawSrKernelNetAniso.KernelTripleFlags.FLOORED
        assertEquals(c, RawSrKernelNetAniso.classifyTriple(2f, 2f, 0f))
        assertEquals(f, RawSrKernelNetAniso.classifyTriple(0.23f, 0.25f, 0f))
        assertEquals(c or f, RawSrKernelNetAniso.classifyTriple(1.5f, 0.6f, 0f))
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(0.7f, 0.7f, 0f))
    }

    @Test fun capThenFloorComposeOnCappedEdge() = withZipperGates {
        // (1.5,0.6,rho 0.5): capped joint, then the floor sees the capped
        // area with the rho determinant (sqrt(0.75)); both guards compose
        // through the determinant instead of fighting over it.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1.5f, 0.6f, 0.5f, out, 0))
        val kc = 0.71 / 1.5
        val c1 = 1.5f * kc
        val c2 = 0.6f * kc
        val det = 1.0 - 0.5 * 0.5
        val k = c1 * c2 * sqrt(det) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * det) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 * -0.5 / (c1 * c2 * det) * k).toFloat(), out[1], 1e-4f)
        assertEquals((2.0 * -0.5 / (c1 * c2 * det) * k).toFloat(), out[2], 1e-4f)
        assertEquals((2.0 / (c1 * c1 * det) * k).toFloat(), out[3], 1e-4f)
    }

    @Test fun capConstantMatchesDocValue() = withZipperGates {
        // The 0.71 joint cap is the widest admissible smoothing (~1.4 raw
        // px); the constant and the doc stay pinned together.
        assertEquals(0.71, RawSrKernelNetAniso.KERNEL_SIGMA_MAX, 0.0)
    }

    @Test fun kernelNetFieldLeavesWideTripleAlone() = withZipperGates {
        // (2,2,rho 0): capped to 0.71 (area 0.252 clears the floor), minor
        // axis 0.71 clears the narrow clamp: the covariance texel is the
        // plain inverse of the capped precision.
        val planes = floatArrayOf(2f, 2f, 0f)
        val values = FloatArray(4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 1, 1, values)
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(2f, 2f, 0f, raw, 0))
        assertEquals((2f / (0.71f * 0.71f)).toDouble(), raw[0].toDouble(), 1e-4)
        val c = field.values
        assertEquals(1.0 / raw[0], c[0].toDouble(), 1e-5)
        assertEquals(0.0, c[1].toDouble(), 1e-6)
        assertEquals(0.0, c[2].toDouble(), 1e-6)
        assertEquals(1.0 / raw[3], c[3].toDouble(), 1e-5)
    }

    @Test fun kernelNetFieldFloorsSharpIsotropicTriple() = withZipperGates {
        // (0.23,0.25,rho 0): floored (area 0.0288), minor axis ~0.61 clears
        // the narrow clamp: the covariance texel is the plain inverse of
        // the floored precision.
        val planes = floatArrayOf(0.23f, 0.25f, 0f)
        val values = FloatArray(4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 1, 1, values)
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.23f, 0.25f, 0f, raw, 0))
        assertTrue("floor must widen (P below verbatim)", raw[0] < 2f / (0.25f * 0.25f))
        val c = field.values
        assertEquals(1.0 / raw[0], c[0].toDouble(), 1e-5)
        assertEquals(1.0 / raw[3], c[3].toDouble(), 1e-5)
    }

    @Test fun kernelNetFieldFloorsEdgeMinorAxis() = withZipperGates {
        // (0.2,1.5,rho 0): capped+framed edge; the narrow clamp floors the
        // covariance minor eigenvalue at 0.09 while the major axis passes
        // through as the plain inverse.
        val planes = floatArrayOf(0.2f, 1.5f, 0f)
        val values = FloatArray(4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 1, 1, values)
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, raw, 0))
        assertTrue("edge across-axis must stay sharp pre-clamp", raw[3] > 1f / 0.09f)
        val c = field.values
        assertTrue("clamp must floor the minor covariance eigenvalue", c[3] >= 0.09f - 1e-4f)
        assertEquals(1.0 / raw[0], c[0].toDouble(), 1e-4)
    }
}
