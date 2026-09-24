// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [RawSrAlignmentField.flowAtSmoothInto] bitwise-identical to
 * [RawSrAlignmentField.flowAtSmooth] on both tile backings: boxed lists and
 * flat [RawSrDirectFlowTiles]. Covers interior, edges, non-finite fallback,
 * and mixed reliability.
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
}
