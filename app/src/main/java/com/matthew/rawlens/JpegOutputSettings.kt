// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build

enum class AgxLook { BASE, GOLDEN, PUNCHY }

/** Output contract captured with each shutter press; it never changes while that RAW is queued. */
data class JpegOutputSettings(
    val ultraHdr: Boolean = false,
    val displayP3: Boolean = false,
    /** darktable-style post-tone-map AgX primary outset multiplier: 0.0 = none, 1.0 = base, 2.0 = maximum. */
    val agxPurityBoost: Float = 1f,
    val agxLook: AgxLook = AgxLook.BASE,
    val agxContrast: Float = 1f,
    val agxSaturation: Float = 1f,
    val agxHuePreservation: Float = 0f,
    /** Stops below scene-linear middle gray covered by the log domain. Official AgX uses 10. */
    val agxShadowEv: Float = 10f,
    /** Stops above scene-linear middle gray covered by the log domain. Official AgX uses 6.5. */
    val agxHighlightEv: Float = 6.5f,
    val agxGamutCompression: Float = 0f,
    val adaptiveExposureAuto: Boolean = true,
    val adaptiveExposureProgramStrength: Float = 0.5f
) {
    /** Ultra HDR is an Android 14 (API 34) platform JPEG feature. */
    fun resolvedForPlatform(): JpegOutputSettings {
        fun bounded(value: Float, minimum: Float, maximum: Float, fallback: Float): Float =
            value.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: fallback
        return copy(
            ultraHdr = ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            agxPurityBoost = bounded(agxPurityBoost, 0f, 2f, 1f),
            agxContrast = bounded(agxContrast, 0.5f, 1.5f, 1f),
            agxSaturation = bounded(agxSaturation, 0f, 2f, 1f),
            agxHuePreservation = bounded(agxHuePreservation, 0f, 1f, 0f),
            agxShadowEv = bounded(agxShadowEv, 4f, 14f, 10f),
            agxHighlightEv = bounded(agxHighlightEv, 3f, 10f, 6.5f),
            agxGamutCompression = bounded(agxGamutCompression, 0f, 1f, 0f),
            adaptiveExposureProgramStrength = bounded(adaptiveExposureProgramStrength, 0f, 1f, 0.5f)
        )
    }
}
