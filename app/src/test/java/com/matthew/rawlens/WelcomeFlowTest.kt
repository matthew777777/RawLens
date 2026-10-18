// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class WelcomeFlowTest {
    @Test fun firstRunOrderIsWelcomePermissionsThenLensDiscovery() {
        assertEquals(
            listOf(
                WelcomeFlow.Step.WELCOME,
                WelcomeFlow.Step.PERMISSIONS,
                WelcomeFlow.Step.LENS_DISCOVERY,
            ),
            WelcomeFlow.steps(),
        )
    }

    @Test fun updateAskLivesOnPermissionsPageOnly() {
        // Updater builds ask on the permissions page, alongside the camera
        // and photo-folder grants — never layered over the welcome content
        // and never inside lens discovery.
        assertFalse(WelcomeFlow.showsUpdateAsk(WelcomeFlow.Step.WELCOME, true))
        assertTrue(WelcomeFlow.showsUpdateAsk(WelcomeFlow.Step.PERMISSIONS, true))
        assertFalse(WelcomeFlow.showsUpdateAsk(WelcomeFlow.Step.LENS_DISCOVERY, true))
    }

    @Test fun updateAskNeverShowsWithoutUpdater() {
        // F-Droid builds leave updates to the store: no ask on any page.
        for (step in WelcomeFlow.steps()) {
            assertFalse(WelcomeFlow.showsUpdateAsk(step, false))
        }
    }

    @Test fun updateConsentWaitsForLensDiscovery() {
        // Lens setup still pending: discovery owns the screen, so the
        // keep-up-to-date ask defers instead of layering over it.
        assertTrue(WelcomeFlow.shouldDeferUpdateConsent(lensSetupComplete = false))
        assertFalse(WelcomeFlow.shouldDeferUpdateConsent(lensSetupComplete = true))
    }
}
