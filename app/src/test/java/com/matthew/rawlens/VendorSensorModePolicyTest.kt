// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
import org.junit.Assert.*
import org.junit.Test
class VendorSensorModePolicyTest {
    private val mode = VendorKeyEntry(VendorSensorModePolicy.CURRENT_MODE, VendorKeyType.INT, VendorKeyScope.REQUEST, "8")
    @Test fun `high rate mode starts only after RAW then retains saved profile`() {
        val profile = VendorKeyProfile(listOf(mode), rawPreviewFps = 60)
        assertTrue(VendorSensorModePolicy.startupProfile(profile, false).requestEntries().isEmpty())
        assertEquals(profile, VendorSensorModePolicy.startupProfile(profile, true))
        assertEquals(listOf(mode), profile.entries)
    }
    @Test fun `normal keys session scope and other modes are unchanged`() {
        for (entry in listOf(mode.copy(scope = VendorKeyScope.SESSION), mode.copy(value = "1"),
            mode.copy(enabled = false), mode.copy(name = "vivo.control.forceSensorMode"))) {
            val profile = VendorKeyProfile(listOf(entry), rawPreviewFps = 60)
            assertEquals(profile, VendorSensorModePolicy.startupProfile(profile, false))
        }
        assertEquals(VendorKeyProfile(listOf(mode)),
            VendorSensorModePolicy.startupProfile(VendorKeyProfile(listOf(mode)), false))
    }
}
