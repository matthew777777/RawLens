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
     * Wronski supplement S.3 high-frequency artefact rejection in the
     * robustness stage (default on, as measured in docs/refactor/
     * mfsr-s3.md). Off gives 1:1 parity with the IPOL reference, which
     * has no S.3.
     */
    val s3: Boolean = true,
    /**
     * Noise model for the merge (GAT, SNR -> tile/kernel params, robustness
     * LUT). On (default): the learned per-device model
     * (RawNoiseModel store, rawlens_noise::fit) sets the green level, the
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
    val greenFit: Boolean = true,
    val rtSharpen: Boolean = false,
    /**
     * Two-band reconstruction (sr-pipeline TwoBandReconstruction on Vulkan):
     * low band = wide Gaussian, high band = narrow Gaussian residual with an
     * overlapped DCT Wiener, no per-frame kernels. Needs the zipper fix
     * (colour differences); off = the Wronski kernel merge.
     */
    val twoBand: Boolean = false,
    /**
     * Merge engine behind the same shutter / ZSL / save path: Wronski MFSR
     * (sr-pipeline) or LK-Reloaded (tools/lkr-lite = LK-R Lite, after Lecouat et al. 2021).
     * Each engine has its own quick tile (MFSR, LK-R); turning one on
     * switches the shared sharp-merge path to that engine.
     */
    val engine: MfsrEngine = MfsrEngine.MFSR,
    /** LK-R: regularise with the Vulkan sliding-DCT x-step; false (default) = no prior. */
    val lkrDctPrior: Boolean = false,
    /** LK-R: learned post-fit network (colour dots / colour zipper; colour only). Stored default: on with
     *  an NPU (LkrBridge.nativeHasNpu); this constructor default only applies before settings load. */
    val lkrNetwork: Boolean = false,
    val lkrNpuDenoise: Boolean = false,
    val lkrNoPrior: Boolean = false,
    /**
     * LK-R: reference = sharpest of the middle three frames instead of the
     * newest three ([BurstSharpness.plan]); more frames survive motion
     * rejection, the photo shows a moment half a burst earlier.
     */
    val lkrMiddleReference: Boolean = true,
    /**
     * LK-R zoom (viewfinder chip): 1x full frame at x1; 1.5x / 2x solve the
     * centre crop at x2 (1.5x then area-downsampled). Output is always the
     * sensor resolution.
     */
    val lkrZoom: LkrZoom = LkrZoom.X1,
    /**
     * LK-R resolution (viewfinder round button), independent of [lkrZoom]:
     * keep the x2 solve instead of returning to sensor size. 1x -> 4x the
     * sensor pixels; 1.5x -> crop x2 (~2.25x pixels); 2x -> sensor size.
     */
    val lkrFullRes: Boolean = false,
    /**
     * MFSR resolution (same viewfinder round button as LK-R when MFSR is the
     * active engine): merge at 2x (Wronski super-resolution, 12.5 -> 50 MP)
     * instead of the sensor grid.
     */
    val mfsrFullRes: Boolean = false,
    /**
     * LK-R HDR (Lecouat et al. 2022 on Lite): AUTO adds short (and a long) forward frame to the ZSL
     * 0 EV frames when the press-moment frame shows they would help ([LkrHdrDecision]); ON always
     * does; OFF never (the normal ZSL merge). Total frame count stays the same.
     */
    val lkrHdr: LkrHdrMode = LkrHdrMode.AUTO
) {
    /** Quick-tile / status label of the active engine. */
    val label: String get() = if (engine == MfsrEngine.LKR) "LK-R" else "MFSR"
    /** Follow the shared burst / ZSL frame count, up to the active engine's ceiling. */
    fun activeFrameCount(configuredCount: Int): Int =
        configuredCount.coerceIn(MIN_MERGE_FRAMES, maxFrames(engine, if (lkrFullRes && lkrZoom == LkrZoom.X1) LkrZoom.FULL_2X else lkrZoom))

    internal val useGreenFit: Boolean
        get() = greenFit && !mfsrFullRes && zipperFix && !twoBand

    fun toPreferences(): Map<String, Any> = mapOf(
        KEY_ENABLED to enabled,
        "mfsr_keep_source_burst" to keepSourceBurst,
        "mfsr_s3" to s3,
        "mfsr_learned_noise" to learnedNoise,
        "mfsr_zipper_fix" to zipperFix,
        "mfsr_green_fit" to greenFit,
        KEY_RT_SHARPEN to rtSharpen,
        KEY_EXPERIMENTAL_TWO_BAND to twoBand,
        "mfsr_engine" to engine.name,
        "lkr_dct_prior" to lkrDctPrior,
        "lkr_network" to lkrNetwork,
        "lkr_npu_denoise" to lkrNpuDenoise,
        "lkr_no_prior" to lkrNoPrior,
        "lkr_middle_reference" to lkrMiddleReference,
        "lkr_zoom" to lkrZoom.name,
        "lkr_full_res" to lkrFullRes,
        "mfsr_full_res" to mfsrFullRes,
        "lkr_hdr" to lkrHdr.name
    )

    /** True when the sharp merge is on with [which] as its engine (that engine's tile is active). */
    fun isOn(which: MfsrEngine): Boolean = enabled && engine == which

    fun quickText(
        which: MfsrEngine, count: Int, buffered: Int, available: Boolean?, busy: Boolean, zsl: Boolean = true
    ): String = (if (which == MfsrEngine.LKR) "LK-R" else "MFSR") + "\n" + when {
        !isOn(which) -> "OFF"
        busy -> "MERGING"
        available == false -> "UNAVAILABLE"
        available == null || (zsl && buffered < activeFrameCount(count)) -> "WARMING"
        // LK-R shows its HDR mode (quick-tile long-press cycles it): "AUTO HDR ×8", "HDR ×8", "×8" = off.
        which == MfsrEngine.LKR && lkrHdr == LkrHdrMode.AUTO -> "AUTO HDR ×${activeFrameCount(count)}"
        which == MfsrEngine.LKR && lkrHdr == LkrHdrMode.ON -> "HDR ×${activeFrameCount(count)}"
        else -> "×${activeFrameCount(count)}"
    }

    companion object {
        const val KEY_ENABLED = "mfsr_enabled"
        // The old key could contain the former default from a bulk settings save.
        const val KEY_RT_SHARPEN = "mfsr_rt_sharpen"
        const val KEY_EXPERIMENTAL_TWO_BAND = "mfsr_experimental_two_band"
        fun fromPreferences(values: Map<String, *>): MfsrSettings = MfsrSettings(
            enabled = values[KEY_ENABLED] as? Boolean ?: false,
            keepSourceBurst = values["mfsr_keep_source_burst"] as? Boolean ?: false,
            s3 = values["mfsr_s3"] as? Boolean ?: true,
            learnedNoise = values["mfsr_learned_noise"] as? Boolean ?: true,
            zipperFix = values["mfsr_zipper_fix"] as? Boolean ?: true,
            greenFit = values["mfsr_green_fit"] as? Boolean ?: true,
            rtSharpen = values[KEY_RT_SHARPEN] as? Boolean ?: false,
            twoBand = values[KEY_EXPERIMENTAL_TWO_BAND] as? Boolean ?: false,
            engine = MfsrEngine.entries.firstOrNull { it.name == values["mfsr_engine"] } ?: MfsrEngine.MFSR,
            lkrDctPrior = values["lkr_dct_prior"] as? Boolean ?: false,
            // Default: on for phones with an NPU (nearly free there; colour-only since d1affb09, no luma
            // sharpening), off otherwise (the Vulkan fallback costs GPU time). Caught here: LkrBridge's class
            // init loads the native library (absent in JVM tests).
            lkrNetwork = values["lkr_network"] as? Boolean ?: runCatching { LkrBridge.nativeHasNpu() }.getOrDefault(false),
            lkrNpuDenoise = values["lkr_npu_denoise"] as? Boolean ?: false,
            lkrNoPrior = values["lkr_no_prior"] as? Boolean ?: false,
            lkrMiddleReference = values["lkr_middle_reference"] as? Boolean ?: true,
            // Legacy FULL_2X (zoom and resolution in one value) splits into 1x + full res.
            lkrZoom = (LkrZoom.entries.firstOrNull { it.name == values["lkr_zoom"] } ?: LkrZoom.X1)
                .let { if (it == LkrZoom.FULL_2X) LkrZoom.X1 else it },
            lkrFullRes = values["lkr_full_res"] as? Boolean ?: (values["lkr_zoom"] == LkrZoom.FULL_2X.name),
            mfsrFullRes = values["mfsr_full_res"] as? Boolean ?: false,
            lkrHdr = LkrHdrMode.entries.firstOrNull { it.name == values["lkr_hdr"] } ?: LkrHdrMode.AUTO
        )

        const val MIN_MERGE_FRAMES = 2
        const val MAX_MERGE_FRAMES = 30
        /** LK-R at x1 follows the largest burst the app captures. */
        const val MAX_LKR_FRAMES = 59
        /**
         * LK-R Lite x2 modes (1.5x, 2x, full 2x): tiled solving and FP16 GPU frame
         * storage use a 30-frame cap, lower than x1 for input and solver memory.
         */
        const val MAX_LKR_FRAMES_X2 = 30

        fun maxFrames(engine: MfsrEngine, zoom: LkrZoom = LkrZoom.X1): Int = when {
            engine != MfsrEngine.LKR -> MAX_MERGE_FRAMES
            zoom == LkrZoom.X1 -> MAX_LKR_FRAMES
            else -> MAX_LKR_FRAMES_X2
        }
    }
}

