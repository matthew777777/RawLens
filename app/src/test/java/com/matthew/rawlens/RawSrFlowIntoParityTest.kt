// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [RawSrAlignmentField.flowAtSmoothInto] bitwise-identical to
 * [RawSrAlignmentField.flowAtSmooth], and
 * [RawSrAlignmentField.flowAtNearestInto] bitwise-identical to
 * [RawSrAlignmentField.flowAt], on both tile backings: boxed lists and
 * flat [RawSrDirectFlowTiles]. Covers interior, edges, non-finite
 * fallback, and mixed reliability.
 */
class RawSrFlowIntoParityTest {
    @Test fun intoMatchesSmoothOnBoxedTiles() {
        val rnd = java.util.Random(1234)
        val cols = 9
        val rows = 7
        val tileSize = 4
        val tiles = List(cols * rows) {
            // Include non-finite flows to exercise the fallback path.
            val kind = rnd.nextInt(12)
            val dx = if (kind == 0) Float.NaN else rnd.nextFloat() * 4f - 2f
            val dy = if (kind == 1) Float.POSITIVE_INFINITY else rnd.nextFloat() * 4f - 2f
            RawSrTileFlow(0f, 0f, dx, dy, rnd.nextFloat(), rnd.nextBoolean())
        }
        val field = RawSrAlignmentField(cols * tileSize, rows * tileSize, tileSize, cols, rows, tiles)
        assertParity(field, cols * tileSize, rows * tileSize)
    }

    @Test fun intoMatchesSmoothOnDirectTiles() {
        val rnd = java.util.Random(987)
        val quadsW = 11
        val quadsH = 6
        val n = quadsW * quadsH
        val dx = FloatArray(n) { rnd.nextFloat() * 4f - 2f }
        val dy = FloatArray(n) { rnd.nextFloat() * 4f - 2f }
        val residual = FloatArray(n) { rnd.nextFloat() }
        val reliable = BooleanArray(n) { rnd.nextBoolean() }
        dx[0] = Float.NaN
        dy[1] = Float.NEGATIVE_INFINITY
        val tiles = RawSrMergeJob.QuadFlowTiles(quadsW, dx, dy, residual, reliable)
        val field = RawSrAlignmentField(quadsW, quadsH, 1, quadsW, quadsH, tiles)
        assertParity(field, quadsW, quadsH)
    }

    private fun assertParity(field: RawSrAlignmentField, width: Int, height: Int) {
        val out = FloatArray(4)
        for (y in 0 until height) for (x in 0 until width) {
            val expected = field.flowAtSmooth(x.toFloat(), y.toFloat())
            field.flowAtSmoothInto(x.toFloat(), y.toFloat(), out)
            assertEquals("dx at $x,$y", expected.dx.toBits(), out[0].toBits())
            assertEquals("dy at $x,$y", expected.dy.toBits(), out[1].toBits())
            assertEquals("residual at $x,$y", expected.residual.toBits(), out[2].toBits())
            assertEquals(
                "reliable at $x,$y",
                (if (expected.reliable) 1f else 0f).toBits(), out[3].toBits()
            )
        }
    }

    @Test fun nearestIntoMatchesFlowAtOnBoxedTiles() {
        val rnd = java.util.Random(1234)
        val cols = 9
        val rows = 7
        val tileSize = 4
        val tiles = List(cols * rows) {
            val kind = rnd.nextInt(12)
            val dx = if (kind == 0) Float.NaN else rnd.nextFloat() * 4f - 2f
            val dy = if (kind == 1) Float.POSITIVE_INFINITY else rnd.nextFloat() * 4f - 2f
            RawSrTileFlow(0f, 0f, dx, dy, rnd.nextFloat(), rnd.nextBoolean())
        }
        val field = RawSrAlignmentField(cols * tileSize, rows * tileSize, tileSize, cols, rows, tiles)
        assertNearestParity(field, cols * tileSize, rows * tileSize)
    }

    @Test fun nearestIntoMatchesFlowAtOnDirectTiles() {
        val rnd = java.util.Random(987)
        val quadsW = 11
        val quadsH = 6
        val n = quadsW * quadsH
        val dx = FloatArray(n) { rnd.nextFloat() * 4f - 2f }
        val dy = FloatArray(n) { rnd.nextFloat() * 4f - 2f }
        val residual = FloatArray(n) { rnd.nextFloat() }
        val reliable = BooleanArray(n) { rnd.nextBoolean() }
        dx[0] = Float.NaN
        dy[1] = Float.NEGATIVE_INFINITY
        val tiles = RawSrMergeJob.QuadFlowTiles(quadsW, dx, dy, residual, reliable)
        val field = RawSrAlignmentField(quadsW, quadsH, 1, quadsW, quadsH, tiles)
        assertNearestParity(field, quadsW, quadsH)
    }

