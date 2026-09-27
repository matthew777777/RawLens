// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/**
 * Prompt 4C CPU tests. Criteria are declared in docs/raw-sr-robustness.md §10
 * before evaluation; thresholds below encode those criteria, not tuned scenes.
 */
class RawSrRobustnessTest {
    @Test fun cachedMotionEdgesMatchPerPixelIncludingBordersAndInvalidFlows() {
        val tiles = List(12) { i ->
            RawSrTileFlow(0f, 0f, if (i == 5) Float.NaN else (i % 3).toFloat(),
                if (i == 9) Float.POSITIVE_INFINITY else (i / 4).toFloat(), 0f, true)
        }
        val flow = RawSrAlignmentField(29, 21, 8, 4, 3, tiles)
        for (threshold in listOf(0f, 1f, 4f)) {
            val cached = RawSrRobustness.flowDisagreementTiles(flow, threshold)
            for (y in -2..24) for (x in -2..32) {
                val index = (y / 8).coerceIn(0, 2) * 4 + (x / 8).coerceIn(0, 3)
                assertEquals("($x,$y), threshold=$threshold",
                    RawSrRobustness.flowDisagrees(flow, x, y, threshold), cached[index])
            }
        }
    }
    private val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
    private val tuning = RawSrTuning.forSnr(18.0)
    private val baseProfile = ImmutableDoubleValues(
        doubleArrayOf(0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0))

