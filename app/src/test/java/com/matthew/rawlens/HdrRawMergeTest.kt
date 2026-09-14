package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrRawMergeTest {
    @Test fun warpPreservesAllFourCfaSitesAtEveryBorder() {
        for (pattern in BayerPattern.entries) {
            val input = UnpackedRawCfa(6, 6, pattern,
                FloatArray(36) { ((it / 6 and 1) * 2 + (it % 6 and 1)).toFloat() },
                RawCrop(0, 0, 6, 6))
            for (shift in listOf(-100f, -2f, 2f, 100f)) {
                val flow = HdrFlowField { _, _ -> shift to shift }
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
        HdrRawMerge.sampleCfaSafe(cfa(0.2f), HdrFlowField { _, _ -> Float.NaN to 0f }, 2, 2)
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
        val shifted = HdrMergeFrame(input, 10_000_000L, 100, flow = HdrFlowField { _, _ -> 1.1f to 0f })
        val merged = HdrRawMerge.merge(listOf(HdrMergeFrame(input, 10_000_000L, 100), shifted), 0)
        assertEquals(BayerPattern.RGGB, merged.pattern)
    }

    private fun cfa(value: Float) = UnpackedRawCfa(
        6, 6, BayerPattern.RGGB, FloatArray(36) { value }, RawCrop(0, 0, 6, 6)
    )
}
