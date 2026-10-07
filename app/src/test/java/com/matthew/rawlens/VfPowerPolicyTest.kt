// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfPowerPolicyTest {
    @Test fun `full rate locks 30fps with jitter margin`() {
        // Above the 60 fps period (halves 60 fps streams to 30) yet far under
        // the 33.3 ms camera period: 33 ms aliased a [30,30] stream to ~20 fps
        // and 28 ms still skipped sub-28 ms intervals (21-30 fps jumps).
        assertEquals(20L, VfResolution.RATE_FULL_MS)
        assertEquals(20L, VfResolution.rateFloorMs(recordMode = false, powerSave = false))
        assertTrue(VfResolution.RATE_FULL_MS > 17L)
        assertTrue(VfResolution.RATE_FULL_MS <= 24L)
    }

    @Test fun `saver halves the frame rate`() {
        assertEquals(66L, VfResolution.RATE_SAVER_MS)
        assertEquals(66L, VfResolution.rateFloorMs(recordMode = false, powerSave = true))
    }

    @Test fun `record wins over saver`() {
        assertEquals(100L, VfResolution.RATE_RECORD_MS)
        assertEquals(100L, VfResolution.rateFloorMs(recordMode = true, powerSave = false))
        assertEquals(100L, VfResolution.rateFloorMs(recordMode = true, powerSave = true))
    }

    @Test fun `floors order full faster than saver faster than record`() {
        assertTrue(VfResolution.RATE_FULL_MS < VfResolution.RATE_SAVER_MS)
        assertTrue(VfResolution.RATE_SAVER_MS < VfResolution.RATE_RECORD_MS)
    }

    @Test fun `saver caps resolution at 640`() {
        assertEquals(VfResolution.MID, VfResolution.SAVER_MAX)
        assertEquals(640, VfResolution.effectiveEdge(1080, recordMode = false, powerSave = true))
        assertEquals(640, VfResolution.effectiveEdge(960, recordMode = false, powerSave = true))
        assertEquals(640, VfResolution.effectiveEdge(640, recordMode = false, powerSave = true))
        assertEquals(480, VfResolution.effectiveEdge(480, recordMode = false, powerSave = true))
    }

    @Test fun `record forces 480 regardless of saver`() {
        assertEquals(480, VfResolution.effectiveEdge(1080, recordMode = true, powerSave = false))
        assertEquals(480, VfResolution.effectiveEdge(1080, recordMode = true, powerSave = true))
    }

    @Test fun `full mode keeps user resolution`() {
        assertEquals(1080, VfResolution.effectiveEdge(1080, recordMode = false, powerSave = false))
        assertEquals(960, VfResolution.effectiveEdge(960, recordMode = false, powerSave = false))
        assertEquals(480, VfResolution.effectiveEdge(480, recordMode = false, powerSave = false))
    }
}
