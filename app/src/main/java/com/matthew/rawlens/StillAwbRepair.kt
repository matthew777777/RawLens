// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult

/**
 * Still frames whose HAL AWB never converged: RawFrameMetadataFactory estimates the
 * neutral from the still's own RAW ([RawWhiteBalanceFallback]); when that gives
 * nothing, the preview's last converged AWB from this object stands in.
 *
 * Field log 2026-11-26 (Xiaomi 24030PN60G, HyperOS 3.0.306, tele 75 mm = camera 4):
 * the preview had converged (AWB state 2, neutral 0.489,1,0.709), yet the single
 * STILL_REQUEST result came back with AWB state SEARCHING and a different, warmer
 * neutral (0.555,1,0.600; another shot carried the first-frame default 0.713,1,0.403).
 * Rendering that neutral as white made the snow scene far too blue. The main
 * camera's still result matched its preview exactly, so only non-converged still
 * results are replaced, and only with a recent converged preview value from the
 * same camera and physical sensor.
 *
 * On DNG IMG_20261126_110359_708 (raw grey of unclipped snow = truth) the still's HAL
 * neutral is 15.3 deg off, the converged preview 5.5 deg, the RAW estimate 0.1 deg
 * (RawWhiteBalanceRealDngTest), hence the estimate first.
 */
object StillAwbRepair {
    /** Preview and still timestamps share the sensor clock; older values are stale. */
    internal const val MAX_AGE_NANOS = 3_000_000_000L

    internal class Converged(
        val cameraId: String,
        val physicalId: String?,
        val neutral: DoubleArray,
        val gains: FloatArray,
        val timestampNs: Long
    )

    @Volatile private var last: Converged? = null

    /** Remembers a preview result whose auto WB has converged or is locked. */
    fun notePreview(cameraId: String, physicalId: String?, result: CaptureResult) {
        val mode = result.get(CaptureResult.CONTROL_AWB_MODE)
        val state = result.get(CaptureResult.CONTROL_AWB_STATE)
        if (mode != CaptureResult.CONTROL_AWB_MODE_AUTO) return
        if (state != CaptureResult.CONTROL_AWB_STATE_CONVERGED &&
            state != CaptureResult.CONTROL_AWB_STATE_LOCKED) return
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.map { it.toDouble() }?.toDoubleArray() ?: return
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) } ?: return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (!validNeutral(neutral) || !validGains(gains)) return
        last = Converged(cameraId, physicalId, neutral, gains, timestamp)
    }

    fun clear() { last = null }

    /** Auto WB that had not converged when this still was exposed. */
    fun unsettled(awbMode: Int?, awbState: Int?): Boolean =
        awbMode == CaptureResult.CONTROL_AWB_MODE_AUTO && awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING

    /**
     * The converged preview neutral and gains to use instead of the still's own,
     * or null to keep the still's values.
     */
    internal fun replacementFor(
        cameraId: String,
        physicalId: String?,
        awbMode: Int?,
        awbState: Int?,
        stillTimestampNs: Long?,
        cached: Converged? = last
    ): Converged? {
        if (!unsettled(awbMode, awbState)) return null
        val entry = cached ?: return null
        if (entry.cameraId != cameraId || entry.physicalId != physicalId) return null
        val timestamp = stillTimestampNs ?: return null
        val age = timestamp - entry.timestampNs
        if (age < -MAX_AGE_NANOS || age > MAX_AGE_NANOS) return null
        return entry
    }

    private fun validNeutral(n: DoubleArray) = n.size == 3 && n.all { it.isFinite() && it > 1e-6 }
    private fun validGains(g: FloatArray) = g.size == 4 && g.all { it.isFinite() && it > 0f } &&
        !g.all { kotlin.math.abs(it - 1f) < 1e-3f }
}
