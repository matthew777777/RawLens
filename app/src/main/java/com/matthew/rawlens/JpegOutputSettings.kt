// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build

/** Output contract captured with each shutter press; it never changes while that RAW is queued. */
data class JpegOutputSettings(
    val ultraHdr: Boolean = false,
    val displayP3: Boolean = false,
    val jpegQuality: Int = 100,
    val chromaSubsampling: JpegChromaSubsampling = JpegChromaSubsampling.YUV_422,
    /** darktable-style post-tone-map AgX primary outset multiplier: 0.0 = none, 1.0 = base, 2.0 = maximum. */
    val agxPurityBoost: Float = 1f,
    val agxContrast: Float = 1f,
    val agxSaturation: Float = 1f,
    val agxHuePreservation: Float = 0f,
    /** Stops below scene-linear middle gray covered by the log domain. Official AgX uses 10. */
    val agxShadowEv: Float = 10f,
    /** Stops above scene-linear middle gray covered by the log domain. Official AgX uses 6.5. */
    val agxHighlightEv: Float = 6.5f,
    val agxGamutCompression: Float = 0f,
    val adaptiveExposureAuto: Boolean = true,
    val adaptiveExposureProgramStrength: Float = 0.5f,
    /**
     * Hard white guard for the p99.5 RAW spike: exposure is capped so the tail
     * lands at this level before AgX. 1.0 = never push past white (sky-safe).
     * Range 0.5..1.5; higher lifts mids but risks flat skies.
     */
    val highlightHeadroom: Float = 1f,
    /**
     * Soft shoulder for broad highlights (p95 sky/wall mass): exposure is capped
     * so large bright areas land at this level. Range 0.6..1.0; lower protects
     * skies, higher keeps foliage bright. Default 0.85.
     */
    val highlightSoftHeadroom: Float = 0.85f,
    /**
     * Scene-linear highlight shoulder strength: 0 = pinned Filament AgX only,
     * 1 = full exponential soft shoulder (knee 0.9, scale 0.8). Default 1.
     */
    val highlightShoulder: Float = 1f
) {
    /** Ultra HDR is an Android 14 (API 34) platform JPEG feature. */
    fun resolvedForPlatform(): JpegOutputSettings {
        fun bounded(value: Float, minimum: Float, maximum: Float, fallback: Float): Float =
            value.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: fallback
        return copy(
            ultraHdr = ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            jpegQuality = jpegQuality.coerceIn(1, 100),
            agxPurityBoost = bounded(agxPurityBoost, 0f, 2f, 1f),
            agxContrast = bounded(agxContrast, 0.5f, 1.5f, 1f),
            agxSaturation = bounded(agxSaturation, 0f, 2f, 1f),
            agxHuePreservation = bounded(agxHuePreservation, 0f, 1f, 0f),
            agxShadowEv = bounded(agxShadowEv, 4f, 14f, 10f),
            agxHighlightEv = bounded(agxHighlightEv, 3f, 10f, 6.5f),
            agxGamutCompression = bounded(agxGamutCompression, 0f, 1f, 0f),
            adaptiveExposureProgramStrength = bounded(adaptiveExposureProgramStrength, 0f, 1f, 0.5f),
            highlightHeadroom = bounded(highlightHeadroom, 0.5f, 1.5f, 1f),
            highlightSoftHeadroom = bounded(highlightSoftHeadroom, 0.6f, 1f, 0.85f),
            highlightShoulder = bounded(highlightShoulder, 0f, 1f, 1f)
        )
    }
}
