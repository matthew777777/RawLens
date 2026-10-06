// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class AhbEncoderBridgeTest {
    @Test
    fun paramsDefaultTo4kEncodeWithHdExport() {
        val p = AhbEncoderBridge.params()
        assertEquals(3840, p.width)
        assertEquals(2160, p.height)
        assertEquals(30, p.fps)
        assertEquals(75_000_000, p.bitrate)
        // Synthetic export stays HD-scale for cheap CPU fills; the blit
        // stretches to the 4K encoder surface.
        assertEquals(1920, p.ahbWidth)
        assertEquals(1080, p.ahbHeight)
    }
}
