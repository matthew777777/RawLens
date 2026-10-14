// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrBracketExclusivityTest {
    // The bug from the field: the HDR tile and the HDR+ bottom tile could
    // both show ON (both highlighted), but the shutter can only run one
    // merger per press, so one of them silently never ran.

    @Test fun enablingBracketTurnsHdrPlusOff() {
        assertEquals(true to false, HdrShutterExclusivity.bracketToggled(false, true))
        assertEquals(true to false, HdrShutterExclusivity.bracketToggled(false, false))
    }

    @Test fun enablingHdrPlusTurnsBracketOff() {
        assertEquals(false to true, HdrShutterExclusivity.plusToggled(true, false))
        assertEquals(false to true, HdrShutterExclusivity.plusToggled(false, false))
    }

    @Test fun turningOneOffLeavesTheOtherAlone() {
        assertEquals(false to true, HdrShutterExclusivity.bracketToggled(true, true))
        assertEquals(false to false, HdrShutterExclusivity.bracketToggled(true, false))
        assertEquals(true to false, HdrShutterExclusivity.plusToggled(true, true))
        assertEquals(false to false, HdrShutterExclusivity.plusToggled(false, true))
    }

    @Test fun staleBothOnResolvesToHdrPlus() {
        assertEquals(false to true, HdrShutterExclusivity.resolveStale(true, true))
    }

    @Test fun otherStalePairsPassThrough() {
        assertEquals(true to false, HdrShutterExclusivity.resolveStale(true, false))
        assertEquals(false to true, HdrShutterExclusivity.resolveStale(false, true))
        assertEquals(false to false, HdrShutterExclusivity.resolveStale(false, false))
    }

    @Test fun bracketOnLeavesZslForAutoOnly() {
        assertEquals(
            CaptureExposureMode.AUTO,
            HdrShutterExclusivity.modeAfterBracketEnabled(CaptureExposureMode.ZSL)
        )
        for (mode in listOf(
            CaptureExposureMode.AUTO,
            CaptureExposureMode.PROGRAM,
            CaptureExposureMode.MANUAL
        )) {
            assertEquals(mode, HdrShutterExclusivity.modeAfterBracketEnabled(mode))
        }
    }
}
