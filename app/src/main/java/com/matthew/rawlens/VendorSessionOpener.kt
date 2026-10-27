// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface

/** Bounded vendor fallback on the same device/outputs, for sync and async rejection. */
internal class VendorSessionOpener(
    private val device: CameraDevice,
    private val base: SessionConfiguration,
    mode: Int,
    private val legacySurfaces: List<Surface>,
    private val handler: Handler,
    private val isCurrent: () -> Boolean,
    private val configured: (VendorSessionAttempt, Boolean, CameraCaptureSession) -> Unit,
    private val failed: (String) -> Unit,
    private val log: (String) -> Unit,
    private val makeConfiguration: (SessionConfiguration, VendorSessionAttempt, CameraCaptureSession.StateCallback) -> SessionConfiguration = ::vendorSessionConfiguration
) {
    private val attempts = VendorSessionAttempt.sequence(mode, base.sessionParameters != null)
    private var serial = 0
    private var finished = false

    fun open() = submit(0, false)

    private fun submit(index: Int, legacy: Boolean) {
        if (finished || !isCurrent()) return
        val token = ++serial
        val attempt = if (legacy) VendorSessionAttempt(0, false) else attempts[index]
        fun rejected(reason: String, allowLegacy: Boolean) {
            if (finished || token != serial || !isCurrent()) return
            log("Vendor session rejected: $attempt legacy=$legacy ($reason)")
            if (!legacy && index + 1 < attempts.size) submit(index + 1, false)
            else if (!legacy && allowLegacy) submit(index, true)
            else {
                finished = true
                failed(reason)
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (finished || token != serial || !isCurrent()) {
                    session.close()
                    return
                }
                finished = true
                configured(attempt, legacy, session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                // Legacy surface fallback is for API incompatibility, not a second
                // submission of the same HAL-rejected standard output combination.
                rejected("HAL configuration failure", false)
            }
        }
        try {
            log("Submitting vendor session: $attempt legacy=$legacy")
            if (legacy) device.createCaptureSession(legacySurfaces, callback, handler)
            else device.createCaptureSession(makeConfiguration(base, attempt, callback))
        } catch (failure: IllegalArgumentException) {
            rejected(failure.toString(), true)
        } catch (failure: UnsupportedOperationException) {
            rejected(failure.toString(), true)
        } catch (failure: CameraAccessException) {
            // A disconnected/in-use camera is not evidence against vendor settings.
            finished = true
            failed(failure.toString())
        } catch (failure: IllegalStateException) {
            finished = true
            failed(failure.toString())
        }
    }
}
