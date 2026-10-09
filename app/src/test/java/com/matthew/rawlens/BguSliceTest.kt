// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguSliceTest {
    @Test fun shadersAreEs3Slice() {
        val vs = BguSlice.vertexShader()
        assertTrue(vs.startsWith("#version 300 es"))
        assertTrue(vs.contains("position") && vs.contains("uv"))
        val fs = BguSlice.fragmentShader()
        assertTrue(fs.startsWith("#version 300 es"))
        assertEquals(3, "sampler3D".toRegex().findAll(fs).count())
        assertTrue(fs.contains("uGuide"))
        assertTrue(fs.contains("uGrid0") && fs.contains("uGrid1") && fs.contains("uGrid2"))
        assertTrue(fs.contains("uScale") && fs.contains("uGridDim"))
        assertTrue(fs.contains("gl_FragCoord"))
        assertFalse(fs.contains("gl_FragColor"))
    }

    @Test fun floatToHalfBasics() {
        assertEquals(0x0000.toShort(), BguSlice.floatToHalf(0f))
        assertEquals(0x3c00.toShort(), BguSlice.floatToHalf(1f))
        assertEquals(0xbc00.toShort(), BguSlice.floatToHalf(-1f))
        assertEquals(0x3800.toShort(), BguSlice.floatToHalf(0.5f))
        assertEquals(0x4000.toShort(), BguSlice.floatToHalf(2f))
        assertEquals(0x4200.toShort(), BguSlice.floatToHalf(3f))
    }

    @Test fun floatToHalfRoundsUp() {
        // Half ulp near 1.0 is 2^-10; 1 + 2^-11 sits exactly halfway up.
        assertEquals(0x3c01.toShort(), BguSlice.floatToHalf(1.00048828125f))
        // Full f32 mantissa rounds up with exponent carry: just-below-2 -> 2.
        assertEquals(0x4000.toShort(), BguSlice.floatToHalf(1.99999988f))
    }

    @Test fun floatToHalfEdges() {
        assertEquals(0x7bff.toShort(), BguSlice.floatToHalf(65504f))
        assertEquals(0x7bff.toShort(), BguSlice.floatToHalf(1e6f))
        assertEquals(0xfbff.toShort(), BguSlice.floatToHalf(-1e6f))
        assertEquals(0x0000.toShort(), BguSlice.floatToHalf(Float.NaN))
        assertEquals(0x7bff.toShort(), BguSlice.floatToHalf(Float.POSITIVE_INFINITY))
        assertEquals(0xfbff.toShort(), BguSlice.floatToHalf(Float.NEGATIVE_INFINITY))
        assertEquals(0x0000.toShort(), BguSlice.floatToHalf(1e-8f))
        assertEquals(0x8000.toShort(), BguSlice.floatToHalf(-0f))
    }

    @Test fun packGridLayout() {
        // 2x1x1 grid, coeffs = index: row r texel (x,y,z) RGBA = at(x,y,z,r,0..3).
        val grid = BguGrid(2, 1, 1, FloatArray(2 * 1 * 1 * 12) { it.toFloat() })
        val tex = BguSlice.packGrid(grid)
        assertEquals(3, tex.size)
        // Row 0: texel0 = coeffs[0,2,4,6], texel1 = coeffs[1,3,5,7].
        assertArrayEquals(
            shortArrayOf(
                BguSlice.floatToHalf(0f), BguSlice.floatToHalf(2f),
                BguSlice.floatToHalf(4f), BguSlice.floatToHalf(6f),
                BguSlice.floatToHalf(1f), BguSlice.floatToHalf(3f),
                BguSlice.floatToHalf(5f), BguSlice.floatToHalf(7f)
            ),
            tex[0]
        )
        // Row 1: texel0 = coeffs[8,10,12,14], texel1 = coeffs[9,11,13,15].
        assertArrayEquals(
            shortArrayOf(
                BguSlice.floatToHalf(8f), BguSlice.floatToHalf(10f),
                BguSlice.floatToHalf(12f), BguSlice.floatToHalf(14f),
                BguSlice.floatToHalf(9f), BguSlice.floatToHalf(11f),
                BguSlice.floatToHalf(13f), BguSlice.floatToHalf(15f)
            ),
            tex[1]
        )
        // Row 2: channels 8..11 -> coeffs[16..23].
        assertEquals(BguSlice.floatToHalf(16f), tex[2][0])
        assertEquals(BguSlice.floatToHalf(23f), tex[2][7])
        assertEquals(8, tex[2].size)
    }
}
