// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Checkout-port alignment contract (the desktop JamyAlignmentParityTest pins
 * stage-by-stage numeric parity against the actual reference code; this file
 * pins the app-facing behavior: schedules, recovery, determinism, and the
 * minimum viable image size).
 */
class RawSrAlignmentTest {
    private fun align(
        ref: UnpackedRawCfa, mov: UnpackedRawCfa,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig()
    ): RawSrAlignmentField {
        val refGray = RawSrAlignment.fftGrey(ref.values, ref.width, ref.height)
        val movGray = RawSrAlignment.fftGrey(mov.values, mov.width, mov.height)
        val refPyr = RawSrAlignment.pyramid(RawSrAlignment.circularPad(refGray, config.tileSize))
        val movPyr = RawSrAlignment.pyramid(movGray)
        return RawSrAlignment.alignPair(refPyr, movPyr, config)
    }

    @Test fun roundHalfAwayMatchesCudaSemantics() {
        // Reference utils.round_half_away: halves away from zero (2.5->3,
        // -2.5->-3), unlike rint (halves to even). Pins L1 seeds and Dogson
        // warp centers.
        assertEquals(3, RawSrAlignment.roundHalfAway(2.5))
        assertEquals(-3, RawSrAlignment.roundHalfAway(-2.5))
        assertEquals(2, RawSrAlignment.roundHalfAway(2.4))
        assertEquals(-2, RawSrAlignment.roundHalfAway(-2.4))
        assertEquals(3, RawSrAlignment.roundHalfAway(2.6))
        assertEquals(-3, RawSrAlignment.roundHalfAway(-2.6))
        assertEquals(4, RawSrAlignment.roundHalfAway(3.5))
        assertEquals(0, RawSrAlignment.roundHalfAway(0.0))
        assertEquals(0, RawSrAlignment.roundHalfAway(-0.0))
        assertEquals(1, RawSrAlignment.roundHalfAway(0.5))
        assertEquals(-1, RawSrAlignment.roundHalfAway(-0.5))
    }

    @Test fun gaussianPyramidPreservesDcAndAttenuatesAliases() {
        // Valid convolution shrinks each level past the nominal /2/4/4.
        val constant = RawSrGrayImage(512, 512, FloatArray(512 * 512) { 0.4f })
        val levels = RawSrAlignment.pyramid(constant)
        assertEquals(
            listOf(512 to 512, 252 to 252, 59 to 59, 10 to 10),
            levels.map { it.width to it.height })
        levels.forEach { level -> level.values.forEach { assertEquals(0.4f, it, 1e-6f) } }
        val stripes = RawSrGrayImage(512, 512, FloatArray(512 * 512) { if (it % 4 < 2) 1f else -1f })
        val reduced = RawSrAlignment.pyramid(stripes)[1]
        // Box decimation aliases period-four input at full amplitude; Gaussian attenuates it.
        for (y in 3 until reduced.height - 3) for (x in 3 until reduced.width - 3)
            assertTrue(abs(reduced[x, y]) < 0.4f)
    }

