// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.SharedPreferences

/**
 * Merges-screen settings builders. Mirrors MainActivity's private
 * [jpegOutputSettings][jxlOutputSettings][denoiseSettings] builders
 * key-for-key so offline merges develop with the same JPEG/JXL and denoise
 * tuning as immediate merges; if those builders gain a knob, mirror it here.
 */
internal object MergeScreenSettings {
    fun jpeg(prefs: SharedPreferences): JpegOutputSettings = JpegOutputSettings(
        ultraHdr = prefs.getBoolean(MainActivity.KEY_JPEG_ULTRA_HDR, false),
        displayP3 = prefs.getBoolean(MainActivity.KEY_JPEG_DISPLAY_P3, false),
        jpegQuality = prefs.getInt(MainActivity.KEY_JPEG_QUALITY, 100),
        chromaSubsampling = JpegChromaSubsampling.fromPreference(
            prefs.getString(MainActivity.KEY_JPEG_CHROMA_SUBSAMPLING, null)
        ),
        demosaic = JpegDemosaic.fromPreference(
            prefs.getString(MainActivity.KEY_JPEG_DEMOSAIC, null)
        ),
        agxPurityBoost = prefs.getFloat(MainActivity.KEY_JPEG_AGX_PURITY, 1f),
        agxContrast = prefs.getFloat(MainActivity.KEY_JPEG_AGX_CONTRAST, 1f),
        agxSaturation = prefs.getFloat(MainActivity.KEY_JPEG_AGX_SATURATION, 1f),
        agxHuePreservation = prefs.getFloat(MainActivity.KEY_JPEG_AGX_HUE, 0f),
        agxShadowEv = prefs.getFloat(MainActivity.KEY_JPEG_AGX_SHADOW_EV, 10f),
        agxHighlightEv = prefs.getFloat(MainActivity.KEY_JPEG_AGX_HIGHLIGHT_EV, 6.5f),
        agxGamutCompression = prefs.getFloat(MainActivity.KEY_JPEG_AGX_GAMUT, 0f),
        adaptiveExposureAuto = prefs.getBoolean(MainActivity.KEY_JPEG_ADAPTIVE_EXPOSURE, true),
        adaptiveExposureProgramStrength = prefs.let { preferences ->
            if (preferences.contains(MainActivity.KEY_JPEG_ADAPTIVE_PROGRAM)) {
                preferences.getFloat(MainActivity.KEY_JPEG_ADAPTIVE_PROGRAM, 0.5f)
            } else {
                preferences.getFloat(MainActivity.LEGACY_KEY_JPEG_ADAPTIVE_PHOTO, 0.5f)
            }
        },
        highlightHeadroom = prefs.getFloat(MainActivity.KEY_JPEG_HIGHLIGHT_HEADROOM, 1f),
        highlightSoftHeadroom = prefs.getFloat(MainActivity.KEY_JPEG_SKY_PROTECTION, 0.85f),
        highlightShoulder = prefs.getFloat(MainActivity.KEY_JPEG_HIGHLIGHT_SHOULDER, 1f),
        hlReconstruction = prefs.getBoolean(MainActivity.KEY_JPEG_HL_RECONSTRUCTION, true)
    ).resolvedForPlatform()

    /** JPEG XL container options (darktable parity); same defaults as MainActivity. */
    fun jxl(prefs: SharedPreferences): JxlOutputSettings = JxlOutputSettings.fromPreferences(
        bitDepthName = prefs.getString(MainActivity.KEY_JXL_BIT_DEPTH, null),
        pixelTypeName = prefs.getString(MainActivity.KEY_JXL_PIXEL_TYPE, null),
        quality = prefs.getInt(MainActivity.KEY_JXL_QUALITY, 100),
        effort = prefs.getInt(MainActivity.KEY_JXL_EFFORT, 7),
        decodingSpeed = prefs.getInt(MainActivity.KEY_JXL_DECODING_SPEED, 0),
        displayP3 = prefs.getBoolean(MainActivity.KEY_JXL_DISPLAY_P3, false),
        hdrTransferName = prefs.getString(MainActivity.KEY_JXL_HDR_TRANSFER, null)
    )

    /** Developed-still container shared with shutter captures. */
    fun developedFormat(prefs: SharedPreferences): DevelopedFormat =
        DevelopedFormat.fromPreference(prefs.getString(MainActivity.KEY_DEVELOPED_FORMAT, null))

    fun denoise(prefs: SharedPreferences): DenoiseSettings = DenoiseSettings(
        aiEnabled = prefs.getBoolean(MainActivity.KEY_AI_DENOISE_ENABLED, false),
        saveOriginalDng = prefs.getBoolean(MainActivity.KEY_SAVE_ORIGINAL_DNG, true),
        aiStrength = prefs.getInt(MainActivity.KEY_AI_STRENGTH_PCT, 100).coerceIn(0, 100) / 100f,
        wavelet = WaveletSettings(
            enabled = prefs.getBoolean(MainActivity.KEY_WAVELET_ENABLED, false),
            strength = prefs.getInt(MainActivity.KEY_WAVELET_STRENGTH_PCT, 100).coerceIn(0, 200) / 100f,
            luma = prefs.getInt(MainActivity.KEY_WAVELET_LUMA_PCT, 25).coerceIn(0, 100) / 100f,
            maxScale = prefs.getInt(MainActivity.KEY_WAVELET_SCALES, 7).coerceIn(1, 7)
        )
    )
}