    @Test fun bilateralLeavesUniformFieldUntouched() {
        val tiles = List(4 * 4) { RawSrTileFlow(0f, 0f, 2.5f, -1.25f, 0.1f, true) }
        val field = RawSrAlignmentField(64, 64, 16, 4, 4, tiles)
        val out = field.bilateralFiltered(1f)
        for (i in 0 until 16) {
            assertEquals("dx $i", 2.5f, out.tiles[i].dx, 1e-6f)
            assertEquals("dy $i", -1.25f, out.tiles[i].dy, 1e-6f)
            assertEquals("residual $i", 0.1f, out.tiles[i].residual, 0f)
            assertTrue("reliable $i", out.tiles[i].reliable)
        }
        assertEquals(64, out.imageWidth)
        assertEquals(64, out.imageHeight)
    }

    @Test fun bilateralCollapsesCoherentStepKeepsSpikeAndJump() {
        // Coherent 0.5px step at sigma 1 (left cols 0, right cols 0.5):
        // the boundary tiles blend toward each other (0 -> ~0.153,
        // 0.5 -> ~0.347), dissolving the seam; interior tiles are
        // untouched (uniform window).
        val stepped = RawSrAlignmentField(80, 80, 16, 5, 5, List(5 * 5) { i ->
            RawSrTileFlow(0f, 0f, if (i % 5 < 2) 0f else 0.5f, 0f, 0f, true)
        })
        val kept = stepped.bilateralFiltered(1f)
        assertEquals("step low side", 0.153f, kept.tiles[2 * 5 + 1].dx, 1e-3f)
        assertEquals("step high side", 0.347f, kept.tiles[2 * 5 + 2].dx, 1e-3f)
        assertEquals("interior low", 0f, kept.tiles[2 * 5 + 0].dx, 0f)
        assertEquals("interior high", 0.5f, kept.tiles[2 * 5 + 4].dx, 1e-6f)
        // Coherent 5px jump: both sides keep their vectors (weight
        // exp(-25/2) ~= 4e-6): blinds-safe by construction.
        val jumped = RawSrAlignmentField(80, 80, 16, 5, 5, List(5 * 5) { i ->
            RawSrTileFlow(0f, 0f, if (i % 5 < 2) 0f else 5f, 0f, 0f, true)
        })
        val same = jumped.bilateralFiltered(1f)
        assertEquals("jump low side", 0f, same.tiles[2 * 5 + 1].dx, 1e-3f)
        assertEquals("jump high side", 5f, same.tiles[2 * 5 + 2].dx, 1e-3f)
        // Isolated 3px spike: the center keeps itself (weight 1) and
        // neighbors barely notice (weight exp(-9/2) ~= 0.011) — spike
        // rejection is robustness's job (mismatch -> low r), not the
        // bilateral's.
        val spiked = RawSrAlignmentField(80, 80, 16, 5, 5, List(5 * 5) { i ->
            val tx = i % 5
            val ty = i / 5
            RawSrTileFlow(0f, 0f, if (tx == 2 && ty == 2) 3f else 0f, 0f, 0f, true)
        })
        val held = spiked.bilateralFiltered(1f)
        assertTrue("spike center holds", held.tiles[2 * 5 + 2].dx > 2.5f)
        assertTrue("spike neighbor holds", held.tiles[2 * 5 + 1].dx < 0.1f)
    }

    @Test fun bilateralDisabledReturnsSameAndSkipsNonFinite() {
        val tiles = listOf(
            RawSrTileFlow(0f, 0f, 1f, 0f, 0.1f, true),
            RawSrTileFlow(0f, 0f, Float.NaN, 0f, 0.2f, true),
            RawSrTileFlow(0f, 0f, 1f, 0f, 0.3f, true),
            RawSrTileFlow(0f, 0f, 1f, 0f, 0.4f, true)
        )
        val field = RawSrAlignmentField(32, 32, 16, 2, 2, tiles)
        for (sigma in listOf(0f, -1f, Float.NaN)) {
            assertSame("sigma=$sigma must return the field itself", field, field.bilateralFiltered(sigma))
        }
        val out = field.bilateralFiltered(1f)
        // NaN center rides through with its residual.
        assertTrue(out.tiles[1].dx.isNaN())
        assertEquals(0.2f, out.tiles[1].residual, 0f)
        // NaN neighbor skipped: tile 0 averages finite neighbors only.
        assertEquals(1f, out.tiles[0].dx, 1e-6f)
    }

    private fun assertNearestParity(field: RawSrAlignmentField, width: Int, height: Int) {
        val out = FloatArray(4)
        for (y in 0 until height) for (x in 0 until width) {
            val expected = field.flowAt(x.toFloat(), y.toFloat())
            field.flowAtNearestInto(x.toFloat(), y.toFloat(), out)
            assertEquals("nearest dx at $x,$y", expected.dx.toBits(), out[0].toBits())
            assertEquals("nearest dy at $x,$y", expected.dy.toBits(), out[1].toBits())
            assertEquals("nearest residual at $x,$y", expected.residual.toBits(), out[2].toBits())
            assertEquals(
                "nearest reliable at $x,$y",
                (if (expected.reliable) 1f else 0f).toBits(), out[3].toBits()
            )
        }
    }
}
