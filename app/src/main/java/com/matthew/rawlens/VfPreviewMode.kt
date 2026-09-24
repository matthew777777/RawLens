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

/** Selectable VF long edge: 480/640 full-rate for weak SoCs, 960 balanced, 1080 detail. */
object VfResolution {
    const val MIN = 480
    const val MID = 640
    const val HIGH = 960
    const val MAX = 1080
    val OPTIONS = intArrayOf(MIN, MID, HIGH, MAX)

    /**
     * NEON fallback cap: the threaded sampler costs ~70 ms at 1020x765 on an
     * MT6878-class SoC, so the CPU path samples at most a 640 long edge
     * (~18 ms, 510x382 on 12 MP) and holds 30 fps. The GPU path always runs
     * the user's full setting; a softer live fallback beats a sharp 3 fps one.
     */
    const val CPU_MAX = 640

    /** Midpoint buckets: stored legacy values (480/640/960) map back to themselves. */
    fun validated(value: Int): Int = when {
        value <= 560 -> MIN
        value <= 800 -> MID
        value <= 1020 -> HIGH
        else -> MAX
    }
}
