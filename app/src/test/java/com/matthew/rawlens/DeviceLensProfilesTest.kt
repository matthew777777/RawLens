// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class DeviceLensProfilesTest {
    @Test fun matchesByModelOnly() {
        assertEquals(listOf("vivo_x300_ultra"), DeviceLensProfiles.matching("V2562").map { it.id })
        assertEquals(listOf("oppo_x9u_standard"), DeviceLensProfiles.matching(" CPH2841 ").map { it.id })
        assertTrue(DeviceLensProfiles.matching("Pixel 9").isEmpty())
    }

    @Test fun firstLensPerCameraUsesRouteLaterOnesVirtual() {
        val ultra = DeviceLensProfiles.all.first { it.id == "vivo_x300_ultra" }
        val keys = DeviceLensProfiles.plan(ultra).associate { (lens, key) -> lens.name to key }
        assertEquals("4", keys["L14"])
        assertEquals("virtual:dp_vivo_x300_ultra_D14", keys["D14"])
        assertEquals("5", keys["L85"])
        assertEquals("virtual:dp_vivo_x300_ultra_Z340", keys["Z340"])
    }

    @Test fun profilesFitStoreLimits() {
        DeviceLensProfiles.all.forEach { profile ->
            assertEquals(profile.id, profile.lenses.size, profile.lenses.map { it.name }.distinct().size)
            profile.lenses.forEach { lens ->
                assertTrue(lens.name, lens.name.length <= LensListStore.NAME_MAX_LENGTH)
                assertTrue(lens.name, lens.name.length <= VirtualLens.MAX_ZOOM)
                assertTrue(lens.rawFormat == null || lens.rawFormat == "RAW10")
                assertTrue(lens.rawSize == null || RawSizePreferenceStore.isPlausibleSize(lens.rawSize))
            }
        }
    }

    @Test fun vivoUltraRequiresMatchingSessionModeAndRoute() {
        val mode = VendorKeyProfile(listOf(
            VendorKeyEntry(DeviceLensProfiles.FORCE_MODE, VendorKeyType.INT, VendorKeyScope.SESSION, "31")
        ))
        val stream = DeviceLensProfiles.vivoUltraStream("3", "3", mode, "V2562")
        assertEquals("RAW10", stream?.rawFormat)
        assertEquals("4080x3064", stream?.rawSize)
        assertNull(DeviceLensProfiles.vivoUltraStream("3", "3", VendorKeyProfile(), "V2562"))
        assertNull(DeviceLensProfiles.vivoUltraStream("4", "4", mode, "V2562"))
    }
}
