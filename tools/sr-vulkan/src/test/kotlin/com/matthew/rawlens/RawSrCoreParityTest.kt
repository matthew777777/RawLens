// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared-core proof (rewrite plan §1b step 7 + §6): the seven `RawSrCore*`
 * modules carry the exact CPU scalar math, and the CPU donors delegate to
 * them instead of duplicating the formulas.
 *
 * - Core entries vs hand-computed values at 0-tolerance (same
 *   doubles/floats, same order; every expectation below is exactly
 *   representable, so 0-tolerance is the honest bound).
 * - Donor-vs-Core wiring at 0-tolerance (guards the delegation call sites).
 * - Source markers: each donor cites its Core entries, and the moved
 *   formula bodies survive in exactly one place (the Core file). This is
 *   the re-divergence tripwire: re-duplicating a formula fails here.
 *
 * CPU behavior itself is pinned by the unchanged suites
 * (RawSrBayerMergeTest, RawSrRobustnessTest, RawSrCovarianceGuideTest,
 * RawSrKernelCovarianceTest, RawSrFlowUnitsTest, RawSrFftTest,
 * RawSrChromaFromLumaTest, RawSrKernelNetAnisoTest,
 * StackerNearestParityTest, JamyCoreParityTest) with identical assertions.
 */
class RawSrCoreParityTest {
    // ---- source-marker helpers (fail closed when sources are missing) ----

