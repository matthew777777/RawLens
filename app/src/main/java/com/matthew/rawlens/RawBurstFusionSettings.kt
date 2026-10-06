// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Native-resolution ZSL burst fusion (Wiener temporal merge).
 *
 * Unlike RAW SR (which reconstructs a super-resolved grid), fusion merges
 * the ZSL ring at sensor resolution: global translation alignment per
 * alternate ([HdrBracketAligner]), then [HdrRawMerge] with the HDR+
 * Wiener deghost pre-pass ([HdrTileDeghost]) driven by the DNG noise
 * profile. One merged CFA DNG out. Mutually exclusive with RAW SR in
 * the quick panel (both own the ZSL ring at shutter time).
 */
data class RawBurstFusionSettings(
    val enabled: Boolean = false,
    /**
     * HDR+ Wiener strength (c = k·tau in the desktop core): how much
     * alternate-frame signal survives on matched bins. Larger = cleaner
     * but less ghost-robust. Same scale as [HdrRawMerge.Options].
     */
    val tau: Float = 8f,
    /** Also save every source frame as DNG alongside the merge. */
    val keepSourceBurst: Boolean = false
) {
    init {
        require(tau.isFinite() && tau in MIN_TAU..MAX_TAU) {
            "Burst fusion tau must be in $MIN_TAU..$MAX_TAU"
        }
    }

    fun activeFrameCount(configuredCount: Int): Int = configuredCount.coerceIn(
        if (enabled) MIN_MERGE_FRAMES else MIN_ZSL_FRAMES,
        MAX_MERGE_FRAMES
    )

    fun toPreferences(): Map<String, Any> = mapOf(
        "burst_fusion_enabled" to enabled,
        "burst_fusion_tau" to tau,
        "burst_fusion_keep_source_burst" to keepSourceBurst
    )

    fun quickText(count: Int, buffered: Int, available: Boolean?, busy: Boolean): String = "FUSION\n" + when {
        !enabled -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || buffered < activeFrameCount(count) -> "WARMING"
        else -> "ON ×${activeFrameCount(count)}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): RawBurstFusionSettings = RawBurstFusionSettings(
            enabled = values["burst_fusion_enabled"] as? Boolean ?: false,
            tau = (values["burst_fusion_tau"] as? Number)?.toFloat()
                ?.takeIf { it.isFinite() }?.coerceIn(MIN_TAU, MAX_TAU) ?: 8f,
            keepSourceBurst = values["burst_fusion_keep_source_burst"] as? Boolean ?: false
        )
        const val MIN_ZSL_FRAMES = 1
        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        const val MIN_TAU = 0.5f
        const val MAX_TAU = 64f
    }
}
