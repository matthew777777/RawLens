// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sensor→RGGB processing-space orientation (reference `cfa_to_rggb`):
 * pattern→flip map, mosaic flip, and flow-field remap. The remap is what
 * keeps non-RGGB sensors on the reference stride phase and pad end;
 * without it coarse near-tie winners flip and imprint the tile quilt.
 */
class RawSrCfaOrientationTest {
    @Test fun patternMapMatchesReference() {
        assertEquals(RawSrCfaOrientation.Flip.IDENTITY,
            RawSrCfaOrientation.forPattern(BayerPattern.RGGB))
        assertEquals(RawSrCfaOrientation.Flip.ROT180,
            RawSrCfaOrientation.forPattern(BayerPattern.BGGR))
        assertEquals(RawSrCfaOrientation.Flip.HFLIP,
            RawSrCfaOrientation.forPattern(BayerPattern.GRBG))
        assertEquals(RawSrCfaOrientation.Flip.VFLIP,
            RawSrCfaOrientation.forPattern(BayerPattern.GBRG))
    }

    @Test fun mosaicFlipIsExact() {
        // 4x3 values double as coordinates (10*y + x).
        val values = FloatArray(12) { i -> (10 * (i / 4) + (i % 4)).toFloat() }
        val vflip = RawSrCfaOrientation.toProcessingSpace(
            values, 4, 3, RawSrCfaOrientation.Flip.VFLIP)
        assertEquals(listOf(20f, 21f, 22f, 23f, 10f, 11f, 12f, 13f, 0f, 1f, 2f, 3f), vflip.toList())
        val hflip = RawSrCfaOrientation.toProcessingSpace(
            values, 4, 3, RawSrCfaOrientation.Flip.HFLIP)
        assertEquals(listOf(3f, 2f, 1f, 0f, 13f, 12f, 11f, 10f, 23f, 22f, 21f, 20f), hflip.toList())
        val rot = RawSrCfaOrientation.toProcessingSpace(
            values, 4, 3, RawSrCfaOrientation.Flip.ROT180)
        assertEquals(listOf(23f, 22f, 21f, 20f, 13f, 12f, 11f, 10f, 3f, 2f, 1f, 0f), rot.toList())
        // Identity returns the input (read-only fftGrey contract).
        assertSame(values, RawSrCfaOrientation.toProcessingSpace(
            values, 4, 3, RawSrCfaOrientation.Flip.IDENTITY))
        // Every flip is its own inverse.
        for (flip in RawSrCfaOrientation.Flip.values()) {
            if (flip == RawSrCfaOrientation.Flip.IDENTITY) continue
            val once = RawSrCfaOrientation.toProcessingSpace(values, 4, 3, flip)
            val twice = RawSrCfaOrientation.toProcessingSpace(once, 4, 3, flip)
            assertEquals("$flip", values.toList(), twice.toList())
        }
    }

    private fun field(): RawSrAlignmentField {
        // 3x2 tiles, ts 16: dx/dy/residual encode the tile index.
        val tiles = List(6) { i ->
            RawSrTileFlow(i.toFloat(), -i.toFloat(), 100f + i, 200f + i, 0.01f * i, i % 2 == 0)
        }
        return RawSrAlignmentField(48, 32, 16, 3, 2, tiles)
    }

    @Test fun fieldRemapMirrorsTilesAndSigns() {
        // VFLIP: sensor(tx,ty) <- proc(tx,1-ty), dy negated.
        val v = RawSrCfaOrientation.remapFieldToSensor(field(), RawSrCfaOrientation.Flip.VFLIP, 48, 32)
        // Sensor tile 0 (tx0,ty0) <- proc tile 3 (tx0,ty1): dx=103, dy=-203.
        assertEquals(103f, v.tiles[0].dx, 0f)
        assertEquals(-203f, v.tiles[0].dy, 0f)
        assertEquals(0.03f, v.tiles[0].residual, 0f)
        assertEquals(false, v.tiles[0].reliable)
        // Canonical sensor-space centers (not mirrored processing centers).
        assertEquals(8f, v.tiles[0].centerX, 0f)
        assertEquals(8f, v.tiles[0].centerY, 0f)
        // Sensor tile 5 (tx2,ty1) <- proc tile 2 (tx2,ty0).
        assertEquals(102f, v.tiles[5].dx, 0f)
        assertEquals(-202f, v.tiles[5].dy, 0f)
        assertEquals(40f, v.tiles[5].centerX, 0f)
        assertEquals(24f, v.tiles[5].centerY, 0f)
        assertEquals(48, v.imageWidth)
        assertEquals(32, v.imageHeight)
        // HFLIP: sensor(tx,ty) <- proc(2-tx,ty), dx negated.
        val h = RawSrCfaOrientation.remapFieldToSensor(field(), RawSrCfaOrientation.Flip.HFLIP, 48, 32)
        assertEquals(-102f, h.tiles[0].dx, 0f)
        assertEquals(202f, h.tiles[0].dy, 0f)
        assertEquals(-101f, h.tiles[1].dx, 0f)
        assertEquals(201f, h.tiles[1].dy, 0f)
        // ROT180: sensor(tx,ty) <- proc(2-tx,1-ty), both negated.
        val r = RawSrCfaOrientation.remapFieldToSensor(field(), RawSrCfaOrientation.Flip.ROT180, 48, 32)
        assertEquals(-105f, r.tiles[0].dx, 0f)
        assertEquals(-205f, r.tiles[0].dy, 0f)
        assertEquals(-100f, r.tiles[5].dx, 0f)
        assertEquals(-200f, r.tiles[5].dy, 0f)
        // Identity returns the field itself.
        val f = field()
        assertSame(f, RawSrCfaOrientation.remapFieldToSensor(f, RawSrCfaOrientation.Flip.IDENTITY, 48, 32))
    }

