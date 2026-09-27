// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls for the GALOSH classical-denoise pipeline.
 *
 * Mirrors [DenoiseSettings]: when [enabled], each capture runs the Vulkan
 * o32 pipeline on the normalized pre-demosaic CFA and the denoised CFA is
 * used for both the developed JPEG and the denoised DNG. Unavailable
 * device/failure falls back silently (behaves as if Galosh were off).
 *
 * When both AI ([DenoiseSettings.aiEnabled]) and Galosh are enabled,
 * Galosh wins and AI is skipped for that capture (single denoise stage).
 *
 * Sliders (UI exposes percent):
 * - [strength] 0–100%: native sigma scaling (1.0 = full denoise). 0%
 *   skips the run and keeps the source CFA.
 * - [luma]/[chroma] 0–200%: luma/chroma sigma multipliers (100% neutral).
 * Noise model is always blind-fit (alpha/sigma 0); wht=8. Any Bayer
 * pattern works: non-RGGB arrangements are losslessly reshuffled to RGGB
 * for the run and restored after ([GaloshBayerRemap]).
 *
 * [fastMode] runs the 4-phase WHT subset instead of all 16 (~4x faster
 * Phase 5, slightly less smooth). Outside the 69 dB parity gate, which
 * covers the full 16-phase config only.
 *
 * [saveOriginalDng] only matters when [enabled]: the denoised DNG is
 * always written (subject to the capture format including DNG); this flag
 * additionally keeps the untouched archival sensor DNG.
 */
data class GaloshSettings(
    val enabled: Boolean = false,
    val saveOriginalDng: Boolean = true,
    val strength: Float = 1f,
    val luma: Float = 1f,
    val chroma: Float = 1f,
    val fastUpsample: Boolean = false,
    val fastMode: Boolean = false
) {
    init {
        require(strength.isFinite() && strength in 0f..1f) {
            "strength must be in [0,1], was $strength"
        }
        require(luma.isFinite() && luma in 0f..2f) {
            "luma must be in [0,2], was $luma"
        }
        require(chroma.isFinite() && chroma in 0f..2f) {
            "chroma must be in [0,2], was $chroma"
        }
    }
}
