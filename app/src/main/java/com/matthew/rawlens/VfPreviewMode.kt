// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** WYSIWYG viewfinder contract: both modes render OUR scene-referred pipeline, never ISP YUV.
 * RAW shows the linear Reinhard preview; JPEG shows the cheap AgX preview approximating the
 * saved JPEG (superpixel demosaic + same WB/CCM + AgX-lite tonemap, no AMaZE detail/denoise). */
enum class VfPreviewMode {
    /** Follows [CaptureFormat]: DNG_ONLY -> RAW, JPEG/JPEG_DNG -> JPEG. */
    FOLLOW,
    RAW,
    JPEG;

    /** True = RAW tonemap, false = cheap JPEG AgX tonemap. Both are scene-referred. */
    fun resolve(format: CaptureFormat): Boolean = when (this) {
        RAW -> true
        JPEG -> false
        FOLLOW -> !format.includesJpeg
    }

    fun label(format: CaptureFormat): String = if (resolve(format)) "RAW VF" else "JPG VF"

    companion object {
        fun fromPreference(value: String?): VfPreviewMode =
            entries.firstOrNull { it.name == value } ?: FOLLOW
    }
}

/** Selectable VF long edge: full-rate 480/640 for weak SoCs, 960 for detail. */
object VfResolution {
    const val MIN = 480
    const val MID = 640
    const val MAX = 960
    val OPTIONS = intArrayOf(MIN, MID, MAX)

    fun validated(value: Int): Int = when {
        value <= 560 -> MIN
        value <= 800 -> MID
        else -> MAX
    }
}
