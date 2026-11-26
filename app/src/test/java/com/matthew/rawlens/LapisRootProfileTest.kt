// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class LapisRootProfileTest {
    @Test fun videoReadoutKeepsRequestedCadencesAndDcgKeepsItsFixedRate() {
        val video=LapisRootSession.Spec(LapisRootProfile.Mode.VIDEO60)
        assertEquals(4080,video.width);assertEquals(2304,video.height)
        for(rate in listOf(24,30,60))assertEquals(rate,video.recordingFps(rate))
        assertEquals(30,LapisRootSession.Spec(LapisRootProfile.Mode.DCG14).recordingFps(60))
        assertEquals(24,LapisRootSession.Spec(LapisRootProfile.Mode.VIDEO60,24).fps)
    }

    @Test fun limitsRootPatchesToTheVerifiedPhoneNotTheFirmwareName() {
        val firmware = "Redmi/lapis_eea/lapis:16/ABCD/${LapisRootProfile.FIRMWARE}:user/release-keys"
        assertTrue(LapisRootProfile.matches("lapis","25080RABDG",firmware,""))
        assertFalse(LapisRootProfile.matches("sweet","25080RABDG",firmware,""))
        assertFalse(LapisRootProfile.matches("lapis","another model",firmware,""))
        // OTAs keep the mode; the root worker's library hash checks are the real guard.
        assertTrue(LapisRootProfile.matches("lapis","25080RABDG","Redmi/lapis_eea/lapis:16/X/OS3.0.305.0.WPPEUXM:user/release-keys",""))
    }
}
