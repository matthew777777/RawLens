// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class RawSrAlignmentTest {
    @Test fun `SNR tuning grows tiles as signal gets weaker`() {
        assertEquals(32, RawSrAlignmentTuning.forSnr(1f).tileSize)
        assertEquals(16, RawSrAlignmentTuning.forSnr(4f).tileSize)
        assertEquals(8, RawSrAlignmentTuning.forSnr(16f).tileSize)
    }

    @Test fun `Bayer quad removes CFA modulation without phase swap`() {
        val raw = syntheticRaw(32, 24, BayerPattern.GRBG, 0f, 0f, 0f, 1)
        val gray = RawSrAlignment.bayerQuadGray(raw)
        assertEquals(16, gray.width)
        assertEquals(12, gray.height)
        assertTrue(gray.values.all(Float::isFinite))
        for (pattern in BayerPattern.entries) {
            val rgb = RawSrMergePrototype.demosaic(syntheticFlat(12, 10, pattern))
            val center = (5 * rgb.width + 6) * 3
            assertEquals(0.8f, rgb.values[center], 1e-5f)
            assertEquals(0.4f, rgb.values[center + 1], 1e-5f)
            assertEquals(0.2f, rgb.values[center + 2], 1e-5f)
        }
    }

    @Test fun `coarse to fine LK recovers subpixel displacement`() {
        val reference = syntheticRaw(128, 96, BayerPattern.RGGB, 0f, 0f, 0f, 2)
        val moving = syntheticRaw(128, 96, BayerPattern.RGGB, 1.4f, -0.8f, 0f, 3)
        val field = RawSrAlignment.align(
            RawSrAlignment.bayerQuadGray(reference), RawSrAlignment.bayerQuadGray(moving),
            RawSrAlignmentConfig(levels = 3, tileSize = 16, searchRadius = 3)
        )
        val interior = field.tiles.filter { it.reliable && it.centerX in 16f..48f && it.centerY in 12f..36f }
        assertTrue("expected reliable interior flow", interior.isNotEmpty())
        // Synthetic displacement is in RAW pixels; alignment operates in 2x2 Bayer-quad pixels.
        assertTrue(interior.map { abs(it.dx - 0.7f) }.average() < 0.25)
        assertTrue(interior.map { abs(it.dy + 0.4f) }.average() < 0.25)
    }

    @Test fun `one-x accumulation reduces static noise and keeps RGB ordering`() {
        val clean = syntheticRaw(96, 72, BayerPattern.BGGR, 0f, 0f, 0f, 10)
        val noisy = (0 until 8).map { index ->
            syntheticRaw(96, 72, BayerPattern.BGGR, 0f, 0f, 0.035f, 100 + index)
        }
        val referenceOnly = RawSrMergePrototype.merge(noisy, referenceOnly = true).image
        val merged = RawSrMergePrototype.merge(noisy,
            RawSrAlignmentConfig(levels = 3, tileSize = 12, searchRadius = 2)).image
        val target = RawSrMergePrototype.demosaic(clean)
        fun mse(image: RawSrRgbImage): Double {
            var sum = 0.0; var count = 0
            for (y in 4 until image.height - 4) for (x in 4 until image.width - 4) for (c in 0..2) {
                val d = image[x, y, c] - target[x, y, c]; sum += d * d; count++
            }
            return sum / count
        }
        assertTrue("merge should reduce noise", mse(merged) < mse(referenceOnly) * 0.55)
        val p = (36 * merged.width + 48) * 3
        assertTrue(merged.values[p] > merged.values[p + 1])
        assertTrue(merged.values[p + 1] > merged.values[p + 2])
    }

    private fun syntheticFlat(width: Int, height: Int, pattern: BayerPattern): UnpackedRawCfa {
        val values = FloatArray(width * height) { p ->
            when (pattern.colorAt(p % width, p / width)) {
                CfaColor.RED -> 0.8f; CfaColor.GREEN -> 0.4f; CfaColor.BLUE -> 0.2f
            }
        }
        return raw(width, height, pattern, values)
    }

    private fun syntheticRaw(width: Int, height: Int, pattern: BayerPattern,
                             shiftX: Float, shiftY: Float, noise: Float, seed: Int): UnpackedRawCfa {
        val random = Random(seed)
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shiftX; val sy = y - shiftY
            val texture = 0.12f * sin(sx * 0.31f) + 0.09f * sin(sy * 0.27f) +
                0.07f * sin((sx + sy) * 0.19f)
            val base = when (pattern.colorAt(x, y)) {
                CfaColor.RED -> 0.68f; CfaColor.GREEN -> 0.43f; CfaColor.BLUE -> 0.24f
            }
            values[y * width + x] = base + texture + (random.nextFloat() - 0.5f) * 2f * noise
        }
        return raw(width, height, pattern, values)
    }

    private fun raw(width: Int, height: Int, pattern: BayerPattern, values: FloatArray) =
        UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height))
}
