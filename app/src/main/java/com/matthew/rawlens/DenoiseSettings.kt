// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls of the develop's noise and sharpness stages:
 * [rawDenoise] is the single-frame RAW-domain denoiser ([RawDenoiser], JPEG/JXL
 * only, never merges or the DNG); [captureSharpening] runs after the demosaic.
 */
data class DenoiseSettings(
    val rawDenoise: RawDenoiseSettings = RawDenoiseSettings(),
    val captureSharpening: CaptureSharpeningSettings = CaptureSharpeningSettings()
) {
    companion object {
        fun read(prefs: android.content.SharedPreferences) = DenoiseSettings(
            rawDenoise = RawDenoiseSettings.read(prefs),
            captureSharpening = CaptureSharpeningSettings.read(prefs)
        )
    }
}
