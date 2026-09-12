// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class RawSrAlignmentTest {
    @Test fun gaussianPyramidPreservesDcAndSuppressesAliases() {
        val constant = RawSrGrayImage(128, 96, FloatArray(128 * 96) { 0.4f })
        val levels = RawSrAlignment.pyramid(constant, 4)
        assertEquals(listOf(128 to 96, 64 to 48, 16 to 12), levels.map { it.width to it.height })
        levels.forEach { level -> level.values.forEach { assertEquals(0.4f, it, 1e-6f) } }
        val stripes = RawSrGrayImage(128, 96, FloatArray(128 * 96) { if (it % 4 < 2) 1f else -1f })
        val reduced = RawSrAlignment.pyramid(stripes, 2)[1]
        // Box decimation aliases period-four input at full amplitude; Gaussian attenuates it.
        for (y in 3 until reduced.height - 3) for (x in 3 until reduced.width - 3)
            assertTrue(abs(reduced[x, y]) < 0.4f)
        for (factor in listOf(2, 4)) {
            val weights = RawSrAlignment.gaussianWeights(factor)
            assertEquals(1f, weights.sum(), 1e-6f)
            assertTrue(weights.contentEquals(weights.reversedArray()))
        }
    }

    @Test fun finestOnlyThreeRefinementsAndLevelSchedulesAreFixed() {
        val config = RawSrAlignmentConfig()
        assertEquals(listOf(1, 4, 4, 4), (0..3).map(config::radiusAt))
        assertEquals(listOf(1, 2, 4, 4), (0..3).map(config::factorAt))
        assertEquals(3, config.lkIterations)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(lkIterations = 4) }
    }

    @Test fun staticIntegerSubpixelLargeMotionAndOddOriginsAreDeterministic() {
        val cases = listOf(0f to 0f, 4f to 2f, -4f to -2f, 1.2f to -0.6f, 20f to -12f)
        for (pattern in BayerPattern.entries) for ((dx, dy) in cases) {
            val large = abs(dx) > 4f
            val width = if (large) 384 else 128; val height = if (large) 288 else 96
            // Odd sensor/crop origin shifts the local pattern; the quad plane is phase-neutral.
            val phase = pattern.shifted(1, 1)
            val a = aperiodicRaw(width, height, 0f, 0f, phase).copy(sensorCropLeft = 1, sensorCropTop = 1)
            val b = aperiodicRaw(width, height, dx, dy, phase).copy(sensorCropLeft = 1, sensorCropTop = 1)
            val config = RawSrAlignmentConfig(levels = if (large) 4 else 3, tileSize = 8, searchRadius = 3)
            fun run() = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(a), RawSrAlignment.bayerQuadGray(b), config)
            val field = run()
            assertEquals(field, run())
            assertTrue(field.tiles.all { it.dx.isFinite() && it.dy.isFinite() && it.residual.isFinite() })
            val interior = field.tiles.filterIndexed { i, _ ->
                i % field.columns in 1 until field.columns - 1 && i / field.columns in 1 until field.rows - 1
            }
            val valid = interior.filter { it.reliable }
            assertTrue("$pattern $dx,$dy coverage ${valid.size}/${interior.size}", valid.size >= interior.size * 0.8)
            assertTrue(valid.map { abs(it.dx - dx / 2f) }.average() < 0.45)
            assertTrue(valid.map { abs(it.dy - dy / 2f) }.average() < 0.45)
            if (dx == 0f && dy == 0f) assertTrue(valid.all { abs(it.dx) <= 0.05f && abs(it.dy) <= 0.05f })
        }
    }

    @Test fun degenerateResidualAndInsufficientCornerSupportRejectWithFiniteDiagnostics() {
        val width = 65; val height = 49
        val config = RawSrAlignmentConfig(tileSize = 8, levels = 3, searchRadius = 3)
        val flat = RawSrGrayImage(width, height, FloatArray(width * height) { 0.4f })
        val stripe = RawSrGrayImage(width, height, FloatArray(width * height) { (it % width) * 0.002f })
        for (input in listOf(flat, stripe, flat.copy(values = flat.values.copyOf().apply { this[20] = Float.NaN }))) {
            val flow = RawSrAlignment.align(input, input, config)
            assertTrue(flow.tiles.none { it.reliable })
            assertTrue(flow.tiles.all { it.dx.isFinite() && it.dy.isFinite() && it.residual.isFinite() })
        }
        val textured = RawSrAlignment.bayerQuadGray(aperiodicRaw(130, 98, 0f, 0f))
        val static = RawSrAlignment.align(textured, textured, config)
        assertTrue(!static.tiles.last().reliable) // one-sample corner
        assertTrue(static.tiles.first().reliable) // supported top-left corner
        val changed = textured.copy(values = textured.values.map { it + 0.8f }.toFloatArray())
        assertTrue(RawSrAlignment.align(textured, changed, config).tiles.none { it.reliable })
    }

    @Test fun flowLookupDoesNotBlendAcrossDiscontinuities() {
        val a = RawSrTileFlow(3.5f, 3.5f, -4f, 0f, 0f, true)
        val b = a.copy(centerX = 11.5f, dx = 4f)
        val field = RawSrAlignmentField(16, 8, 8, 2, 1, listOf(a, b))
        assertEquals(-4f, field.flowAt(7.99f, 4f).dx, 0f)
        assertEquals(4f, field.flowAt(8f, 4f).dx, 0f)
    }

    @Test fun reverseConsistencyRejectsMismatchMissingSupportAndOutOfBounds() {
        val tile = RawSrTileFlow(3.5f, 3.5f, 1f, -1f, 0.01f, true)
        fun field(t: RawSrTileFlow) = RawSrAlignmentField(8, 8, 8, 1, 1, listOf(t))
        val config = RawSrAlignmentConfig()
        val reverse = tile.copy(dx = -1f, dy = 1f)
        fun accepted(a: RawSrTileFlow, b: RawSrTileFlow) =
            RawSrAlignment.checkConsistency(field(a), field(b), config).tiles.single().reliable
        assertTrue(accepted(tile, reverse))
        assertTrue(!accepted(tile, reverse.copy(dx = 1f)))
        assertTrue(!accepted(tile, reverse.copy(reliable = false)))
        assertTrue(!accepted(tile.copy(dx = 10f), reverse.copy(dx = -10f)))
    }

    @Test fun `SNR tuning grows tiles as signal gets weaker`() {
        assertEquals(32, RawSrTuning.forSnr(6.0).alignmentConfig().tileSize)
        assertEquals(16, RawSrTuning.forSnr(18.0).alignmentConfig().tileSize)
        assertEquals(8, RawSrTuning.forSnr(30.0).alignmentConfig().tileSize)
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

    private fun aperiodicRaw(width: Int, height: Int, shiftX: Float, shiftY: Float,
                             pattern: BayerPattern = BayerPattern.RGGB): UnpackedRawCfa {
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shiftX; val sy = y - shiftY
            val texture = 0.20f * valueNoise(sx, sy, 7f) +
                0.12f * valueNoise(sx + 31f, sy - 17f, 19f) +
                0.04f * (sx / width + sy / height - 1f)
            values[y * width + x] = when (pattern.colorAt(x, y)) {
                CfaColor.RED -> 0.68f; CfaColor.GREEN -> 0.43f; CfaColor.BLUE -> 0.24f
            } + texture
        }
        return UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height))
    }

    private fun valueNoise(x: Float, y: Float, scale: Float): Float {
        val gx = x / scale; val gy = y / scale
        val x0 = kotlin.math.floor(gx).toInt(); val y0 = kotlin.math.floor(gy).toInt()
        val fx = gx - x0; val fy = gy - y0
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val top = hash(x0, y0) * (1f - sx) + hash(x0 + 1, y0) * sx
        val bottom = hash(x0, y0 + 1) * (1f - sx) + hash(x0 + 1, y0 + 1) * sx
        return top * (1f - sy) + bottom * sy
    }

    private fun hash(x: Int, y: Int): Float {
        var bits = x * 0x1f123bb5 + y * 0x5f356495
        bits = (bits xor (bits ushr 15)) * 0x2c1b3c6d
        bits = bits xor (bits ushr 12)
        return ((bits ushr 8) and 0xffff) / 32767.5f - 1f
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
