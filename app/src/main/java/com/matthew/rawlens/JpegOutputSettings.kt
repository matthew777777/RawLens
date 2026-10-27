// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build

/** Output contract captured with each shutter press; it never changes while that RAW is queued. */
data class JpegOutputSettings(
    val ultraHdr: Boolean = false,
    val activeDLighting: ActiveDLighting = ActiveDLighting.OFF,
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
    val highlightShoulder: Float = 1f,
    /**
     * Fixed-center subject weight for the develop meter's brightness
     * placement (log-average only; highlight guards stay global). 0 =
     * uniform whole-frame metering. Range 0..3; default 0 (off).
     */
    val centerWeight: Float = 0f,
    /** Still-JPEG demosaic backend (AMaZE default; RCD via the Vulkan bridge). */
    val demosaic: JpegDemosaic = JpegDemosaic.AMAZE,
    // RAWR-parity Full Ultra HDR encode parameters. The gain map is the
    // scene-linear HDR to SDR-base ratio (log2, normalized by
    // gainMapMin/MaxLog2), dual-encoded with the SDR base and assembled by
    // the native UltrahdrMux (MPF + GContainer XMP + hdrgm + ISO 21496-1).
    // Defaults mirror RAWR's UltraHdrParams verdicts.
    val gainmapQuality: Int = 92,
    /** Linear gain matching the SDR base's exposure domain. 1 = scene tap already exposed. */
    val hdrExposure: Float = 1f,
    /** Identity floor (1x): the file must never darken the base rendition. */
    val gainMapMinLog2: Float = 0f,
    val gainMapMaxLog2: Float = 4.7090998f,
    val gainmapGamma: Float = 1f,
    val gainmapOffsetSdr: Float = 0.015625f,
    val gainmapOffsetHdr: Float = 0.015625f,
    /** Chroma protection for saturated brights. 0 = off. */
    val satProtect: Float = 0.6f,
    /** Per-channel RGB map (hue survives a white SDR base). */
    val multiChannelMap: Boolean = true,
    /** Adaptive ceiling: p99.9 + margin, stepped, clamped to [fitFloor, displayCap]. */
    val gainMapFitMargin: Float = 0.25f,
    val gainMapFitStep: Float = 0.25f,
    val gainMapFitFloor: Float = 1f,
    val gainMapDisplayCap: Float = 1.45f,
    val shoulderWidth: Float = 0.5f,
    val shoulderKneeBelowMax: Float = 0.5f,
    /** Luma baseline floor share (0 disables). */
    val baseShare: Float = 0.75f,
    val baseExponent: Float = 0.4f,
    /** Soft-knee chroma compression around the per-pixel channel mean. */
    val chromaKnee: Float = 0.05f,
    /**
     * darktable inpaint opposed on normalized Bayer data before demosaic.
     * Repairs clipped photosites; disable to compare with the measured CFA.
     */
    val hlReconstruction: Boolean = true
) {
    /**
     * Expand the existing AgX shadow range to restore middle grey after protection.
     * Solving (S' - b)/(S' + H) = S/(S + H) gives S' = S + b*(S+H)/H.
     * This also lifts shadows more than highlights; no extra shader/pass or LUT.
     * Kept separate from the stored knob so repeated resolution is idempotent.
     */
    val effectiveAgxShadowEv: Float get() = if (activeDLighting == ActiveDLighting.OFF) agxShadowEv else
        (agxShadowEv + activeDLighting.protectionEv.toFloat() *
            (agxShadowEv + agxHighlightEv) / agxHighlightEv.coerceAtLeast(3f)).coerceAtMost(18f)

    /** Ultra HDR is an Android 14 (API 34) platform JPEG feature. */
    fun resolvedForPlatform(): JpegOutputSettings {
        fun bounded(value: Float, minimum: Float, maximum: Float, fallback: Float): Float =
            value.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: fallback
        // Bound the map floor before the ceiling so a wild floor can never
        // invert the coerceIn range below (which would throw). The floor
        // stops 0.25 EV under the 8 EV ceiling so the ceiling range stays valid.
        val boundedMinLog2 = bounded(gainMapMinLog2, -8f, 7.75f, 0f)
        val boundedFitFloor = bounded(gainMapFitFloor, 0f, 4f, 1f)
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
            highlightShoulder = bounded(highlightShoulder, 0f, 1f, 1f),
            centerWeight = bounded(centerWeight, 0f, 3f, 0f),
            gainmapQuality = gainmapQuality.coerceIn(1, 100),
            hdrExposure = bounded(hdrExposure, 1e-6f, 1024f, 1f),
            gainMapMinLog2 = boundedMinLog2,
            gainMapMaxLog2 = bounded(gainMapMaxLog2, boundedMinLog2 + 0.25f, 8f, 4.7090998f),
            gainmapGamma = bounded(gainmapGamma, 0.1f, 4f, 1f),
            gainmapOffsetSdr = bounded(gainmapOffsetSdr, 0f, 1f, 0.015625f),
            gainmapOffsetHdr = bounded(gainmapOffsetHdr, 0f, 1f, 0.015625f),
            satProtect = bounded(satProtect, 0f, 1f, 0.6f),
            gainMapFitMargin = bounded(gainMapFitMargin, 0f, 2f, 0.25f),
            gainMapFitStep = bounded(gainMapFitStep, 0.05f, 2f, 0.25f),
            gainMapFitFloor = boundedFitFloor,
            gainMapDisplayCap = bounded(gainMapDisplayCap, boundedFitFloor, 8f, 1.45f),
            shoulderWidth = bounded(shoulderWidth, 0f, 4f, 0.5f),
            shoulderKneeBelowMax = bounded(shoulderKneeBelowMax, 0f, 4f, 0.5f),
            baseShare = bounded(baseShare, 0f, 2f, 0.75f),
            baseExponent = bounded(baseExponent, 0.05f, 4f, 0.4f),
            chromaKnee = bounded(chromaKnee, 0.001f, 1f, 0.05f)
        )
    }
}
