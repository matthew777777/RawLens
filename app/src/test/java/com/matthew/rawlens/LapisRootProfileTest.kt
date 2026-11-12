// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class LapisRootProfileTest {
    @Test fun limitsRootPatchesToTheVerifiedPhoneAndFirmware() {
        val firmware = "Redmi/lapis_eea/lapis:16/ABCD/${LapisRootProfile.FIRMWARE}:user/release-keys"
        assertTrue(LapisRootProfile.matches("lapis","25080RABDG",firmware,""))
        assertFalse(LapisRootProfile.matches("sweet","25080RABDG",firmware,""))
        assertFalse(LapisRootProfile.matches("lapis","another model",firmware,""))
        assertFalse(LapisRootProfile.matches("lapis","25080RABDG","new firmware",""))
    }
}
