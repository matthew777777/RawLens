// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/**
 * Mosaic sharpness contract: the narrow-axis floor must preserve texture
 * micro-contrast. Measured tradeoff (period-4 grating, 3-frame static
 * burst, green target sites): σ0.25 keeps 87%, σ0.3 keeps 73%, σ0.4 keeps
 * 51%, σ0.5 keeps 39%; step rise is 0/1/2 target px respectively. The
 * default floor sits at 0.3 — neighbour-tap pooling without the lottery,
 * micro-contrast without the mush.
 */
class MosaicSharpnessTest {
    private fun frameOf(
        w: Int,
        h: Int,
        samples: FloatArray,
        precisionValue: Float
    ): RawSrBayerMerge.MergeFrame {
        val qw = w / 2
        val qh = h / 2
        // Route through the production narrow-axis clamp (default floor),
        // exactly like RawSrMergeJob.mergeFrame does: P=16I (σ0.25) leaves
        // as σ0.3, so these tests pin shipped behaviour, not raw kernels.
        val precision = MosaicSrReconstructor.clampMinorAxis(
            RawSrKernelCovariance.MatrixField(
                qw, qh, FloatArray(qw * qh * 4) { i -> if (i % 4 == 0 || i % 4 == 3) precisionValue else 0f }))
        val tiles = List(qw * qh) { RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true) }
        val flow = RawSrAlignmentField(qw, qh, 1, qw, qh, tiles)
        val robustness = RawSrRobustness.FrameRobustness(
            qw, qh, FloatArray(qw * qh) { 1f }, IntArray(qw * qh))
        return RawSrBayerMerge.MergeFrame(
            w, h, samples, BayerPattern.RGGB, 0, 0, precision, flow, robustness)
    }

    private fun greenRow(result: MosaicSrReconstructor.MosaicSrResult): List<Double> {
        val y = result.height / 2
        return (0 until result.width).filter {
            result.pattern.colorAt(it, y) == CfaColor.GREEN
        }.map { result.cfa[y * result.width + it].toDouble() }
    }

    @Test fun defaultFloorRetainsTextureContrast() {
        // Period-4 grating at 0.3 amplitude through the production clamp:
        // retention must stay at the measured 0.73 level (tolerance holds
        // float rounding; the 0.5 floor kept only 0.39).
        val w = 64
        val h = 64
        val samples = FloatArray(w * h) { i ->
            (0.5 + 0.3 * kotlin.math.sin(2 * Math.PI * (i % w) / 4.0)).toFloat()
        }
        // P=16I (σ0.25) clamped by the default floor to σ0.3.
        val ref = frameOf(w, h, samples, 16f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(ref, ref))
        val row = greenRow(result)
        val retained = (row.max() - row.min()) / 2.0 / 0.3
        assertTrue("retained=$retained", retained >= 0.65)
    }

    @Test fun defaultFloorKeepsStepEdgeTight() {
        // Step 0.2|0.8 through clamped σ0.3 kernels: 10-90% rise within one
        // target pixel (the 0.5 floor smeared it over two).
        val w = 64
        val h = 64
        val samples = FloatArray(w * h) { i -> if (i % w < 32) 0.2f else 0.8f }
        val ref = frameOf(w, h, samples, 16f)
        val result = MosaicSrReconstructor.reconstruct(ref, listOf(ref, ref))
        val row = greenRow(result)
        val lo = row.min()
        val hi = row.max()
        assertTrue("range=$lo..$hi", hi - lo > 0.5)
        val x10 = row.indexOfFirst { it > lo + 0.1 * (hi - lo) }
        val x90 = row.indexOfFirst { it > lo + 0.9 * (hi - lo) }
        assertTrue("rise=${x90 - x10}", x90 - x10 <= 1)
    }

    @Test fun floorOverrideRestoresOldMushForAB() {
        // The A/B switch must move behaviour: at 0.5 the same grating keeps
        // under half its contrast (measured 0.39).
        val w = 64
        val h = 64
        val samples = FloatArray(w * h) { i ->
            (0.5 + 0.3 * kotlin.math.sin(2 * Math.PI * (i % w) / 4.0)).toFloat()
        }
        MosaicSrReconstructor.minorAxisSigmaFloor = 0.5
        try {
            val field = RawSrKernelCovariance.MatrixField(2, 2, FloatArray(16) { 16f })
            val clamped = MosaicSrReconstructor.clampMinorAxis(
                field, MosaicSrReconstructor.minorAxisSigmaFloor)
            assertEquals((1.0 / (0.5 * 0.5)).toFloat(), clamped.values[0], 1e-4f)
        } finally {
            MosaicSrReconstructor.minorAxisSigmaFloor = MosaicSrReconstructor.MIN_MINOR_SIGMA
        }
        assertEquals(0.3, MosaicSrReconstructor.minorAxisSigmaFloor, 0.0)
    }
}
