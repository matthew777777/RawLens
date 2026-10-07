// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * HDR+ ZSL burst merge (RAWR merge_hdrplus 1:1 port on Vulkan).
 *
 * Merges the ZSL ring at sensor resolution: coarse-to-fine tile alignment
 * per alternate, then a robust merge — spatial weighted average ("Fast")
 * or per-frequency Wiener merge ("Higher quality") — with the reference
 * frame's black/white normalization. One merged CFA DNG out. Mutually
 * exclusive with RAW SR and burst fusion in the quick panel (all three
 * own the ZSL ring at shutter time).
 */
data class HdrPlusSettings(
    val enabled: Boolean = false,
    /**
     * Upstream "noise reduction" slider, 1..22 (RAWR `Config.strength`).
     * Higher merges more aggressively (cleaner, more ghosting).
     */
    val strength: Float = DEFAULT_STRENGTH,
    /**
     * False = spatial ("Fast"); true = frequency ("Higher quality" 4-pass
     * Wiener). HQ is the default: HDR+ is an explicit quality opt-in, and
     * RAWR's align-once mode keeps the frequency merge practical. Costs
     * ~0.85 GB vs ~0.5 GB at 12.5 MP and takes longer; long-press the
     * quick tile to cycle back to Fast.
     */
    val highQuality: Boolean = true,
    /** Also save every source frame as DNG alongside the merge. */
    val keepSourceBurst: Boolean = false,
    /**
     * True = the auto brain picks strength from motion (manual slider
     * value is kept as the fallback). Dragging the slider turns this
     * off; the settings checkbox turns it back on.
     */
    val autoStrength: Boolean = true,
    /**
     * True = the auto brain picks Fast/HQ from motion. Long-pressing
     * the quick tile cycles AUTO -> HQ -> FAST -> AUTO; [highQuality]
     * holds the manual choice while auto is off.
     */
    val autoQuality: Boolean = true
) {
    init {
        require(strength.isFinite() && strength in MIN_STRENGTH..MAX_STRENGTH) {
            "HDR+ strength must be in $MIN_STRENGTH..$MAX_STRENGTH"
        }
    }

    fun activeFrameCount(configuredCount: Int): Int = configuredCount.coerceIn(
        if (enabled) MIN_MERGE_FRAMES else MIN_ZSL_FRAMES,
        MAX_MERGE_FRAMES
    )

    fun toPreferences(): Map<String, Any> = mapOf(
        "hdr_plus_enabled" to enabled,
        "hdr_plus_strength" to strength,
        "hdr_plus_high_quality" to highQuality,
        "hdr_plus_keep_source_burst" to keepSourceBurst,
        "hdr_plus_auto_strength" to autoStrength,
        "hdr_plus_auto_quality" to autoQuality
    )

    fun quickText(count: Int, buffered: Int, available: Boolean?, busy: Boolean): String = "HDR+\n" + when {
        !enabled -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || buffered < activeFrameCount(count) -> "WARMING"
        autoQuality -> "AUTO ×${activeFrameCount(count)}"
        else -> "${if (highQuality) "HQ" else "FAST"} ×${activeFrameCount(count)}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): HdrPlusSettings = HdrPlusSettings(
            enabled = values["hdr_plus_enabled"] as? Boolean ?: false,
            strength = (values["hdr_plus_strength"] as? Number)?.toFloat()
                ?.takeIf { it.isFinite() }?.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
                ?: DEFAULT_STRENGTH,
            highQuality = values["hdr_plus_high_quality"] as? Boolean ?: true,
            keepSourceBurst = values["hdr_plus_keep_source_burst"] as? Boolean ?: false,
            autoStrength = values["hdr_plus_auto_strength"] as? Boolean ?: true,
            autoQuality = values["hdr_plus_auto_quality"] as? Boolean ?: true
        )
        const val MIN_ZSL_FRAMES = 1
        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        const val MIN_STRENGTH = 1f
        const val MAX_STRENGTH = 22f
        const val DEFAULT_STRENGTH = 13f
        /**
         * Settings strength slider contract: integer progress 0..210 maps
         * linearly to 1.0..22.0 in 0.1 steps. Both directions clamp;
         * non-finite strength falls back to the default.
         */
        const val STRENGTH_SLIDER_STEPS = 210
        fun progressToStrength(progress: Int): Float =
            MIN_STRENGTH + progress.coerceIn(0, STRENGTH_SLIDER_STEPS) / 10f
        fun strengthToProgress(strength: Float): Int {
            val sane = strength.takeIf { it.isFinite() } ?: DEFAULT_STRENGTH
            return ((sane.coerceIn(MIN_STRENGTH, MAX_STRENGTH) - MIN_STRENGTH) * 10f + 0.5f).toInt()
        }
        fun strengthSliderText(strength: Float): String =
            String.format(java.util.Locale.US, "HDR+ strength: %.1f", strength)
        /** Fixed alignment geometry (RAWR defaults; upstream tile-64 unsupported). */
        const val TILE_SIZE = 32
        const val SEARCH_DISTANCE = 64
    }
}
