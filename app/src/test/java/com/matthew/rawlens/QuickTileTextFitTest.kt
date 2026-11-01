// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTileTextFitTest {
    @Test fun shortLabelsKeepTheirOriginalSize() {
        assertEquals(1f, QuickTileTextFit.scale(30f, 22f, 44f, 110f), 0f)
    }

    @Test fun landscapeResolutionFitsTheShortSideOfTheTile() {
        val scale = QuickTileTextFit.scale(58f, 24f, 44f, 156f)
        assertTrue(scale < 1f)
        assertEquals(44f, 58f * scale, .001f)
        assertTrue(24f * scale < 156f)
    }

    @Test fun textStaysWithinBothPortraitAndLandscapeBounds() {
        for (width in listOf(24f, 44f, 58f, 90f)) {
            for (height in listOf(20f, 30f, 70f)) {
                for ((availableWidth, availableHeight) in listOf(44f to 156f, 74f to 44f, 156f to 44f)) {
                    val scale = QuickTileTextFit.scale(width, height, availableWidth, availableHeight)
                    assertTrue(scale > 0f && scale <= 1f)
                    assertTrue(width * scale <= availableWidth + .001f)
                    assertTrue(height * scale <= availableHeight + .001f)
                }
            }
        }
    }
}
