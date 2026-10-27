// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.SharedPreferences

/** Adopt the new opposed default once per lens, then retain the user's explicit switch. */
internal object HighlightReconstructionDefaults {
    const val ENABLED = "jpeg_hl_reconstruction"
    const val APPLIED = "jpeg_hl_opposed_default_applied"
    fun enabled(stored: Boolean?, applied: Boolean): Boolean = if (applied) stored ?: true else true
    fun read(preferences: SharedPreferences): Boolean {
        val applied = preferences.getBoolean(APPLIED, false)
        val stored = if (preferences.contains(ENABLED)) preferences.getBoolean(ENABLED, true) else null
        val result = enabled(stored, applied)
        if (!applied) preferences.edit().putBoolean(ENABLED, result).putBoolean(APPLIED, true).apply()
        return result
    }
}
