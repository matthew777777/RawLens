// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguGeometryTest {
    @Test fun guideFullFrame1080() {
        val g = BguGeometry.guideGeometry(4080, 3060, null, 1080)
        assertEquals(BguGuideGeo(0, 0, 1020, 764, 4), g)
    }

    @Test fun guideFullFrame480() {
        val g = BguGeometry.guideGeometry(4080, 3060, null, 480)
        assertEquals(BguGuideGeo(0, 0, 408, 306, 10), g)
    }

    @Test fun guideCrop() {
        val g = BguGeometry.guideGeometry(4080, 3060, intArrayOf(0, 0, 3264, 2448), 1080)
        assertEquals(BguGuideGeo(0, 0, 816, 612, 4), g)
    }

    @Test fun guideEvenAlignsOddCropOrigin() {
        val g = BguGeometry.guideGeometry(100, 100, intArrayOf(1, 3, 99, 99), 48)
        assertEquals(0, g.left % 2)
        assertEquals(0, g.top % 2)
        assertEquals(0, g.left)
        assertEquals(2, g.top)
        assertEquals(0, g.width % 2)
        assertEquals(0, g.height % 2)
    }

    @Test fun lowDims() {
        assertArrayEquals(intArrayOf(128, 96), BguGeometry.lowDims(1020, 764))
        assertArrayEquals(intArrayOf(1, 1), BguGeometry.lowDims(8, 8))
        assertArrayEquals(intArrayOf(2, 1), BguGeometry.lowDims(9, 8))
    }

    @Test fun lowQuadOrigin() {
        assertArrayEquals(intArrayOf(0, 0), BguGeometry.lowQuadOrigin(0, 0, 0, 0, 4))
        assertArrayEquals(intArrayOf(32, 64), BguGeometry.lowQuadOrigin(1, 2, 0, 0, 4))
        assertArrayEquals(intArrayOf(44, 76), BguGeometry.lowQuadOrigin(1, 2, 12, 12, 4))
    }

    @Test fun displayUvIdentity() {
        assertArrayEquals(
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            BguGeometry.displayUv(0, false), 0f
        )
    }

    @Test fun displayUvRotations() {
        // Content rotates clockwise: guide top-left moves toward top-right.
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f),
            BguGeometry.displayUv(90, false), 0f
        )
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f),
            BguGeometry.displayUv(180, false), 0f
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f),
            BguGeometry.displayUv(270, false), 0f
        )
        assertArrayEquals(BguGeometry.displayUv(0, false), BguGeometry.displayUv(360, false), 0f)
        assertArrayEquals(BguGeometry.displayUv(270, false), BguGeometry.displayUv(-90, false), 0f)
    }

    @Test fun displayUvMirror() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f),
            BguGeometry.displayUv(0, true), 0f
        )
    }

    @Test fun displayUvMatchesLegacySensorPoint() {
        // Orientation parity with the legacy engine: same corners through
        // RawPreviewGeometry.sensorPoint (BL, BR, TL, TR order) must yield
        // the same eight floats for every rotation x mirror combination.
        val corners = listOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f)
        for (rot in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val want = FloatArray(8)
            corners.forEachIndexed { i, (x, y) ->
                val p = RawPreviewGeometry.sensorPoint(x, y, rot, mirror)
                want[i * 2] = p.first
                want[i * 2 + 1] = p.second
            }
            assertArrayEquals("rot=$rot mirror=$mirror", want, BguGeometry.displayUv(rot, mirror), 0f)
        }
    }

    @Test fun displayUvAlwaysPermutesCorners() {
        val corners = setOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f)
        for (rot in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val uv = BguGeometry.displayUv(rot, mirror)
            val got = setOf(uv[0] to uv[1], uv[2] to uv[3], uv[4] to uv[5], uv[6] to uv[7])
            assertEquals("rot=$rot mirror=$mirror", corners, got)
        }
    }

    @Test fun cfaSites() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3), BguGeometry.cfaSites(0))
        assertArrayEquals(intArrayOf(1, 0, 3, 2), BguGeometry.cfaSites(1))
        assertArrayEquals(intArrayOf(2, 3, 0, 1), BguGeometry.cfaSites(2))
        assertArrayEquals(intArrayOf(3, 2, 1, 0), BguGeometry.cfaSites(3))
    }

    @Test fun cfaSitesRejectsBadLayout() {
        assertThrows(IllegalArgumentException::class.java) { BguGeometry.cfaSites(4) }
    }
}
