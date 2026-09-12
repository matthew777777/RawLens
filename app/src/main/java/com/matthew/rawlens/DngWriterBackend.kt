// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Selects the DNG metadata/writer implementation independently of RawLens development. */
enum class DngWriterBackend(val preferenceValue: String, val label: String) {
    /** Try the platform writer first and transparently fall back to patched TinyDNG on failure. */
    AUTO("auto", "AUTO (Android → TinyDNG fallback)"),

    /** Android's official Camera2 DngCreator. This is the RawLens default. */
    ANDROID("android", "ANDROID (official DngCreator)"),

    /** Pinned TinyDNG v3 with RawLens Camera2 metadata support. */
    TINY_DNG("tinydng", "TINYDNG v3");

    companion object {
        fun fromPreference(value: String?): DngWriterBackend =
            entries.firstOrNull { it.preferenceValue == value } ?: ANDROID
    }
}
