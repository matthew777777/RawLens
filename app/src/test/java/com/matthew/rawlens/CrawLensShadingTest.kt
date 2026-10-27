// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrsLensShadingTest {
    private fun snapshot(rows: Int, cols: Int, gains: FloatArray) =
        LensShadingSnapshot(rows, cols, ImmutableFloatValues(gains))

    private fun active(w: Int, h: Int, left: Int = 0, top: Int = 0) =
        IntRectSnapshot(left, top, left + w, top + h)

    @Test fun constantMapStaysConstantInColorOrder() {
        // Distinct per-channel constants: R=1, Ge=2, Go=3, B=4.
        val hal = snapshot(2, 2, FloatArray(2 * 2 * 4) { (it % 4 + 1).toFloat() })
        val grid = checkNotNull(
            CrsLensShading.resample(hal, 0, active(8, 8), 8, 8, 0, 8)
        )
        assertEquals(2, grid.columns)
        assertEquals(2, grid.rows)
        // RGGB on an even origin: R rows are even, B rows odd.
        for (point in 0 until 4) {
            val base = point * 4
            assertEquals(1f, grid.gains[base], 0f)
            assertEquals(2f, grid.gains[base + 1], 0f)
            assertEquals(3f, grid.gains[base + 2], 0f)
            assertEquals(4f, grid.gains[base + 3], 0f)
        }
    }

    @Test fun greenPlanesFollowCfaRowParity() {
        val hal = snapshot(1, 1, floatArrayOf(1f, 2f, 3f, 4f))
        // Gr reads the HAL green matching the R rows' sensor parity, Gb the
        // one matching the B rows. RGGB/GRBG put R rows even; GBRG/BGGR odd.
        val expected = mapOf(
            0 to floatArrayOf(1f, 2f, 3f, 4f), // RGGB
            1 to floatArrayOf(1f, 2f, 3f, 4f), // GRBG
            2 to floatArrayOf(1f, 3f, 2f, 4f), // GBRG
            3 to floatArrayOf(1f, 3f, 2f, 4f) // BGGR
        )
        for ((cfa, want) in expected) {
            val grid = checkNotNull(
                CrsLensShading.resample(hal, cfa, active(8, 8), 8, 8, 0, 8)
            )
            for (ch in 0 until 4) {
                assertEquals("cfa=$cfa ch=$ch", want[ch], grid.gains[ch], 0f)
            }
        }
    }

    @Test fun oddActiveOriginFlipsGreenSources() {
        val hal = snapshot(1, 1, floatArrayOf(1f, 2f, 3f, 4f))
        // RGGB rows are arrangement-fixed, but the active array starts one
        // row down: R rows sit on odd sensor rows (Go=3), B rows on even
        // ones (Ge=2). The decoder maps the same frame rows to the same
        // planes, so each pixel keeps a gain from its own physical channel.
        val grid = checkNotNull(
            CrsLensShading.resample(hal, 0, active(8, 8, 0, 1), 8, 8, 0, 8)
        )
        val want = floatArrayOf(1f, 3f, 2f, 4f)
        for (ch in 0 until 4) {
            assertEquals("ch=$ch", want[ch], grid.gains[ch], 0f)
        }
    }

    @Test fun singlePointMapIsGeometryIndependent() {
        val hal = snapshot(1, 1, floatArrayOf(1.5f, 1.5f, 1.5f, 1.5f))
        for (cfa in 0..3) {
            val grid = checkNotNull(
                CrsLensShading.resample(hal, cfa, active(64, 48), 64, 48, 8, 32)
            )
            assertTrue(grid.gains.all { it == 1.5f })
        }
    }

    @Test fun cropWindowRestrictsSamplingToEncodedRows() {
        // Vertical R gradient across a 2x2 HAL grid; other channels constant.
        val hal = snapshot(
            2, 2, floatArrayOf(
                1f, 9f, 9f, 9f, 1f, 9f, 9f, 9f,
                2f, 9f, 9f, 9f, 2f, 9f, 9f, 9f
            )
        )
        // Encode rows 4..8 of an 8x8 frame; grid point (0,0) sits at encoded
        // y=1 -> sensor y=5, point (0,1) at encoded y=3 -> sensor y=7.
        val grid = checkNotNull(
            CrsLensShading.resample(hal, 0, active(8, 8), 8, 8, 4, 4)
        )
        fun halR(sensorY: Double): Double {
            val mapY = (sensorY / 7.0) * 1.0
            return 1.0 * (1.0 - mapY) + 2.0 * mapY
        }
        assertEquals(halR(5.0).toFloat(), grid.gains[0 * 4], 1e-5f)
        assertEquals(halR(7.0).toFloat(), grid.gains[2 * 4], 1e-5f)
    }

    @Test fun invalidContentAndGeometryReturnNull() {
        val good = FloatArray(2 * 2 * 4) { 1f }
        val box = active(8, 8)
        // Bad gain content.
        assertNull(CrsLensShading.resample(snapshot(2, 2, good.copyOf().also { it[3] = Float.NaN }), 0, box, 8, 8, 0, 8))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good.copyOf().also { it[0] = 0f }), 0, box, 8, 8, 0, 8))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good.copyOf().also { it[7] = Float.POSITIVE_INFINITY }), 0, box, 8, 8, 0, 8))
        // Gain-count mismatch.
        assertNull(CrsLensShading.resample(snapshot(2, 2, FloatArray(15) { 1f }), 0, box, 8, 8, 0, 8))
        // Oversize grid.
        assertNull(CrsLensShading.resample(snapshot(1, 513, FloatArray(0)), 0, box, 8, 8, 0, 8))
        assertNull(CrsLensShading.resample(snapshot(0, 2, FloatArray(0)), 0, box, 8, 8, 0, 8))
        // Non-Bayer CFA.
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 4, box, 8, 8, 0, 8))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), -1, box, 8, 8, 0, 8))
        // Frame must cover the active array exactly.
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 0, box, 8, 4, 0, 4))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 0, active(0, 8), 0, 8, 0, 8))
        // Bad crop window.
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 0, box, 8, 8, -2, 8))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 0, box, 8, 8, 0, 0))
        assertNull(CrsLensShading.resample(snapshot(2, 2, good), 0, box, 8, 8, 4, 8))
    }

    private fun parseTriplet(triplet: String): Triple<Int, Int, List<List<Float>>> {
        assertTrue(triplet.startsWith("\"lensShadingMapWidth\":"))
        val width = triplet.substringAfter("\"lensShadingMapWidth\":").substringBefore(',').toInt()
        val height = triplet.substringAfter("\"lensShadingMapHeight\":").substringBefore(',').toInt()
        val body = triplet.substringAfter("\"lensShadingMap\":[")
        assertTrue(body.endsWith("]"))
        val planes = body.dropLast(1).split("],[")
            .map { plane -> plane.trim('[', ']').split(",").map { it.toFloat() } }
        return Triple(width, height, planes)
    }

    @Test fun frameJsonCarriesNestedPlanesAndRoundTrips() {
        val grid = CrsLensShading.Grid(2, 1, FloatArray(8) { 1f + it * 0.125f })
        val (width, height, planes) = parseTriplet(CrsLensShading.toFrameJson(grid))
        assertEquals(2, width)
        assertEquals(1, height)
        assertEquals(4, planes.size)
        for (ch in 0 until 4) {
            assertEquals(2, planes[ch].size)
            for (i in 0 until 2) {
                assertEquals(grid.gains[i * 4 + ch], planes[ch][i], 0f)
            }
        }
    }

    @Test fun frameJsonMatchesUpstreamGoldenValues() {
        // Upstream EncoderTests golden (C++ prints 1, Kotlin prints 1.0 —
        // same JSON numbers, equivalent text).
        val grid = CrsLensShading.Grid(
            2, 1, floatArrayOf(1.0f, 1.5f, 2.0f, 2.5f, 0.5f, 1.25f, 3.0f, 4.0f)
        )
        val (width, height, planes) = parseTriplet(CrsLensShading.toFrameJson(grid))
        assertEquals(2, width)
        assertEquals(1, height)
        val want = listOf(
            listOf(1f, 0.5f), listOf(1.5f, 1.25f), listOf(2f, 3f), listOf(2.5f, 4f)
        )
        assertEquals(want, planes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameJsonRejectsBadGains() {
        CrsLensShading.toFrameJson(
            CrsLensShading.Grid(1, 1, floatArrayOf(1f, 1f, Float.NaN, 1f))
        )
    }
}
