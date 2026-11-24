// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Persisted with the develop contract, including queued captures and offline merges. */
enum class PhotoToneMapper(val label: String) {
    BASIC("Basic"), AGX("AgX"), SIGMOID("Sigmoid"), ACES_2("ACES 2.0"), OPENDRT("OpenDRT");
    val shaderMode: Int get() = when(this) { AGX -> 0; BASIC -> 1; SIGMOID -> 2; ACES_2 -> 3; OPENDRT -> 4 }
    companion object {
        const val PREFERENCE_KEY = "jpeg_tone_mapper"
        val selectable: List<PhotoToneMapper> = entries.filter { it != BASIC }

        fun fromPreference(value: String?): PhotoToneMapper =
            selectable.firstOrNull { it.name == value } ?: SIGMOID
    }
}