    @Test fun levelSchedulesAndValidationAreFixed() {
        val config = RawSrAlignmentConfig()
        assertEquals(listOf(1, 4, 4, 4), (0..3).map(config::radiusAt))
        assertEquals(listOf(1, 2, 4, 4), (0..3).map(config::factorAt))
        assertEquals(listOf(16, 16, 16, 8), (0..3).map(config::tileSizeAt))
        assertEquals(3, config.lkIterations)
        assertEquals(RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR, config.flowUpscale)
        assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(levels = 3) }
        assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(tileSize = 8) }
        assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(tileSize = 12) }
        assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(lkIterations = 6) }
        assertThrows(IllegalArgumentException::class.java) { RawSrAlignmentConfig(searchRadius = 7) }
    }

    @Test fun tinyImagesThrowLevelRequirement() {
        // L3 is 1/32 resolution with 8px tiles: below 256x256 the coarsest
        // level holds no tile and the port refuses instead of misaligning.
        val ref = aperiodicRaw(128, 96, 0f, 0f)
        val mov = aperiodicRaw(128, 96, 4f, 2f)
        assertThrows(IllegalArgumentException::class.java) { align(ref, mov) }
    }

    @Test fun staticIntegerSubpixelAndLargeMotionAreDeterministic() {
        // Capture range is reference-algorithm behavior (proven identical by
        // desktop parity), not port behavior: on synthetic texture the
        // coarse chain demonstrably captures through (12, -8) — L1's true
        // shift (6, -4) already exceeds its ±4 search, so the L2 seed is
        // load-bearing — while (20, -12) strands a third of the field at
        // zero-flow local minima (fringe zero-seeds plus ambiguous coarse
        // content). Median error there is 2e-4; the mean is outlier-driven.
        val cases = listOf(0f to 0f, 4f to 2f, -4f to -2f, 1.2f to -0.6f, 12f to -8f)
        for (pattern in BayerPattern.entries) for ((dx, dy) in cases) {
            val large = abs(dx) > 4f
            val width = if (large) 768 else 512
            val height = 512
            // Odd sensor/crop origin shifts the local pattern; FFT grey is
            // phase-blind, so recovery must not depend on it.
            val phase = pattern.shifted(1, 1)
            val a = aperiodicRaw(width, height, 0f, 0f, phase).copy(sensorCropLeft = 1, sensorCropTop = 1)
            val b = aperiodicRaw(width, height, dx, dy, phase).copy(sensorCropLeft = 1, sensorCropTop = 1)
            val config = RawSrAlignmentConfig()
            fun run() = align(a, b, config)
            val field = run()
            assertEquals(field, run())
            assertTrue(field.tiles.all { it.dx.isFinite() && it.dy.isFinite() && it.residual.isFinite() })
            val interior = field.tiles.filterIndexed { i, _ ->
                i % field.columns in 1 until field.columns - 1 && i / field.columns in 1 until field.rows - 1
            }
            val valid = interior.filter { it.reliable }
            assertTrue("$pattern $dx,$dy coverage ${valid.size}/${interior.size}", valid.size >= interior.size * 0.8)
            // Flows are raw pixels (Jamy-L convention), not quad pixels.
            // The mean is outlier-driven (a few tiles strand at zero-flow
            // local minima); the median pins the bulk lock tightly.
            val maeX = valid.map { abs(it.dx - dx) }.average()
            val maeY = valid.map { abs(it.dy - dy) }.average()
            val medX = valid.map { abs(it.dx - dx) }.sorted().let { it[it.size / 2] }
            val medY = valid.map { abs(it.dy - dy) }.sorted().let { it[it.size / 2] }
            val maeBound = if (large) 0.75 else 0.45
            assertTrue("$pattern $dx,$dy dx MAE $maeX", maeX < maeBound)
            assertTrue("$pattern $dx,$dy dy MAE $maeY", maeY < maeBound)
            assertTrue("$pattern $dx,$dy dx median $medX", medX < 0.05)
            assertTrue("$pattern $dx,$dy dy median $medY", medY < 0.05)
            if (dx == 0f && dy == 0f) assertTrue(valid.all { abs(it.dx) <= 0.05f && abs(it.dy) <= 0.05f })
        }
    }

    @Test fun degenerateInputsRejectWithFiniteDiagnostics() {
        val width = 512
        val height = 512
        val config = RawSrAlignmentConfig()
        val flat = RawSrGrayImage(width, height, FloatArray(width * height) { 0.4f })
        val stripe = RawSrGrayImage(width, height, FloatArray(width * height) { (it % width) * 0.002f })
        for (input in listOf(flat, stripe)) {
            val refPyr = RawSrAlignment.pyramid(RawSrAlignment.circularPad(input, config.tileSize))
            val flow = RawSrAlignment.alignPair(refPyr, RawSrAlignment.pyramid(input), config)
            // Zero-padded borders carry gradient energy, so only the
            // interior (no padding artifact) must reject a degenerate input.
            val interior = flow.tiles.filterIndexed { i, _ ->
                i % flow.columns in 1 until flow.columns - 1 &&
                    i / flow.columns in 1 until flow.rows - 1
            }
            assertTrue(interior.none { it.reliable })
            assertTrue(flow.tiles.all { it.dx.isFinite() && it.dy.isFinite() && it.residual.isFinite() })
        }
        // A single NaN tap (pixel (20, 0), tile (1, 0)): valid convolution
        // spreads it locally, never globally (this path bypasses FFT grey).
        // NaN must neither create trust (interior stays rejected like the
        // flat input) nor be trusted (the tainted tile rejects via a NaN
        // Hessian determinant or residual). Corner tiles still solve the
        // flat field trivially, as above.
        val nan = flat.copy(values = flat.values.copyOf().apply { this[20] = Float.NaN })
        val nanPyr = RawSrAlignment.pyramid(RawSrAlignment.circularPad(nan, config.tileSize))
        val nanFlow = RawSrAlignment.alignPair(nanPyr, RawSrAlignment.pyramid(nan), config)
        val nanInterior = nanFlow.tiles.filterIndexed { i, _ ->
            i % nanFlow.columns in 1 until nanFlow.columns - 1 &&
                i / nanFlow.columns in 1 until nanFlow.rows - 1
        }
        assertTrue(nanInterior.none { it.reliable })
        assertTrue(!nanFlow.tiles[1].reliable)
        // A global brightness shift is not motion: LK has no brightness
        // constancy to latch onto, so it must overwhelmingly reject.
        // Measured: 18/1024 tiles false-lock (LK runs away fully out of
        // bounds, mov zero-fills, and the dark top corner reads residual
        // ~0.08 — genuine checkout behavior, shared with the reference,
        // whose robustness stage rejects such tiles downstream via mov
        // OOB; only the auxiliary residual gate is fooled). The 5% bound
        // pins this with 3x margin: gate deletion would read 100%.
        val textured = RawSrAlignment.fftGrey(
            aperiodicRaw(512, 512, 0f, 0f).values, 512, 512)
        val changed = textured.copy(values = textured.values.map { it + 1.5f }.toFloatArray())
        val changedPyr = RawSrAlignment.pyramid(RawSrAlignment.circularPad(textured, config.tileSize))
        val changedFlow = RawSrAlignment.alignPair(changedPyr, RawSrAlignment.pyramid(changed), config)
        val changedRel = changedFlow.tiles.count { it.reliable }
        assertTrue(
            "brightness-shift reliable tiles: $changedRel",
            changedRel < changedFlow.tiles.size * 0.05)
    }

    @Test fun flowLookupDoesNotBlendAcrossDiscontinuities() {
        val a = RawSrTileFlow(3.5f, 3.5f, -4f, 0f, 0f, true)
        val b = a.copy(centerX = 11.5f, dx = 4f)
        val field = RawSrAlignmentField(16, 8, 8, 2, 1, listOf(a, b))
        assertEquals(-4f, field.flowAt(7.99f, 4f).dx, 0f)
        assertEquals(4f, field.flowAt(8f, 4f).dx, 0f)
    }

    @Test fun `SNR tuning grows tiles as signal gets weaker`() {
        assertEquals(64, RawSrTuning.forSnr(6.0).alignmentConfig().tileSize)
        assertEquals(32, RawSrTuning.forSnr(18.0).alignmentConfig().tileSize)
        assertEquals(16, RawSrTuning.forSnr(30.0).alignmentConfig().tileSize)
    }

    @Test fun `FFT grey removes CFA modulation`() {
        // Flat R/G/B mosaic (0.8/0.4/0.2): the (pi,pi) checkerboard is fully
        // masked, so every grey sample is the quad mean 0.45.
        val pattern = BayerPattern.GRBG
        val mosaic = FloatArray(256 * 256) { i ->
            when (pattern.colorAt(i % 256, i / 256)) {
                CfaColor.RED -> 0.8f; CfaColor.GREEN -> 0.4f; CfaColor.BLUE -> 0.2f
            }
        }
        val gray = RawSrAlignment.fftGrey(mosaic, 256, 256)
        assertEquals(256, gray.width)
        assertEquals(256, gray.height)
        assertTrue(gray.values.all(Float::isFinite))
        gray.values.forEach { assertEquals(0.45f, it, 0.01f) }
    }

    @Test fun `FFT grey demodulates at 12MP without heap planes`() {
        // Logcat 2026-10-04: fftGrey's two 99MB DoubleArrays OOMed a 512MB
        // heap (485MB footprint) and the linear merge saved nothing. The
        // complex planes now live off-heap; the same 4080x3060 shape must
        // complete with only the float mosaics on-heap, and still remove
        // the (pi,pi) CFA checkerboard down to the quad mean.
        val w = 4080
        val h = 3060
        val pattern = BayerPattern.GRBG
        val mosaic = FloatArray(w * h) { i ->
            when (pattern.colorAt(i % w, i / w)) {
                CfaColor.RED -> 0.8f; CfaColor.GREEN -> 0.4f; CfaColor.BLUE -> 0.2f
            }
        }
        val gray = RawSrAlignment.fftGrey(mosaic, w, h)
        assertEquals(w, gray.width)
        assertEquals(h, gray.height)
        var worst = 0f
        for (v in gray.values) {
            assertTrue(v.isFinite())
            worst = maxOf(worst, abs(v - 0.45f))
        }
        assertTrue("worst demodulation deviation $worst", worst < 0.01f)
    }

    @Test fun `coarse to fine LK recovers subpixel displacement`() {
        val reference = syntheticRaw(512, 512, BayerPattern.RGGB, 0f, 0f, 0f, 2)
        val moving = syntheticRaw(512, 512, BayerPattern.RGGB, 1.4f, -0.8f, 0f, 3)
        val field = align(reference, moving)
        val interior = field.tiles.filter { it.reliable && it.centerX in 128f..384f && it.centerY in 128f..384f }
        assertTrue("expected reliable interior flow", interior.isNotEmpty())
        // Synthetic displacement is in RAW pixels and so are the flows.
        assertTrue(interior.map { abs(it.dx - 1.4f) }.average() < 0.25)
        assertTrue(interior.map { abs(it.dy + 0.8f) }.average() < 0.25)
    }

    @Test fun `dense flow upsampling pins bilinear bicubic nearest and edges`() {
        // Prior 4x2 tiles: dx = column, dy = 2 * row.
        val values = DoubleArray(8 * 2) { i ->
            val tx = (i / 2) % 4
            val ty = (i / 2) / 4
            if (i % 2 == 0) tx.toDouble() else (2 * ty).toDouble()
        }
        val prior = RawSrAlignment.LevelFlow(4, 2, values)
        fun up(mode: RawSrAlignmentConfig.FlowUpscaleMode) =
            RawSrAlignment.upsampleFlow(prior, 8, 4, 2, 16, 16, mode).values
        val bilinear = up(RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR)
        // Tile (2, 1): sx=0.75 -> dx 0.75*2=1.5; sy=0.25 -> dy 0.5*2=1.0.
        assertEquals(1.5, bilinear[(1 * 8 + 2) * 2], 0.0)
        assertEquals(1.0, bilinear[(1 * 8 + 2) * 2 + 1], 0.0)
        val bicubic = up(RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC)
        // Interior tile (4, 2): Keys a=-0.75 does NOT reproduce linear ramps
        // (1.703125*2=3.40625, verified by hand); torch agrees (desktop
        // parity pins bicubic to 4e-6), so the port must match this exactly.
        assertEquals(3.40625, bicubic[(2 * 8 + 4) * 2], 1e-12)
        // Clamped edges: tile (0, 0) samples sx=sy=-0.25 -> taps clamp to
        // columns/rows (0,0,0,1): bilinear blends non-negative weights -> 0,
        // bicubic's negative lobe leaks tap 1 in -> -0.2109375 (torch agrees).
        assertEquals(0.0, bilinear[0], 0.0)
        assertEquals(-0.2109375, bicubic[0], 1e-12)
        assertTrue(bilinear.all(Double::isFinite) && bicubic.all(Double::isFinite))
        val nearest = up(RawSrAlignmentConfig.FlowUpscaleMode.NEAREST)
        // Tile (2, 1): tap(2/2, 1/2) = tile (1, 0) -> (1*2, 0*2).
        assertEquals(2.0, nearest[(1 * 8 + 2) * 2], 0.0)
        assertEquals(0.0, nearest[(1 * 8 + 2) * 2 + 1], 0.0)
        // NaN propagates like torch F.interpolate (no sanitization): parity
        // requires the same non-finite seeds, never invented zeros.
        val poisoned = RawSrAlignment.LevelFlow(4, 2, values.copyOf().apply { this[0] = Double.NaN })
        val seeds = RawSrAlignment.upsampleFlow(
            poisoned, 8, 4, 2, 16, 16, RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR).values
        assertTrue(seeds[0].isNaN())
    }

    @Test fun `bilinear upscale matches torch-order oracle`() {
        // Non-planar 2x2 prior (a-b-c+d = 1-3-5+11 = 4 != 0), so the
        // pinned values actually exercise the bilinear weights — a linear
        // ramp would reproduce under any convex weighting. Coordinates are
        // torch align_corners=False (sx = (tx+0.5)/repeat - 0.5, here
        // repeat = 2), taps edge-clamped, output scaled by factor = 2,
        // grid oversized by one column to pin F.pad zero-fill:
        //   dx = [[1, 3], [5, 11]], dy = 1 everywhere.
        // Expected dx (hand-derived from the reference formula, exact
        // dyadics, so 0-tolerance is honest):
        //   (0,0) corner clamps to a -> 1*2 = 2.0
        //   (1,1): lerp rows at fx=fy=0.25 -> 2.75*2 = 5.5
        //   (2,1): fx=0.75, fy=0.25 -> 4.25*2 = 8.5
        //   (2,2): fx=fy=0.75 -> 7.75*2 = 15.5
        //   (3,3) corner clamps to d -> 11*2 = 22.0
        val prior = RawSrAlignment.LevelFlow(
            2, 2, doubleArrayOf(1.0, 1.0, 3.0, 1.0, 5.0, 1.0, 11.0, 1.0))
        val got = RawSrAlignment.upsampleFlow(
            prior, 5, 4, 2, 16, 16, RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR).values
        fun dx(tx: Int, ty: Int) = got[(ty * 5 + tx) * 2]
        fun dy(tx: Int, ty: Int) = got[(ty * 5 + tx) * 2 + 1]
        assertEquals(2.0, dx(0, 0), 0.0)
        assertEquals(5.5, dx(1, 1), 0.0)
        assertEquals(8.5, dx(2, 1), 0.0)
        assertEquals(15.5, dx(2, 2), 0.0)
        assertEquals(22.0, dx(3, 3), 0.0)
        // Constant dy reproduces under any weighting, scaled by factor.
        for (ty in 0 until 4) for (tx in 0 until 4) assertEquals(2.0, dy(tx, ty), 0.0)
        // F.pad zero-fill past the 4-wide upsampled field.
        for (ty in 0 until 4) {
            assertEquals(0.0, dx(4, ty), 0.0)
            assertEquals(0.0, dy(4, ty), 0.0)
        }
        assertTrue(got.all(Double::isFinite))
    }

    @Test fun `upscale modes recover known shift`() {
        // Aperiodic value noise: periodic sinusoids admit wrong-period locks
        // that coarse-seeded propagation (both schemes) can follow.
        val reference = aperiodicRaw(512, 512, 0f, 0f)
        val moving = aperiodicRaw(512, 512, 4f, 2f)
        for (mode in RawSrAlignmentConfig.FlowUpscaleMode.entries) {
            val config = RawSrAlignmentConfig(flowUpscale = mode)
            val field = align(reference, moving, config)
            val interior = field.tiles.filter {
                it.reliable && it.centerX in 128f..384f && it.centerY in 128f..384f
            }
            assertTrue("expected reliable interior flow for $mode", interior.isNotEmpty())
            // Shift is +4/+2 RAW px; 0.45/axis is the contract limit.
            val maeX = interior.map { abs(it.dx - 4f) }.average()
            val maeY = interior.map { abs(it.dy - 2f) }.average()
            assertTrue("dx MAE $maeX for $mode", maeX < 0.45)
            assertTrue("dy MAE $maeY for $mode", maeY < 0.45)
        }
    }

    @Test fun `upscale modes agree on static images`() {
        val gray = aperiodicRaw(512, 512, 0f, 0f)
        val fields = RawSrAlignmentConfig.FlowUpscaleMode.entries.associateWith { mode ->
            align(gray, gray, RawSrAlignmentConfig(flowUpscale = mode))
        }
        val first = fields.values.first()
        for ((mode, field) in fields) {
            assertEquals(first.tiles.size, field.tiles.size)
            for (i in first.tiles.indices) {
                assertEquals("mode $mode tile $i dx", first.tiles[i].dx, field.tiles[i].dx, 0f)
                assertEquals("mode $mode tile $i dy", first.tiles[i].dy, field.tiles[i].dy, 0f)
                assertEquals("mode $mode tile $i reliable", first.tiles[i].reliable, field.tiles[i].reliable)
            }
        }
    }

    private fun aperiodicRaw(width: Int, height: Int, shiftX: Float, shiftY: Float,
                             pattern: BayerPattern = BayerPattern.RGGB): UnpackedRawCfa {
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shiftX; val sy = y - shiftY
            // Textured like the desktop parity fixture (proven to lock):
            // coarse blobs feed L3 (real scenes have strong low frequencies;
            // at most 3 cycles across — finer blobs hit Nyquist at L3 and
            // their apparent shift flips sign, poisoning the coarse lock),
            // value noise textures L0/L1/L2, and a sigmoid step gives every
            // level a strong local gradient to bite. Noise periods (5, 27px)
            // avoid near-integer ratios with every tested shift: a shift of
            // ~N periods is near-invisible to that component and tiles slip
            // to zero-flow local minima — fixture design, not a port bug.
            val texture = 0.20f * valueNoise(sx, sy, 5f) +
                0.12f * valueNoise(sx + 31f, sy - 17f, 27f) +
                0.16f * sin(6.2831853f * (3f * sx / width + 2f * sy / height) + 0.3f) +
                0.12f * sin(6.2831853f * (2f * sx / width + 3f * sy / height) + 1.7f) +
                (0.10f / (1f + kotlin.math.exp(-((sx - 0.62f * width) * 0.9f + (sy - 0.5f * height) * 0.35f)))) +
                0.16f * (sx / width + sy / height - 1f)
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

    @Test fun flowUpscaleDefaultsToBilinearLikeReference() {
        // Unified CPU/GPU default (reference AlignmentConfig.flow_upscale_mode
        // is bilinear): the GPU 1:1 port implements bilinear inter-level
        // propagation, so the retired NEAREST default (which existed only
        // because the legacy GPU path was nearest-only) is gone. NEAREST /
        // BICUBIC remain explicit opt-ins. A silent default flip moves both
        // CPU and GPU flows.
        assertEquals(
            RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR,
            RawSrAlignmentConfig().flowUpscale
        )
    }
}
