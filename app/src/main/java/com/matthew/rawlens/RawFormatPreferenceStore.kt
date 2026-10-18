// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context

/**
 * Per-lens RAW stream format preference (`"RAW10"` / `"RAW12"`).
 * Absent means auto, which is `RAW_SENSOR`: packed capture is strictly
 * opt-in per lens, never a default. Unknown names read back as auto, so
 * stale choices can never wedge a session.
 */
class RawFormatPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stored format name for this route identity, or null for auto. */
    fun get(cameraId: String?): String? =
        cameraId?.let { preferences.getString(key(it), null)?.takeIf(::isKnownFormat) }

    /** Persist a choice; null clears back to auto. */
    fun set(cameraId: String, format: String?) {
        val editor = preferences.edit()
        if (format != null && isKnownFormat(format)) editor.putString(key(cameraId), format)
        else editor.remove(key(cameraId))
        editor.apply()
    }

    private fun key(cameraId: String) = "format_$cameraId"

    companion object {
        const val PREFS_NAME = "rawlens_raw_format"

        /** Pure + host-tested: only names the session layer can honor. */
        fun isKnownFormat(name: String): Boolean =
            runCatching { PackedRawFormat.valueOf(name) }.isSuccess
    }
}
