// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

enum class RawSrDngMode(val preferenceValue: String, val label: String) {
    LINEAR_RGB("linear_rgb", "LINEAR"),
    MOSAIC_SR("mosaic_sr", "MOSAIC");

    companion object {
        fun fromPreference(value: String?): RawSrDngMode =
            entries.firstOrNull { it.preferenceValue == value } ?: LINEAR_RGB
    }
}

data class RawSuperResolutionSettings(
    val enabled: Boolean = false,
    val dngMode: RawSrDngMode = RawSrDngMode.LINEAR_RGB,
    val outputScale: Float = 1f,
    val keepSourceBurst: Boolean = false
) {
    init {
        require(outputScale in MIN_OUTPUT_SCALE..MAX_OUTPUT_SCALE) {
            "RAW SR output scale must be in $MIN_OUTPUT_SCALE..$MAX_OUTPUT_SCALE"
        }
    }

    fun activeFrameCount(configuredCount: Int): Int = configuredCount.coerceIn(
        if (enabled) MIN_MERGE_FRAMES else MIN_ZSL_FRAMES,
        MAX_MERGE_FRAMES
    )

    companion object {
        const val MIN_ZSL_FRAMES = 1
        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        const val MIN_OUTPUT_SCALE = 1f
        const val MAX_OUTPUT_SCALE = 2f
    }
}
