// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Sharp-merge mode: takes a fresh Photo burst or the ZSL ring, reorders sharpest-first,
 * and merges at native sensor resolution through the shared SR engine
 * (tools/sr-pipeline, scale 1) instead of the HDR+ merge. Output is
 * linear RGB: one linear DNG plus one developed JPEG (Amaze bypassed —
 * the merge output is already RGB — through the shared AGX/JPEG tail).
 *
 * HDR+ is pattern reference only (settings shape, quick tile states,
 * shutter discipline); the merge itself never runs here. Mutually
 * exclusive with HDR+, RAW SR and the HDR bracket tile in the quick
 * panel (all own the shutter): enabling one turns
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
     * frame. Default KernelNet (owner 2026-11-04) after the C24 width-window
     * fix (texture checker 0.67x Wronski on 20 Vulkan crops, ~5 % softer
     * edges); Wronski stays selectable in Settings -> Burst.
     */
    val kernel: MfsrKernel = MfsrKernel.KERNELNET,
    /**
     * Wronski supplement S.3 high-frequency artefact rejection in the
     * robustness stage (default on, as measured in docs/refactor/
     * mfsr-s3.md). Off gives 1:1 parity with the IPOL reference, which
     * has no S.3.
     */
    val s3: Boolean = true,
    /**
     * Noise model for the merge (GAT, SNR -> tile/kernel params, robustness
     * LUT, KernelNet sigma). On (default): the learned per-device model
     * (WaveletDenoiser store, rawlens_noise::fit) sets the green level, the
     * Camera2 profile only the R/B-to-G shape; until the model is trained
     * it falls back to the profile. Off: the Camera2 profile as reported
     * (this phone's overstates base-ISO noise 4-14x in variance,
     * docs/refactor/wavelet-rawlens-wiener.md).
     */
    val learnedNoise: Boolean = true,
    /**
     * Z1 zipper fix: colour-difference accumulation (sr-pipeline ColourDiff:
     * R/B splat as wb*C - G_hat, rebuilt after the divide). Default on; off =
     * the plain Wronski Eq. 1 merge (previous output, bit for bit).
     */
    val zipperFix: Boolean = true,
    /**
     * Merge engine behind the same shutter / ZSL / save path: Wronski MFSR
     * (sr-pipeline) or LK-Reloaded (tools/lkr-pipeline, Lecouat et al. 2021).
     * Each engine has its own quick tile (MFSR, LK-SR); turning one on
     * switches the shared sharp-merge path to that engine.
     */
    val engine: MfsrEngine = MfsrEngine.MFSR,
    /** LK-SR: regularise with the Vulkan sliding-DCT x-step; false (default) = no prior. */
    val lkrDctPrior: Boolean = false,
    /**
     * LK-SR zoom (viewfinder chip): 1x full frame at x1; 1.5x / 2x solve the
     * centre crop at x2 (1.5x then area-downsampled). Output is always the
     * sensor resolution.
     */
    val lkrZoom: LkrZoom = LkrZoom.X1
) {
    /** Quick-tile / status label of the active engine. */
    val label: String get() = if (engine == MfsrEngine.LKR) "LK-SR" else "MFSR"
    /** Follow the shared burst / ZSL frame count, up to the active engine's ceiling. */
    fun activeFrameCount(configuredCount: Int): Int =
        configuredCount.coerceIn(MIN_MERGE_FRAMES, maxFrames(engine, lkrZoom))

    fun toPreferences(): Map<String, Any> = mapOf(
        "mfsr_enabled" to enabled,
        "mfsr_keep_source_burst" to keepSourceBurst,
        "mfsr_kernel" to kernel.name,
        "mfsr_s3" to s3,
        "mfsr_learned_noise" to learnedNoise,
        "mfsr_zipper_fix" to zipperFix,
        "mfsr_engine" to engine.name,
        "lkr_dct_prior" to lkrDctPrior,
        "lkr_zoom" to lkrZoom.name
    )

    /** True when the sharp merge is on with [which] as its engine (that engine's tile is active). */
    fun isOn(which: MfsrEngine): Boolean = enabled && engine == which

    fun quickText(
        which: MfsrEngine, count: Int, buffered: Int, available: Boolean?, busy: Boolean, zsl: Boolean = true
    ): String = (if (which == MfsrEngine.LKR) "LK-SR" else "MFSR") + "\n" + when {
        !isOn(which) -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || (zsl && buffered < activeFrameCount(count)) -> "WARMING"
        else -> "×${activeFrameCount(count)}"
    }

    companion object {
        fun fromPreferences(values: Map<String, *>): MfsrSettings = MfsrSettings(
            enabled = values["mfsr_enabled"] as? Boolean ?: false,
            keepSourceBurst = values["mfsr_keep_source_burst"] as? Boolean ?: false,
            kernel = MfsrKernel.entries.firstOrNull { it.name == values["mfsr_kernel"] }
                ?: MfsrKernel.KERNELNET,
            s3 = values["mfsr_s3"] as? Boolean ?: true,
            learnedNoise = values["mfsr_learned_noise"] as? Boolean ?: true,
            zipperFix = values["mfsr_zipper_fix"] as? Boolean ?: true,
            engine = MfsrEngine.entries.firstOrNull { it.name == values["mfsr_engine"] } ?: MfsrEngine.MFSR,
            lkrDctPrior = values["lkr_dct_prior"] as? Boolean ?: false,
            lkrZoom = LkrZoom.entries.firstOrNull { it.name == values["lkr_zoom"] } ?: LkrZoom.X1
        )

        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 16
        /** LK-SR at x1 follows the largest burst the app captures. */
        const val MAX_LKR_FRAMES = 59
        /**
         * LK-SR x2 modes (1.5x, 2x, full 2x): per-frame solver buffers are 4x larger, so one
         * worker at 24 frames already needs ~600 MB of native memory next to the frames and output.
         */
        const val MAX_LKR_FRAMES_X2 = 24

        fun maxFrames(engine: MfsrEngine, zoom: LkrZoom = LkrZoom.X1): Int = when {
            engine != MfsrEngine.LKR -> MAX_MERGE_FRAMES
            zoom == LkrZoom.X1 -> MAX_LKR_FRAMES
            else -> MAX_LKR_FRAMES_X2
        }
    }
}

