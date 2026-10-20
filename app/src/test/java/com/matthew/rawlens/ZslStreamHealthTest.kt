// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.ZslStreamHealth.OutageAction
import com.matthew.rawlens.ZslStreamHealth.OverflowAction
import com.matthew.rawlens.ZslStreamHealth.WatchdogVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslStreamHealthTest {
    private var now = 10_000L
    private val health = ZslStreamHealth { now }

    @Test
    fun briefOverflowDrains() {
        health.startStream()
        now += ZslOverflowTracker.DEFAULT_MAX_STALL_MS
        assertEquals(OverflowAction.DRAIN, health.onReaderOverflow())
        assertFalse(health.streamBroken)
    }

    @Test
    fun overflowStormBeforeFillLatchesBurstMode() {
        health.startStream()
        now += ZslOverflowTracker.DEFAULT_MAX_STALL_MS + 1
        assertEquals(OverflowAction.LATCH_AND_COOLDOWN, health.onReaderOverflow())
        assertTrue(health.streamBroken)
        assertTrue(health.isBlocked(disabledForSession = false))
    }

    @Test
    fun overflowStormAfterFillOnlyCoolsDown() {
        health.startStream()
        health.filled = true
        now += ZslOverflowTracker.DEFAULT_MAX_STALL_MS + 1
        assertEquals(OverflowAction.COOLDOWN, health.onReaderOverflow())
        assertFalse(health.streamBroken)
    }

    @Test
    fun admissionResetsStallClock() {
        health.startStream()
        now += 1_900L
        health.onRingAdmission()
        now += 1_900L
        assertEquals(OverflowAction.DRAIN, health.onReaderOverflow())
    }

    @Test
    fun zeroPairWindowLatches() {
        health.startStream()
        assertEquals(WatchdogVerdict.LatchAndCooldown, health.onWatchdogWindowExpired())
        assertTrue(health.streamBroken)
    }

    @Test
    fun slowWindowsExtendTwiceThenCoolDown() {
        health.startStream()
        repeat(3) { health.onRingAdmission() }
        assertEquals(WatchdogVerdict.Extend(3, 1), health.onWatchdogWindowExpired())
        assertEquals(0, health.epochPairs)
        health.onRingAdmission()
        assertEquals(WatchdogVerdict.Extend(1, 2), health.onWatchdogWindowExpired())
        health.onRingAdmission()
        assertEquals(WatchdogVerdict.Cooldown, health.onWatchdogWindowExpired())
        assertFalse(health.streamBroken)
    }

    @Test
    fun wedgedAfterExtensionStillLatches() {
        health.startStream()
        health.onRingAdmission()
        health.onWatchdogWindowExpired()
        assertEquals(WatchdogVerdict.LatchAndCooldown, health.onWatchdogWindowExpired())
    }

    @Test
    fun secondFruitlessOutageRebuildsUnlessCaptureBusy() {
        assertEquals(OutageAction.PARK, health.onTransientOutage(2_000L, captureBusy = false))
        assertEquals(now + 2_000L, health.cooldownUntilMs)
        assertTrue(health.isBlocked(disabledForSession = false))
        assertEquals(OutageAction.PARK, health.onTransientOutage(2_000L, captureBusy = true))
        assertEquals(OutageAction.REBUILD_SESSION, health.onTransientOutage(2_000L, captureBusy = false))
        assertEquals(OutageAction.PARK, health.onTransientOutage(2_000L, captureBusy = false))
    }

    @Test
    fun anyPairMakesNextOutageFruitfulAgain() {
        health.onTransientOutage(2_000L, captureBusy = false)
        health.onForwardPair()
        assertEquals(OutageAction.PARK, health.onTransientOutage(2_000L, captureBusy = false))
    }

    @Test
    fun cooldownExpires() {
        health.onTransientOutage(2_000L, captureBusy = false)
        now += 1_999L
        assertFalse(health.cooldownExpired())
        now += 1L
        assertTrue(health.cooldownExpired())
        assertFalse(health.isBlocked(disabledForSession = false))
        assertTrue(health.isBlocked(disabledForSession = true))
    }

    @Test
    fun sessionResetAndUserReentryClearLatches() {
        health.startStream()
        health.onWatchdogWindowExpired()
        health.onTransientOutage(2_000L, captureBusy = false)
        health.clearLatches()
        assertFalse(health.isBlocked(disabledForSession = false))
        health.filled = true
        health.resetForSession()
        assertFalse(health.filled)
        // resetForSession also clears the fruitless counter.
        assertEquals(OutageAction.PARK, health.onTransientOutage(2_000L, captureBusy = false))
    }

    @Test
    fun watchdogTimeoutTakesTheLargestBound() {
        assertEquals(4_000L, ZslStreamHealth.watchdogTimeoutMs(33_000_000L, 3))
        assertEquals(9_500L, ZslStreamHealth.watchdogTimeoutMs(8_000_000_000L, 3))
        assertEquals(30_000L, ZslStreamHealth.watchdogTimeoutMs(33_000_000L, 30))
    }
}
