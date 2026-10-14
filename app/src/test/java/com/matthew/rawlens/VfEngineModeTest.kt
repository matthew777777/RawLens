// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfEngineModeTest {
    @Test fun `overlay tap cycles AUTO GPU CPU BGU BGU_CPU`() {
        assertEquals(VfEngineMode.GPU, VfEngineMode.AUTO.next())
        assertEquals(VfEngineMode.CPU, VfEngineMode.GPU.next())
        assertEquals(VfEngineMode.BGU, VfEngineMode.CPU.next())
        assertEquals(VfEngineMode.BGU_CPU, VfEngineMode.BGU.next())
        assertEquals(VfEngineMode.AUTO, VfEngineMode.BGU_CPU.next())
    }

    @Test fun `short labels keep path and override distinguishable`() {
        assertEquals("AUTO", VfEngineMode.AUTO.shortLabel())
        assertEquals("BGU!", VfEngineMode.BGU.shortLabel())
        // Same engine rendering as BGU: the GPU/NEON path prefix in the
        // overlay line carries the guide path, not the engine label.
        assertEquals("BGU!", VfEngineMode.BGU_CPU.shortLabel())
        assertEquals("GPU!", VfEngineMode.GPU.shortLabel())
        assertEquals("CPU!", VfEngineMode.CPU.shortLabel())
    }

    @Test fun `missing preference keeps AUTO`() {
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference(null))
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference("UNKNOWN"))
        assertEquals(VfEngineMode.BGU, VfEngineMode.fromPreference("BGU"))
        assertEquals(VfEngineMode.BGU_CPU, VfEngineMode.fromPreference("BGU_CPU"))
        assertEquals(VfEngineMode.GPU, VfEngineMode.fromPreference("GPU"))
        assertEquals(VfEngineMode.CPU, VfEngineMode.fromPreference("CPU"))
    }

    @Test fun `forced BGU stays BGU even when dead`() {
        assertTrue(resolveVfRoute(VfEngineMode.BGU, legacyDead = false, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.BGU, legacyDead = false, bguDead = false, hasBgu = false))
    }

    @Test fun `forced BGU_CPU routes BGU and never falls back`() {
        assertTrue(resolveVfRoute(VfEngineMode.BGU_CPU, legacyDead = true, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.BGU_CPU, legacyDead = false, bguDead = false, hasBgu = false))
    }

    @Test fun `forced legacy modes never route BGU`() {
        assertFalse(resolveVfRoute(VfEngineMode.GPU, legacyDead = true, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.CPU, legacyDead = true, bguDead = true, hasBgu = true))
    }

    @Test fun `AUTO routes legacy first, BGU only when legacy dead or absent`() {
        // Ladder: legacy GPU -> legacy CPU (inside legacy) -> BGU GPU ->
        // BGU CPU (inside BGU). BGU engages only past a dead/absent legacy.
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, legacyDead = false, bguDead = false, hasBgu = true, hasLegacy = true))
        assertTrue(resolveVfRoute(VfEngineMode.AUTO, legacyDead = true, bguDead = false, hasBgu = true, hasLegacy = true))
        assertTrue(resolveVfRoute(VfEngineMode.AUTO, legacyDead = false, bguDead = false, hasBgu = true, hasLegacy = false))
        // Both dead: back to legacy as the last-resort engine.
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, legacyDead = true, bguDead = true, hasBgu = true, hasLegacy = true))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, legacyDead = true, bguDead = false, hasBgu = false, hasLegacy = true))
    }

    @Test fun `legacy is the default way with BGU as last option`() {
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference(null))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, legacyDead = false, bguDead = false, hasBgu = true, hasLegacy = true))
        assertTrue(resolveVfRoute(VfEngineMode.AUTO, legacyDead = true, bguDead = false, hasBgu = true, hasLegacy = true))
        assertFalse(resolveVfRoute(VfEngineMode.GPU, legacyDead = false, bguDead = false, hasBgu = true, hasLegacy = true))
    }

    @Test fun `recording follows the routed viewfinder view`() {
        assertEquals("bgu", selectRoutedViewfinder(true, "bgu", "legacy"))
        assertEquals("legacy", selectRoutedViewfinder(false, "bgu", "legacy"))
        assertEquals("legacy", selectRoutedViewfinder(true, null, "legacy"))
        assertNull(selectRoutedViewfinder(false, "bgu", null))
    }
}
