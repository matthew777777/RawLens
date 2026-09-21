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
    val motionRadiansPerSecond: Float,
    /**
     * PhotonCamera-style approximation flag: the paired result never arrived
     * (capture-result stall) so this frame carries the latest preview result
     * instead of its own. Pixels are exact; AE-dependent tags (ISO/exposure
     * derived) may be stale. Penalized in scoring, logged per burst, never
     * silent.
     */
    val metadataApproximate: Boolean = false
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
        timestampNanos: Long = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp,
        metadataApproximate: Boolean = false
    ) {
        addSnapshot(
            image, result, timestampNanos,
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
            motionRadiansPerSecond,
            metadataApproximate
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
        motionRadiansPerSecond: Float,
        metadataApproximate: Boolean = false
    ) {
        val timestamp = timestampNanos
        val duplicate = frames.indexOfFirst { it.timestampNanos == timestamp }
        if (duplicate >= 0) RawImageOwnership.release(frames.removeAt(duplicate).image)
        frames.addLast(
            BufferedRawFrame(
                image,
                result,
                timestamp,
                exposureNanos,
                rollingShutterSkewNanos,
                motionRadiansPerSecond,
                metadataApproximate
            )
        )
        while (frames.size > capacity) RawImageOwnership.release(frames.removeFirst().image)
    }

    /** OFF retains incomplete selections; ON transfers every returned frame to the caller. */
    fun takeForCapture(cutoffNanos: Long, realtimeTimestamps: Boolean, count: Int,
                       hybridTopup: Boolean): List<BufferedRawFrame> =
        if (hybridTopup) takeUpTo(cutoffNanos, realtimeTimestamps, count)
        else takeBest(cutoffNanos, realtimeTimestamps, count)

    @Synchronized
    fun takeBest(
        cutoffNanos: Long,
        realtimeTimestamps: Boolean,
        count: Int
    ): List<BufferedRawFrame> {
        val eligible = eligibleFrames(cutoffNanos, realtimeTimestamps)
        if (eligible.size < count) return emptyList()
        return takeRanked(eligible, cutoffNanos, realtimeTimestamps, count)
    }

    /**
     * PhotonCamera-style partial take (GCam's available-capacity rung): returns the
     * best up to [maxCount] eligible frames, or empty when the ring holds nothing
     * usable. Backs hybrid top-up, where a thin ring is completed with fresh
     * forward captures instead of refusing the shutter.
     */
    @Synchronized
    fun takeUpTo(
        cutoffNanos: Long,
        realtimeTimestamps: Boolean,
        maxCount: Int
    ): List<BufferedRawFrame> {
        if (maxCount <= 0) return emptyList()
        val eligible = eligibleFrames(cutoffNanos, realtimeTimestamps)
        if (eligible.isEmpty()) return emptyList()
        return takeRanked(eligible, cutoffNanos, realtimeTimestamps, minOf(maxCount, eligible.size))
    }

    private fun eligibleFrames(
        cutoffNanos: Long,
        realtimeTimestamps: Boolean
    ): List<BufferedRawFrame> {
        return frames.filter { frame ->
            val completedAt = completedAt(frame, realtimeTimestamps)
            val ageNanos = cutoffNanos - completedAt
            ageNanos in 0L..MAX_FRAME_AGE_NANOS
        }
    }

    private fun takeRanked(
        eligible: List<BufferedRawFrame>,
        cutoffNanos: Long,
        realtimeTimestamps: Boolean,
        count: Int
    ): List<BufferedRawFrame> {
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
        while (frames.isNotEmpty()) RawImageOwnership.release(frames.removeFirst().image)
    }

    /**
     * Frees exactly one gralloc slot under ImageReader back-pressure by closing
     * the oldest buffered frame. Returns true when a frame was evicted. The
     * ZSL overflow path must free a held slot before draining: with the queue
     * full because maxImages are outstanding, acquiring-and-closing queued
     * images alone frees nothing and the stream never recovers.
     */
    @Synchronized
    fun evictOldest(): Boolean {
        if (frames.isEmpty()) return false
        RawImageOwnership.release(frames.removeFirst().image)
        return true
    }

    private fun qualityScore(
        frame: BufferedRawFrame,
        cutoffNanos: Long,
        realtimeTimestamps: Boolean
    ): Double {
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
        // Approximate metadata (paired result never arrived; carrying the latest
        // preview result) sorts below clean equals but above genuinely bad frames
        // (hunting AF/AE at -2, moving lens at -2): pixels are exact, only the
        // AE-dependent tags may be stale.
        val approxPenalty = if (frame.metadataApproximate) 1.5 else 0.0
        return aeScore + lensScore - isoPenalty - motionPenalty - agePenalty - approxPenalty
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
