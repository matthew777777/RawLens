// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashStillPolicyTest {
    private val off = RawCameraController.FlashStillMode.OFF.ordinal
    private val on = RawCameraController.FlashStillMode.ON.ordinal
    private val auto = RawCameraController.FlashStillMode.AUTO.ordinal

    private val fullAeModes = intArrayOf(
        CaptureRequest.CONTROL_AE_MODE_OFF,
        CaptureRequest.CONTROL_AE_MODE_ON,
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
    )
    private val noFlashAeModes = intArrayOf(
        CaptureRequest.CONTROL_AE_MODE_OFF,
        CaptureRequest.CONTROL_AE_MODE_ON
    )

    // AE mode resolution: flash ON wants ALWAYS, AUTO wants AUTO_FLASH,
    // anything unsupported falls back to plain ON, OFF stays ON.

    @Test
    fun flashOffResolvesPlainAeOn() {
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON,
            FlashStillPolicy.resolveStillFlashAeMode(off, fullAeModes, true)
        )
    }

    @Test
    fun flashUnavailableResolvesPlainAeOn() {
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON,
            FlashStillPolicy.resolveStillFlashAeMode(on, fullAeModes, false)
        )
    }

    @Test
    fun flashOnUsesAlwaysFlashWhenAdvertised() {
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
            FlashStillPolicy.resolveStillFlashAeMode(on, fullAeModes, true)
        )
    }

    @Test
    fun flashAutoUsesAutoFlashWhenAdvertised() {
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
            FlashStillPolicy.resolveStillFlashAeMode(auto, fullAeModes, true)
        )
    }

    @Test
    fun unsupportedFlashAeModeFallsBackToPlainOn() {
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON,
            FlashStillPolicy.resolveStillFlashAeMode(on, noFlashAeModes, true)
        )
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON,
            FlashStillPolicy.resolveStillFlashAeMode(auto, noFlashAeModes, true)
        )
    }

    // FLASH_MODE resolution: SINGLE only under manual exposure, OFF
    // otherwise, TORCH never.

    @Test
    fun manualExposureFiresSingle() {
        assertEquals(
            CaptureRequest.FLASH_MODE_SINGLE,
            FlashStillPolicy.resolveStillFlashMode(on, true, true)
        )
        assertEquals(
            CaptureRequest.FLASH_MODE_SINGLE,
            FlashStillPolicy.resolveStillFlashMode(auto, true, true)
        )
    }

    @Test
    fun hardwareAeNeverSetsFlashMode() {
        assertEquals(
            CaptureRequest.FLASH_MODE_OFF,
            FlashStillPolicy.resolveStillFlashMode(on, false, true)
        )
        assertEquals(
            CaptureRequest.FLASH_MODE_OFF,
            FlashStillPolicy.resolveStillFlashMode(auto, false, true)
        )
    }

    @Test
    fun torchIsNeverAResolution() {
        for (mode in intArrayOf(off, on, auto)) {
            for (manual in booleanArrayOf(false, true)) {
                for (available in booleanArrayOf(false, true)) {
                    val resolved = FlashStillPolicy.resolveStillFlashMode(mode, manual, available)
                    assertTrue(
                        resolved == CaptureRequest.FLASH_MODE_OFF ||
                            resolved == CaptureRequest.FLASH_MODE_SINGLE
                    )
                }
            }
        }
        assertEquals(
            CaptureRequest.FLASH_MODE_OFF,
            FlashStillPolicy.resolveStillFlashMode(off, true, true)
        )
    }

    // Precapture gate: only AE-driven flash on a full HAL with an
    // advertised flash AE mode.

    @Test
    fun precaptureOnlyForAeDrivenFlash() {
        assertTrue(FlashStillPolicy.needsFlashPrecapture(false, on, fullAeModes, true, false))
        assertTrue(FlashStillPolicy.needsFlashPrecapture(false, auto, fullAeModes, true, false))
    }

    @Test
    fun noPrecaptureWhenOffOrUnavailable() {
        assertFalse(FlashStillPolicy.needsFlashPrecapture(false, off, fullAeModes, true, false))
        assertFalse(FlashStillPolicy.needsFlashPrecapture(false, on, fullAeModes, false, false))
    }

    @Test
    fun noPrecaptureForManualOrLegacy() {
        assertFalse(FlashStillPolicy.needsFlashPrecapture(true, on, fullAeModes, true, false))
        assertFalse(FlashStillPolicy.needsFlashPrecapture(false, on, fullAeModes, true, true))
    }

    @Test
    fun noPrecaptureWhenFlashAeModeUnsupported() {
        assertFalse(FlashStillPolicy.needsFlashPrecapture(false, on, noFlashAeModes, true, false))
        assertFalse(FlashStillPolicy.needsFlashPrecapture(false, auto, noFlashAeModes, true, false))
    }
    @Test fun programAndEttrDoNotOfferAutoFlash() {
        assertFalse(FlashStillPolicy.allowsAutoFlash(programMode = true, ettrActive = false))
        assertFalse(FlashStillPolicy.allowsAutoFlash(programMode = false, ettrActive = true))
        assertFalse(FlashStillPolicy.allowsAutoFlash(programMode = true, ettrActive = true))
        assertTrue(FlashStillPolicy.allowsAutoFlash(programMode = false, ettrActive = false))
    }

    @Test fun unavailableAutoIsSkippedAndExistingAutoBecomesOff() {
        assertEquals(RawCameraController.FlashStillMode.OFF, FlashStillPolicy.nextMode(RawCameraController.FlashStillMode.ON, false))
        assertEquals(RawCameraController.FlashStillMode.ON, FlashStillPolicy.nextMode(RawCameraController.FlashStillMode.OFF, false))
        assertEquals(RawCameraController.FlashStillMode.OFF, FlashStillPolicy.permittedMode(RawCameraController.FlashStillMode.AUTO, false))
        assertEquals(RawCameraController.FlashStillMode.ON, FlashStillPolicy.permittedMode(RawCameraController.FlashStillMode.ON, false))
        assertEquals(RawCameraController.FlashStillMode.AUTO, FlashStillPolicy.nextMode(RawCameraController.FlashStillMode.ON, true))
    }

}
