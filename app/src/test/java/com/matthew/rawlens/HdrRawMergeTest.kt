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

    @Test(expected = IllegalArgumentException::class)
    fun smoothWarpRejectsNonfiniteFlow() {
        HdrRawMerge.sampleSmooth(cfa(0.2f), HdrFlowField { _, _ -> Float.NaN to 0f }, 2, 2)
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

    @Test fun exactModeMatchesDarktableClippedFallback() {
        val bright = cfa(1f)
        val short = cfa(0.25f)
        val merged = HdrRawMerge.mergeExact(listOf(
            HdrMergeFrame(short, 2_500_000L, 100),
            HdrMergeFrame(bright, 10_000_000L, 100)
        ), referenceIndex = 1)
        assertTrue(merged.values.all { it.isFinite() && it > 0f })
        assertEquals(0.25, merged.values.average(), 0.03)
    }

    @Test fun cfaWarpRoundsToCompleteBayerCells() {
        val values = FloatArray(36) { it.toFloat() / 36f }
        val input = UnpackedRawCfa(6, 6, BayerPattern.RGGB, values, RawCrop(0, 0, 6, 6))
        val shifted = HdrMergeFrame(input, 10_000_000L, 100, flow = HdrFlowField { _, _ -> 1.1f to 0f })
        val merged = HdrRawMerge.merge(listOf(HdrMergeFrame(input, 10_000_000L, 100), shifted), 0)
        assertEquals(BayerPattern.RGGB, merged.pattern)
    }

    @Test fun exactMergeAccumulatesInInputOrder() {
        // Darktable has no reference-first ordering: unclipped accumulation must
        // be identical regardless of which frame is the geometric reference.
        val a = cfa(0.2f, 8)
        val b = cfa(0.4f, 8)
        val frames = listOf(HdrMergeFrame(a, 5_000_000L, 100), HdrMergeFrame(b, 10_000_000L, 100))
        val asRef0 = HdrRawMerge.mergeExact(frames, 0)
        val asRef1 = HdrRawMerge.mergeExact(frames, 1)
        assertTrue(asRef0.values.zip(asRef1.values.toList()).all { (x, y) -> x == y })
    }

    @Test fun smoothWarpNeverMixesBayerColours() {
        // Distinct per-site values so any cross-colour leak is detectable.
        val values = FloatArray(64) { i ->
            val x = i % 8
            val y = i / 8
            when (BayerPattern.RGGB.colorAt(x, y)) {
                CfaColor.RED -> 0.1f
                CfaColor.GREEN -> 0.5f
                CfaColor.BLUE -> 0.9f
            }
        }
        val input = UnpackedRawCfa(8, 8, BayerPattern.RGGB, values, RawCrop(0, 0, 8, 8))
        val flow = HdrFlowField { _, _ -> 1f to 1f } // odd shift: nearest-cell would snap
        for (y in 0..7) for (x in 0..7) {
            val v = HdrRawMerge.sampleSmooth(input, flow, x, y)
            val expected = when (BayerPattern.RGGB.colorAt(x, y)) {
                CfaColor.RED -> 0.1f
                CfaColor.GREEN -> 0.5f
                CfaColor.BLUE -> 0.9f
            }
            assertEquals(expected, v, 1e-6f)
        }
    }

    @Test fun smoothMaskInterpolatesAcrossBlockBoundary() {
        // Two adjacent 2x2 cells with different block maxima must blend, not step.
        val blockMax = floatArrayOf(0f, 1f, 0f, 1f)
        val blockMin = floatArrayOf(0f, 0f, 0f, 0f)
        val atLeftCenter = HdrRawMerge.sampleBlockSmooth(blockMax, blockMin, 2, 2, 1, 1)
        val between = HdrRawMerge.sampleBlockSmooth(blockMax, blockMin, 2, 2, 2, 1)
        assertEquals(0f, atLeftCenter.first, 1e-6f)
        assertTrue(between.first > 0f && between.first < 1f)
    }

    @Test fun deghostRejectsMovingOutlier() {
        val ref = cfa(0.3f, 8)
        val movValues = FloatArray(64) { 0.3f }.also {
            // Bright square outlier in the moving frame (ghost).
            for (y in 2..5) for (x in 2..5) it[y * 8 + x] = 0.9f
        }
        val mov = UnpackedRawCfa(8, 8, BayerPattern.RGGB, movValues, RawCrop(0, 0, 8, 8))
        val merged = HdrRawMerge.merge(
            listOf(HdrMergeFrame(ref, 10_000_000L, 100), HdrMergeFrame(mov, 10_000_000L, 100)),
            referenceIndex = 0)
        val ghosted = merged.values[3 * 8 + 3]
        // Reference-normalized output of a 0.3 input is ~0.3; ghost must not pull it far.
        assertTrue("ghost leaked: $ghosted", ghosted < 0.45f && ghosted > 0.15f)
    }

    @Test fun highlightFallbackWinnerIsCoherentPerCell() {
        // Short frame fully clipped; long frame clipped except one dark pixel
        // in cell (0,0). Darktable awards the whole cell to the long frame
        // (smallest block minimum); per-pixel winners would checkerboard the
        // fallback into contour lines.
        val size = 8
        val short = cfa(1f, size)
        val longVals = FloatArray(size * size) { 1f }.also { it[0] = 0.5f }
        val long = UnpackedRawCfa(size, size, BayerPattern.RGGB, longVals, RawCrop(0, 0, size, size))
        val merged = HdrRawMerge.merge(listOf(
            HdrMergeFrame(short, 2_500_000L, 100),
            HdrMergeFrame(long, 10_000_000L, 100)
        ), referenceIndex = 1)
        // White is the short frame's calibration: long fallback = in/4.
        assertEquals(0.125f, merged.values[0], 1e-4f)
        assertEquals(0.25f, merged.values[1], 1e-4f)
        assertEquals(0.25f, merged.values[size], 1e-4f)
        assertEquals(0.25f, merged.values[size + 1], 1e-4f)
        // Other interior cells: short frame wins the fully-clipped fallback.
        assertEquals(1f, merged.values[4 * size + 4], 1e-4f)
    }

    @Test fun shadowsStillMergeWithoutOutliers() {
        // Identical dark frames must average (denoise), not collapse to reference.
        val a = cfa(0.05f, 8)
        val b = cfa(0.05f, 8)
        val merged = HdrRawMerge.merge(
            listOf(HdrMergeFrame(a, 10_000_000L, 100), HdrMergeFrame(b, 10_000_000L, 100)),
            referenceIndex = 0)
        assertEquals(0.05, merged.values.average(), 0.01)
    }

    @Test fun fastFlowPathsMatchLegacyLambdaSampling() {
        // TranslationFlow/CombinedFlow must reproduce the legacy per-pixel Pair
        // path bit-exactly (they only remove allocations, never math).
        val values = FloatArray(100) { i -> (i % 13) / 13f }
        val input = UnpackedRawCfa(10, 10, BayerPattern.RGGB, values, RawCrop(0, 0, 10, 10))
        val legacy = HdrFlowField { _, _ -> 2.3f to -1.25f }
        val fast = CombinedFlow(TranslationFlow(2f, -1f), TranslationFlow(0.3f, -0.25f))
        for (y in 0 until 10) for (x in 0 until 10) {
            assertEquals(
                HdrRawMerge.sampleSmooth(input, legacy, x, y),
                HdrRawMerge.sampleSmooth(input, fast, x, y), 0f)
        }
    }

    @Test fun threadedMergeMatchesSingleThreadNumerics() {
        // Row-strip fan-out must not change output: run a textured merge twice
        // (pool scheduling varies) and require bit-exact equality.
        val mk = { seed: Int ->
            UnpackedRawCfa(24, 24, BayerPattern.RGGB,
                FloatArray(24 * 24) { i -> (((i * 7 + seed) % 19) / 19f).coerceIn(0.02f, 0.95f) },
                RawCrop(0, 0, 24, 24))
        }
        val frames = listOf(
            HdrMergeFrame(mk(1), 5_000_000L, 100),
            HdrMergeFrame(mk(2), 10_000_000L, 100, flow = TranslationFlow(1.5f, -0.5f)))
        val a = HdrRawMerge.merge(frames, 0).values
        val b = HdrRawMerge.merge(frames, 0).values
        assertTrue(a.zip(b.toList()).all { (x, y) -> x == y })
    }

    private fun cfa(value: Float, size: Int = 6) = UnpackedRawCfa(
        size, size, BayerPattern.RGGB, FloatArray(size * size) { value },
        RawCrop(0, 0, size, size)
    )
}
