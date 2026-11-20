// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class FirstUseAsksTest {
    @Test fun captureSavedMatchesSaveLinesOnly() {
        assertTrue(FirstUseAsks.isCaptureSaved("HDR+ DNG SAVED"))
        assertTrue(FirstUseAsks.isCaptureSaved("BURST ×8 • SAVED"))
        assertTrue(FirstUseAsks.isCaptureSaved("SAVED IMG_1.dng"))
        assertFalse(FirstUseAsks.isCaptureSaved("RECORDING FOLDER SAVED"))
        assertFalse(FirstUseAsks.isCaptureSaved("FOLDER ACCESS NOT SAVED"))
        assertFalse(FirstUseAsks.isCaptureSaved("RAW HDR TEST DNG SAVED"))
        assertFalse(FirstUseAsks.isCaptureSaved("SAVING ZSL ×8"))
    }

    @Test fun locationAskedOnceAfterFirstSaveAndNotWhenGpsOn() {
        assertTrue(FirstUseAsks.shouldAskLocation("DNG SAVED", asked = false, gpsOn = false))
        assertFalse(FirstUseAsks.shouldAskLocation("DNG SAVED", asked = true, gpsOn = false))
        assertFalse(FirstUseAsks.shouldAskLocation("DNG SAVED", asked = false, gpsOn = true))
        assertFalse(FirstUseAsks.shouldAskLocation("CAPTURING", asked = false, gpsOn = false))
    }

    @Test fun folderAskTriggersOnZslBurstSave() {
        assertTrue(FirstUseAsks.isSidecarBurstSave("SAVING ZSL ×8"))
        assertFalse(FirstUseAsks.isSidecarBurstSave("DNG SAVED"))
    }

    @Test fun updateAskWaitsForThirdLaunch() {
        assertFalse(FirstUseAsks.shouldAskUpdates(1))
        assertFalse(FirstUseAsks.shouldAskUpdates(2))
        assertTrue(FirstUseAsks.shouldAskUpdates(3))
        assertTrue(FirstUseAsks.shouldAskUpdates(10))
    }
}
