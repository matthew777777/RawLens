// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared merge-output descriptor (unified phone/desktop bytes): the merge
// orchestrator fills it with opaque Int image handles; consumers download
// through the registry twins (vkDownload*) or the RawSrMergeJob readbacks.
package com.matthew.rawlens

data class RawSrGpuOutput(
    /** Ordinary linear camera-RGB numerators (rgb; Prompt 4D merge contract). */
    val numeratorTextureId: Int,
    /** Independent per-channel R/G/B denominators (rgb; RGBA32F, contract §6). */
    val denominatorTextureId: Int,
    val width: Int,
    val height: Int,
    val acceptedFrames: Int,
    /** Only the last moving flow is live. Use onFlow for per-frame diagnostics. */
    val flowTextureIds: List<Int>,
    /** Accumulated per-quad robustness Rc; identically zero in reference-only mode. */
    val rcTextureId: Int,
    /** Reference-only A/B numerators (rgb): the reference-last pass through the same path. */
    val refNumeratorTextureId: Int,
    /** Reference-only A/B denominators (rgb): backs the local fallback. */
    val refDenominatorTextureId: Int,
    /** Final merged linear RGB after the reference-last add, normalization, and fallback. */
    val mergedTextureId: Int,
    /** Per-pixel local-fallback mask (1 where any channel fell back to reference-only). */
    val fallbackTextureId: Int,
    /** Per-pixel out-of-bounds diagnostic counter (contract §7). */
    val oobTextureId: Int,
    val peakTextureBytes: Long = 0
)
