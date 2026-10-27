// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * RAW ZSL ring policy: repeating fps range, frames taken per press, and the
 * save slots a capture needs before it is admitted.
 *
 * Moved verbatim from RawCameraController's companion (A10,
 * docs/refactor/controller-remaining-plan.md).
 */
internal object ZslPolicy {
    /**
     * Pure MotionCam-style fixed-rate selection: prefer the exact fixed
     * [requestedFps, requestedFps] range, then the narrowest range containing it,
     * then the fastest advertised range. Plain [IntRange] keeps this unit-testable
     * without the Android framework; the caller maps the match back to the
     * advertised Camera2 range.
     */
    internal fun selectRawZslFpsRange(
        ranges: List<IntRange>,
        requestedFps: Int
    ): IntRange? {
        if (ranges.isEmpty()) return null
        return ranges
            .filter { it.last >= requestedFps }
            .minWithOrNull(
                compareBy<IntRange>(
                    { if (it.first >= requestedFps) 0 else 1 },
                    { it.last - requestedFps },
                    { it.last - it.first },
                    { -it.first }
                )
            ) ?: ranges.maxWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })
    }

    /**
     * Pure ZSL selection size behind [RawCameraController.zslSelectedFrameCount].
     * Merge modes save one artifact (one save slot), so each uses its
     * own merge bounds and bypasses the per-frame JPEG queue cap;
     * plain bursts keep the cap in JPEG modes (JPEG development holds
     * large intermediates per frame). Unit-testable without the Android
     * framework.
     */
    internal fun zslSelectedFrameCountFor(
        hdrPlus: HdrPlusSettings,
        configuredCount: Int,
        outputFormat: CaptureFormat,
        mfsr: MfsrSettings = MfsrSettings()
    ): Int {
        if (mfsr.enabled) return mfsr.activeFrameCount(configuredCount)
        if (hdrPlus.enabled) return hdrPlus.activeFrameCount(configuredCount)
        return if (outputFormat.includesJpeg) {
            minOf(configuredCount, RawCameraController.MAX_IN_FLIGHT_JPEG_SAVES)
        } else {
            configuredCount
        }
    }

    /**
     * Pure save-slot reservation behind [RawCameraController.refreshCaptureAvailability]
     * and the admission check in beginSingleCapture: merge modes save one
     * artifact, so the shutter gates on one slot — otherwise a 12-frame
     * HDR+ selection in a JPEG mode would grey out the shutter against
     * the 6-slot per-frame JPEG budget. Plain bursts gate on the full
     * selection. Unit-testable without the Android framework.
     */
    internal fun availabilitySlotCountFor(
        hdrPlus: HdrPlusSettings,
        configuredCount: Int,
        outputFormat: CaptureFormat,
        mfsr: MfsrSettings = MfsrSettings()
    ): Int =
        if (hdrPlus.enabled || mfsr.enabled) 1
        else zslSelectedFrameCountFor(hdrPlus, configuredCount, outputFormat)
}
