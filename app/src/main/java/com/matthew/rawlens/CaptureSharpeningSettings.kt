// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.SharedPreferences

/** RAWR SettingsModels.kt: exact labels and Richardson–Lucy iteration counts. */
enum class CaptureSharpeningLevel(val label: String, val iterations: Int) {
    Low("Low", 2), Medium("Medium", 3), Sharp("Sharp", 4), Sharpest("Sharpest", 6)
}

/** Frozen with the capture; native auto radius/contrast and iteration limit match RAWR. */
data class CaptureSharpeningSettings(
    val enabled: Boolean = false,
    val level: CaptureSharpeningLevel = CaptureSharpeningLevel.Low
) {
    companion object {
        const val ENABLED_KEY = "photo_capture_sharpening_enabled"
        const val LEVEL_KEY = "photo_capture_sharpening_level"
        /** RAWR collapseRggbToRgb: preserve absolute gains, average both greens. */
        fun collapseWhiteBalance(rggb: FloatArray?): FloatArray? {
            if (rggb == null || rggb.size < 4 || rggb.take(4).any { !it.isFinite() || it <= 0f }) return null
            return floatArrayOf(rggb[0], 0.5f * (rggb[1] + rggb[2]), rggb[3])
        }

        /** RAWR FrameMetadataSnapshot defaults absent/short Camera2 gains to unity. */
        fun whiteBalance(rggb: FloatArray?): FloatArray {
            if (rggb == null || rggb.size < 4) return floatArrayOf(1f, 1f, 1f)
            return requireNotNull(collapseWhiteBalance(rggb)) {
                "Capture sharpening requires valid Camera2 WB gains"
            }
        }

        fun read(prefs: SharedPreferences) = CaptureSharpeningSettings(
            prefs.getBoolean(ENABLED_KEY, false),
            CaptureSharpeningLevel.entries.firstOrNull {
                it.name == prefs.getString(LEVEL_KEY, null)
            } ?: CaptureSharpeningLevel.Low
        )
    }
}
