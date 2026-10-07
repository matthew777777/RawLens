// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** WYSIWYG viewfinder contract: both modes render OUR scene-referred pipeline, never ISP YUV.
 * RAW shows the linear Reinhard preview; JPEG shows the calibrated AgX preview matching the
 * saved JPEG (superpixel demosaic + save-path color/exposure/tonemap, no AMaZE detail/denoise). */
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

    /**
     * Viewfinder offer-rate floors in milliseconds (pure power policy, unit-tested).
     * The VF renders at most one frame per floor interval. FULL = 20 ms: low
     * enough that a 30 fps camera locks every frame despite delivery jitter
     * (a 28 ms floor still skipped sub-28 ms intervals and jumped 21-30 fps),
     * high enough that a 60 fps stream (16.6 ms) still halves to 30.
     * SAVER = 15 fps, RECORD = 10 fps (a take owns the shared queue).
     */
    const val RATE_FULL_MS = 20L
    const val RATE_SAVER_MS = 66L
    const val RATE_RECORD_MS = 100L

    /** Power-save resolution cap: saver never renders above 640 long edge. */
    const val SAVER_MAX = MID

    fun rateFloorMs(recordMode: Boolean, powerSave: Boolean): Long = when {
        recordMode -> RATE_RECORD_MS
        powerSave -> RATE_SAVER_MS
        else -> RATE_FULL_MS
    }

    fun effectiveEdge(userEdge: Int, recordMode: Boolean, powerSave: Boolean): Int {
        val validatedEdge = validated(userEdge)
        return when {
            recordMode -> MIN
            powerSave -> minOf(validatedEdge, SAVER_MAX)
            else -> validatedEdge
        }
    }
}