/** MFSR reconstruction kernel source (see [MfsrSettings.kernel]). */
enum class MfsrKernel { KERNELNET, WRONSKI }

/**
 * LK-SR zoom: [factor] of the centre crop. 1x solves the full frame at x1;
 * 1.5x and 2x solve the centre 1/factor crop at x2 (2x lands exactly on the
 * sensor size, 1.5x is area-downsampled to it), so the output is always
 * sensor resolution with real super-resolved detail.
 */
enum class LkrZoom(val factor: Float, val label: String) {
    X1(1f, "1×"), X1_5(1.5f, "1.5×"), X2(2f, "2×"),
    /** Whole frame solved at x2: no crop, output is twice the sensor size on each side. */
    FULL_2X(1f, "Full 2×");

    fun next(): LkrZoom = entries[(ordinal + 1) % entries.size]

    /** Centre crop `[x, y, w, h]` (even) of a [w]x[h] sensor, or null at 1x. */
    fun crop(w: Int, h: Int): IntArray? {
        if (factor == 1f) return null
        val cw = (Math.round(w / factor / 2f) * 2).coerceAtMost(w)
        val ch = (Math.round(h / factor / 2f) * 2).coerceAtMost(h)
        return intArrayOf(((w - cw) / 2) and 1.inv(), ((h - ch) / 2) and 1.inv(), cw, ch)
    }
}

/** Merge engine of the MFSR shutter path (see [MfsrSettings.engine]). */
enum class MfsrEngine { MFSR, LKR }
