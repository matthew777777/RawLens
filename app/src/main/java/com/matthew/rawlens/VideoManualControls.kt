// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

/**
 * Manual exposure/focus/WB request keys and slider ranges shared by
 * [DirectLogRecorder] and [RawVideoRecorder] (V8, docs/refactor/video-study.md:
 * both carried the same copy). The recorders keep their own AE fps-range pick
 * (Direct-Log fails fast, RAW video falls back to fixed FPS) and Direct-Log
 * adds the lens-shading map request after this.
 */
internal object VideoManualControls {
    private const val TAG = "VideoFocus"
    /** Last logged AF decision; requests rebuild on every slider move, so log changes only. */
    @Volatile private var lastAfDecision: String? = null

    private fun logAfDecision(decision: String) {
        if (decision == lastAfDecision) return
        lastAfDecision = decision
        Log.i(TAG, decision)
    }

    fun hasManualSensor(c: CameraCharacteristics): Boolean =
        (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
            .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

    fun applyToRequest(
        builder: CaptureRequest.Builder,
        c: CameraCharacteristics,
        frameFps: Int,
        fpsRange: Pair<Int, Int>,
        manual: VideoManualSnapshot,
        locks: VideoRecordLocks,
        meteredIso: Int,
        meteredShutterNanos: Long,
        meteredFocus: Float?,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (manual.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
        }
        builder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(fpsRange.first, fpsRange.second)
        )
        val manualFocus = manual.focusDiopters
        val lockedFocus = VideoManualPolicy.resolveFocusLock(manualFocus, locks.lockFocus)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        fun coerceAf(mode: Int): Int {
            if (afModes.contains(mode)) return mode
            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                return CaptureRequest.CONTROL_AF_MODE_OFF
            }
            return afModes.firstOrNull() ?: mode
        }
        val frozenFocus = manualFocus ?: if (lockedFocus) meteredFocus else null
        val caps = afModes.joinToString(",") { Camera2Names.afModeName(it) }
        if (frozenFocus != null) {
            val reason = if (manualFocus != null) "manual" else "lock-on-record"
            logAfDecision(
                "Video AF request ${Camera2Names.afModeName(coerceAf(CaptureRequest.CONTROL_AF_MODE_OFF))} " +
                    "fd=${String.format(java.util.Locale.US, "%.2f", frozenFocus)}D ($reason) caps=[$caps]"
            )
            builder.set(CaptureRequest.CONTROL_AF_MODE, coerceAf(CaptureRequest.CONTROL_AF_MODE_OFF))
            try {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, frozenFocus)
            } catch (_: IllegalArgumentException) {
            }
        } else {
            val continuous = when {
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CaptureRequest.CONTROL_AF_MODE_AUTO
            }
            logAfDecision(
                "Video AF request ${Camera2Names.afModeName(coerceAf(continuous))} " +
                    "(auto${if (lockedFocus) ", focus lock armed but no metered focus yet" else ""}) " +
                    "caps=[$caps]"
            )
            builder.set(CaptureRequest.CONTROL_AF_MODE, coerceAf(continuous))
        }
        val resolvedIso = VideoManualPolicy.resolveIso(
            manual.iso, meteredIso, locks.lockExposure,
        )
        val resolvedShutter = VideoManualPolicy.resolveShutter(
            manual.shutterNanos, meteredShutterNanos, locks.lockExposure,
        )
        val manualSensor = hasManualSensor(c) && (resolvedIso != null || resolvedShutter != null)
        if (manualSensor) {
            val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val timeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val iso = VideoManualPolicy.clampIso(
                resolvedIso ?: meteredIso,
                isoRange?.lower ?: (resolvedIso ?: meteredIso),
                isoRange?.upper ?: (resolvedIso ?: meteredIso),
            )
            val shutter = VideoManualPolicy.clampShutter(
                resolvedShutter ?: meteredShutterNanos,
                timeRange?.lower ?: (resolvedShutter ?: meteredShutterNanos),
                timeRange?.upper ?: (resolvedShutter ?: meteredShutterNanos),
                frameFps,
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        } else {
            val aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
            val aeMode = if (aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
                CaptureRequest.CONTROL_AE_MODE_ON
            } else {
                aeModes.firstOrNull() ?: CaptureRequest.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val ev = (manual.evIndex ?: 0).coerceIn(
                evRange?.lower ?: 0, evRange?.upper ?: 0,
            )
            try {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            } catch (_: IllegalArgumentException) {
            }
        }
        val manualWb = manual.wbKelvin
        val awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        fun coerceAwb(mode: Int): Int {
            if (awbModes.contains(mode)) return mode
            if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
                return CaptureRequest.CONTROL_AWB_MODE_AUTO
            }
            return awbModes.firstOrNull() ?: mode
        }
        if (manualWb != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, coerceAwb(CaptureRequest.CONTROL_AWB_MODE_OFF))
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            val gains = VideoManualPolicy.wbGainsForKelvin(manualWb)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(
                    gains[0], gains[1], gains[2], gains[3],
                ),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, coerceAwb(CaptureRequest.CONTROL_AWB_MODE_AUTO))
            if (VideoManualPolicy.resolveAwbLock(null, locks.lockWb) &&
                c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
            ) {
                try {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    fun controlRange(
        control: ManualControl,
        c: CameraCharacteristics,
        frameFps: Int,
        manual: VideoManualSnapshot,
        locks: VideoRecordLocks,
        meteredIso: Int,
        meteredShutterNanos: Long,
        meteredFocus: Float?,
    ): ManualControlRange? {
        return when (control) {
            ManualControl.ISO -> {
                if (!hasManualSensor(c)) return null
                val range = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?: return null
                val current = (manual.iso ?: meteredIso).coerceIn(range.lower, range.upper)
                ManualControlRange(
                    range.lower.toLong(), range.upper.toLong(), current.toLong(),
                    manual.iso == null && !locks.lockExposure,
                )
            }
            ManualControl.SHUTTER -> {
                if (!hasManualSensor(c)) return null
                val range = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: return null
                val cappedMax = minOf(range.upper, 1_000_000_000L / frameFps).coerceAtLeast(range.lower)
                val current = (manual.shutterNanos ?: meteredShutterNanos)
                    .coerceIn(range.lower, cappedMax)
                ManualControlRange(
                    range.lower, cappedMax, current,
                    manual.shutterNanos == null && !locks.lockExposure,
                )
            }
            ManualControl.WHITE_BALANCE -> {
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: intArrayOf()
                if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
                    return null
                }
                ManualControlRange(
                    VideoManualPolicy.MIN_WB_KELVIN.toLong(),
                    VideoManualPolicy.MAX_WB_KELVIN.toLong(),
                    (manual.wbKelvin ?: 5500).toLong(),
                    manual.wbKelvin == null,
                )
            }
            ManualControl.FOCUS_DISTANCE -> {
                val minDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    ?.takeIf { it > 0f } ?: return null
                val scale = 1_000f
                val current = ((manual.focusDiopters ?: meteredFocus ?: 0f) * scale).toLong()
                    .coerceIn(0L, (minDist * scale).toLong())
                ManualControlRange(
                    0L, (minDist * scale).toLong(), current,
                    manual.focusDiopters == null,
                )
            }
            ManualControl.EXPOSURE_COMPENSATION -> {
                val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    ?: return null
                val current = (manual.evIndex ?: 0).coerceIn(range.lower, range.upper)
                ManualControlRange(
                    range.lower.toLong(), range.upper.toLong(), current.toLong(),
                    (manual.evIndex ?: 0) == 0,
                )
            }
        }
    }
}
