// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/** Sensor timestamps measure delivered capture cadence, independently of output/preview FPS. */
internal class SensorCadence {
    private var first = 0L
    private var last = 0L
    private var intervals = 0
    fun add(timestamp: Long?): Double? {
        if (timestamp == null || timestamp <= 0 || timestamp <= last) return null
        last = timestamp
        if (first == 0L) { first = timestamp; return null }
        intervals++
        if (timestamp - first < 2_000_000_000L) return null
        val fps = intervals * 1e9 / (timestamp - first)
        first = timestamp
        intervals = 0
        return fps
    }
}

/** One instance per callback/session; no global frame or key caches. */
internal class CameraResultDiagnostics(private val route: String, private val pipeline: String) {
    private val cadence = SensorCadence()
    private var lastAf: List<Int?>? = null
    private var lastMode = ""
    private var modesLogged = false
    private var failed = false

    fun result(request: CaptureRequest, result: TotalCaptureResult, trigger: Boolean = false) {
        // Diagnostics must not interrupt capture or result pairing on a quirky HAL.
        if (failed) return
        try { inspect(request, result, trigger) } catch (failure: Exception) {
            failed = true
            Log.w("RawLensCamera", "Diagnostics unavailable route=$route pipeline=$pipeline: ${failure.message}")
        }
    }

    private fun inspect(request: CaptureRequest, result: TotalCaptureResult, trigger: Boolean) {
        val afValues = listOf(request.get(CaptureRequest.CONTROL_AF_MODE),
            request.get(CaptureRequest.CONTROL_AF_TRIGGER), result.get(CaptureResult.CONTROL_AF_MODE),
            result.get(CaptureResult.CONTROL_AF_STATE))
        if (trigger || afValues != lastAf) {
            lastAf = afValues
            Log.i("RawLensCamera", "AF_RESULT route=$route pipeline=$pipeline frame=${result.frameNumber} " +
                "requestedMode=${afValues[0]} trigger=${afValues[1]} returnedMode=${afValues[2]} " +
                "state=${Camera2Names.afStateName(afValues[3])} " +
                "focusD=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} lensState=${result.get(CaptureResult.LENS_STATE)} " +
                "regions=${request.get(CaptureRequest.CONTROL_AF_REGIONS)?.contentToString()} " +
                "activePhys=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}")
        }
        val fps = cadence.add(result.get(CaptureResult.SENSOR_TIMESTAMP))
        if (modesLogged && fps == null && !trigger) return
        // Read only advertised result keys, retaining vendor identity and native type.
        // Missing keys are explicitly unavailable, never interpreted as mode 0.
        val names = setOf("org.codeaurora.qcamera3.sensor_meta_data.current_mode", "vivo.control.forceSensorMode")
        val keys = result.keys.filter { it.name in names }
        @Suppress("UNCHECKED_CAST")
        fun returned(key: CaptureResult.Key<*>): String = runCatching {
            render(result.get(key as CaptureResult.Key<Any>))
        }.getOrElse { "unreadable(${it.javaClass.simpleName})" }
        @Suppress("UNCHECKED_CAST")
        fun requested(key: CaptureRequest.Key<*>): String = runCatching {
            render(request.get(key as CaptureRequest.Key<Any>))
        }.getOrElse { "unreadable(${it.javaClass.simpleName})" }
        val requestKeys = request.keys.filter { it.name in names }
        val mode = names.joinToString("; ") { name ->
            "$name requested=${requestKeys.firstOrNull { it.name == name }?.let(::requested) ?: "absent"} " +
                "returned=${keys.firstOrNull { it.name == name }?.let(::returned) ?: "unavailable"}"
        }
        if (!modesLogged || mode != lastMode) {
            modesLogged = true; lastMode = mode
            Log.i("RawLensCamera", "SENSOR_MODE route=$route pipeline=$pipeline frame=${result.frameNumber} $mode " +
                "dynamicWhite=${result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)} (reported metadata; DCG unverified)")
        }
        fps?.let { measured ->
            Log.i("RawLensCamera", "SENSOR_FPS route=$route pipeline=$pipeline measured=${"%.2f".format(java.util.Locale.US, measured)} " +
                "requestedRange=${request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)} " +
                "returnedRange=${result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)} " +
                "frameDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}")
        }
    }

    private fun render(value: Any?): String = when (value) {
        null -> "unavailable"
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        else -> value.toString()
    }
}
