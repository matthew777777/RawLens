// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewPanelSwipeTest {
    private val quick = PreviewPanelSwipe.Action.QUICK_CONTROLS
    private val settings = PreviewPanelSwipe.Action.SETTINGS

    @Test fun upOpensQuickControlsAndDownOpensSettings() {
        assertEquals(quick, PreviewPanelSwipe.classify(0f, -80f, 48f))
        assertEquals(settings, PreviewPanelSwipe.classify(0f, 80f, 48f))
        assertEquals(quick, PreviewPanelSwipe.classify(0f, -48f, 48f))
    }

    @Test fun tapsJitterAndHorizontalPansDoNotOpenPanels() {
        for ((dx, dy) in listOf(0f to 0f, 4f to 20f, 0f to -47f, 90f to 10f, 80f to 80f)) {
            assertNull(PreviewPanelSwipe.classify(dx, dy, 48f))
        }
    }

    @Test fun slightlyDiagonalVerticalSwipesStillWork() {
        assertEquals(quick, PreviewPanelSwipe.classify(30f, -80f, 48f))
        assertEquals(settings, PreviewPanelSwipe.classify(-30f, 80f, 48f))
    }

    @Test fun landscapeDirectionsFollowTheUsersOrientation() {
        assertEquals(quick, PreviewPanelSwipe.classify(-80f, 0f, 48f, 90))
        assertEquals(settings, PreviewPanelSwipe.classify(80f, 0f, 48f, 90))
        assertEquals(quick, PreviewPanelSwipe.classify(80f, 0f, 48f, 270))
        assertEquals(settings, PreviewPanelSwipe.classify(-80f, 0f, 48f, 270))
        assertNull(PreviewPanelSwipe.classify(0f, -80f, 48f, 90))
    }

    @Test fun upsideDownAndWrappedOrientationsKeepDirection() {
        assertEquals(quick, PreviewPanelSwipe.classify(0f, 80f, 48f, 180))
        assertEquals(settings, PreviewPanelSwipe.classify(0f, -80f, 48f, 180))
        assertEquals(quick, PreviewPanelSwipe.classify(80f, 0f, 48f, -90))
        assertEquals(quick, PreviewPanelSwipe.classify(-80f, 0f, 48f, 450))
    }
}
