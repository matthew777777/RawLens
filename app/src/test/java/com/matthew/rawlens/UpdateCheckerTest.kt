// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun newerPatchIsUpdate() {
        assertTrue(UpdateChecker.isNewerThan("1.0.153", "1.0.184"))
    }

    @Test fun sameVersionIsNotUpdate() {
        assertFalse(UpdateChecker.isNewerThan("1.0.184", "1.0.184"))
    }

    @Test fun olderTagIsNotUpdate() {
        assertFalse(UpdateChecker.isNewerThan("1.0.184", "1.0.153"))
    }

    @Test fun numericNotLexicographic() {
        // "1.0.9" < "1.0.10" numerically, opposite lexicographically.
        assertTrue(UpdateChecker.isNewerThan("1.0.9", "1.0.10"))
        assertFalse(UpdateChecker.isNewerThan("1.0.10", "1.0.9"))
    }

    @Test fun vPrefixAndSuffixIgnored() {
        assertTrue(UpdateChecker.isNewerThan("1.0.184", "v1.0.185"))
        assertFalse(UpdateChecker.isNewerThan("1.0.185", "v1.0.185-rc1"))
    }

    @Test fun unequalLengthPadsWithZero() {
        assertFalse(UpdateChecker.isNewerThan("1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewerThan("1.0", "1.0.1"))
    }
}
