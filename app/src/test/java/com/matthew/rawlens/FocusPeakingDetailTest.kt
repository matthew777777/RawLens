// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class FocusPeakingDetailTest {
    private val n = FocusPeakingDetail.SIDE
    private fun patch(f: (Int, Int) -> Float) = FloatArray(n * n) { f(it % n, it / n) }
    private fun detects(p: FloatArray, clipped: BooleanArray = BooleanArray(n * n), variance: Float = 0f) =
        FocusPeakingDetail.detect(p, clipped, variance) >= 0
    private fun edge(contrast: Float = 0.6f, axis: Int = 0) = patch { x, y ->
        val side = when (axis) { 1 -> y >= n / 2; 2 -> x + y >= n; else -> x >= n / 2 }
        0.2f + if (side) contrast else 0f
    }
    private fun blur(input: FloatArray, sigma: Double): FloatArray {
        if (sigma == 0.0) return input.copyOf()
        val radius = ceil(sigma * 3).toInt()
        val kernel = DoubleArray(radius * 2 + 1) { exp(-(it - radius).toDouble().pow(2) / (2 * sigma * sigma)) }
        val norm = kernel.sum()
        val tmp = FloatArray(input.size)
        val out = FloatArray(input.size)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0.0
            for (k in -radius..radius) s += input[y * n + (x + k).coerceIn(0, n - 1)] * kernel[k + radius]
            tmp[y * n + x] = (s / norm).toFloat()
        }
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0.0
            for (k in -radius..radius) s += tmp[(y + k).coerceIn(0, n - 1) * n + x] * kernel[k + radius]
            out[y * n + x] = (s / norm).toFloat()
        }
        return out
    }
    @Test fun smoothSurfacesAndRampsDoNotInventFocus() {
        for (level in listOf(0f, 0.1f, 0.5f, 0.99f, 1f)) assertFalse(detects(patch { _, _ -> level }))
        assertFalse(detects(patch { x, y -> 0.1f + x * 0.02f + y * 0.01f }))
    }
    @Test fun lowContrastEdgesSurviveWithoutTextureOrBrightReference() {
        for (contrast in listOf(0.02f, 0.05f, 0.2f, 0.6f))
            for (axis in 0..2) assertTrue("contrast=$contrast axis=$axis", detects(edge(contrast, axis)))
    }
    @Test fun blurSweepRejectsDefocusAtEqualContrast() {
        for (axis in 0..2) {
            assertTrue(detects(edge(axis = axis)))
            assertTrue(detects(blur(edge(axis = axis), 0.5)))
            for (sigma in listOf(1.2, 2.0, 3.0, 4.0))
                assertFalse("axis=$axis sigma=$sigma", detects(blur(edge(axis = axis), sigma)))
        }
    }
    @Test fun contrastDoesNotRescueBroadDefocusedEdges() {
        for (contrast in listOf(0.05f, 0.2f, 0.6f, 0.79f))
            assertFalse(detects(blur(edge(contrast), 2.0)))
    }
    @Test fun clippedHighlightAndItsBoundaryAreNotPeakingEvidence() {
        val p = patch { x, _ -> if (x < n / 2) 0.3f else 1f }
        assertFalse(detects(p, BooleanArray(p.size) { p[it] >= FocusPeakingDetail.CLIP_LEVEL }))
        // A single clipped RAW colour vetoes the support even when green itself is not clipped.
        assertFalse(detects(edge(), BooleanArray(n * n) { it % n >= n / 2 }))
    }
    @Test fun saturationVetoIsLocalAndDoesNotHideSeparateValidEdges() {
        val p = edge()
        val clipped = BooleanArray(n * n) { it % n < 2 }
        assertTrue(detects(p, clipped))
    }
    @Test fun noiseOnlyPatchesDoNotFloodOverlayWithoutHalNoiseMetadata() {
        val rng = Random(18941)
        for (sigma in listOf(0.002, 0.01, 0.03, 0.07)) repeat(100) {
            assertFalse("noise sigma=$sigma trial=$it", detects(patch { _, _ ->
                (0.4 + rng.nextGaussian() * sigma).toFloat()
            }))
        }
    }
    @Test fun modelNoiseRaisesConfidenceFloorForWeakEdges() {
        assertTrue(detects(edge(0.03f)))
        assertFalse(detects(edge(0.03f), variance = 0.03f * 0.03f))
    }
    @Test fun isolatedHotAndDeadSamplesAreRejected() {
        for (v in listOf(0f, 0.9f, 1f)) {
            val p = patch { _, _ -> 0.2f }; p[8 * n + 8] = v
            assertFalse(detects(p))
        }
    }
    @Test fun patchLocalSupportKeepsThinSharpLines() {
        assertTrue(detects(patch { x, _ -> if (x == n / 2) 0.6f else 0.2f }))
    }
    @Test fun detectedPointStaysOnSharpEdge() {
        val index = FocusPeakingDetail.detect(edge(), BooleanArray(n * n))
        assertTrue(index >= 0)
        assertTrue(index % n in (n / 2 - 1)..(n / 2))
    }
    @Test fun nonfiniteInputAbstainsAndScratchDoesNotRetainOldDetections() {
        val scratch = FocusPeakingDetail.Scratch()
        assertTrue(FocusPeakingDetail.detect(edge(), BooleanArray(n * n), scratch = scratch) >= 0)
        val p = patch { _, _ -> 0.3f }
        assertEquals(-1, FocusPeakingDetail.detect(p, BooleanArray(n * n), scratch = scratch))
        p[100] = Float.NaN
        assertEquals(-1, FocusPeakingDetail.detect(p, BooleanArray(n * n)))
    }
}