/**
 * LK-R zoom: [factor] of the centre crop. 1x solves the full frame at x1;
 * 1.5x and 2x solve the centre 1/factor crop at x2 (2x lands exactly on the
 * sensor size, 1.5x is area-downsampled to it), so the output is always
 * sensor resolution with real super-resolved detail.
 */
enum class LkrZoom(val factor: Float, val label: String) {
    X1(1f, "1×"), X1_5(1.5f, "1.5×"), X2(2f, "2×"),
    /** Whole frame solved at x2: no crop, output is twice the sensor size on each side. */
    FULL_2X(1f, "Full 2×");

    fun next(): LkrZoom = entries[(ordinal + 1) % entries.size]

    /** Viewfinder zoom chip cycle: 1x -> 1.5x -> 2x -> 1x (resolution is separate). */
    fun nextZoom(): LkrZoom = when (this) { X1 -> X1_5; X1_5 -> X2; else -> X1 }

    /** Centre crop `[x, y, w, h]` (even) of a [w]x[h] sensor, or null at 1x. */
    fun crop(w: Int, h: Int): IntArray? {
        if (factor == 1f) return null
        val cw = (Math.round(w / factor / 2f) * 2).coerceAtMost(w)
        val ch = (Math.round(h / factor / 2f) * 2).coerceAtMost(h)
        return intArrayOf(((w - cw) / 2) and 1.inv(), ((h - ch) / 2) and 1.inv(), cw, ch)
    }
}

/** LK-R HDR capture mode (see [MfsrSettings.lkrHdr]). */
enum class LkrHdrMode(val label: String) { AUTO("Auto"), ON("On"), OFF("Off") }

/** Merge engine of the MFSR shutter path (see [MfsrSettings.engine]). */
enum class MfsrEngine { MFSR, LKR }
