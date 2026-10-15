// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class UpdateConsentTest {
    @Test fun asksOnceOnUpdaterBuilds() {
        assertTrue(UpdateConsent.shouldShow(updaterEnabled = true, consentAsked = false))
        assertFalse(UpdateConsent.shouldShow(updaterEnabled = true, consentAsked = true))
    }

    @Test fun neverAsksWithoutUpdater() {
        // F-Droid builds leave updates to the store: no dialog, no check.
        assertFalse(UpdateConsent.shouldShow(updaterEnabled = false, consentAsked = false))
        assertFalse(UpdateConsent.shouldShow(updaterEnabled = false, consentAsked = true))
    }
}
