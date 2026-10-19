// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Developed-still container selected for capture output. JPEG is the legacy
 * 8-bit path (libjpeg-turbo / platform Ultra HDR); JXL encodes the same
 * AgX develop through [NativeJxlEncoder] at up to 16-bit integer (plus
 * 16/32-bit float) honoring [JxlOutputSettings].
 *
 * Orthogonal to [CaptureFormat], which pairs "a developed still" with DNG:
 * `includesJpeg` still means "save a developed still", whichever container
 * holds it. Frozen with the shutter press like every other output choice.
 */
enum class DevelopedFormat(
    val label: String,
    /** Short status/badge token. */
    val token: String,
    val extension: String
) {
    JPEG("JPEG", "JPG", "jpg"),
    JXL("JXL", "JXL", "jxl");

    fun next(): DevelopedFormat = if (this == JPEG) JXL else JPEG

    /** Badge pairing with DNG, mirroring the CaptureFormat badge contract. */
    fun badgeLabel(includesDng: Boolean): String =
        if (includesDng) "$label + DNG" else label

    companion object {
        fun fromPreference(value: String?): DevelopedFormat =
            entries.firstOrNull { it.name == value } ?: JPEG
    }
}
