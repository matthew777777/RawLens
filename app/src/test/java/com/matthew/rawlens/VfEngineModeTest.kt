// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfEngineModeTest {
    @Test fun `overlay tap cycles AUTO BGU BGU_CPU GPU CPU`() {
        assertEquals(VfEngineMode.BGU, VfEngineMode.AUTO.next())
        assertEquals(VfEngineMode.BGU_CPU, VfEngineMode.BGU.next())
        assertEquals(VfEngineMode.GPU, VfEngineMode.BGU_CPU.next())
        assertEquals(VfEngineMode.CPU, VfEngineMode.GPU.next())
        assertEquals(VfEngineMode.AUTO, VfEngineMode.CPU.next())
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
        assertTrue(resolveVfRoute(VfEngineMode.BGU, autoPrefersBgu = false, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.BGU, autoPrefersBgu = true, bguDead = false, hasBgu = false))
    }

    @Test fun `forced BGU_CPU routes BGU and never falls back`() {
        assertTrue(resolveVfRoute(VfEngineMode.BGU_CPU, autoPrefersBgu = false, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.BGU_CPU, autoPrefersBgu = true, bguDead = false, hasBgu = false))
    }

    @Test fun `forced legacy modes never route BGU`() {
        assertFalse(resolveVfRoute(VfEngineMode.GPU, autoPrefersBgu = true, bguDead = false, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.CPU, autoPrefersBgu = true, bguDead = false, hasBgu = true))
    }

    @Test fun `AUTO prefers BGU only while alive preferred and present`() {
        assertTrue(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = true, bguDead = false, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = true, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = false, bguDead = false, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = true, bguDead = false, hasBgu = false))
    }

    @Test fun `BGU is the default way with legacy fallback`() {
        // Default intent, pinned: AUTO (the unset-preference mode) routes to
        // BGU while healthy, and any BGU failure degrades to legacy for the
        // session (fresh sessions retry BGU). Forced modes bypass both.
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference(null))
        assertTrue(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = true, bguDead = false, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.AUTO, autoPrefersBgu = true, bguDead = true, hasBgu = true))
        assertFalse(resolveVfRoute(VfEngineMode.GPU, autoPrefersBgu = true, bguDead = false, hasBgu = true))
    }
}
