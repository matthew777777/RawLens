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

/** Shared by the controller and regression tests: legacy selection always dispatches independently. */
internal fun <T> dispatchRawZslSelection(frames: List<T>, settings: RawSuperResolutionSettings,
                                       burst: (List<T>) -> Unit, source: (Int, T) -> Unit) {
    if (settings.enabled) burst(frames) else frames.forEachIndexed(source)
}

data class RawSuperResolutionSettings(
    val enabled: Boolean = false,
    val dngMode: RawSrDngMode = RawSrDngMode.LINEAR_RGB,
    val outputScale: Float = 1f,
    val keepSourceBurst: Boolean = false,
    /** GCam-payload-style debug: dump every merged input frame plus the
     * merged output on each SR save. Off by default; costs extra unpacks. */
    val saveMergeDebugFrames: Boolean = false
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

    fun toPreferences(): Map<String, Any> = mapOf(
        "raw_super_resolution_enabled" to enabled,
        "raw_super_resolution_dng_mode" to dngMode.preferenceValue,
        "raw_super_resolution_output_scale" to outputScale,
        "raw_super_resolution_keep_source_burst" to keepSourceBurst,
        "raw_super_resolution_save_merge_debug" to saveMergeDebugFrames
    )

    fun quickText(count: Int, buffered: Int, available: Boolean?, busy: Boolean): String = "RAW SR\n" + when {
        !enabled -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || buffered < activeFrameCount(count) -> "WARMING"
        else -> "ON ×${activeFrameCount(count)}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): RawSuperResolutionSettings = RawSuperResolutionSettings(
            enabled = values["raw_super_resolution_enabled"] as? Boolean ?: false,
            dngMode = RawSrDngMode.fromPreference(values["raw_super_resolution_dng_mode"] as? String),
            outputScale = (values["raw_super_resolution_output_scale"] as? Number)?.toFloat()
                ?.takeIf { it.isFinite() }?.coerceIn(MIN_OUTPUT_SCALE, MAX_OUTPUT_SCALE) ?: 1f,
            keepSourceBurst = values["raw_super_resolution_keep_source_burst"] as? Boolean ?: false,
            saveMergeDebugFrames = values["raw_super_resolution_save_merge_debug"] as? Boolean ?: false
        )
        const val MIN_ZSL_FRAMES = 1
        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        const val MIN_OUTPUT_SCALE = 1f
        const val MAX_OUTPUT_SCALE = 2f
    }
}
