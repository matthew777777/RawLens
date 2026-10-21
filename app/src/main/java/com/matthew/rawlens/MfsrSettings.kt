// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * ZSL sharp-merge mode: absorbs the ZSL ring, reorders sharpest-first,
 * and merges at native sensor resolution through the shared SR engine
 * (tools/sr-pipeline, scale 1) instead of the HDR+ merge. Output is
 * linear RGB: one linear DNG plus one developed JPEG (Amaze bypassed —
 * the merge output is already RGB — through the shared AGX/JPEG tail).
 *
 * HDR+ is pattern reference only (settings shape, quick tile states,
 * shutter discipline); the merge itself never runs here. Mutually
 * exclusive with HDR+, RAW SR and the HDR bracket tile in the quick
 * panel (all own the ZSL ring at shutter time): enabling one turns
 * the others off.
 */
data class MfsrSettings(
    val enabled: Boolean = false,
    /**
     * Frames per merge, 2..15. Defaults to 8: the SR engine's proven
     * operating point (the 30-frame proof is still pending, so the
     * ceiling stays conservative).
     */
    val frameCount: Int = DEFAULT_FRAMES,
    /** Also save every source frame as DNG alongside the merge. */
    val keepSourceBurst: Boolean = false,
    /**
     * Reconstruction kernel: PhotonCamera KernelNet from the reference
     * frame (default), or Wronski's per-frame anisotropic kernels (paper
     * supplement S.1, kept for parity with the reference implementation
     * and as the fallback).
     */
    val kernel: MfsrKernel = MfsrKernel.KERNELNET
) {
    init {
        require(frameCount in MIN_MERGE_FRAMES..MAX_MERGE_FRAMES) {
            "ZSL sharp merge needs $MIN_MERGE_FRAMES..$MAX_MERGE_FRAMES frames, got $frameCount"
        }
    }

    fun activeFrameCount(): Int = frameCount.coerceIn(MIN_MERGE_FRAMES, MAX_MERGE_FRAMES)

    fun toPreferences(): Map<String, Any> = mapOf(
        "mfsr_enabled" to enabled,
        "mfsr_frames" to frameCount,
        "mfsr_keep_source_burst" to keepSourceBurst,
        "mfsr_kernel" to kernel.name
    )

    fun quickText(buffered: Int, available: Boolean?, busy: Boolean): String = "MFSR\n" + when {
        !enabled -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || buffered < activeFrameCount() -> "WARMING"
        else -> "×${activeFrameCount()}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): MfsrSettings = MfsrSettings(
            enabled = values["mfsr_enabled"] as? Boolean ?: false,
            frameCount = (values["mfsr_frames"] as? Number)?.toInt()
                ?.coerceIn(MIN_MERGE_FRAMES, MAX_MERGE_FRAMES)
                ?: DEFAULT_FRAMES,
            keepSourceBurst = values["mfsr_keep_source_burst"] as? Boolean ?: false,
            kernel = MfsrKernel.entries.firstOrNull { it.name == values["mfsr_kernel"] }
                ?: MfsrKernel.KERNELNET
        )

        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 15
        const val DEFAULT_FRAMES = 8
    }
}

/** MFSR reconstruction kernel source (see [MfsrSettings.kernel]). */
enum class MfsrKernel { KERNELNET, WRONSKI }