    @Test fun fieldRemapNonMultipleHeightUsesMajorityTile() {
        // H=20, ts=16 -> 2 tile rows (the 3060-row burst in
        // miniature). Sensor row 0 (bottom-up rows 0..15) vflips to
        // processing rows 4..19: 12 rows of processing tile 0, 4 of
        // tile 1 — the majority (tile 0) wins, NOT rows-1-ty, which
        // is off by one tile row whenever H is not a multiple of ts.
        val tiles = List(3 * 2) { i ->
            RawSrTileFlow(0f, 0f, 100f + i, 200f + i, 0f, true)
        }
        val field = RawSrAlignmentField(48, 32, 16, 3, 2, tiles)
        val v = RawSrCfaOrientation.remapFieldToSensor(field, RawSrCfaOrientation.Flip.VFLIP, 48, 20)
        // Sensor (tx0,ty0) <- proc tile 0, dy negated.
        assertEquals(100f, v.tiles[0].dx, 0f)
        assertEquals(-200f, v.tiles[0].dy, 0f)
        // Sensor (tx2,ty0) <- proc tile 2.
        assertEquals(102f, v.tiles[2].dx, 0f)
        assertEquals(-202f, v.tiles[2].dy, 0f)
        // Sensor (tx0,ty1: partial rows 16..19) <- proc tile 0 (rows 0..3).
        assertEquals(100f, v.tiles[3].dx, 0f)
        assertEquals(-200f, v.tiles[3].dy, 0f)
        // HFLIP on a divisible width stays the exact mirror.
        val h = RawSrCfaOrientation.remapFieldToSensor(field, RawSrCfaOrientation.Flip.HFLIP, 48, 20)
        assertEquals(-102f, h.tiles[0].dx, 0f)
        assertEquals(202f, h.tiles[0].dy, 0f)
    }

    @Test fun fieldRemapBurstGeometry() {
        // Exact burst lattice: 4080x3060, ts 16 -> 255x192. The blinds
        // pit quad sits in sensor tile (81,71) <- proc tile (81,119).
        val cols = 255
        val rows = 192
        val tiles = List(cols * rows) { i ->
            RawSrTileFlow(0f, 0f, i.toFloat(), 100000f + i, 0f, true)
        }
        val field = RawSrAlignmentField(4080, 3072, 16, cols, rows, tiles)
        val v = RawSrCfaOrientation.remapFieldToSensor(field, RawSrCfaOrientation.Flip.VFLIP, 4080, 3060)
        val src = 119 * cols + 81
        assertEquals(src.toFloat(), v.tiles[71 * cols + 81].dx, 0f)
        assertEquals(-(100000f + src), v.tiles[71 * cols + 81].dy, 0f)
        // Top partial sensor row (rows 3056..3059) <- proc tile row 0.
        assertEquals(81f, v.tiles[191 * cols + 81].dx, 0f)
        // ROT180 inherits the majority row with both signs negated.
        val r = RawSrCfaOrientation.remapFieldToSensor(field, RawSrCfaOrientation.Flip.ROT180, 4080, 3060)
        val rsrc = 119 * cols + (cols - 1 - 81)
        assertEquals(-rsrc.toFloat(), r.tiles[71 * cols + 81].dx, 0f)
        assertEquals(-(100000f + rsrc), r.tiles[71 * cols + 81].dy, 0f)
    }

    @Test fun fieldRemapIsInvolution() {
        for (flip in RawSrCfaOrientation.Flip.values()) {
            if (flip == RawSrCfaOrientation.Flip.IDENTITY) continue
            val f = field()
            val twice = RawSrCfaOrientation.remapFieldToSensor(
                RawSrCfaOrientation.remapFieldToSensor(f, flip, 48, 32), flip, 48, 32)
            for (i in f.tiles.indices) {
                assertEquals("$flip dx $i", f.tiles[i].dx, twice.tiles[i].dx, 0f)
                assertEquals("$flip dy $i", f.tiles[i].dy, twice.tiles[i].dy, 0f)
                assertEquals("$flip res $i", f.tiles[i].residual, twice.tiles[i].residual, 0f)
                assertEquals("$flip rel $i", f.tiles[i].reliable, twice.tiles[i].reliable)
            }
            assertTrue("$flip", twice.tiles.indices.all {
                twice.tiles[it].centerX == 8f + 16f * (it % 3) &&
                    twice.tiles[it].centerY == 8f + 16f * (it / 3)
            })
        }
    }
}
