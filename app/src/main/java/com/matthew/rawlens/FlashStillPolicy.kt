// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.RawCameraController.FlashStillMode

/**
 * Still-flash decisions: flash mode per request, the AE mode it needs, and
 * whether an AE precapture sequence must run first.
 *
 * Moved verbatim from RawCameraController's companion (A10,
 * docs/refactor/controller-remaining-plan.md).
 */
internal object FlashStillPolicy {
    const val AUTO_UNAVAILABLE_NOTE =
        "Auto flash is unavailable in PROGRAM and ETTR. RawLens needs its own auto-flash metering."

    fun allowsAutoFlash(programMode: Boolean, ettrActive: Boolean): Boolean =
        !programMode && !ettrActive

    fun nextMode(current: FlashStillMode, autoAllowed: Boolean): FlashStillMode = when (current) {
        FlashStillMode.OFF -> FlashStillMode.ON
        FlashStillMode.ON -> if (autoAllowed) FlashStillMode.AUTO else FlashStillMode.OFF
        FlashStillMode.AUTO -> FlashStillMode.OFF
    }

    fun permittedMode(current: FlashStillMode, autoAllowed: Boolean): FlashStillMode =
        if (current == FlashStillMode.AUTO && !autoAllowed) FlashStillMode.OFF else current

    /**
     * Still-flash AE resolution (P0/P3, pure for unit tests). Manual
     * exposure keeps AE OFF (the flash fires via FLASH SINGLE, not the AE
     * unit). Hardware AE uses the advertised flash AE mode matching the
     * still-flash setting, falling back to plain AE ON when the HAL does
     * not advertise it. Never returns TORCH: TORCH is preview-assist only.
     *
     * @param flashModeOrdinal [FlashStillMode] ordinal (OFF/ON/AUTO) so
     * tests stay off the Android framework; only primitive args in/out.
     */
    internal fun resolveStillFlashAeMode(
        flashModeOrdinal: Int,
        availableAeModes: IntArray,
        flashAvailable: Boolean
    ): Int {
        if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) {
            return android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
        }
        val want = if (flashModeOrdinal == FlashStillMode.ON.ordinal) {
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        }
        return if (availableAeModes.contains(want)) {
            want
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
        }
    }

    /**
     * Still FLASH_MODE resolution (P0, pure for unit tests): SINGLE only
     * for manual exposure (AE OFF, unmetered fire); OFF everywhere else —
     * under hardware AE the flash AE mode drives the unit. TORCH is never
     * returned: TORCH is preview-assist only and would smear a still.
     */
    internal fun resolveStillFlashMode(
        flashModeOrdinal: Int,
        manualExposure: Boolean,
        flashAvailable: Boolean
    ): Int {
        if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) {
            return android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF
        }
        if (manualExposure) return android.hardware.camera2.CaptureRequest.FLASH_MODE_SINGLE
        return android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF
    }

    /**
     * Precapture gate (P0/P3, pure for unit tests). Metering precapture is
     * only meaningful for AE-driven flash on a full-capability HAL: manual
     * exposure fires an unmetered SINGLE, LEGACY HALs meter best-effort
     * without a precapture round-trip, and an unadvertised flash AE mode
     * means the HAL cannot meter flash at all.
     */
    internal fun needsFlashPrecapture(
        manualExposure: Boolean,
        flashModeOrdinal: Int,
        availableAeModes: IntArray,
        flashAvailable: Boolean,
        hardwareLevelLegacy: Boolean
    ): Boolean {
        if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) return false
        if (manualExposure || hardwareLevelLegacy) return false
        val want = if (flashModeOrdinal == FlashStillMode.ON.ordinal) {
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        }
        return availableAeModes.contains(want)
    }
}
