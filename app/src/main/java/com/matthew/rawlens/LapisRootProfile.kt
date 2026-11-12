// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build

/** Only the exact firmware whose vendor-library hash and sensor modes were verified. */
internal object LapisRootProfile {
    const val PREFS = "rawlens_lapis_root"
    const val ENABLED = "enabled"
    const val FULL_RESOLUTION = "full_resolution"
    const val SENSOR_ZOOM = "sensor_zoom"
    const val FIRMWARE = "OS3.0.303.0.WPPEUXM"
    const val VENDOR_SHA256 = "95eded2eef1767fdb07f74d31a4ef1a9b40668e6d5984d30444e0165a489a7dc"

    const val CORE_SHA256 = "287418474d0ddb781e5c6c33f4c38367e690061617a0bb599ddedb836a8bd8cc"
    const val SMH_SHA256 = "ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1"
    const val SMH_CUSTOM_SHA256 = "c1eecbf9ff25c137b59eb48a62cef8bef15c22b9006e910fc1d118fdaa654fd2"
    const val SENSOR_ZOOM_4X = "sensor_zoom_4x"
    const val DCG_ENABLED = "dcg14"

    enum class Mode(val argument: String, val label: String) {
        NORMAL("normal", "12.5 MP"), FULL("50mp", "50 MP RAW"), CROP_2X("2x", "2× sensor RAW"), CROP_4X("4x", "4× sensor RAW"), DCG14("dcg14", "DCG14 · Auto AE")
    }

    fun matches(device: String, model: String, fingerprint: String, display: String): Boolean =
        device == "lapis" && model == "25080RABDG" &&
            (fingerprint.contains(FIRMWARE) || display.contains(FIRMWARE))

    fun supported(): Boolean = matches(Build.DEVICE, Build.MODEL, Build.FINGERPRINT, Build.DISPLAY)
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = supported() && preferences(context).getBoolean(ENABLED, false)
    fun mode(context: Context): Mode {
        if (!enabled(context)) return Mode.NORMAL
        val p = preferences(context)
        return if (p.getBoolean(DCG_ENABLED, false)) Mode.DCG14
        else if (p.getBoolean(SENSOR_ZOOM_4X, false)) Mode.CROP_4X
        else if (p.getBoolean(SENSOR_ZOOM, false)) Mode.CROP_2X
        else if (p.getBoolean(FULL_RESOLUTION, true)) Mode.FULL else Mode.NORMAL
    }

    fun select(context:Context,mode:Mode) {
        preferences(context).edit().putBoolean(FULL_RESOLUTION,mode==Mode.FULL)
            .putBoolean(SENSOR_ZOOM_4X,mode==Mode.CROP_4X).putBoolean(SENSOR_ZOOM,mode==Mode.CROP_2X).putBoolean(DCG_ENABLED,mode==Mode.DCG14).apply()
    }

    fun fromArgument(value:String):Mode? = Mode.entries.firstOrNull { it.argument==value }
}
