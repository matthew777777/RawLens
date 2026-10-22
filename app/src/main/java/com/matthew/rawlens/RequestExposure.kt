// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Sensor pair a request drives directly with AE off. */
internal data class SensorExposure(val iso: Int, val exposureNanos: Long)

/**
 * Which exposure source a capture request uses: the user's manual ISO /
 * shutter, the RAW-measured ETTR pair (AUTO stills), the PROGRAM live pair,
 * or hardware AE (null). Pure; extracted from
 * RawCameraController.applyCameraControls (A3,
 * docs/refactor/controller-remaining-plan.md).
 */
internal object RequestExposure {
    class Inputs(
        val selectedIso: Int?,
        val selectedExposureNanos: Long?,
        /** Still/bracket requests opt in; repeating requests rely on [programCustomActive]. */
        val applyDynamicCurve: Boolean,
        val programCustomActive: Boolean,
        val dynamicExposureEnabled: Boolean,
        val rawZslRequested: Boolean,
        val programIso: Int?,
        val programShutterNanos: Long?,
        val programTargetIso: Int?,
        val programTargetShutterNanos: Long?,
        val ettrCaptureActive: Boolean,
        val ettrIso: Int?,
        val ettrShutterNanos: Long?,
        val lastIso: Int,
        val lastExposureNanos: Long,
        val isoRange: IntRange?,
        val exposureRange: LongRange?,
        val manualSensorSupported: Boolean,
    )

    fun resolve(i: Inputs): SensorExposure? {
        val manualExposure = i.selectedIso != null || i.selectedExposureNanos != null
        val programLive = !manualExposure && i.programCustomActive &&
            i.programIso != null && i.programShutterNanos != null
        val dynamicExposure = (i.applyDynamicCurve || programLive) && !manualExposure &&
            i.dynamicExposureEnabled && !i.rawZslRequested &&
            i.programIso != null && i.programShutterNanos != null
        // RAW-measured ETTR (AUTO mode only): the still request exposes so the
        // hottest CFA channel lands just below clipping.
        // While the glide is mid-flight the live (ramping) pair wins over both
        // targets, so every repeating rebuild keeps moving smoothly instead of
        // jumping straight to the endpoint.
        val rampGliding = i.programTargetIso != null && i.programTargetShutterNanos != null &&
            (i.programIso != i.programTargetIso || i.programShutterNanos != i.programTargetShutterNanos)
        val ettrExposure = (i.applyDynamicCurve || programLive) && !manualExposure && i.ettrCaptureActive &&
            !rampGliding
        if (!((manualExposure || dynamicExposure || ettrExposure) && i.manualSensorSupported)) return null
        // The PROGRAM pair only while PROGRAM drives the exposure: with one axis
        // manual, the other keeps the last metered value (latest result), not a
        // left-over PROGRAM pair from an earlier mode (R-A3-1).
        val programDriving = dynamicExposure
        val targetIso = i.selectedIso ?: (if (ettrExposure) i.ettrIso else null)
            ?: (if (programDriving) i.programIso else null) ?: i.lastIso
        val targetExposure = i.selectedExposureNanos ?: (if (ettrExposure) i.ettrShutterNanos else null)
            ?: (if (programDriving) i.programShutterNanos else null) ?: i.lastExposureNanos
        val iso = targetIso.coerceIn(i.isoRange?.first ?: targetIso, i.isoRange?.last ?: targetIso)
        val exposure = targetExposure.coerceIn(
            i.exposureRange?.first ?: i.lastExposureNanos,
            i.exposureRange?.last ?: i.lastExposureNanos
        )
        return SensorExposure(iso, exposure)
    }
}
