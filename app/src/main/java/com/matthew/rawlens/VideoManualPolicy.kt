// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Shared video manual-exposure policy for RAW (.mcraw) and Direct-Log takes.
 *
 * Photo modes drive ISO/shutter/WB/focus/EV through [RawCameraController]'s
 * live preview; video mode reuses the same tiles. This helper keeps the
 * recorder-side decisions pure and unit-tested: clamping, lock resolution,
 * and WB-gain mapping. Recorders own the Camera2 request; MainActivity owns
 * the prefs keys.
 */
data class VideoManualSnapshot(
    val iso: Int? = null,
    val shutterNanos: Long? = null,
    val wbKelvin: Int? = null,
    val focusDiopters: Float? = null,
    /** Null = auto (AE compensation only applies with AE ON). */
    val evIndex: Int? = null,
)

/** Lock-on-record switches (all default off: continuous AE/AWB/AF). */
data class VideoRecordLocks(
    val lockExposure: Boolean = false,
    val lockWb: Boolean = false,
    val lockFocus: Boolean = false,
) {
    companion object {
        const val KEY_LOCK_EXPOSURE = "video_lock_exposure_on_record"
        const val KEY_LOCK_WB = "video_lock_wb_on_record"
        const val KEY_LOCK_FOCUS = "video_lock_focus_on_record"

        fun defaults() = VideoRecordLocks()

        /** Pure loader so JVM tests cover the default-off contract. */
        fun load(getBoolean: (String) -> Boolean) = VideoRecordLocks(
            lockExposure = getBoolean(KEY_LOCK_EXPOSURE),
            lockWb = getBoolean(KEY_LOCK_WB),
            lockFocus = getBoolean(KEY_LOCK_FOCUS),
        )
    }

    fun save(putBoolean: (String, Boolean) -> Unit) {
        putBoolean(KEY_LOCK_EXPOSURE, lockExposure)
        putBoolean(KEY_LOCK_WB, lockWb)
        putBoolean(KEY_LOCK_FOCUS, lockFocus)
    }
}

object VideoManualPolicy {
    const val MIN_WB_KELVIN = 2000
    const val MAX_WB_KELVIN = 10000

    /** True when the take must drive the sensor manually (AE OFF). */
    fun wantsManualExposure(snapshot: VideoManualSnapshot): Boolean =
        snapshot.iso != null || snapshot.shutterNanos != null

    /**
     * Resolve the ISO to drive: explicit manual wins, else a locked take
     * freezes the metered preview value, else null (continuous AE).
     */
    fun resolveIso(
        manualIso: Int?,
        meteredIso: Int,
        lockExposure: Boolean,
    ): Int? = when {
        manualIso != null -> manualIso
        lockExposure -> meteredIso
        else -> null
    }

    /** Same as [resolveIso] for exposure time. */
    fun resolveShutter(
        manualShutterNanos: Long?,
        meteredShutterNanos: Long,
        lockExposure: Boolean,
    ): Long? = when {
        manualShutterNanos != null -> manualShutterNanos
        lockExposure -> meteredShutterNanos
        else -> null
    }

    /** AWB lock only matters when WB is auto; manual Kelvin drives OFF. */
    fun resolveAwbLock(manualWbKelvin: Int?, lockWb: Boolean): Boolean =
        manualWbKelvin == null && lockWb

    /** Focus lock only matters when focus is auto; manual diopters drive OFF. */
    fun resolveFocusLock(manualFocusDiopters: Float?, lockFocus: Boolean): Boolean =
        manualFocusDiopters == null && lockFocus

    fun clampIso(iso: Int, min: Int, max: Int): Int {
        if (min >= max) return iso.coerceAtLeast(1)
        return iso.coerceIn(min, max)
    }

    /**
     * Clamp shutter to the sensor range and the frame budget (1/fps).
     * Video exposure can never exceed one frame; longer photo shutters
     * clamp to the frame duration instead of failing the request.
     */
    fun clampShutter(shutterNanos: Long, min: Long, max: Long, fps: Int): Long {
        val frameBudget = if (fps > 0) 1_000_000_000L / fps else max
        val cappedMax = minOf(max, frameBudget).coerceAtLeast(min)
        return shutterNanos.coerceIn(min, cappedMax)
    }

    /** Mirrors RawCameraController's Kelvin -> RGGB gain ramp (pure). */
    fun wbGainsForKelvin(kelvin: Int): FloatArray {
        val t = ((kelvin.coerceIn(MIN_WB_KELVIN, MAX_WB_KELVIN) - MIN_WB_KELVIN) /
            (MAX_WB_KELVIN - MIN_WB_KELVIN).toFloat())
        return floatArrayOf(1f + 1.2f * t, 1f, 1f, 2.6f - 1.5f * t)
    }
}
