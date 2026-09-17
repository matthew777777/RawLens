// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GyroCameraFrameMapperTest {
    private fun det(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun assertOrthogonal(m: DoubleArray) {
        // Rows unit + mutually orthogonal (proper rotation building block).
        for (r in 0..2) {
            var n = 0.0
            for (c in 0..2) n += m[r * 3 + c] * m[r * 3 + c]
            assertEquals(1.0, n, 1e-12)
        }
        var dot = 0.0
        for (c in 0..2) dot += m[c] * m[3 + c]
        assertEquals(0.0, dot, 1e-12)
    }

    @Test fun `anchor mount matrix is exact`() {
        // Portrait back camera, SENSOR_ORIENTATION=90: derived stored axes
        // (+x = device-down, +y = device-left) plus the pinned 180° about
        // the normal. Any change here breaks every gyro seed: fail loudly.
        assertEquals(
            listOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0),
            GyroCameraFrameMapper.matrix(90, false).toList()
        )
    }

    @Test fun `pan right yields negative camera x rate`() {
        // Device yaw right = device ωy<0. In stored landscape coords the
        // content shifts toward stored +y, which the seed reaches through
        // the x-rate term (ty += −fy·rx must go positive): rx<0.
        val (x, y, z) = GyroCameraFrameMapper.map(0f, -1f, 0f, 90, false)
        assertTrue("rx=$x", x < 0f)
        assertEquals(0f, y, 0f)
        assertEquals(0f, z, 0f)
    }

    @Test fun `tilt down yields negative camera y rate`() {
        // Device pitch down = device ωx<0. Content shifts toward stored −x,
        // i.e. tx<0, which the seed expresses as ry<0 (tx += +fx·ry).
        val (x, y, z) = GyroCameraFrameMapper.map(-1f, 0f, 0f, 90, false)
        assertEquals(0f, x, 0f)
        assertTrue("ry=$y", y < 0f)
        assertEquals(0f, z, 0f)
    }

    @Test fun `clockwise roll yields positive camera z rate`() {
        // User-view clockwise = device ωz<0; desktop +rz is screen-clockwise.
        val (x, y, z) = GyroCameraFrameMapper.map(0f, 0f, -1f, 90, false)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
        assertTrue("rz=$z", z > 0f)
    }

    @Test fun `portrait-native mount matches axes`() {
        // θ=0 back camera: portrait-native dump (columns right, rows down).
        assertEquals(
            listOf(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0),
            GyroCameraFrameMapper.matrix(0, false).toList()
        )
    }

    @Test fun `all mounts stay proper rotations and cycle`() {
        for (theta in listOf(0, 90, 180, 270)) {
            val m = GyroCameraFrameMapper.matrix(theta, false)
            assertOrthogonal(m)
            assertEquals(1.0, det(m), 1e-9)
        }
        assertEquals(
            GyroCameraFrameMapper.matrix(90, false).toList(),
            GyroCameraFrameMapper.matrix(450, false).toList()
        )
        // Norm preservation (rotation, not scale/shear).
        val (x, y, z) = GyroCameraFrameMapper.map(0.3f, -0.4f, 0.5f, 270, false)
        val want = sqrt(0.3 * 0.3 + 0.4 * 0.4 + 0.5 * 0.5)
        assertEquals(want, sqrt((x * x + y * y + z * z).toDouble()), 1e-6)
    }

    @Test fun `front camera mirrors x (provisional)`() {
        // Selfie mirror flips the x contribution; y/z pass through. Hardware
        // validation required before trusting front captures (see class docs).
        val back = GyroCameraFrameMapper.matrix(90, false)
        val front = GyroCameraFrameMapper.matrix(90, true)
        for (i in 0..2) assertEquals(-back[i], front[i], 0.0)
        for (i in 3..8) assertEquals(back[i], front[i], 0.0)
    }

    @Test fun `non-quarter-turn orientation fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            GyroCameraFrameMapper.matrix(45, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GyroCameraFrameMapper.map(0f, 0f, 1f, 45, false)
        }
    }

    @Test fun `mapper version is pinned`() {
        assertEquals(1, GyroCameraFrameMapper.MAPPER_VERSION)
    }
}
