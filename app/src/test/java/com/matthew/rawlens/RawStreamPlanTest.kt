// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStreamPlanTest {
    private val idle = RawStreamPlan.Inputs(
        allowRawZsl = true, zslRestartFitsReader = true, zslRequested = true, zslBlocked = false,
        zslCapacity = 8, readerPresent = true, rawRetryReady = true, captureInProgress = false,
        stillExecuting = false, histogramEnabled = true, histogramDisabledForSession = false,
        ettrEnabled = true, ettrModeAvailable = true, manualIsoOrShutter = false,
        exposureModeManual = false, programCustomActive = true, hasRawViewfinder = true,
    )

    @Test
    fun zslExcludesEveryMeteringStream() {
        val p = RawStreamPlan.plan(idle)
        assertTrue(p.zsl)
        assertFalse(p.histogram || p.ettr || p.program)
        assertTrue(p.viewfinder && p.anyRaw)
    }

    @Test
    fun reservedCaptureKeepsZslButStopsMetering() {
        val held = RawStreamPlan.plan(idle.copy(captureInProgress = true, stillExecuting = false))
        assertTrue("a reserved slot must not block the ring", held.zsl)
        val noZsl = RawStreamPlan.plan(idle.copy(zslRequested = false, captureInProgress = true))
        assertFalse(noZsl.histogram || noZsl.ettr || noZsl.program)
    }

    @Test
    fun executingStillStopsZsl() {
        assertFalse(RawStreamPlan.plan(idle.copy(captureInProgress = true, stillExecuting = true)).zsl)
    }

    @Test
    fun blockedOrUnfittingZslFallsBackToMetering() {
        for (inputs in listOf(idle.copy(zslBlocked = true), idle.copy(zslRestartFitsReader = false),
            idle.copy(allowRawZsl = false), idle.copy(zslCapacity = 0))) {
            val p = RawStreamPlan.plan(inputs)
            assertFalse(p.zsl)
            assertEquals(inputs.zslCapacity > 0, p.histogram)
        }
    }

    @Test
    fun manualExposureDisablesEttrOnly() {
        val p = RawStreamPlan.plan(idle.copy(zslRequested = false, manualIsoOrShutter = true))
        assertFalse(p.ettr)
        assertTrue(p.histogram && p.program)
        assertFalse(RawStreamPlan.plan(idle.copy(zslRequested = false, exposureModeManual = true)).ettr)
    }

    @Test
    fun viewfinderNeedsReaderAndRetryWindowOnly() {
        assertTrue(RawStreamPlan.plan(idle.copy(zslCapacity = 0, zslRequested = false)).viewfinder)
        assertFalse(RawStreamPlan.plan(idle.copy(rawRetryReady = false)).anyRaw)
        assertFalse(RawStreamPlan.plan(idle.copy(readerPresent = false)).anyRaw)
    }
}
