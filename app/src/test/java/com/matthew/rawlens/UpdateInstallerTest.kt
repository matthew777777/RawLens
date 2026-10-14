// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallerTest {
    @Test fun stagingNameEmbedsTag() {
        assertEquals(
            "RawLens-1.0.153-update.apk",
            UpdateInstaller.apkFileName("1.0.153")
        )
    }

    @Test fun stagingNameSanitizesTag() {
        // A hostile tag must not escape the staging dir.
        val name = UpdateInstaller.apkFileName("../../v1.0/x")
        assertFalse(name.contains("/"))
        assertTrue(name.startsWith("RawLens-"))
        assertTrue(name.endsWith("-update.apk"))
    }

    @Test fun blankTagFallsBack() {
        assertEquals(
            "RawLens-unknown-update.apk",
            UpdateInstaller.apkFileName("  ")
        )
    }
}
