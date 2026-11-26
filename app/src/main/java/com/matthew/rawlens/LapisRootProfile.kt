// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build

/**
 * Redmi Note 15 Pro 5G (lapis). The UI gate is device + model only: firmware OTAs often leave
 * the camera stack untouched. Every root path re-checks the vendor-library SHA-256s below before
 * patching, so a firmware that really changed the camera libraries still fails safely.
 */
internal object LapisRootProfile {
    const val PREFS = "rawlens_lapis_root"
    const val ENABLED = "enabled"
    const val FULL_RESOLUTION = "full_resolution"
    const val SENSOR_ZOOM = "sensor_zoom"
    /** Firmware the modes were first verified on (305 has identical camera libraries). */
    const val FIRMWARE = "OS3.0.303.0.WPPEUXM"
    const val VENDOR_SHA256 = "95eded2eef1767fdb07f74d31a4ef1a9b40668e6d5984d30444e0165a489a7dc"

    const val CORE_SHA256 = "287418474d0ddb781e5c6c33f4c38367e690061617a0bb599ddedb836a8bd8cc"
    const val SMH_SHA256 = "ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1"
    const val SMH_CUSTOM_SHA256 = "c1eecbf9ff25c137b59eb48a62cef8bef15c22b9006e910fc1d118fdaa654fd2"
    const val SENSOR_ZOOM_4X = "sensor_zoom_4x"
    const val VIDEO_60 = "video60"
    const val DSG_2X = "2xdsg14"
    const val DCG_ENABLED = "dcg14"
    const val HEXADECA_200 = "hexadeca200"

    enum class Mode(val argument: String, val label: String) {
        VIDEO60("video60", "9.4 MP · 60 fps"),
        CROP_2X_DSG("2xdsg14", "2× DSG · Auto AE"),
        NORMAL("normal", "12.5 MP"), FULL("50mp", "50 MP RAW"), CROP_2X("2x", "2× sensor RAW"), CROP_4X("4x", "4× sensor RAW"), DCG14("dcg14", "DCG14 · Auto AE"),
        /** One-shot: rawr same-colour sums + Quad Detail remosaic to 50 MP Bayer CFA. */
        HEXADECA_200("200mp", "200→50 MP RAW")
    }

    @Suppress("UNUSED_PARAMETER")
    fun matches(device: String, model: String, fingerprint: String, display: String): Boolean =
        device == "lapis" && model == "25080RABDG"

    fun supported(): Boolean = matches(Build.DEVICE, Build.MODEL, Build.FINGERPRINT, Build.DISPLAY)
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = supported() && preferences(context).getBoolean(ENABLED, false)
    fun mode(context: Context): Mode {
        if (!enabled(context)) return Mode.NORMAL
        val p = preferences(context)
        return if (p.getBoolean(HEXADECA_200, false)) Mode.HEXADECA_200
        else if (p.getBoolean(VIDEO_60, false)) Mode.VIDEO60
        else if (p.getBoolean(DSG_2X, false)) Mode.CROP_2X_DSG
        else if (p.getBoolean(DCG_ENABLED, false)) Mode.DCG14
        else if (p.getBoolean(SENSOR_ZOOM_4X, false)) Mode.CROP_4X
        else if (p.getBoolean(SENSOR_ZOOM, false)) Mode.CROP_2X
        else if (p.getBoolean(FULL_RESOLUTION, true)) Mode.FULL else Mode.NORMAL
    }

    fun select(context:Context,mode:Mode) {
        preferences(context).edit().putBoolean(FULL_RESOLUTION,mode==Mode.FULL)
            .putBoolean(VIDEO_60,mode==Mode.VIDEO60).putBoolean(DSG_2X,mode==Mode.CROP_2X_DSG).putBoolean(SENSOR_ZOOM_4X,mode==Mode.CROP_4X).putBoolean(SENSOR_ZOOM,mode==Mode.CROP_2X).putBoolean(DCG_ENABLED,mode==Mode.DCG14).putBoolean(HEXADECA_200,mode==Mode.HEXADECA_200).apply()
    }

    /** A root HAL restart may temporarily hide another enabled camera route. */
    fun keepSwitcherLens(rootEnabled: Boolean, explicitlyEnabled: Boolean, routeAvailable: Boolean): Boolean =
        routeAvailable || (rootEnabled && explicitlyEnabled)

    /** Never hide the active selection behind an older per-mode visibility preference. */
    fun showSwitcherLens(lens: LensOption, savedVisible: Boolean): Boolean = lens.selected || savedVisible

    private fun visibilityKey(lens: LensOption) = "show_lens:${lens.cameraId}:${lens.readout}"
    fun lensVisible(context: Context, lens: LensOption): Boolean =
        preferences(context).getBoolean(visibilityKey(lens), true)
    fun setLensVisible(context: Context, lens: LensOption, visible: Boolean) {
        preferences(context).edit().putBoolean(visibilityKey(lens), visible).apply()
    }

    fun videoFps(context: Context): Int = preferences(context).getInt("video_fps",60).takeIf { it in listOf(24,30,60) } ?: 60

    fun fromArgument(value:String):Mode? = Mode.entries.firstOrNull { it.argument==value }
}
