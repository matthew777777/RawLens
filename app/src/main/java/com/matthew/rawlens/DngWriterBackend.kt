// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Selects the DNG metadata/writer implementation independently of RawLens development. */
enum class DngWriterBackend(val preferenceValue: String, val label: String) {
    /** Try the platform writer first and transparently fall back to patched TinyDNG on failure. */
    AUTO("auto", "AUTO (Android → TinyDNG fallback)"),

    /** Android's official Camera2 DngCreator. This is the RawLens default. */
    ANDROID("android", "ANDROID (official DngCreator)"),

    /** RawLens' patched PhotonCamera/TinyDNG compatibility writer. */
    TINY_DNG("tinydng", "TINYDNG (patched fallback)");

    companion object {
        fun fromPreference(value: String?): DngWriterBackend =
            entries.firstOrNull { it.preferenceValue == value } ?: ANDROID
    }
}
