// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/**
 * Hot-pixel pre-mask tests. Detection criteria encode docs/raw-sr-hotpixels.md:
 * bright-only 5x5 same-phase ring gate, never a fabricated flag without a
 * valid model, inpaint from unmasked ring taps only.
 */
class RawSrHotPixelTest {
    private val baseProfile = ImmutableDoubleValues(
        doubleArrayOf(0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0))

    private fun packed(
        codes: (sensorX: Int, sensorY: Int) -> Int,
        profile: ImmutableDoubleValues? = baseProfile
    ): RawSrPackedFrame {
        val layoutW = 34
        val layoutH = 26
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(sx, sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, 0, 0),
            RawCrop(0, 0, 32, 24), RawNormalization(BayerPattern.RGGB, listOf(64f, 64f, 64f, 64f), 4000f),
            null, profile)
    }

    private fun flat(): (Int, Int) -> Int = { _, _ -> 1500 }

    @Test fun flatFieldYieldsEmptyMask() {
        val mask = RawSrHotPixel.detectPacked(packed(flat()))
        assertEquals(32 * 24, mask.size)
        assertFalse(mask.any { it })
    }

    @Test fun nullProfileYieldsEmptyMask() {
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 3900 else 1500
        }, profile = null))
        assertFalse(mask.any { it })
    }

    @Test fun invalidProfileYieldsEmptyMask() {
        val bad = ImmutableDoubleValues(doubleArrayOf(-0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0))
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 3900 else 1500
        }, profile = bad))
        assertFalse(mask.any { it })
    }

    @Test fun singleHotTapFlaggedInEveryPhase() {
        // Gate at code ~1500: sigma = sqrt(0.02*1900+1) ~= 6.2, 6σ ~= 37;
        // absolute floor 0.02*4000 = 80 dominates, so +400 must fire.
        for ((hx, hy) in listOf(10 to 10, 11 to 10, 10 to 11, 11 to 11)) {
            val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
                if (sx == hx && sy == hy) 1900 else 1500
            }))
            assertTrue("hot tap at ($hx,$hy) missed", mask[hy * 32 + hx])
            val flagged = mask.count { it }
            assertEquals("hot tap at ($hx,$hy) smeared to $flagged taps", 1, flagged)
        }
    }

    @Test fun darkDipIsNeverFlagged() {
        // Shallow dips below the gate stay untouched (bright dot test's
        // mirror at these levels is photo texture, not a stuck tap).
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 1470 else 1500
        }))
        assertFalse(mask.any { it })
    }

    @Test fun singleDarkTapFlaggedInEveryPhase() {
        // Mirror of the bright-tap test: a −400-code dip clears both the
        // sigma arm and the absolute floor against ring mean and minimum.
        for ((hx, hy) in listOf(10 to 10, 11 to 10, 10 to 11, 11 to 11)) {
            val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
                if (sx == hx && sy == hy) 1100 else 1500
            }))
            assertTrue("dark tap at ($hx,$hy) missed", mask[hy * 32 + hx])
            val flagged = mask.count { it }
            assertEquals("dark tap at ($hx,$hy) smeared to $flagged taps", 1, flagged)
        }
    }

    @Test fun weakWarmTapFlaggedAtLowIsoFloor() {
        // At base 1500 the photon sigma (~5.6) leaves 6σ ≈ 34 below
        // the 1%-of-white floor (40): a +60-code warm tap — invisible to the
        // old 2% floor, lifted into visibility by tone mapping — must flag.
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 1560 else 1500
        }))
        assertTrue(mask[10 * 32 + 10])
        assertEquals(1, mask.count { it })
    }

    @Test fun darkClusterCenterKeepsOriginal() {
        // 5x5 dead block: the center's whole ring is masked, so there is no
        // trustworthy replacement — mirrors the bright-cluster rule.
        val w = 20
        val h = 20
        val samples = FloatArray(w * h) { 0.5f }
        val mask = BooleanArray(w * h)
        for (y in 8..12) for (x in 8..12) {
            samples[y * w + x] = 0.05f
            mask[y * w + x] = true
        }
        RawSrHotPixel.inpaintNormalized(samples, mask, w, h, BayerPattern.RGGB)
        assertEquals(0.05f, samples[10 * w + 10], 0f)
    }

    @Test fun subThresholdBumpIsNeverFlagged() {
        // +40 clears 6σ (~37) but not the absolute floor (80): floor governs.
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 1540 else 1500
        }))
        assertFalse(mask.any { it })
    }

    @Test fun exactCornerIsNeverFlagged() {
        // A corner tap sees only three ring taps (< MIN_RING_TAPS): the gate
        // stays silent rather than deciding on a biased neighbourhood.
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx == 0 && sy == 0) 3900 else 1500
        }))
        assertFalse(mask[0])
    }

    @Test fun uniformBrightBlockInteriorIsNeverFlagged() {
        // Interior taps of a step block are not local maxima: the maximum
        // arm keeps exposure/illumination steps out of the hot mask (only
        // the saturation rail may claim them near white).
        val mask = RawSrHotPixel.detectPacked(packed({ sx, sy ->
            if (sx in 8 until 24 && sy in 6 until 18) 1800 else 1500
        }))
        assertFalse(mask.any { it })
    }

    @Test fun photoTextureDoesNotFalseFire() {
        // Pixel-level ±50 texture routinely clears a mean-only gate; the
        // maximum arm must keep the mask empty here.
        val textured: (Int, Int) -> Int =
            { sx, sy -> 1500 + ((sx * 79 + sy * 43 + 7 * 131) % 101) }
        val mask = RawSrHotPixel.detectPacked(packed(textured))
        assertFalse(mask.any { it })
    }

    @Test fun inpaintReplacesSpikeWithRingMean() {
        val w = 16
        val h = 12
        val samples = FloatArray(w * h) { 0.5f }
        samples[6 * w + 6] = 0.9f
        val mask = BooleanArray(w * h)
        mask[6 * w + 6] = true
        val count = RawSrHotPixel.inpaintNormalized(samples, mask, w, h, BayerPattern.RGGB)
        assertEquals(1, count)
        assertEquals(0.5f, samples[6 * w + 6], 1e-6f)
    }

    @Test fun inpaintClusterCenterKeepsOriginal() {
        // 5x5 hot block: the center's whole ring is masked, so there is no
        // trustworthy replacement — the sample stands, visibly, instead of
        // being smeared from equally stuck neighbours.
        val w = 20
        val h = 20
        val samples = FloatArray(w * h) { 0.5f }
        val mask = BooleanArray(w * h)
        for (y in 8..12) for (x in 8..12) {
            samples[y * w + x] = 0.9f
            mask[y * w + x] = true
        }
        RawSrHotPixel.inpaintNormalized(samples, mask, w, h, BayerPattern.RGGB)
        assertEquals(0.9f, samples[10 * w + 10], 0f)
    }

    @Test fun inpaintNeverFeedsClusters() {
        // A 3x3 block: edge taps inpaint from the clean ring outside, but no
        // replacement may use another masked tap. Every inpainted value must
        // equal the flat background exactly.
        val w = 20
        val h = 20
        val samples = FloatArray(w * h) { 0.5f }
        val mask = BooleanArray(w * h)
        for (y in 9..11) for (x in 9..11) {
            samples[y * w + x] = 0.9f
            mask[y * w + x] = true
        }
        RawSrHotPixel.inpaintNormalized(samples, mask, w, h, BayerPattern.RGGB)
        for (y in 9..11) for (x in 9..11) {
            assertEquals("tap ($x,$y)", 0.5f, samples[y * w + x], 1e-6f)
        }
    }

    @Test fun quadHotProjectsAnyOfFour() {
        val mask = BooleanArray(8 * 6)
        mask[2 * 8 + 3] = true
        val hot = RawSrHotPixel.quadHot(mask, 8, 6)
        assertEquals(4 * 3, hot.size)
        assertTrue(hot[1 * 4 + 1])
        assertEquals(1, hot.count { it })
    }

    @Test fun guideRailsHotQuadWithHotFlag() {
        val frame = packed({ sx, sy -> if (sx == 10 && sy == 10) 1900 else 1500 })
        val guide = RawSrRobustness.linearGuide(frame)
        // Tap (10,10) sits in quad (5,5) of the 16x12 guide.
        val q = 5 * 16 + 5
        assertTrue(guide.rail[q])
        assertTrue(guide.hot[q])
        assertFalse(guide.hot[q + 1])
    }

    @Test fun evaluateZeroesHotQuadWithHotFlag() {
        val tuning = RawSrTuning.forSnr(18.0)
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val ref = RawSrRobustness.linearGuide(packed(flat()))
        val mov = RawSrRobustness.linearGuide(packed({ sx, sy ->
            if (sx == 10 && sy == 10) 1900 else 1500
        }))
        val flow = RawSrAlignmentField(16, 12, 16, 1, 1, listOf(
            RawSrTileFlow(8f, 6f, 0f, 0f, 0f, true)))
        val result = RawSrRobustness.evaluate(ref, mov, flow, tuning, config)
        val q = 5 * 16 + 5
        assertEquals(0f, result.r[q], 0f)
        assertTrue("flags=${result.flags[q]}",
            result.flags[q] and RawSrRobustness.FLAG_HOTPIXEL != 0)
        // Untouched quads still accept the static pair.
        assertEquals(1f, result.r[0], 0f)
        assertEquals(0, result.flags[0])
    }
}
