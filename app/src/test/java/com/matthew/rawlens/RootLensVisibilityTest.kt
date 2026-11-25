// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RootLensVisibilityTest {
    @Test fun enabledAuxiliaryLensSurvivesTransientRootRouteLoss() {
        assertTrue(LapisRootProfile.keepSwitcherLens(true, true, false))
        assertFalse(LapisRootProfile.keepSwitcherLens(true, false, false))
        assertFalse(LapisRootProfile.keepSwitcherLens(false, true, false))
        assertTrue(LapisRootProfile.keepSwitcherLens(false, true, true))
    }

    @Test fun activeLensStaysVisibleButOtherHiddenModesRemainHidden() {
        val wide = LensOption("2", "16 mm", true)
        assertTrue(LapisRootProfile.showSwitcherLens(wide, false))
        assertTrue(LapisRootProfile.showSwitcherLens(wide.copy(selected = false), true))
        assertFalse(LapisRootProfile.showSwitcherLens(wide.copy(selected = false), false))
        val dcg = LensOption("0", "DCG14", false, "dcg14")
        assertFalse(LapisRootProfile.showSwitcherLens(dcg, false))
    }
}
