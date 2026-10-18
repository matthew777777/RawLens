// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context

/**
 * Per-lens RAW capture size preference (`"WxH"`, e.g. `"4080x3072"`).
 * Absent means auto (the combo ladder's largest-first order). A stored
 * size missing from the live stream map reads back as auto, so stale
 * choices after HAL changes can never wedge a session.
 */
class RawSizePreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stored size for this route identity, or null for auto. */
    fun get(cameraId: String?): String? =
        cameraId?.let { preferences.getString(key(it), null)?.takeIf(::isPlausibleSize) }

    /** Persist a choice; null clears back to auto. */
    fun set(cameraId: String, size: String?) {
        val editor = preferences.edit()
        if (size != null && isPlausibleSize(size)) editor.putString(key(cameraId), size)
        else editor.remove(key(cameraId))
        editor.apply()
    }

    private fun key(cameraId: String) = "size_$cameraId"

    companion object {
        const val PREFS_NAME = "rawlens_raw_size"

        /** Pure + host-tested: "WxH" with positive dimensions. */
        fun isPlausibleSize(text: String): Boolean {
            val parts = text.trim().split('x')
            if (parts.size != 2) return false
            val width = parts[0].toIntOrNull() ?: return false
            val height = parts[1].toIntOrNull() ?: return false
            return width > 0 && height > 0
        }
    }
}