    private fun packed(
        layoutW: Int = 34,
        layoutH: Int = 26,
        originX: Int = 0,
        originY: Int = 0,
        crop: RawCrop = RawCrop(0, 0, 32, 24),
        codes: (sensorX: Int, sensorY: Int) -> Int,
        sensorPattern: BayerPattern = BayerPattern.RGGB,
        profile: ImmutableDoubleValues? = baseProfile,
        lens: LensShadingModel? = null
    ): RawSrPackedFrame {
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(originX + sx, originY + sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, originX, originY),
            crop, RawNormalization(sensorPattern, listOf(64f, 64f, 64f, 64f), 4000f),
            lens, profile)
    }

    private fun manualFlow(width: Int, height: Int, reliable: Boolean, dx: Float, dy: Float,
                           residual: Float = 0f): RawSrAlignmentField {
        val tileSize = 8
        val columns = (width + tileSize - 1) / tileSize
        val rows = (height + tileSize - 1) / tileSize
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            RawSrTileFlow((tx * tileSize + tileSize / 2).toFloat(), (ty * tileSize + tileSize / 2).toFloat(),
                dx, dy, residual, reliable)
        }
        return RawSrAlignmentField(width, height, tileSize, columns, rows, tiles)
    }

    private fun textured(seed: Int): (Int, Int) -> Int =
        { sx, sy -> 1500 + ((sx * 79 + sy * 43 + seed * 131) % 101) }

    private fun acceptance(result: RawSrRobustness.FrameRobustness, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var accepted = 0
        var total = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            total++
            if (result.r[y * result.width + x] > 0f) accepted++
        }
        return accepted.toDouble() / total
    }

    @Test fun greenCoefficientsAreQuarterOfPhasePairs() {
        // The guide green channel is the MEAN of two samples, whose variance
        // is (v1+v2)/4: halving (mean of pair variances) overstates green
        // noise 2x and mottles chroma. 8-coefficient profile with distinct
        // green slopes (rasters 1 and 2 under RGGB at origin 0).
        val profile = ImmutableDoubleValues(
            doubleArrayOf(0.02, 1.0, 0.04, 2.0, 0.06, 3.0, 0.08, 4.0))
        val guide = RawSrRobustness.linearGuide(packed(codes = textured(7), profile = profile))
        val w = 4000.0 - 64.0
        val a1 = 0.04 / w
        val a2 = 0.06 / w
        assertEquals(((a1 + a2) * 0.25).toFloat(), guide.alpha[1], 1e-12f)
        val b1 = (0.04 * 64.0 + 2.0) / (w * w)
        val b2 = (0.06 * 64.0 + 3.0) / (w * w)
        assertEquals(((b1 + b2) * 0.25).toFloat(), guide.beta[1], 1e-18f)
        // R/B single-sample pairs pass through unscaled.
        assertEquals((0.02 / w).toFloat(), guide.alpha[0], 1e-12f)
        // Same reduction drives the GPU params (Float-sum vs Double-sum
        // rounding only).
        val params = RawSrRobustness.gpuParams(packed(codes = textured(7), profile = profile))
        assertEquals(guide.alpha[1], params.alpha[1], 1e-12f)
        assertEquals(guide.beta[1], params.beta[1], 1e-12f)
    }

    @Test fun shadowQuadsNeverRail() {
        // The rail diagnostic is highlight-side only: codes near black never
        // rail. A perfectly flat dark field reads r = 0 everywhere (the
        // reference-undefined 0/0 edge — zero distance over zero measured
        // variance — rejects on doubt), while dark texture with genuine
        // signal variance fuses at full weight with a clean mask.
        val dark = { _: Int, _: Int -> 66 }
        val ref = RawSrRobustness.linearGuide(packed(codes = dark))
        val mov = RawSrRobustness.linearGuide(packed(codes = dark))
        assertFalse(ref.rail.any { it })
        assertFalse(mov.rail.any { it })
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val flat = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        assertTrue(flat.r.all { it == 0f })
        assertTrue(flat.flags.all { it == 0 })
        val shadowTexture = { sx: Int, sy: Int -> 66 + ((sx * 79 + sy * 43) % 7) }
        val texturedRef = RawSrRobustness.linearGuide(packed(codes = shadowTexture))
        val texturedMov = RawSrRobustness.linearGuide(packed(codes = shadowTexture))
        assertFalse(texturedRef.rail.any { it })
        val fused = RawSrRobustness.evaluate(texturedRef, texturedMov, flow, tuning, config)
        assertTrue(fused.r.all { it == 1f })
        assertTrue(fused.flags.all { it == 0 })
    }

    @Test fun flowDisagreesNeedsDemonstratedDisagreement() {
        // 2x2 tiles of size 8 over a 16x12 quad grid; threshold 1.0.
        fun field(dx: (tx: Int, ty: Int) -> Float): RawSrAlignmentField {
            val tiles = List(4) { i ->
                RawSrTileFlow(0f, 0f, dx(i % 2, i / 2), 0f, 0f, true)
            }
            return RawSrAlignmentField(16, 12, 8, 2, 2, tiles)
        }
        // Uniform flow agrees everywhere.
        val calm = field { _, _ -> 0f }
        assertFalse(RawSrRobustness.flowDisagrees(calm, 5, 5, 1f))
        // A 5-quad split across columns disagrees (mirrors the merge gate).
        val split = field { tx, _ -> if (tx == 0) 0f else 5f }
        assertTrue(RawSrRobustness.flowDisagrees(split, 5, 5, 1f))
        // Sub-threshold jitter agrees.
        val jitter = field { tx, _ -> if (tx == 0) 0f else 0.5f }
        assertFalse(RawSrRobustness.flowDisagrees(jitter, 5, 5, 1f))
        // A non-finite neighbour is missing data, not motion: the finite
        // pair agrees, so no veto even though spread is unmeasurable.
        val holes = RawSrAlignmentField(16, 12, 8, 2, 2, listOf(
            RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true),
            RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0f, false),
            RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true),
            RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0f, false)))
        assertFalse(RawSrRobustness.flowDisagrees(holes, 5, 5, 1f))
        // …but demonstrated disagreement among the finite subset still vetoes.
        val mixed = RawSrAlignmentField(16, 12, 8, 2, 2, listOf(
            RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true),
            RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0f, false),
            RawSrTileFlow(0f, 0f, 5f, 0f, 0f, true),
            RawSrTileFlow(0f, 0f, 5f, 0f, 0f, true)))
        assertTrue(RawSrRobustness.flowDisagrees(mixed, 5, 5, 1f))
        // A single finite tile cannot disagree with itself.
        val solo = RawSrAlignmentField(16, 12, 16, 1, 1, listOf(
            RawSrTileFlow(0f, 0f, 3f, 0f, 0f, true)))
        assertFalse(RawSrRobustness.flowDisagrees(solo, 5, 5, 1f))
        // flowIrregular skips non-finite tiles as missing data (reference
        // block matching yields finite flows by construction, so this edge
        // is reference-undefined; the skip matches the merge shader): the
        // agreeing finite pair reads regular.
        assertFalse(RawSrRobustness.flowIrregular(holes, 5, 5, 1f))
        assertFalse(RawSrRobustness.flowIrregular(calm, 5, 5, 1f))
    }

    @Test fun unreliableTilesDoNotPoisonSpread() {
        // Wild flow on unreliable tiles is garbage, not motion: a reliable
        // quad surrounded by it must still fuse under s2 when content
        // agrees — otherwise every night scene paints s1 halos around each
        // unreliable tile and static regions never average.
        val ref = RawSrRobustness.linearGuide(packed(codes = { _, _ -> 1600 }))
        val mov = RawSrRobustness.linearGuide(packed(codes = { _, _ -> 1600 }))
        val tiles = List(4) { i ->
            val tx = i % 2
            val ty = i / 2
            if (tx == 1 && ty == 1) RawSrTileFlow(0f, 0f, 9f, -7f, 0f, false)
            else RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(16, 12, 8, 2, 2, tiles)
        assertFalse(RawSrRobustness.flowDisagrees(flow, 5, 5, 1f))
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        // Quad (5,5) sits in tile (0,0), reliable, agreeing: full weight,
        // no motion-irregular bit (its window's wild tile is excluded).
        val o = 5 * 16 + 5
        assertEquals(1f, result.r[o], 0f)
        assertEquals(0, result.flags[o] and RawSrRobustness.FLAG_MOTION_IRREGULAR)
        assertEquals(0, result.flags[o])
    }

    @Test fun staticNoisyPairRetainedAcrossBrightnessLevels() {
        for (base in listOf(300, 1500, 3000)) {
            fun noisy(seed: Int): (Int, Int) -> Int {
                val sigma = sqrt(0.02 * base + 1.0)
                return { sx, sy ->
                    val hash = (((sx * 73856093) xor (sy * 19349663) xor seed) and 0x7fffffff) % 1001 / 1000.0 * 2.0 - 1.0
                    base + (hash * 1.73 * sigma).toInt()
                }
            }
            val ref = RawSrRobustness.linearGuide(packed(codes = noisy(7)))
            val mov = RawSrRobustness.linearGuide(packed(codes = noisy(99)))
            val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
            val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
            assertTrue(result.r.all { it.isFinite() })
            // 16x12 guide; 5x5 minimum spreads border effects, so judge interior.
            val rate = acceptance(result, 3, 13, 3, 9)
            assertTrue("base=$base retention=$rate", rate >= 0.95)
        }
    }

    @Test fun reliabilityIgnoredAcceptanceIsPurelyPhotometric() {
        // The reference defines no reliability gate: the flow's reliable bit
        // is ignored and acceptance follows the photo term alone, with no
        // static-hypothesis or unreliable-flow flags (the base path reports
        // out-of-bounds and invalid flow only).
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val flow = manualFlow(16, 12, reliable = false, dx = 0f, dy = 0f)
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        assertTrue(acceptance(result, 3, 13, 3, 9) >= 0.95)
        assertTrue(result.flags.all { it == 0 })
        // Same unreliable flow, falsified content: uniform +400-code mean
        // shift rejects (re-shuffled stationary texture would still agree).
        val other = RawSrRobustness.linearGuide(packed(codes = { sx, sy -> textured(7)(sx, sy) + 400 }))
        val rejected = RawSrRobustness.evaluate(ref, other, flow, tuning, config)
        assertTrue(acceptance(rejected, 3, 13, 3, 9) <= 0.05)
        assertTrue(rejected.flags.all { it == 0 })
    }

    @Test fun translatedForegroundRejectedStaticKept() {
        // Left half covered by a uniform bright foreground block (mean-level
        // conflict), right half identical; zero flow everywhere. A re-shuffled
        // stationary texture would still agree statistically, so the foreground
        // must differ in local mean.
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = { sx, sy ->
            if (sx < 16) 2500 else textured(7)(sx, sy)
        }))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        // The 3x3 statistics window touches the foreground one quad out and the
        // 5x5 minimum spreads two more: judge clear of x=8 by four quads.
        val rejectedHalf = 1.0 - acceptance(result, 1, 6, 3, 9)
        val keptHalf = acceptance(result, 12, 15, 3, 9)
        assertTrue("shifted rejection=$rejectedHalf", rejectedHalf >= 0.90)
        assertTrue("static retention=$keptHalf", keptHalf >= 0.95)
    }

    private fun constLut(sigma: Float, d: Float, bins: Int = 8) = RawSrNoiseLut.Lut(
        bins = bins, trials = 800, seed = 0,
        alpha = DoubleArray(4), beta = DoubleArray(4),
        sigmaSq = FloatArray(bins) { sigma }, dSq = FloatArray(bins) { d },
        sigmaSem = FloatArray(bins), dSem = FloatArray(bins),
        counts = LongArray(bins))

    @Test fun noiseLutShrinkRecoversConflict() {
        // Mild mean-level conflict (foreground 1800 vs texture ~1550): the
        // analytic path rejects, a LUT distance of 1 shrinks d² away and accepts.
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = { sx, sy ->
            if (sx < 16) 1800 else textured(7)(sx, sy)
        }))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val analytic = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        assertTrue(1.0 - acceptance(analytic, 1, 6, 3, 9) >= 0.90)
        val corrected = RawSrRobustness.evaluate(ref, mov, flow, tuning, config, constLut(0f, 1f))
        assertTrue(acceptance(corrected, 1, 6, 3, 9) >= 0.95)
        for (y in 3 until 9) for (x in 1 until 6)
            assertEquals(0, corrected.flags[y * 16 + x] and RawSrRobustness.FLAG_PHOTO_CONFLICT)
    }

    @Test fun noiseLutSigmaFloorRecoversConflict() {
        // Same conflict: flooring sigma² at 0.01 absorbs the disagreement.
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = { sx, sy ->
            if (sx < 16) 1800 else textured(7)(sx, sy)
        }))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val corrected = RawSrRobustness.evaluate(ref, mov, flow, tuning, config, constLut(0.01f, 0f))
        assertTrue(acceptance(corrected, 1, 6, 3, 9) >= 0.95)
    }

    @Test fun zeroLutIsBitExactNoOp() {
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = textured(9)))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val analytic = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        val withLut = RawSrRobustness.evaluate(ref, mov, flow, tuning, config, constLut(0f, 0f))
        assertArrayEquals(analytic.r, withLut.r, 0f)
        assertArrayEquals(analytic.flags, withLut.flags)
    }

    @Test fun missingModelLeavesVerdictUnchangedAndLutApplies() {
        // The noise profile feeds diagnostics only: the photo term runs over
        // measured guide variance either way, so a missing model changes
        // neither the analytic verdict nor the LUT-corrected one (bitwise),
        // and no model-missing bit is set (the base path reports
        // out-of-bounds and invalid flow only).
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val movValid = RawSrRobustness.linearGuide(packed(codes = textured(9)))
        val movMissing = RawSrRobustness.linearGuide(packed(codes = textured(9), profile = null))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val lut = constLut(1f, 1f)
        val analyticValid = RawSrRobustness.evaluate(ref, movValid, flow, tuning, config)
        val analyticMissing = RawSrRobustness.evaluate(ref, movMissing, flow, tuning, config)
        assertArrayEquals(analyticValid.r, analyticMissing.r, 0f)
        assertArrayEquals(analyticValid.flags, analyticMissing.flags)
        val lutValid = RawSrRobustness.evaluate(ref, movValid, flow, tuning, config, lut)
        val lutMissing = RawSrRobustness.evaluate(ref, movMissing, flow, tuning, config, lut)
        assertArrayEquals(lutValid.r, lutMissing.r, 0f)
        assertArrayEquals(lutValid.flags, lutMissing.flags)
        assertTrue(analyticMissing.flags.all { it == 0 })
    }

    @Test fun exposureMismatchRejectedAsBackstop() {
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        // 1.5x exposure stays below white: pure photometric conflict, no rails.
        val mov = RawSrRobustness.linearGuide(packed(codes = { sx, sy -> (textured(7)(sx, sy) * 1.5).toInt() }))
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        val rejected = 1.0 - acceptance(result, 3, 13, 3, 9)
        assertTrue("exposure rejection=$rejected", rejected >= 0.70)
    }

    @Test fun saturatedBlockRejectedByPhotoTermWithoutFlag() {
        // S=0.02 at white 4000: sigma ~= 9, rail diagnostic at 3973. The
        // reference defines no saturation gate: the clipped block rejects
        // through the photo term alone (huge color distance over texture
        // variance), with no saturated bit set. The below-rail block rejects
        // identically — the photo term is rail-independent.
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(
            codes = { sx, sy -> if (sx in 8 until 24 && sy in 6 until 18) 3995 else textured(7)(sx, sy) }))
        assertTrue(mov.rail.any { it })
        val flow = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        // Saturated raw block maps to quads x in 4..11, y in 3..8; judge interior.
        for (y in 4..7) for (x in 5..10) {
            val o = y * 16 + x
            assertEquals(0f, result.r[o], 0f)
            assertEquals(0, result.flags[o] and RawSrRobustness.FLAG_SATURATED)
        }
        // Below the rail gate (3900 < 3973): still a photo conflict, still
        // rejected, still no saturation flag.
        val below = RawSrRobustness.linearGuide(packed(
            codes = { sx, sy -> if (sx in 8 until 24 && sy in 6 until 18) 3900 else textured(7)(sx, sy) }))
        val belowResult = RawSrRobustness.evaluate(ref, below, flow, tuning, config)
        for (y in 4..7) for (x in 5..10) {
            val o = y * 16 + x
            assertEquals(0f, belowResult.r[o], 0f)
            assertEquals(0, belowResult.flags[o] and RawSrRobustness.FLAG_SATURATED)
        }
    }

    @Test fun flowScaleDiscriminatesIdenticalDisagreement() {
        // Quad-block texture (uniform local variance: 2x2 code blocks
        // alternate 1600/1604, so every interior 3x3 guide window holds a
        // 5/4 split) plus a uniform +4-code offset: d²/σ² ~= 4.0
        // everywhere, inside the (2.8, 4.6) window where irregular flow
        // (s1 = 2) rejects and smooth flow (s2 = 12) accepts. No
        // motion-irregular bit exists (the base path reports out-of-bounds
        // and invalid flow only): both masks stay clean.
        val blocks = { sx: Int, sy: Int -> 1600 + ((sx / 2 + sy / 2) % 2) * 4 }
        val ref = RawSrRobustness.linearGuide(packed(codes = blocks))
        val mov = RawSrRobustness.linearGuide(packed(codes = { sx, sy -> blocks(sx, sy) + 4 }))
        val smooth = manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f)
        val accepted = RawSrRobustness.evaluate(ref, mov, smooth, tuning, config)
        assertTrue(acceptance(accepted, 3, 13, 3, 9) >= 0.95)
        assertTrue(accepted.flags.all { it == 0 })
        val columns = 2
        val rows = 2
        val tiles = List(columns * rows) { i ->
            // Even shift (0/2): the warp preserves block phase, so the
            // disagreement stays the calibrated offset (an odd shift would
            // flip blocks against the offset and cancel it in places).
            val raggedDx = if ((i % columns + i / columns) % 2 == 0) 0f else 2f
            RawSrTileFlow(4f, 4f, raggedDx, 0f, 0f, true)
        }
        val ragged = RawSrAlignmentField(16, 12, 8, columns, rows, tiles)
        val rejected = RawSrRobustness.evaluate(ref, mov, ragged, tuning, config)
        assertTrue(acceptance(rejected, 3, 13, 3, 9) <= 0.05)
        // Interior mask stays clean (no motion-irregular bit exists); the
        // +2 shift legitimately pushes right-edge quads out of bounds.
        for (y in 3 until 9) for (x in 3 until 13)
            assertEquals(0, rejected.flags[y * 16 + x])
    }

    @Test fun outOfBoundsInvalidAndResidualGates() {
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val oob = RawSrRobustness.evaluate(ref, mov,
            manualFlow(16, 12, reliable = true, dx = 100f, dy = 0f), tuning, config)
        for (y in 0 until 12) for (x in 0 until 16) {
            val o = y * 16 + x
            assertEquals(0f, oob.r[o], 0f)
            assertTrue(oob.flags[o] and RawSrRobustness.FLAG_OUT_OF_BOUNDS != 0)
        }
        val poisoned = RawSrAlignmentField(16, 12, 8, 2, 2, List(4) { i ->
            RawSrTileFlow(4f, 4f,
                if (i == 0) Float.NaN else 0f, if (i == 1) Float.POSITIVE_INFINITY else 0f, 0f, i >= 2)
        })
        val invalid = RawSrRobustness.evaluate(ref, mov, poisoned, tuning, config)
        assertTrue(invalid.r.all { it.isFinite() })
        // Tiles 0,1 (top half) carry NaN/Inf flow; tiles 2,3 (bottom half) are
        // valid zero flow on identical frames. The 5x5 minimum spreads rejection
        // two rows down, so judge away from the boundary.
        for (y in 0 until 4) for (x in 0 until 16) {
            val o = y * 16 + x
            assertEquals(0f, invalid.r[o], 0f)
            assertTrue(invalid.flags[o] and RawSrRobustness.FLAG_INVALID_FLOW != 0)
        }
        for (y in 10 until 12) for (x in 0 until 16) {
            val o = y * 16 + x
            assertTrue(invalid.r[o] > 0f)
            assertEquals(0, invalid.flags[o] and RawSrRobustness.FLAG_INVALID_FLOW)
        }
        // The reference defines no residual gate: a huge residual changes
        // nothing — the run equals the zero-residual run bitwise.
        val residual = RawSrRobustness.evaluate(ref, mov,
            manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f, residual = 10f), tuning, config)
        val clean = RawSrRobustness.evaluate(ref, mov,
            manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f), tuning, config)
        assertArrayEquals(clean.r, residual.r, 0f)
        assertArrayEquals(clean.flags, residual.flags)
    }

    @Test fun lensPresenceLeavesDecisionsUnchanged() {
        // Shading-invariant by construction: statistics never see gains.
        val lens = LensShadingModel(2, 2, FloatArray(16) { 2f }, IntRectSnapshot(0, 0, 34, 26), false)
        val plain = RawSrRobustness.evaluate(
            RawSrRobustness.linearGuide(packed(codes = textured(7))),
            RawSrRobustness.linearGuide(packed(codes = textured(99))),
            manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f), tuning, config)
        val shaded = RawSrRobustness.evaluate(
            RawSrRobustness.linearGuide(packed(codes = textured(7), lens = lens)),
            RawSrRobustness.linearGuide(packed(codes = textured(99), lens = lens)),
            manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f), tuning, config)
        assertArrayEquals(plain.r, shaded.r, 0f)
        assertArrayEquals(plain.flags, shaded.flags)
    }

    @Test fun patternsOriginsAndSixCoefficientsRetainStaticScenes() {
        val six = ImmutableDoubleValues(doubleArrayOf(0.02, 0.002, 0.01, 0.001, 0.03, 0.003))
        var cases = 0
        for (pattern in BayerPattern.entries) {
            for ((ox, oy) in listOf(0 to 0, 1 to 0)) {
                val ref = RawSrRobustness.linearGuide(packed(originX = ox, originY = oy,
                    codes = textured(7), sensorPattern = pattern, profile = six))
                val mov = RawSrRobustness.linearGuide(packed(originX = ox, originY = oy,
                    codes = textured(99), sensorPattern = pattern, profile = six))
                val result = RawSrRobustness.evaluate(ref, mov,
                    manualFlow(16, 12, reliable = true, dx = 0f, dy = 0f), tuning, config)
                assertTrue(result.r.all { it.isFinite() })
                val rate = acceptance(result, 3, 13, 3, 9)
                assertTrue("$pattern ($ox,$oy) retention=$rate", rate >= 0.95)
                cases++
            }
        }
        assertEquals(8, cases)
    }

    @Test fun accumulationIsExactAndRepeatable() {
        val frame = RawSrRobustness.FrameRobustness(4, 3,
            FloatArray(12) { (it + 1) * 0.05f }, IntArray(12))
        val once = RawSrRobustness.accumulate(null, frame)
        assertArrayEquals(FloatArray(12) { (it + 1) * 0.05f }, once.values, 0f)
        val twice = RawSrRobustness.accumulate(once, frame)
        assertArrayEquals(FloatArray(12) { (it + 1) * 0.1f }, twice.values, 1e-6f)
        // Determinism of the full evaluation.
        val ref = RawSrRobustness.linearGuide(packed(codes = textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(codes = textured(99)))
        val flow = manualFlow(16, 12, reliable = true, dx = 1f, dy = -1f)
        val first = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        val second = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        assertArrayEquals(first.r, second.r, 0f)
        assertArrayEquals(first.flags, second.flags)
    }

    @Test fun motionThresholdUsesQuadUnits() {
        assertEquals(0.4f, RawSrRobustness.motionThresholdQuad(RawSrTuning.forSnr(18.0)), 0f)
    }

    @Test fun flowIrregularityMatchesDocumentedGate() {
        val smooth = manualFlow(16, 12, reliable = true, dx = 1f, dy = 0f)
        assertEquals(false, RawSrRobustness.flowIrregular(smooth, 8, 6, 0.4f))
        val columns = 2
        val rows = 2
        val ragged = RawSrAlignmentField(16, 12, 8, columns, rows, List(columns * rows) { i ->
            RawSrTileFlow(4f, 4f, if (i == 0) 3f else 0f, 0f, 0f, true)
        })
        assertEquals(true, RawSrRobustness.flowIrregular(ragged, 8, 6, 0.4f))
        // Single-tile fields cannot spread: smooth by construction.
        val single = RawSrAlignmentField(16, 12, 16, 1, 1,
            listOf(RawSrTileFlow(8f, 6f, 5f, -2f, 0f, true)))
        assertEquals(false, RawSrRobustness.flowIrregular(single, 8, 6, 0.4f))
    }

    @Test fun linearGuideChannelsMapAnyPattern() {
        // One quad: R/G/B values must come from the pattern's own samples,
        // square-rooted per reference Alg. 7 (guide of normalized linear).
        // Green takes the root of the two-tap mean, not the mean of roots.
        val frame = packed(layoutW = 2, layoutH = 2, crop = RawCrop(0, 0, 2, 2),
            codes = { sx, sy -> 1000 + ((sy and 1) shl 1 or (sx and 1)) * 100 },
            sensorPattern = BayerPattern.GRBG)
        val guide = RawSrRobustness.linearGuide(frame)
        // GRBG quad: (0,0)=G:1000, (1,0)=R:1100, (0,1)=B:1200, (1,1)=G:1300.
        val scale = 1f / (4000f - 64f)
        assertEquals(sqrt(((1100f - 64f) * scale).toDouble()).toFloat(), guide.red[0], 1e-6f)
        assertEquals(sqrt((((1000f - 64f) + (1300f - 64f)) * 0.5f * scale).toDouble()).toFloat(),
            guide.green[0], 1e-6f)
        assertEquals(sqrt(((1200f - 64f) * scale).toDouble()).toFloat(), guide.blue[0], 1e-6f)
        assertTrue(guide.modelValid)
    }
}
