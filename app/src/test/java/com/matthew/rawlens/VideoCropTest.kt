// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCropTest {
    @Test fun openGateIsFullFrame() {
        val r = VideoCrop.OPEN_GATE.resolve(4080, 3060)
        assertEquals(0, r.top)
        assertEquals(3060, r.height)
        assertEquals(4080, r.width)
    }

    @Test fun allCropsSatisfyEncoderContract() {
        val sizes = listOf(4080 to 3060, 1920 to 1080, 3264 to 2448, 4000 to 3000, 2592 to 1944)
        for ((w, h) in sizes) {
            for (crop in VideoCrop.entries) {
                val r = crop.resolve(w, h)
                assertEquals("top even $crop ${w}x$h", 0, r.top % 2)
                assertEquals("height %4 $crop ${w}x$h", 0, r.height % 4)
                assertTrue("bounds $crop ${w}x$h", r.top >= 0 && r.top + r.height <= h)
                assertTrue("non-empty $crop ${w}x$h", r.height >= 4)
                assertEquals(w, r.width)
            }
        }
    }

    @Test fun cinematicCropsCenteredOn4080x3060() {
        // 16:9 -> 4080x2292 (2295 floored to %4), top=(3060-2292)/2=384
        assertEquals(VideoCrop.Resolved(384, 2292, 4080), VideoCrop.WIDE_16_9.resolve(4080, 3060))
        // 2.00:1 -> 4080x2040, top=510
        assertEquals(VideoCrop.Resolved(510, 2040, 4080), VideoCrop.UNIVISIUM_2_00.resolve(4080, 3060))
        val scope = VideoCrop.SCOPE_2_39.resolve(4080, 3060)
        assertEquals(0, scope.top % 2)
        assertEquals(0, scope.height % 4)
        // 4080/2.39 = 1707.1 -> 1704, top=(3060-1704)/2=678
        assertEquals(VideoCrop.Resolved(678, 1704, 4080), scope)
    }

    @Test fun tallCropClampsToSensor() {
        // Absurdly wide ratio on a square-ish sensor clamps to full height.
        val r = VideoCrop.SCOPE_2_39.resolve(100, 300)
        assertTrue(r.top + r.height <= 300)
        assertEquals(0, r.top % 2)
        assertEquals(0, r.height % 4)
    }
}
