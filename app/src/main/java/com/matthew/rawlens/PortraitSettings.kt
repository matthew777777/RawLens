// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.SharedPreferences

/**
 * Portrait (synthetic depth of field). The look is a full-frame portrait lens
 * framed head-and-shoulders: [fNumber] sets the blur disc of the far
 * background, as a fraction of the long image side, from the thin-lens
 * relation b = f^2 / (N (s - f)) for 85 mm at 2 m (f/1.8 -> 2.9 % radius).
 */
data class PortraitSettings(
    val enabled: Boolean = false,
    val fNumber: Float = 1.8f,
    /** Cat's-eye (optical vignetting) strength toward the frame edges, 0..1. */
    val catEye: Float = 0.5f,
    /** Grain put back into blurred areas, relative to signal (sqrt-shaped). */
    val grain: Float = 0f,
) {
    /** Far-background blur radius as a fraction of the long side. */
    val radiusFraction: Float get() = BASE_RADIUS_AT_F18 * 1.8f / fNumber.coerceIn(0.95f, 16f)

    fun save(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor
        .putBoolean(KEY_ENABLED, enabled)
        .putFloat(KEY_FNUMBER, fNumber)
        .putFloat(KEY_CAT_EYE, catEye)

    companion object {
        const val KEY_ENABLED = "portrait_enabled"
        const val KEY_FNUMBER = "portrait_fnumber"
        const val KEY_CAT_EYE = "portrait_cat_eye"
        const val BASE_RADIUS_AT_F18 = 0.029f
        /**
         * Portrait is hidden (2026-11-26): too slow (~4-6 s per shot with Depth Anything V2 on CPU),
         * false person blobs pull focus, blue dots reported. Needs rework; models moved out of the APK.
         */
        const val AVAILABLE = false
        val F_STOPS = floatArrayOf(1.2f, 1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f)

        fun read(prefs: SharedPreferences) = PortraitSettings(
            enabled = AVAILABLE && prefs.getBoolean(KEY_ENABLED, false),
            fNumber = prefs.getFloat(KEY_FNUMBER, 1.8f),
            catEye = prefs.getFloat(KEY_CAT_EYE, 0.5f),
        )

        fun label(fNumber: Float): String =
            if (fNumber < 10f && fNumber % 1f != 0f) "f/%.1f".format(java.util.Locale.US, fNumber)
            else "f/%.0f".format(java.util.Locale.US, fNumber)
    }
}
