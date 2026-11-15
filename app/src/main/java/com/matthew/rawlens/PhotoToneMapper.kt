// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Persisted with the develop contract, including queued captures and offline merges. */
enum class PhotoToneMapper(val label: String) {
    BASIC("Basic"), AGX("AgX");
    companion object {
        const val PREFERENCE_KEY = "jpeg_tone_mapper"
        fun fromPreference(value: String?): PhotoToneMapper =
            entries.firstOrNull { it.name == value } ?: AGX
    }
}