    private fun coreSource(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val mine = File(dir, "src/main/kotlin/com/matthew/rawlens/$name")
            if (mine.isFile) return mine.readText()
            val fromRoot = File(dir, "tools/sr-vulkan/src/main/kotlin/com/matthew/rawlens/$name")
            if (fromRoot.isFile) return fromRoot.readText()
            dir = dir!!.parentFile ?: return@repeat
        }
        error("missing source $name under ${System.getProperty("user.dir")}")
    }

    private fun assertDelegates(donor: String, core: String) {
        assertTrue("$donor must call $core",
            coreSource(donor).contains(core))
    }

    private fun assertMovedOnce(fragment: String, donor: String, coreFile: String) {
        assertFalse("$donor must not duplicate <$fragment>", coreSource(donor).contains(fragment))
        assertTrue("$coreFile must hold <$fragment>", coreSource(coreFile).contains(fragment))
    }

    // ---- RawSrCoreSampling ----

    @Test fun samplingCoordinatesAreExact() {
        assertEquals(1, RawSrCoreSampling.flowTileIndex(31.9f, 16))
        assertEquals(2, RawSrCoreSampling.flowTileIndex(32f, 16))
        // Plain truncation toward zero (NOT floor): negatives land on tile 0.
        assertEquals(0, RawSrCoreSampling.flowTileIndex(-0.5f, 16))
        assertEquals(2, RawSrCoreSampling.robustnessQuad(2.7))
        assertEquals(-1, RawSrCoreSampling.robustnessQuad(-0.2))
        assertEquals(0.5, RawSrCoreSampling.guideScale(2, 4), 0.0)
        assertEquals(1.5, RawSrCoreSampling.covarianceGuideCoord(4.0, 0.5), 0.0)
        assertEquals(0.5, RawSrCoreSampling.sourceCenter(0, 1.0), 0.0)
        assertEquals(0.5, RawSrCoreSampling.flowLookupPos(0, 1.0), 0.0)
        assertEquals(-0.75, RawSrCoreSampling.robustnessSamplePos(0, 1.0), 0.0)
        val r = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(4.0, RawSrCoreSampling.sampleRobustness(r, 2, 2, 1.2, 1.9), 0.0)
        // Far edge clamps; the N=0 lane lands on quad 0.
        assertEquals(1.0, RawSrCoreSampling.sampleRobustness(r, 2, 2, -5.0, -5.0), 0.0)
        assertEquals(4.0, RawSrCoreSampling.sampleRobustness(r, 2, 2, 99.0, 99.0), 0.0)
    }

    @Test fun covarianceInterpolationInvertsIdentityExactly() {
        // Identity field: lerp reproduces identity, inverse is identity.
        val field = FloatArray(2 * 2 * 4) { if (it % 4 == 0 || it % 4 == 3) 1f else 0f }
        val out = DoubleArray(4)
        val got = RawSrCoreSampling.interpolateCovariance(field, 2, 2, 0.25, 0.75, out)
        assertTrue(got === out)
        assertEquals(1.0, out[0], 0.0)
        assertEquals(0.0, out[1], 0.0)
        assertEquals(0.0, out[2], 0.0)
        assertEquals(1.0, out[3], 0.0)
    }

    // ---- RawSrCoreKernel ----

    @Test fun tapMathIsExact() {
        assertEquals(0.0, RawSrCoreKernel.EPS, 0.0)
        assertEquals(0.25, RawSrCoreKernel.chromaZScale(2.0), 0.0)
        assertEquals(1.0, RawSrCoreKernel.chromaZScale(1.0), 0.0)
        assertEquals(1.0, RawSrCoreKernel.tapZ(1.0, 0.0, 1.0, 1.0, 0.0), 0.0)
        assertEquals(4.0, RawSrCoreKernel.tapZ(1.0, 2.0, 1.0, 1.0, 1.0), 0.0)
        assertEquals(3.0, RawSrCoreKernel.scaleChromaZ(3.0, 0.25, true), 0.0)
        assertEquals(0.75, RawSrCoreKernel.scaleChromaZ(3.0, 0.25, false), 0.0)
        assertEquals(1.0, RawSrCoreKernel.tapWeight(0.0), 0.0)
        assertEquals(1.0, RawSrCoreKernel.tapWeight(-4.0), 0.0)
        val out = DoubleArray(2)
        assertTrue(RawSrCoreKernel.accumulateTap(0.0, 0.5, 2.0, 0.25, true, out))
        assertEquals(1.0, out[0], 0.0)
        assertEquals(0.5, out[1], 0.0)
        assertFalse(RawSrCoreKernel.accumulateTap(Double.NaN, 0.5, 2.0, 0.25, true, out))
    }

    @Test fun divideMatchesUtilsDivide() {
        assertEquals(1.5, RawSrCoreKernel.divide(3.0, 2.0), 0.0)
        assertEquals(0.0, RawSrCoreKernel.divide(1.0, 0.0), 0.0)
        assertEquals(0.0, RawSrCoreKernel.divide(0.0, 0.0), 0.0)
        assertEquals(0.0, RawSrCoreKernel.divide(Double.NaN, 1.0), 0.0)
        assertFalse(RawSrCoreKernel.divideFallback(3.0, 2.0))
        assertTrue(RawSrCoreKernel.divideFallback(1.0, 0.0))
        assertTrue(RawSrCoreKernel.divideFallback(0.0, 0.0))
        assertTrue(RawSrCoreKernel.divideFallback(Double.NaN, 1.0))
        assertEquals(2f, RawSrCoreKernel.sanitize(2f), 0f)
        assertEquals(0f, RawSrCoreKernel.sanitize(Float.NaN), 0f)
    }

    // ---- RawSrCoreRobustness ----

    @Test fun dogsonKernelIsExact() {
        assertEquals(1.0, RawSrCoreRobustness.dogsonQuadratic(0.0), 0.0)
        assertEquals(0.5, RawSrCoreRobustness.dogsonQuadratic(0.5), 0.0)
        assertEquals(0.0, RawSrCoreRobustness.dogsonQuadratic(1.0), 0.0)
        assertEquals(0.0, RawSrCoreRobustness.dogsonQuadratic(2.0), 0.0)
        // Donor wrapper agrees bitwise (wiring guard).
        for (x in doubleArrayOf(-1.7, -0.5, 0.0, 0.3, 0.9, 1.4, 2.5)) {
            assertEquals(RawSrCoreRobustness.dogsonQuadratic(x), RawSrRobustness.dogsonQuadratic(x), 0.0)
        }
    }

    @Test fun warpOfConstantFieldIsConstant() {
        val mov = Array(3) { FloatArray(4 * 4) { 1f } }
        val out = DoubleArray(3)
        RawSrCoreRobustness.warpDogson(mov, 4, 4, 1.25, 2.5, out)
        assertEquals(1.0, out[0], 0.0)
        assertEquals(1.0, out[1], 0.0)
        assertEquals(1.0, out[2], 0.0)
        // Donor wrapper agrees bitwise (wiring guard).
        val donorOut = DoubleArray(3)
        RawSrRobustness.warpDogson(mov, 4, 4, 1.25, 2.5, donorOut)
        assertEquals(out[0], donorOut[0], 0.0)
        assertEquals(out[1], donorOut[1], 0.0)
        assertEquals(out[2], donorOut[2], 0.0)
    }

    @Test fun distanceThresholdAndLocalMinAreExact() {
        val refMean = Array(3) { FloatArray(1) { 2f } }
        val refVar = Array(3) { FloatArray(1) { 1f } }
        val distVar = DoubleArray(2)
        RawSrCoreRobustness.distanceAndVariance(refMean, refVar, doubleArrayOf(1.0, 1.0, 1.0), 0, distVar)
        assertEquals(3.0, distVar[0], 0.0)
        assertEquals(3.0, distVar[1], 0.0)
        val corrected = DoubleArray(2)
        RawSrCoreRobustness.correctNoise(3.0, 3.0, 2f, 2f, 2f, null, corrected)
        assertEquals(3.0, corrected[0], 0.0)
        assertEquals(3.0, corrected[1], 0.0)
        assertEquals(0f, RawSrCoreRobustness.threshold(1.0, 0.0, 1f, 0.1f), 0f)
        assertEquals(1f, RawSrCoreRobustness.threshold(0.0, 1.0, 1f, 0f), 0f)
        val raw = floatArrayOf(5f, 4f, 3f, 2f, 1f, 6f, 7f, 8f, 9f)
        assertEquals(1f, RawSrCoreRobustness.localMin(raw, 3, 3, 1, 1), 0f)
        assertEquals(1f, RawSrCoreRobustness.localMin(raw, 3, 3, 0, 0), 0f)
    }

    @Test fun rcSumsAndSupportAreExact() {
        val base = floatArrayOf(1f, 2f)
        assertEquals(floatArrayOf(4f, 6f).toList(),
            RawSrCoreRobustness.accumulateRc(base, floatArrayOf(3f, 4f)).toList())
        // Sanitized: NaN weighs 0. Plain: NaN propagates (exact donor spelling).
        assertEquals(1f, RawSrCoreRobustness.accumulateRc(base, floatArrayOf(Float.NaN, 0f))[0], 0f)
        assertTrue(RawSrCoreRobustness.accumulateRcPlain(base, floatArrayOf(Float.NaN, 0f))[0].isNaN())
        assertEquals(3f, RawSrCoreRobustness.supportValue(2f), 0f)
        assertEquals(0f, RawSrCoreRobustness.supportValue(Float.NaN), 0f)
        assertEquals(0f, RawSrCoreRobustness.sanitizeWeight(Float.NaN), 0f)
        // flowIrregular wiring: calm field is regular, split field is not.
        // Quad (12, 4) sits on tile column 1, whose 3x3 neighborhood spans
        // the column-1|2 split below.
        fun field(dx: (Int) -> Float): RawSrAlignmentField {
            val tiles = List(16) { i -> RawSrTileFlow(0f, 0f, dx(i), 0f, 0f, true) }
            return RawSrAlignmentField(64, 64, 16, 4, 4, tiles)
        }
        val split = field { if (it % 4 < 2) 0f else 5f }
        assertFalse(RawSrCoreRobustness.flowIrregular(field { 1f }, 12, 4, 1f))
        assertTrue(RawSrCoreRobustness.flowIrregular(split, 12, 4, 1f))
        assertEquals(RawSrCoreRobustness.flowIrregular(split, 12, 4, 1f),
            RawSrRobustness.flowIrregular(split, 12, 4, 1f))
    }

    // ---- RawSrCoreKernels ----

    @Test fun eigendecompositionIsExact() {
        val e = RawSrCoreKernels.eigenDecomposition(1f, 0f, 1f)
        assertEquals(floatArrayOf(1f, 0f, 0f, 1f, 1f, 1f).toList(), e.toList())
        val scratch = FloatArray(6)
        RawSrCoreKernels.eigenInto(1f, 0f, 1f, scratch)
        assertEquals(e.toList(), scratch.toList())
        // Donor wrappers agree bitwise (wiring guard).
        assertEquals(e.toList(), RawSrKernelCovariance.eigenDecomposition(1f, 0f, 1f).toList())
        val donorScratch = FloatArray(6)
        RawSrKernelCovariance.eigenInto(2f, 1f, 2f, donorScratch)
        RawSrCoreKernels.eigenInto(2f, 1f, 2f, scratch)
        assertEquals(scratch.toList(), donorScratch.toList())
    }

    @Test fun selectionAndBlendLawAreExact() {
        val linear = RawSrKernelCovariance.SelectionLaw.LINEAR
        val hard = RawSrKernelCovariance.SelectionLaw.HARD
        assertEquals(1.95f, RawSrCoreKernels.HARD_ANISOTROPY_GATE, 0f)
        assertEquals(RawSrCoreKernels.HARD_ANISOTROPY_GATE, RawSrKernelCovariance.HARD_ANISOTROPY_GATE, 0f)
        assertEquals(1f to 1f, RawSrCoreKernels.selectionAxes(1f, 2f, 4f, linear))
        assertEquals(1f to 1f, RawSrCoreKernels.selectionAxes(1.95f, 2f, 4f, hard))
        assertEquals(0.5f to 4f, RawSrCoreKernels.selectionAxes(2f, 2f, 4f, hard))
        assertEquals(1f, RawSrCoreKernels.selectionAxis1(1f, 2f, linear), 0f)
        assertEquals(1f, RawSrCoreKernels.selectionAxis2(1f, 4f, linear), 0f)
        assertEquals(1f, RawSrCoreKernels.anisotropy(0f, 0f), 0f)
        assertEquals(2f, RawSrCoreKernels.detail(4f), 0f)
        assertEquals(1f, RawSrCoreKernels.denoiseWeight(0f, 0.1f, 1f), 0f)
        assertEquals(2f, RawSrCoreKernels.blendRadius(1f, 2f, null, 0f, 1f, 1f), 0f)
        assertEquals(3f, RawSrCoreKernels.blendRadius(1f, 2f, 3f, 0f, 1f, 1f), 0f)
        assertEquals(0.5f, RawSrCoreKernels.gradientX(1f, 2f, 3f, 4f), 0f)
        assertEquals(1.0f, RawSrCoreKernels.gradientY(1f, 2f, 3f, 4f), 0f)
        val assembled = FloatArray(4)
        RawSrCoreKernels.assembleCovariance(1f, 1f, 1f, 0f, 0f, 1f, assembled, 0)
        assertEquals(floatArrayOf(1f, 0f, 0f, 1f).toList(), assembled.toList())
        RawSrCoreKernels.assemblePrecision(1f, 1f, 1f, 0f, 0f, 1f, assembled, 0)
        assertEquals(floatArrayOf(1f, 0f, 0f, 1f).toList(), assembled.toList())
        // Donor selection wrapper agrees (wiring guard).
        assertEquals(RawSrCoreKernels.selectionAxes(1.5f, 2f, 4f, linear),
            RawSrKernelCovariance.selectionAxes(1.5f, 2f, 4f, linear))
    }

    @Test fun structureTensorCountsTapsExactly() {
        // Constant (1, 0) gradients: interior quads see all four taps.
        val grads = FloatArray(4 * 4 * 2) { if (it % 2 == 0) 1f else 0f }
        val out = FloatArray(3)
        RawSrCoreKernels.structureTensor(grads, 4, 4, 2, 2, out)
        assertEquals(4f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
        // Corner quads see one tap (out-of-bounds contributes nothing).
        RawSrCoreKernels.structureTensor(grads, 4, 4, 0, 0, out)
        assertEquals(1f, out[0], 0f)
    }

    // ---- RawSrCoreGuide ----

    @Test fun guideMathIsExact() {
        assertEquals(1.0, RawSrCoreGuide.normalize(12, 4.0, 12.0), 0.0)
        assertEquals(0.0, RawSrCoreGuide.normalize(4, 4.0, 12.0), 0.0)
        // Affine limit: 4/sqrt(16) = 1. GAT: (2/2)*sqrt(0+1.5+2.5) = 2.
        assertEquals(1.0, RawSrCoreGuide.stabilize(4.0, 0.0, 16.0), 0.0)
        assertEquals(2.0, RawSrCoreGuide.stabilize(0.0, 2.0, 2.5), 0.0)
        assertEquals(0.5, RawSrCoreGuide.quadMean(2.0), 0.0)
    }

    // ---- RawSrCoreAlign ----

    @Test fun roundHalfAwayMatchesCudaSemantics() {
        for (x in doubleArrayOf(2.5, -2.5, 2.4, -2.4, 0.5, -0.5, 0.0, -0.0, 3.5)) {
            assertEquals(RawSrCoreAlign.roundHalfAway(x), RawSrAlignment.roundHalfAway(x))
        }
        assertEquals(3, RawSrCoreAlign.roundHalfAway(2.5))
        assertEquals(-3, RawSrCoreAlign.roundHalfAway(-2.5))
    }

    @Test fun blockCostsAndSamplersAreExact() {
        val ref = RawSrGrayImage(4, 4, FloatArray(16))
        val mov = RawSrGrayImage(4, 4, FloatArray(16))
        assertEquals(0.0, RawSrCoreAlign.blockCostL1(ref, mov, 0, 0, 4, 0, 0), 0.0)
        assertEquals(0.0, RawSrCoreAlign.blockCostL2(ref, mov, 0, 0, 4, 0, 0), 0.0)
        val flat = RawSrGrayImage(4, 4, FloatArray(16) { 0.3f })
        assertEquals(0.3f.toDouble(), RawSrCoreAlign.sampleZeroFilled(flat, 1.25, 2.5), 0.0)
        assertEquals(0.3f.toDouble(), RawSrCoreAlign.sampleClamped(flat, 1.25, 2.5), 0.0)
        // (Retired: lerpRow died with the CUDA-only tile-64 sliding
        // window; current reference cpu_ica has no 64 path.)
        val (t0, t1) = RawSrCoreAlign.treeClippedSums(DoubleArray(128), 4)
        assertEquals(0.0, t0, 0.0)
        assertEquals(0.0, t1, 0.0)
        val (sx, sy) = RawSrCoreAlign.icaStep(1.0, 1.0, 0.0, 1.0, 2.0, 3.0)
        assertEquals(2.0, sx, 0.0)
        assertEquals(3.0, sy, 0.0)
        assertEquals(0.0, RawSrCoreAlign.meanAbsidual(ref, mov, 0, 0, 4, 0.0, 0.0), 0.0)
        // Steepest sums of identical flats are exactly zero (e = 0 per tap).
        val gx = DoubleArray(16) { 1.0 }
        val gy = DoubleArray(16) { -1.0 }
        val (b0, b1, terms) = RawSrCoreAlign.steepestSums(ref, ref, gx, gy, 0, 0, 4, 0.0, 0.0)
        assertEquals(0.0, b0, 0.0)
        assertEquals(0.0, b1, 0.0)
        assertTrue(terms == null)
    }

    @Test fun upsampleAndReliabilityAreExact() {
        val zeros = RawSrCoreAlign.upsampleFlow(
            null, 3, 2, 2, 16, 16, RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR)
        assertTrue(zeros.values.all { it == 0.0 })
        // Constant prior: nearest/bilinear reproduce the constant, scaled by
        // factor; bicubic sums its Keys weights in float64 (≈1 to 1 ulp).
        val prior = RawSrAlignment.LevelFlow(2, 2, DoubleArray(8) { 1.0 })
        for (mode in RawSrAlignmentConfig.FlowUpscaleMode.values()) {
            val got = RawSrCoreAlign.upsampleFlow(prior, 2, 2, 2, 16, 16, mode)
            val bound = if (mode == RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC) 1e-12 else 0.0
            for (v in got.values) assertEquals("$mode", 2.0, v, bound)
            val donor = RawSrAlignment.upsampleFlow(prior, 2, 2, 2, 16, 16, mode)
            assertEquals(got.values.toList(), donor.values.toList())
        }
        val config = RawSrAlignmentConfig()
        assertTrue(RawSrCoreAlign.auxiliaryReliable(0.1, true, 1.0, 2.0, config))
        assertFalse(RawSrCoreAlign.auxiliaryReliable(0.5, true, 1.0, 2.0, config))
        assertFalse(RawSrCoreAlign.auxiliaryReliable(0.1, false, 1.0, 2.0, config))
        assertEquals(RawSrCoreAlign.auxiliaryReliable(0.1, true, 1.0, 2.0, config),
            RawSrAlignment.auxiliaryReliable(0.1, true, 1.0, 2.0, config))
        // flowTileIndex wiring through the field lookup.
        val tiles = List(4) { i -> RawSrTileFlow(0f, 0f, i.toFloat(), 0f, 0f, true) }
        val field = RawSrAlignmentField(32, 32, 16, 2, 2, tiles)
        val out = FloatArray(4)
        field.flowAtNearestInto(20f, 20f, out)
        assertEquals(3f, out[0], 0f)
    }

    // ---- RawSrCoreFinish ----

    @Test fun finishConstantsAreShared() {
        assertEquals(3, RawSrCoreFinish.MAX_RING)
        assertEquals(RawSrCoreFinish.MAX_RING, RawSrDeadLaneInpaint.MAX_RING)
        assertEquals(1.0f, RawSrCoreFinish.SIGMA, 0f)
        assertEquals(RawSrCoreFinish.SIGMA, RawSrChromaFromLuma.SIGMA, 0f)
        assertEquals(RawSrCoreFinish.RADIUS, RawSrChromaFromLuma.RADIUS)
        assertEquals(RawSrCoreFinish.GUIDE_EPS, RawSrChromaFromLuma.GUIDE_EPS, 0f)
        assertSame(RawSrCoreFinish.KERNEL, RawSrChromaFromLuma.KERNEL)
    }

    @Test fun inpaintHealsSingleDeadLaneExactly() {
        val w = 3
        val h = 3
        val rgb = FloatArray(w * h * 3) { 1f }
        val den = FloatArray(w * h * 3) { 1f }
        rgb[13] = 0f // center pixel, lane 1 (o = 4*3+1).
        den[13] = 0f
        assertEquals(1, RawSrCoreFinish.inpaint(rgb, den, w, h, 0f))
        assertEquals(1f, rgb[13], 0f)
        // Donor wrapper agrees bitwise on textured input (wiring guard).
        val rgbA = FloatArray(25 * 3) { (it % 7) * 0.13f }
        val denA = FloatArray(25 * 3) { if (it % 11 == 0) 0f else 1f }
        val rgbB = rgbA.copyOf()
        val healedA = RawSrCoreFinish.inpaint(rgbA, denA, 5, 5, 0f)
        val healedB = RawSrDeadLaneInpaint.inpaint(rgbB, denA.copyOf(), 5, 5)
        assertEquals(healedA, healedB)
        assertEquals(rgbA.toList(), rgbB.toList())
    }

    @Test fun chromaFromLumaMatchesDonorBitwise() {
        val rgbA = FloatArray(25 * 3) { 0.05f + (it % 13) * 0.07f }
        val rgbB = rgbA.copyOf()
        val countA = RawSrCoreFinish.stabilize(rgbA, 5, 5)
        val countB = RawSrChromaFromLuma.stabilize(rgbB, 5, 5)
        assertEquals(countA, countB)
        assertEquals(rgbA.toList(), rgbB.toList())
        // G lane is never touched.
        for (p in 0 until 25) {
            assertEquals(0.05f + ((p * 3 + 1) % 13) * 0.07f, rgbA[p * 3 + 1], 0f)
        }
    }

    // ---- anti-divergence markers ----

    @Test fun donorsCallTheCoreEntries() {
        assertDelegates("RawSrBayerMerge.kt", "RawSrCoreSampling")
        assertDelegates("RawSrBayerMerge.kt", "RawSrCoreKernel")
        assertDelegates("RawSrBayerMerge.kt", "RawSrCoreRobustness")
        assertDelegates("MosaicSrReconstructor.kt", "RawSrCoreSampling")
        assertDelegates("MosaicSrReconstructor.kt", "RawSrCoreKernel")
        assertDelegates("MosaicSrReconstructor.kt", "RawSrCoreRobustness")
        assertDelegates("RawSrRobustness.kt", "RawSrCoreRobustness")
        assertDelegates("RawSrRobustness.kt", "RawSrCoreGuide")
        assertDelegates("RawSrKernelCovariance.kt", "RawSrCoreKernels")
        assertDelegates("RawSrCovarianceGuide.kt", "RawSrCoreGuide")
        assertDelegates("RawSrAlignment.kt", "RawSrCoreSampling")
        assertDelegates("RawSrAlignment.kt", "RawSrCoreAlign")
        assertDelegates("RawSrDeadLaneInpaint.kt", "RawSrCoreFinish")
        assertDelegates("RawSrChromaFromLuma.kt", "RawSrCoreFinish")
    }

    @Test fun movedFormulasSurviveInExactlyOnePlace() {
        assertMovedOnce("cyy / det", "RawSrBayerMerge.kt", "RawSrCoreSampling.kt")
        assertMovedOnce("cyy / det", "MosaicSrReconstructor.kt", "RawSrCoreSampling.kt")
        assertMovedOnce("maxOf(zc, 0.0)", "RawSrBayerMerge.kt", "RawSrCoreKernel.kt")
        assertMovedOnce("z * chromaZScale", "MosaicSrReconstructor.kt", "RawSrCoreKernel.kt")
        assertMovedOnce("a * a - 2.5 * a + 1.5", "RawSrRobustness.kt", "RawSrCoreRobustness.kt")
        assertMovedOnce("distance * shrink * shrink", "RawSrRobustness.kt", "RawSrCoreRobustness.kt")
        assertMovedOnce("e1x /= norm", "RawSrKernelCovariance.kt", "RawSrCoreKernels.kt")
        assertMovedOnce("(kDetail * ((1f - denoise)", "RawSrKernelCovariance.kt", "RawSrCoreKernels.kt")
        assertMovedOnce("0.375 * alpha * alpha", "RawSrCovarianceGuide.kt", "RawSrCoreGuide.kt")
        assertMovedOnce("parsedSlope * (white - black)", "RawSrCovarianceGuide.kt", "RawSrCoreGuide.kt")
        // (Retired pin: "floorY + 1, floorX, fracX" was the CUDA-only
        // tile-64 sliding window; the current reference cpu_ica has no
        // 64 path, so the formula lives nowhere now.)
        assertMovedOnce("(a + 2.0) * x * x * x", "RawSrAlignment.kt", "RawSrCoreAlign.kt")
        assertMovedOnce("y - ring", "RawSrDeadLaneInpaint.kt", "RawSrCoreFinish.kt")
        assertMovedOnce("tmpR[yy * width + x]", "RawSrChromaFromLuma.kt", "RawSrCoreFinish.kt")
    }
}
