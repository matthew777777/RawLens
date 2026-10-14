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

    @Test fun binnedCropsSatisfyEncoderContract() {
        val sizes = listOf(4080 to 3060, 1920 to 1080, 3264 to 2448, 4000 to 3000, 2592 to 1944)
        for ((w, h) in sizes) {
            for (crop in VideoCrop.entries) {
                val r = crop.resolve(w, h, binned = true)
                assertEquals("top even $crop ${w}x$h", 0, r.top % 2)
                assertEquals("height %8 $crop ${w}x$h", 0, r.height % 8)
                assertTrue("bounds $crop ${w}x$h", r.top >= 0 && r.top + r.height <= h)
                assertTrue("non-empty $crop ${w}x$h", r.height >= 8)
                assertEquals(w, r.width)
                // The binned extent stays tile-legal: even width, height % 4.
                assertEquals(0, (w / 2) % 2)
                assertEquals(0, (r.height / 2) % 4)
            }
        }
    }

    @Test fun binnedCropsCenteredOn4080x3060() {
        // Open Gate trims 3060 -> 3056 (% 8), centered: top=(3060-3056)/2=2.
        assertEquals(VideoCrop.Resolved(2, 3056, 4080), VideoCrop.OPEN_GATE.resolve(4080, 3060, true))
        // 16:9 -> 4080x2288 (2295 floored to %8), top=(3060-2288)/2=386.
        assertEquals(VideoCrop.Resolved(386, 2288, 4080), VideoCrop.WIDE_16_9.resolve(4080, 3060, true))
        // 2.00:1 -> 4080x2040 (already %8), top=510.
        assertEquals(
            VideoCrop.Resolved(510, 2040, 4080),
            VideoCrop.UNIVISIUM_2_00.resolve(4080, 3060, true)
        )
        // 2.39:1 -> 4080x1704 (1707 floored to %8), top=(3060-1704)/2=678.
        assertEquals(VideoCrop.Resolved(678, 1704, 4080), VideoCrop.SCOPE_2_39.resolve(4080, 3060, true))
    }

    @Test fun binnedOpenGateKeepsFullFrameWhenDivisible() {
        assertEquals(VideoCrop.Resolved(0, 3000, 4000), VideoCrop.OPEN_GATE.resolve(4000, 3000, true))
    }

    @Test fun binnedTallCropClampsToSensor() {
        // (100/2.39)=41 floored to %8 -> 40, top=(300-40)/2=130.
        assertEquals(VideoCrop.Resolved(130, 40, 100), VideoCrop.SCOPE_2_39.resolve(100, 300, true))
    }

    @Test fun binningSupportNeedsWidthMod4() {
        assertTrue(VideoCrop.supportsBinning(4080))
        assertTrue(VideoCrop.supportsBinning(68))
        assertTrue(!VideoCrop.supportsBinning(66))
        assertTrue(!VideoCrop.supportsBinning(2590))
    }

    @Test fun binnedResolveRejectsNarrowWidth() {
        try {
            VideoCrop.OPEN_GATE.resolve(66, 40, binned = true)
            throw AssertionError("expected width % 4 rejection")
        } catch (_: IllegalArgumentException) {
        }
        // Same geometry records fine unbinned.
        assertEquals(VideoCrop.Resolved(0, 40, 66), VideoCrop.OPEN_GATE.resolve(66, 40))
    }
}
