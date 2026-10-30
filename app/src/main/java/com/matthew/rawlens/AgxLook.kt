// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Google Filament creative look, between the AgX sigmoid and outset. */
enum class AgxLook(val label: String) {
    BASE("Base"), PUNCHY("Punchy");

    companion object {
        const val PREFERENCE_KEY = "jpeg_agx_look"
        fun fromPreference(value: String?): AgxLook = entries.firstOrNull { it.name == value } ?: BASE
    }
}
