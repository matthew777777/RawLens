// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.SessionConfiguration

/** AOSP reserves 1 for high speed; vendor stream modes start at 0x8000. */
internal data class VendorSessionAttempt(val operatingMode: Int, val parameters: Boolean) {
    companion object {
        const val VENDOR_START = 0x8000

        fun validMode(mode: Int): Boolean = mode == 0 || mode >= VENDOR_START

        /** Each opt-in axis is removed once; default profiles have exactly one attempt. */
        fun sequence(mode: Int, hasParameters: Boolean): List<VendorSessionAttempt> = buildList {
            add(VendorSessionAttempt(mode.takeIf(::validMode) ?: 0, hasParameters))
            if (mode >= VENDOR_START) add(VendorSessionAttempt(0, hasParameters))
            if (hasParameters) add(VendorSessionAttempt(0, false))
        }.distinct()
    }
}

/**
 * AOSP API 30–36 stores sessionType verbatim. CameraDeviceImpl forwards that
 * type AND sessionParameters, unlike createCustomCaptureSession's null params.
 * No hidden API invocation or mutation of the original configuration.
 */
internal fun vendorSessionConfiguration(
    base: SessionConfiguration,
    attempt: VendorSessionAttempt,
    callback: CameraCaptureSession.StateCallback
): SessionConfiguration = SessionConfiguration(
    attempt.operatingMode, base.outputConfigurations, base.executor, callback
).apply {
    base.inputConfiguration?.let(::setInputConfiguration)
    if (attempt.parameters) base.sessionParameters?.let(::setSessionParameters)
}
