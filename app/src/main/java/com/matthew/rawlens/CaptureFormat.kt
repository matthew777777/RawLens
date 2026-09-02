// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** User-visible output contract. JPEG output is always developed from the paired RAW frame. */
enum class CaptureFormat(
    val badgeLabel: String,
    val includesJpeg: Boolean,
    val includesDng: Boolean
) {
    JPEG("JPEG", includesJpeg = true, includesDng = false),
    JPEG_DNG("JPEG + DNG", includesJpeg = true, includesDng = true),
    DNG_ONLY("DNG", includesJpeg = false, includesDng = true);

    fun next(): CaptureFormat = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): CaptureFormat =
            entries.firstOrNull { it.name == value } ?: DNG_ONLY
    }
}
