package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrRawMergeTest {
    @Test fun clippedFallbackCannotSwitchToDarkerLongExposureAtLampEdge() {
        val short = cfa(1f).also { it.values[4 * 6 + 4] = 0.8f }
        val middle = cfa(1f).also { it.values[4 * 6 + 4] = 0.1f }
        val long = cfa(1f).also { it.values[4 * 6 + 4] = 0.05f }
        // All three neighborhoods contain clipping. A lower neighboring minimum is not
        // evidence that a longer exposure can recover the clipped center measurement.
        val frames = listOf(HdrMergeFrame(short, 1_000_000, 100),
            HdrMergeFrame(middle, 4_000_000, 100), HdrMergeFrame(long, 16_000_000, 100))
        assertEquals(1f, HdrRawMerge.merge(frames, 1).values[2 * 6 + 2], 1e-6f)
        assertEquals(1f, HdrRawMerge.merge(frames.reversed(), 1).values[2 * 6 + 2], 1e-6f)
    }

    @Test fun clippedFallbackPreservesShortestWarpedCfaInsteadOfSynthesizingWhite() {
        val short = cfa(1f).also { it.values[2 * 6 + 2] = 0.8f }
        val flow = HdrFlowField { _, _ -> packHdrDisplacement(1f, 0f) }
        val frames = listOf(HdrMergeFrame(short, 1_000_000, 100, flow = flow),
            HdrMergeFrame(cfa(1f), 4_000_000, 100))
        assertEquals(0.9f, HdrRawMerge.merge(frames, 1).values[2 * 6 + 2], 1e-6f)
    }

    @Test fun clippedImageBordersUseSameFallbackAsInterior() {
        val frames = listOf(HdrMergeFrame(cfa(1f), 1_000_000, 100),
            HdrMergeFrame(cfa(1f), 4_000_000, 100))
        HdrRawMerge.merge(frames, 1).values.forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test fun deghostingCannotReplaceClippedFallbackWithUnderexposedReference() {
        val reference = cfa(1f)
        for (y in 2..3) for (x in 2..3) reference.values[y * 6 + x] = 0.8f
        val result = HdrRawMerge.merge(listOf(HdrMergeFrame(cfa(1f), 1_000_000, 100),
            HdrMergeFrame(reference, 4_000_000, 100)), 1)
        // The neighboring clipped samples invalidate this whole merge envelope, even
        // though the reference's inner 2x2 alone passes the deghosting brightness check.
        assertEquals(1f, result.values[2 * 6 + 2], 1e-6f)
    }

    @Test fun movingFoamRetainsReferenceRatherThanBackground() {
        val reference = cfa(0.6f)
        val merged = FloatArray(36) { 0.03f }
        HdrRawMerge.suppressMotionDisagreement(reference, merged, 0.25f)
        merged.forEach { assertEquals(0.15f, it, 1e-6f) }
    }

    @Test fun staticRadianceAndClippedHighlightRecoveryArePreserved() {
        val merged = FloatArray(36) { 0.15f }
        HdrRawMerge.suppressMotionDisagreement(cfa(0.6f), merged, 0.25f)
        merged.forEach { assertEquals(0.15f, it, 0f) }
        HdrRawMerge.suppressMotionDisagreement(cfa(1f), merged, 0.25f)
        merged.forEach { assertEquals(0.15f, it, 0f) }
    }

    @Test fun clippedSourceTapCannotBecomeUnsaturatedThroughInterpolation() {
        val input = cfa(0.1f)
        input.values[2 * 6 + 4] = 1f
        val flow = HdrFlowField { _, _ -> packHdrDisplacement(1f, 0f) }
        assertEquals(0.55f, HdrRawMerge.sampleCfaSafe(input, flow, 2, 2), 1e-6f)
        assertEquals(1f, HdrRawMerge.sampleCfaSafe(input, flow, 2, 2, true), 0f)
        val identity = HdrFlowField { _, _ -> packHdrDisplacement(0f, 0f) }
        assertEquals(0.1f, HdrRawMerge.sampleCfaSafe(input, identity, 2, 2, true), 0f)
    }

    @Test fun warpedClippedLampEdgeUsesUnclippedShortExposure() {
        val short = cfa(0.2f)
        val long = cfa(0.1f)
        long.values[2 * 6 + 4] = 1f
        val flow = HdrFlowField { _, _ -> packHdrDisplacement(1f, 0f) }
        val merged = HdrRawMerge.merge(listOf(
            HdrMergeFrame(short, 1_000_000L, 100),
            HdrMergeFrame(long, 4_000_000L, 100, flow = flow)
        ), 0)
        assertEquals(0.2f, merged.values[2 * 6 + 2], 1e-5f)
    }

    @Test fun warpPreservesAllFourCfaSitesAtEveryBorder() {
        for (pattern in BayerPattern.entries) {
            val input = UnpackedRawCfa(6, 6, pattern,
                FloatArray(36) { ((it / 6 and 1) * 2 + (it % 6 and 1)).toFloat() },
                RawCrop(0, 0, 6, 6))
            for (shift in listOf(-100f, -2f, 2f, 100f)) {
                val flow = HdrFlowField { _, _ -> packHdrDisplacement(shift, shift) }
                for (y in 0..5) for (x in 0..5) assertEquals(
                    input.values[y * 6 + x], HdrRawMerge.sampleCfaSafe(input, flow, x, y), 0f)
            }
        }
    }

    @Test fun negativeBlackNoiseIsClampedAtFinalNormalization() {
        val input = cfa(-0.01f)
        val result = HdrRawMerge.merge(listOf(
            HdrMergeFrame(input, 1_000_000, 100), HdrMergeFrame(input, 2_000_000, 100)))
        assertTrue(result.values.all { it == 0f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonfiniteFlow() {
        HdrRawMerge.sampleCfaSafe(cfa(0.2f), HdrFlowField { _, _ -> packHdrDisplacement(Float.NaN, 0f) }, 2, 2)
    }
    @Test fun envelopeMatchesDarktableEndpointsAndPeak() {
        assertEquals(0f, HdrRawMerge.envelope(0f), 1e-6f)
        assertEquals(1f, HdrRawMerge.envelope(0.5f), 1e-6f)
        assertEquals(0f, HdrRawMerge.envelope(1f), 1e-6f)
    }

    @Test fun shortExposureRecoversClippedHighlight() {
        val bright = cfa(1f)
        val short = cfa(0.25f)
        val merged = HdrRawMerge.merge(listOf(
            HdrMergeFrame(short, 2_500_000L, 100),
            HdrMergeFrame(bright, 10_000_000L, 100)
        ), referenceIndex = 1)
        assertTrue(merged.values.all { it.isFinite() && it > 0f })
        // Output white is the shortest frame's saturation point, matching Darktable's float DNG.
        assertEquals(0.25, merged.values.average(), 0.03)
    }

    @Test fun cfaWarpRoundsToCompleteBayerCells() {
        val values = FloatArray(36) { it.toFloat() / 36f }
        val input = UnpackedRawCfa(6, 6, BayerPattern.RGGB, values, RawCrop(0, 0, 6, 6))
        val shifted = HdrMergeFrame(input, 10_000_000L, 100,
            flow = HdrFlowField { _, _ -> packHdrDisplacement(1.1f, 0f) })
        val merged = HdrRawMerge.merge(listOf(HdrMergeFrame(input, 10_000_000L, 100), shifted), 0)
        assertEquals(BayerPattern.RGGB, merged.pattern)
    }

    private fun cfa(value: Float) = UnpackedRawCfa(
        6, 6, BayerPattern.RGGB, FloatArray(36) { value }, RawCrop(0, 0, 6, 6)
    )
}
