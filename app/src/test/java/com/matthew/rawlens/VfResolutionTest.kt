// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VfResolutionTest {
    @Test fun `options run low to high detail`() {
        assertArrayEquals(intArrayOf(480, 640, 960, 1080), VfResolution.OPTIONS)
        assertEquals(480, VfResolution.MIN)
        assertEquals(1080, VfResolution.MAX)
    }

    @Test fun `cpu fallback cap sits on an existing option`() {
        assertEquals(640, VfResolution.CPU_MAX)
        assertEquals(VfResolution.MID, VfResolution.CPU_MAX)
    }

    @Test fun `stored values map to the nearest option`() {
        // Legacy prefs (480/640/960) survive the 1080 upgrade unchanged.
        assertEquals(480, VfResolution.validated(480))
        assertEquals(640, VfResolution.validated(640))
        assertEquals(960, VfResolution.validated(960))
        assertEquals(1080, VfResolution.validated(1080))
        // Midpoint buckets between neighbors.
        assertEquals(480, VfResolution.validated(560))
        assertEquals(640, VfResolution.validated(561))
        assertEquals(640, VfResolution.validated(800))
        assertEquals(960, VfResolution.validated(801))
        assertEquals(960, VfResolution.validated(1020))
        assertEquals(1080, VfResolution.validated(1021))
        // Clamped extremes.
        assertEquals(480, VfResolution.validated(0))
        assertEquals(480, VfResolution.validated(-100))
        assertEquals(1080, VfResolution.validated(9999))
    }
}
