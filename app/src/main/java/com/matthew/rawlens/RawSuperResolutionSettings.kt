// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.floor

enum class RawSrDngMode(val preferenceValue: String, val label: String) {
    LINEAR_RGB("linear_rgb", "LINEAR"),
    MOSAIC_SR("mosaic_sr", "MOSAIC");

    companion object {
        fun fromPreference(value: String?): RawSrDngMode =
            entries.firstOrNull { it.preferenceValue == value } ?: LINEAR_RGB
    }


}

/** Kernel/robustness tuning source: SNR-adaptive reference or a fixed A/B preset. */
enum class RawSrKernelPreset(val preferenceValue: String, val label: String) {
    REFERENCE("reference", "REFERENCE"),
    DECOUPLED_SHARP("decoupled_sharp", "DECOUPLED");

    companion object {
        fun fromPreference(value: String?): RawSrKernelPreset =
            entries.firstOrNull { it.preferenceValue == value } ?: REFERENCE
    }

    /**
     * Resolve to a fixed tuning from a reference SNR scan, or null to use
     * the scan itself (REFERENCE). DECOUPLED_SHARP keeps the RAWR detail
     * end but sizes the flat to the scene's own scanned width, so bright
     * bursts don't inherit a mid-SNR flat.
     */
    fun resolve(scan: RawSrTuning): RawSrTuning? = when (this) {
        REFERENCE -> null
        DECOUPLED_SHARP -> RawSrTuning.decoupledSharp(
            flatSigma = scan.kDetail * scan.kDenoise, snrDb = scan.snr)
    }
}

/** Linear RGB output grid: sensor resolution or the shared √2 super-resolved grid (2x area, same lattice as mosaic SR). */
enum class RawSrLinearScale(val preferenceValue: String, val label: String, val factor: Double) {
    X1("1x", "1X", 1.0),
    SR("sr", "SR", MosaicSrReconstructor.LINEAR_SCALE);

    companion object {
        fun fromPreference(value: String?): RawSrLinearScale =
            entries.firstOrNull { it.preferenceValue == value } ?: X1
    }
}

/** Mosaic SR output grid: native sensor resolution or the √2 super-resolved grid (2x area). */
enum class RawSrMosaicScale(val preferenceValue: String, val label: String, val factor: Double) {
    NATIVE("native", "NATIVE", 1.0),
    SR("sr", "SR", MosaicSrReconstructor.LINEAR_SCALE);

    companion object {
        fun fromPreference(value: String?): RawSrMosaicScale =
            entries.firstOrNull { it.preferenceValue == value } ?: SR
    }
}

/**
 * Even SR output grid at linear [factor], shared by the mosaic and linear
 * paths so both SR grids are pixel-identical. Rounding is floor-to-even
 * (largest even grid at or below dim×s), so every output site maps strictly
 * inside the source frame. A ~12 MP source lands at ~2× area at √2; 1.0
 * reproduces the source grid exactly. Returns (width, height).
 */
internal fun planSrOutputDims(sourceWidth: Int, sourceHeight: Int, factor: Double): Pair<Int, Int> {
    require(sourceWidth >= 2 && sourceHeight >= 2 && sourceWidth % 2 == 0 && sourceHeight % 2 == 0) {
        "SR output needs an even source crop of at least 2x2"
    }
    fun floorToEven(value: Double): Int = (floor(value / 2.0).toInt() * 2)
    // Floor to even: 2×floor(dim×s/2). A ~12 MP source lands at ~2× area
    // at SR scale; 1.0 reproduces the source grid exactly.
    val width = floorToEven(sourceWidth * factor).coerceAtLeast(2)
    val height = floorToEven(sourceHeight * factor).coerceAtLeast(2)
    return width to height
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
    val saveMergeDebugFrames: Boolean = false,
    /** Anchor the merge on the sharpest eligible frame instead of the most
     * central stable one ([RawSrBurstPlanner.ReferenceMode.SHARPEST_FIRST]).
     * On by default (sharpest-first reference). */
    val sharpestReference: Boolean = true,
    /** Kernel tuning source; DECOUPLED_SHARP runs the fixed decoupled-sharp
     * preset on both the GPU and CPU merge paths. Default is REFERENCE. */
    val kernelPreset: RawSrKernelPreset = RawSrKernelPreset.REFERENCE,
    /** Mosaic SR output grid; NATIVE reconstructs at sensor resolution,
     * SR upscales to the √2 grid (2x area). Default is SR. */
    val mosaicScale: RawSrMosaicScale = RawSrMosaicScale.SR,
    /** Linear RGB output grid; SR resolves the shared √2 grid (2x area,
     * ~25 MP, pixel-identical to the mosaic SR lattice). Default is X1.
     * SR needs ~2x merge memory; the save guards on free RAM and falls
     * back to 1x with a warning when short. */
    val linearScale: RawSrLinearScale = RawSrLinearScale.X1
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
        "raw_super_resolution_save_merge_debug" to saveMergeDebugFrames,
        "raw_super_resolution_sharpest_reference" to sharpestReference,
        "raw_super_resolution_kernel_preset" to kernelPreset.preferenceValue,
        "raw_super_resolution_mosaic_scale" to mosaicScale.preferenceValue,
        "raw_super_resolution_linear_scale" to linearScale.preferenceValue
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
            saveMergeDebugFrames = values["raw_super_resolution_save_merge_debug"] as? Boolean ?: false,
            sharpestReference = values["raw_super_resolution_sharpest_reference"] as? Boolean ?: true,
            kernelPreset = RawSrKernelPreset.fromPreference(values["raw_super_resolution_kernel_preset"] as? String),
            mosaicScale = RawSrMosaicScale.fromPreference(values["raw_super_resolution_mosaic_scale"] as? String),
            linearScale = RawSrLinearScale.fromPreference(values["raw_super_resolution_linear_scale"] as? String)
        )
        const val MIN_ZSL_FRAMES = 1
        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        const val MIN_OUTPUT_SCALE = 1f
        const val MAX_OUTPUT_SCALE = 2f
    }
}
