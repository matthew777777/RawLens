// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import kotlin.math.ln
import kotlin.math.min

internal data class BufferedRawFrame(
    val image: Image,
    val result: TotalCaptureResult,
    val timestampNanos: Long,
    val exposureNanos: Long,
    val rollingShutterSkewNanos: Long,
    val motionRadiansPerSecond: Float
)

/** Owns every Image added to it. Evicted, rejected, and unselected images are closed immediately. */
internal class RawZslBuffer(private val capacity: Int) {
    private val frames = ArrayDeque<BufferedRawFrame>(capacity)

    @get:Synchronized
    val size: Int get() = frames.size

    @Synchronized
    fun add(
        image: Image,
        result: TotalCaptureResult,
        motionRadiansPerSecond: Float,
        timestampNanos: Long = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
    ) {
        addSnapshot(
            image, result, timestampNanos,
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
            motionRadiansPerSecond
        )
    }

    /** Primitive snapshot seam keeps selection/ownership tests independent of android.jar keys. */
    @Synchronized
    internal fun addSnapshot(
        image: Image,
        result: TotalCaptureResult,
        timestampNanos: Long,
        exposureNanos: Long,
        rollingShutterSkewNanos: Long,
        motionRadiansPerSecond: Float
    ) {
        val timestamp = timestampNanos
        val duplicate = frames.indexOfFirst { it.timestampNanos == timestamp }
        if (duplicate >= 0) frames.removeAt(duplicate).image.close()
        frames.addLast(
            BufferedRawFrame(
                image,
                result,
                timestamp,
                exposureNanos,
                rollingShutterSkewNanos,
                motionRadiansPerSecond
            )
        )
        while (frames.size > capacity) frames.removeFirst().image.close()
    }

    @Synchronized
    fun takeBest(
        cutoffNanos: Long,
        realtimeTimestamps: Boolean,
        count: Int
    ): List<BufferedRawFrame> {
        val eligible = frames.filter { frame ->
            val completedAt = completedAt(frame, realtimeTimestamps)
            val ageNanos = cutoffNanos - completedAt
            ageNanos in 0L..MAX_FRAME_AGE_NANOS
        }
        if (eligible.size < count) return emptyList()
        val selected = eligible
            .sortedByDescending { qualityScore(it, cutoffNanos, realtimeTimestamps) }
            .take(count)
            .sortedBy { it.timestampNanos }
        selected.forEach(frames::remove)
        clear()
        return selected
    }

    @Synchronized
    fun clear() {
        while (frames.isNotEmpty()) frames.removeFirst().image.close()
    }

    private fun qualityScore(
        frame: BufferedRawFrame,
        cutoffNanos: Long,
        realtimeTimestamps: Boolean
    ): Double {
        val afScore = when (frame.result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> 4.0
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> 3.0
            CaptureResult.CONTROL_AF_STATE_INACTIVE, null -> 0.0
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> -2.0
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> -4.0
            else -> 0.0
        }
        val aeScore = when (frame.result.get(CaptureResult.CONTROL_AE_STATE)) {
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_LOCKED -> 2.0
            CaptureResult.CONTROL_AE_STATE_SEARCHING,
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> -2.0
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> -1.0
            else -> 0.0
        }
        val lensScore = when (frame.result.get(CaptureResult.LENS_STATE)) {
            CaptureResult.LENS_STATE_STATIONARY -> 1.0
            CaptureResult.LENS_STATE_MOVING -> -2.0
            else -> 0.0
        }
        val iso = (frame.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100).coerceAtLeast(100)
        val isoPenalty = ln(iso / 100.0) / ln(2.0) * 0.15
        val readoutNanos = saturatingAdd(frame.exposureNanos, frame.rollingShutterSkewNanos)
        val angularTravel = frame.motionRadiansPerSecond * readoutNanos.coerceAtLeast(0L) / 1_000_000_000.0
        val motionPenalty = min(8.0, angularTravel * 120.0)
        val completedAt = completedAt(frame, realtimeTimestamps)
        val agePenalty = min(10.0, (cutoffNanos - completedAt).coerceAtLeast(0L) / 100_000_000.0)
        return afScore + aeScore + lensScore - isoPenalty - motionPenalty - agePenalty
    }

    private fun completedAt(frame: BufferedRawFrame, realtimeTimestamps: Boolean): Long {
        if (!realtimeTimestamps) return frame.timestampNanos
        return saturatingAdd(
            saturatingAdd(frame.timestampNanos, frame.exposureNanos),
            frame.rollingShutterSkewNanos
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        // A 30-frame full-resolution RAW ring can take much longer to fill at slow exposures.
        const val MAX_FRAME_AGE_NANOS = 30_000_000_000L
    }
}
