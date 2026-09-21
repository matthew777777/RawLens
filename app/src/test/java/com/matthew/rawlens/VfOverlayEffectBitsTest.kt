// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfOverlayEffectBitsTest {
    @Test fun `raw tonemap never flags AGX`() {
        val moved = JpegOutputSettings(agxContrast = 1.2f, agxSaturation = 1.1f, agxPurityBoost = 0.5f)
        assertTrue(vfOverlayEffectBits(false, false, moved).isEmpty())
    }

    @Test fun `jpeg tonemap flags moved sliders only`() {
        val moved = JpegOutputSettings(agxContrast = 1.2f)
        assertEquals(listOf("AGX+"), vfOverlayEffectBits(true, false, moved))
        assertTrue(vfOverlayEffectBits(true, false, JpegOutputSettings()).isEmpty())
    }

    @Test fun `denoise and ultra-hdr pass through in both modes`() {
        val settings = JpegOutputSettings(ultraHdr = true)
        assertEquals(listOf("DENOISE", "UHDR"), vfOverlayEffectBits(false, true, settings))
        assertEquals(listOf("DENOISE", "UHDR"), vfOverlayEffectBits(true, true, settings))
    }

    @Test fun `default settings report no effects`() {
        assertFalse(vfOverlayEffectBits(false, false, JpegOutputSettings()).isNotEmpty())
    }
}
