// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the KernelNet s1/s2/rho -> precision law (PhotonCamera
 * `mergeCombineWeight` convention: s1 is the y-sigma, s2 the x-sigma, with
 * the exp(-0.5 z) bridge scale x2), the quad-mean luma input domain, and the
 * quarter-to-quad resampling. If the axis convention drifts, every
 * anisotropic kernel rotates 90 degrees and the merge zippers along edges.
 */
class RawSrKernelNetAnisoTest {
    @Test fun isotropicTripleGivesScaledIdentity() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1f, 1f, 0f, out, 0))
        // Capped to 0.71 joint (area 0.252, no floor): P = 2/c^2 I.
        val c = 1f * (0.71 / 1)
        assertEquals((2.0 / (c * c * 1.0)).toFloat(), out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals((2.0 / (c * c * 1.0)).toFloat(), out[3], 1e-5f)
    }

    @Test fun quadScaleFollowsInverseSquareLaw() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0f, out, 0))
        // Below the cap, above the floor: exact 2/s^2 law.
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[3], 1e-5f)
    }

    @Test fun widthCapTrimsSaturatedIsotropic() {
        // (2,2,0) joint-scales to (0.71,0.71): P = 2/c^2 I, no floor.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(2f, 2f, 0f, out, 0))
        val c = 2f * (0.71 / 2)
        assertEquals((2.0 / (c * c * 1.0)).toFloat(), out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals((2.0 / (c * c * 1.0)).toFloat(), out[3], 1e-5f)
    }

    @Test fun widthCapPreservesAnisotropyRatio() {
        // (1.5,0.6,0) scales by 0.71/1.5 to (0.71,0.284); area 0.101 < 0.2
        // floors uniformly, so the 2.5 ratio survives cap and floor alike.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1.5f, 0.6f, 0f, out, 0))
        val kc = 0.71 / 1.5
        val c1 = 1.5f * kc
        val c2 = 0.6f * kc
        val k = c1 * c2 * kotlin.math.sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * 1.0) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (c1 * c1 * 1.0) * k).toFloat(), out[3], 1e-5f)
        // σy/σx = 1.5/0.6 = 2.5 before and after (s1 is y).
        val sx = 1.0 / kotlin.math.sqrt(out[0].toDouble())
        val sy = 1.0 / kotlin.math.sqrt(out[3].toDouble())
        assertEquals(2.5, sy / sx, 1e-6)
    }

    @Test fun widthCapStrictBoundaryUntouched() {
        // Peak exactly 0.71: no cap (strict >); area 0.227: no floor.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.71f, 0.639f, 0f, out, 0))
        assertEquals((2.0 / (0.639 * 0.639)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.71 * 0.71)).toFloat(), out[3], 1e-6f)
    }

    @Test fun correlationTiltsOffDiagonal() {
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0.5f, out, 0))
        // det = 0.75, no cap, area 0.212: p00 = 2/(c^2*det), p01 = -2*rho/(c^2*det).
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
        // Triple sits below the cap and above the floor: exact law.
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
        // Capped to 0.71 joint, det = 0.75, then floored uniformly:
        // p00 = 2/(c2^2*det)*k, p11 = 2/(c1^2*det)*k, p01 = -2*rho/(c1*c2*det)*k.
        val kc = 0.71 / 1.0
        val c1 = 0.5f * kc
        val c2 = 1.0f * kc
        val det = 1.0 - 0.5 * 0.5
        val k = c1 * c2 * kotlin.math.sqrt(det) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * det) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 * -0.5 / (c1 * c2 * det) * k).toFloat(), out[1], 1e-5f)
        assertEquals(out[1], out[2], 0f)
        assertEquals((2.0 / (c1 * c1 * det) * k).toFloat(), out[3], 1e-4f)
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
        // Capped to 0.71 joint with rho clamped to 0.999, then fattened by
        // the area floor: p00 = 2/(c^2*det) * (area/0.2).
        val c = 1f * (0.71 / 1)
        val r = 0.999f
        val det = 1.0 - r * r
        val k = c * c * kotlin.math.sqrt(det) / 2.0 / 0.2
        assertEquals((2.0 / (c * c * det) * k).toFloat(), out[0], out[0] * 1e-4f)
        assertTrue("ridge must fatten below the unfloored law", out[0] < 2f / det.toFloat())
    }

    @Test fun kernelAreaFloorWidensTextureKernels() {
        // Isotropic-sharp triple (texture-like): area 0.23*0.25/2 scales P
        // by k = area/0.2 while keeping isotropy, so neighbouring
        // same-colour taps pool instead of a single nearest tap.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.23f, 0.25f, 0f, out, 0))
        val k = 0.23 * 0.25 / 2 / 0.2
        assertEquals((2.0 / (0.25 * 0.25) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (0.23 * 0.23) * k).toFloat(), out[3], 1e-4f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(out[1], out[2], 0f)
    }

    @Test fun kernelAreaFloorLeavesWideKernelsAlone() {
        // area 0.7*0.7/2 = 0.245 >= 0.2: exact unfloored law.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.7f, 0.7f, 0f, out, 0))
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (0.7 * 0.7)).toFloat(), out[3], 1e-5f)
    }

    @Test fun kernelAreaFloorKeepsEdgeAnisotropy() {
        // Strong-edge triple, capped then floored: area 0.2*0.71/2 = 0.071
        // < 0.2 widens uniformly (x2.0 on sigma), preserving
        // narrow-across/wide-along.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1f, 0f, out, 0))
        val kc = 0.71 / 1.0
        val c1 = 0.2f * kc
        val c2 = 1.0f * kc
        val k = c1 * c2 * kotlin.math.sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * 1.0) * k).toFloat(), out[0], 1e-5f)
        assertEquals((2.0 / (c1 * c1 * 1.0) * k).toFloat(), out[3], 1e-4f)
        assertTrue("edge must stay narrow across (y)", out[3] > out[0])
    }

    @Test fun capConstantMatchesDocValue() {
        assertEquals(0.71, RawSrKernelNetAniso.KERNEL_SIGMA_MAX, 0.0)
    }

    @Test fun classifyTripleReportsRegimes() {
        val C = RawSrKernelNetAniso.KernelTripleFlags.CAPPED
        val F = RawSrKernelNetAniso.KernelTripleFlags.FLOORED
        val R = RawSrKernelNetAniso.KernelTripleFlags.REJECTED
        // (2,2,0): capped only (area 0.252, no floor).
        assertEquals(C, RawSrKernelNetAniso.classifyTriple(2f, 2f, 0f))
        // (0.23,0.25,0): floored only (no cap).
        assertEquals(F, RawSrKernelNetAniso.classifyTriple(0.23f, 0.25f, 0f))
        // (1.5,0.6,0): capped and floored.
        assertEquals(C or F, RawSrKernelNetAniso.classifyTriple(1.5f, 0.6f, 0f))
        // (0.7,0.7,0): mid-range, neither guard.
        assertEquals(0, RawSrKernelNetAniso.classifyTriple(0.7f, 0.7f, 0f))
        // Unusable triples reject before either guard.
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(0f, 1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(1f, -1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(Float.NaN, 1f, 0f))
        assertEquals(R, RawSrKernelNetAniso.classifyTriple(1f, 1f, Float.POSITIVE_INFINITY))
    }

    @Test fun areaFloorOverrideNarrowsTextureKernels() {
        // (0.5,0.5,0): area 0.125 — floored by default, untouched at 0.1.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.5f, 0.5f, 0f, out, 0))
        assertEquals((2.0 / (0.5 * 0.5) * (0.5 * 0.5 / 2.0 / 0.2)).toFloat(), out[0], 1e-5f)
        RawSrKernelNetAniso.kernelAreaFloor = 0.1
        try {
            assertEquals(0, RawSrKernelNetAniso.classifyTriple(0.5f, 0.5f, 0f))
            assertTrue(RawSrKernelNetAniso.precisionOf(0.5f, 0.5f, 0f, out, 0))
            assertEquals((2.0 / (0.5 * 0.5)).toFloat(), out[0], 1e-6f)
            assertEquals((2.0 / (0.5 * 0.5)).toFloat(), out[3], 1e-6f)
        } finally {
            RawSrKernelNetAniso.kernelAreaFloor = RawSrKernelNetAniso.MIN_KERNEL_AREA
        }
    }

    @Test fun kernelStatsCensusMergesAcrossShards() {
        val C = RawSrKernelNetAniso.KernelTripleFlags.CAPPED
        val F = RawSrKernelNetAniso.KernelTripleFlags.FLOORED
        val a = RawSrKernelNetAniso.KernelStats()
        a.add(2f, 2f, C)
        a.add(0.23f, 0.25f, F)
        a.add(0f, 1f, RawSrKernelNetAniso.KernelTripleFlags.REJECTED)
        val b = RawSrKernelNetAniso.KernelStats()
        b.add(0.7f, 0.7f, 0)
        a.merge(b)
        assertEquals(4, a.total)
        assertEquals(1, a.capped)
        assertEquals(1, a.floored)
        assertEquals(1, a.rejected)
        assertEquals(0.25f, a.peakMin, 0f)
        assertEquals(2f, a.peakMax, 0f)
        assertEquals((2.0 + 0.25 + 0.7) / 3, a.peakSum / (a.total - a.rejected), 1e-6)
        val line = a.logLine("test")
        assertTrue(line, line.contains("quads=4") && line.contains("capped=1") &&
            line.contains("floored=1") && line.contains("rejected=1"))
    }
    @Test fun capThenFloorComposeOnCappedEdge() {
        // (0.2,1.5,0): cap scales to (0.2*kc,0.71), then the area floor
        // widens uniformly; ratio preserved end to end.
        val out = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, out, 0))
        val kc = 0.71 / 1.5
        val c1 = 0.2f * kc
        val c2 = 1.5f * kc
        val k = c1 * c2 * kotlin.math.sqrt(1.0) / 2.0 / 0.2
        assertEquals((2.0 / (c2 * c2 * 1.0) * k).toFloat(), out[0], 1e-4f)
        assertEquals((2.0 / (c1 * c1 * 1.0) * k).toFloat(), out[3], 1e-3f)
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

    @Test fun kernelNetFieldFloorsEdgeMinorAxis() {
        // Zipper regression: the per-triple cap + area floor preserve
        // anisotropy, so a strong-edge triple (0.2,1.5,0) keeps a
        // sub-lattice across-axis that collapses each colour channel onto
        // its own sparse taps (~1px inter-channel straddle plus ringing).
        // The produced field must floor every kernel's narrow axis at 0.5
        // quads while leaving orientation and the wide axis untouched.
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(0.2f, 1.5f, 0f, raw, 0))
        // The unclamped triple is narrow across y: p11 >> 1/0.25.
        assertTrue("edge triple must be sub-lattice across y before the floor", raw[3] > 4f)
        // Constant 1x1 model plane resampled onto a 2x2 quad grid: every
        // quad samples the edge triple exactly (clamped-edge resampling).
        val planes = floatArrayOf(0.2f, 1.5f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        assertEquals(2, field.width)
        assertEquals(2, field.height)
        for (i in 0 until 4) {
            val o = i * 4
            val p00 = field.values[o].toDouble()
            val p01 = field.values[o + 1].toDouble()
            val p11 = field.values[o + 3].toDouble()
            // Axis-aligned triple stays axis-aligned.
            assertEquals(0.0, p01, 1e-6)
            // Minor sigma of Sigma = P^-1 is >= 0.5: lambda_max(P) <= 4.
            val trace = p00 + p11
            val det = p00 * p11 - p01 * p01
            val lambdaMax = (trace + kotlin.math.sqrt(maxOf(trace * trace - 4 * det, 0.0))) / 2
            assertTrue("quad $i minor sigma ${1 / kotlin.math.sqrt(lambdaMax)} below floor",
                lambdaMax <= 4.0 * (1 + 1e-5))
            // Wide (x) axis untouched by the floor.
            assertEquals(raw[0].toDouble(), p00, 1e-4)
        }
    }

    @Test fun kernelNetFieldFloorsSharpIsotropicTriple() {
        // Isotropic-sharp triple (0.23,0.25,0): the area floor widens to
        // sigma ~0.45, still below the 0.5 lattice floor — the field clamp
        // finishes the job on both axes.
        val planes = floatArrayOf(0.23f, 0.25f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        for (i in 0 until 4) {
            val o = i * 4
            val p00 = field.values[o].toDouble()
            val p11 = field.values[o + 3].toDouble()
            assertTrue("quad $i p00=$p00 above 1/0.25", p00 <= 4.0 * (1 + 1e-5))
            assertTrue("quad $i p11=$p11 above 1/0.25", p11 <= 4.0 * (1 + 1e-5))
            assertEquals(0.0, field.values[o + 1].toDouble(), 1e-6)
        }
    }

    @Test fun kernelNetFieldLeavesWideTripleAlone() {
        // Capped isotropic triple (1,1,0) -> (0.71,0.71): true sigma is
        // s/sqrt(2) = 0.502, just above the 0.5 floor, so the field passes
        // through to float tolerance (P -> Sigma -> P roundtrip only).
        val planes = floatArrayOf(1f, 1f, 0f)
        val values = FloatArray(2 * 2 * 4)
        val field = RawSrKernelNetAniso.convertPlanesToField(planes, 1, 1, 2, 2, values)
        val raw = FloatArray(4)
        assertTrue(RawSrKernelNetAniso.precisionOf(1f, 1f, 0f, raw, 0))
        for (i in 0 until 4) {
            val o = i * 4
            assertEquals(raw[0], field.values[o], 1e-4f)
            assertEquals(raw[3], field.values[o + 3], 1e-4f)
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
}
