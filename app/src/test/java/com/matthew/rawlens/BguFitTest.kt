// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/** Pure grid-geometry contract for the BGU fit (no device needed). */
class BguFitTest {
    @Test fun gridDimsDefault() {
        assertArrayEquals(intArrayOf(5, 4, 9), BguFit.gridDims(68, 51, 16, 1f / 8))
    }

    @Test fun gridDimsExactMultiples() {
        // Spatial dims divide evenly; z still carries the inclusive top bin.
        assertArrayEquals(intArrayOf(4, 3, 9), BguFit.gridDims(64, 48, 16, 1f / 8))
    }

    @Test fun gridDimsCoarseLuma() {
        assertArrayEquals(intArrayOf(5, 4, 5), BguFit.gridDims(68, 51, 16, 1f / 4))
    }

    @Test fun gridDimsRejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { BguFit.gridDims(0, 51) }
        assertThrows(IllegalArgumentException::class.java) { BguFit.gridDims(68, 0) }
        assertThrows(IllegalArgumentException::class.java) { BguFit.gridDims(68, 51, 0) }
        assertThrows(IllegalArgumentException::class.java) { BguFit.gridDims(68, 51, 16, 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            BguFit.gridDims(68, 51, 16, Float.NaN)
        }
    }

    @Test fun gridAtUsesCallerStridedLayout() {
        // index = x + gw * (y + gh * (z + gz * ch)), ch = row * 4 + col.
        val grid = BguGrid(2, 2, 2, FloatArray(2 * 2 * 2 * 12) { it.toFloat() })
        assertEquals(0f, grid.at(0, 0, 0, 0, 0))
        assertEquals(1f, grid.at(1, 0, 0, 0, 0))
        assertEquals(2f, grid.at(0, 1, 0, 0, 0))
        assertEquals(4f, grid.at(0, 0, 1, 0, 0))
        assertEquals(8f, grid.at(0, 0, 0, 0, 1))
        assertEquals(31f, grid.at(1, 1, 1, 0, 3))
        assertEquals(56f, grid.at(0, 0, 0, 1, 3))
    }

    @Test fun gridRejectsBadShape() {
        assertThrows(IllegalArgumentException::class.java) { BguGrid(0, 1, 1, FloatArray(12)) }
        assertThrows(IllegalArgumentException::class.java) { BguGrid(1, 1, 1, FloatArray(11)) }
    }
}
