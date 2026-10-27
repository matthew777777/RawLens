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
    /** Also save every source frame as DNG alongside the merge. */
    val keepSourceBurst: Boolean = false,
    /**
     * Reconstruction kernel: Wronski's per-frame anisotropic kernels
     * (paper supplement S.1, default — parity with the reference
     * implementation), or PhotonCamera KernelNet from the reference
     * frame. KernelNet stays selectable in Settings -> Burst but is
     * not the default until it is fixed (owner 2026-10-23).
     */
    val kernel: MfsrKernel = MfsrKernel.WRONSKI,
    /**
     * Wronski supplement S.3 high-frequency artefact rejection in the
     * robustness stage (default on, as measured in docs/refactor/
     * mfsr-s3.md). Off gives 1:1 parity with the IPOL reference, which
     * has no S.3.
     */
    val s3: Boolean = true
) {
    /** Follow the shared ZSL selection, with an MFSR-only ceiling. */
    fun activeFrameCount(configuredCount: Int): Int =
        configuredCount.coerceIn(MIN_MERGE_FRAMES, MAX_MERGE_FRAMES)

    fun toPreferences(): Map<String, Any> = mapOf(
        "mfsr_enabled" to enabled,
        "mfsr_keep_source_burst" to keepSourceBurst,
        "mfsr_kernel" to kernel.name,
        "mfsr_s3" to s3
    )

    fun quickText(count: Int, buffered: Int, available: Boolean?, busy: Boolean): String = "MFSR\n" + when {
        !enabled -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || buffered < activeFrameCount(count) -> "WARMING"
        else -> "×${activeFrameCount(count)}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): MfsrSettings = MfsrSettings(
            enabled = values["mfsr_enabled"] as? Boolean ?: false,
            keepSourceBurst = values["mfsr_keep_source_burst"] as? Boolean ?: false,
            kernel = MfsrKernel.entries.firstOrNull { it.name == values["mfsr_kernel"] }
                ?: MfsrKernel.WRONSKI,
            s3 = values["mfsr_s3"] as? Boolean ?: true
        )

        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 16
    }
}

/** MFSR reconstruction kernel source (see [MfsrSettings.kernel]). */
enum class MfsrKernel { KERNELNET, WRONSKI }
