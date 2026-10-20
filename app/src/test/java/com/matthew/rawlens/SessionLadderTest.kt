// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLadderTest {
    private val ladder = SessionLadder()

    @Test
    fun altRouteFirstThenPrimaryDegradesWithoutAlt() {
        ladder.selectLens("0")
        assertTrue(ladder.altRouteAvailable("5"))
        ladder.switchToAltRoute()
        assertTrue(ladder.altRouteActive)
        assertFalse("alternate is used once", ladder.altRouteAvailable("5"))
        assertTrue(ladder.canDegrade("0", comboCount = 3))
        assertEquals(1, ladder.degrade())
        assertFalse("degrading returns to the primary route", ladder.altRouteActive)
        assertEquals(2, ladder.degrade())
        assertFalse(ladder.canDegrade("0", comboCount = 3))
    }

    @Test
    fun failedAltOrPrimaryStopsThatRung() {
        ladder.recordFailedOpen("5")
        assertFalse(ladder.altRouteAvailable("5"))
        assertFalse(ladder.altRouteAvailable(null))
        ladder.recordFailedOpen("0")
        assertFalse(ladder.canDegrade("0", comboCount = 3))
        assertTrue("unknown primary id never counts as failed", ladder.canDegrade(null, comboCount = 3))
    }

    @Test
    fun lensChangeResetsLadderButSameLensKeepsIt() {
        ladder.selectLens("0")
        ladder.degrade()
        ladder.recordFailedOpen("5")
        ladder.selectLens("0")
        assertEquals("sticky per lens", 1, ladder.comboIndex)
        ladder.selectLens("2")
        assertEquals(0, ladder.comboIndex)
        assertTrue(ladder.altRouteAvailable("5"))
    }

    @Test
    fun denylistSurvivesLensChangeUntilCleared() {
        ladder.selectLens("0")
        ladder.recordFailedOpen("5")
        ladder.denylist("0")
        assertEquals(setOf("0", "5"), ladder.unopenableOpenIds)
        ladder.selectLens("2")
        assertEquals(setOf("0", "5"), ladder.unopenableOpenIds)
        ladder.clearDenylist()
        assertTrue(ladder.unopenableOpenIds.isEmpty())
    }

    @Test
    fun retryBudgetsAreBoundedAndResetOnOpen() {
        assertEquals(1, ladder.takeRouteRetry(max = 2))
        assertEquals(2, ladder.takeRouteRetry(max = 2))
        assertNull(ladder.takeRouteRetry(max = 2))
        assertEquals(1, ladder.takeRejectionRetry(max = 1))
        assertNull(ladder.takeRejectionRetry(max = 1))
        ladder.onOpened("0")
        assertEquals(1, ladder.takeRouteRetry(max = 2))
        assertEquals(1, ladder.takeRejectionRetry(max = 1))
        assertEquals(setOf("0"), ladder.openedOnceOpenIds)
    }

    @Test
    fun startResetsOnlyTheRouteBudget() {
        ladder.takeRouteRetry(max = 1)
        ladder.takeRejectionRetry(max = 1)
        ladder.resetRouteRetries()
        assertEquals(1, ladder.takeRouteRetry(max = 1))
        assertNull(ladder.takeRejectionRetry(max = 1))
    }
}
