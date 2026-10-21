// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image

internal data class BufferedRawFrame(
    val image: Image,
    val result: TotalCaptureResult,
    val timestampNanos: Long,
    val exposureNanos: Long,
    val rollingShutterSkewNanos: Long,
    val motionRadiansPerSecond: Float,
    /**
     * Approximate-metadata flag: the paired result never arrived (capture-result
     * stall) so this frame carries the latest preview result instead of its own.
     * PhotonCamera (CaptureController.triggerZslCapture) uses the preview result
     * for every ZSL frame; RawLens pairs exactly and falls back only here. Pixels are exact; AE-dependent tags (ISO/exposure
     * derived) may be stale. Penalized in scoring, logged per burst, never
     * silent.
     */
    val metadataApproximate: Boolean = false
)

/** Owns every Image added to it. Evicted and rejected images are closed
 * immediately; unselected frames stay buffered as pre-roll for the next
 * press (age-gated by MAX_FRAME_AGE_NANOS), so takes never force a refill
 * from zero. */
internal class RawZslBuffer(private val capacity: Int) {
    private val frames = ArrayDeque<BufferedRawFrame>(capacity)

    /**
     * Angular travel (radians, over exposure + rolling-shutter readout) at
     * which a frame counts as motion-blurred: [BLUR_LIMIT_PX] divided by
     * the lens focal length in pixels (set by the controller per camera,
     * see [blurLimitRadians]). Infinite = gyro blur never rejects.
     */
    @Volatile var blurLimitRadians: Double = Double.POSITIVE_INFINITY

    /** Last take's per-frame decisions, newest first (logging/tests). */
    @Volatile var lastTakeReport: List<String> = emptyList()
        private set

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
     * Partial take, as PhotonCamera (`take = min(ring, frameCount)` in
     * CaptureController.triggerZslCapture): returns the
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

    /**
     * HDR+ ZSL selection (Hasinoff et al. 2016, "Burst photography for high
     * dynamic range and low-light imaging on mobile cameras", sec. 2-3: the
     * merge uses the frames captured just BEFORE the shutter; PhotonCamera
     * CaptureController.triggerZslCapture likewise takes the newest ring
     * frames). Newest first, skipping frames that cannot be sharp: lens
     * moving, AE still searching, or gyro travel over [blurLimitRadians]
     * (> [BLUR_LIMIT_PX] of motion blur). Short of clean frames, approximate-
     * metadata frames come next, then the least-blurred defective ones, so a
     * full ring never refuses the shutter. The earlier weighted ranking
     * (unsourced weights) let a frame up to 30 s old win: its age penalty
     * saturated at 1 s.
     */
    private fun takeRanked(
        eligible: List<BufferedRawFrame>,
        cutoffNanos: Long,
        realtimeTimestamps: Boolean,
        count: Int
    ): List<BufferedRawFrame> {
        val newestFirst = eligible.sortedByDescending { it.timestampNanos }
        fun travel(frame: BufferedRawFrame): Double {
            val readoutNanos = saturatingAdd(frame.exposureNanos, frame.rollingShutterSkewNanos)
            return frame.motionRadiansPerSecond * readoutNanos.coerceAtLeast(0L) / 1_000_000_000.0
        }
        fun defect(frame: BufferedRawFrame): String? {
            val ae = frame.result.get(CaptureResult.CONTROL_AE_STATE)
            return when {
                frame.result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING -> "lens-moving"
                ae == CaptureResult.CONTROL_AE_STATE_SEARCHING ||
                    ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "ae-searching"
                travel(frame) > blurLimitRadians -> "blur"
                else -> null
            }
        }
        val clean = newestFirst.filter { defect(it) == null && !it.metadataApproximate }
        val approximate = newestFirst.filter { defect(it) == null && it.metadataApproximate }
        val defective = newestFirst.filter { defect(it) != null }.sortedBy { travel(it) }
        val selected = (clean + approximate + defective).take(count)
        val picked = selected.toHashSet()
        val limit = blurLimitRadians
        lastTakeReport = newestFirst.map { frame ->
            val ageMs = (cutoffNanos - completedAt(frame, realtimeTimestamps)) / 1_000_000
            val blurPx = if (limit.isFinite() && limit > 0) travel(frame) / limit * BLUR_LIMIT_PX else Double.NaN
            "age=${ageMs}ms blur=${"%.2f".format(blurPx)}px" +
                (defect(frame)?.let { " $it" } ?: "") +
                (if (frame.metadataApproximate) " approx" else "") +
                (if (frame in picked) " KEPT" else " skip")
        }
        val ordered = selected.sortedBy { it.timestampNanos }
        // Non-destructive take: only selected frames transfer to the caller.
        // Unselected frames stay in the ring as pre-roll — the next press
        // reuses them instead of refilling from zero. Age and capacity
        // eviction bound staleness.
        ordered.forEach(frames::remove)
        return ordered
    }

    @Synchronized
    fun clear() {
        while (frames.isNotEmpty()) RawImageOwnership.release(frames.removeFirst().image)
    }

    /**
     * Single-tap BURST dump: transfers the whole current ring to the caller
     * in capture order. Unfiltered by age or score — offline merge decides
     * reference and keepers later from the stored container.
     */
    @Synchronized
    fun takeAll(): List<BufferedRawFrame> {
        if (frames.isEmpty()) return emptyList()
        val all = frames.sortedBy { it.timestampNanos }
        frames.clear()
        return all
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

    private fun completedAt(frame: BufferedRawFrame, realtimeTimestamps: Boolean): Long {
        if (!realtimeTimestamps) return frame.timestampNanos
        return saturatingAdd(
            saturatingAdd(frame.timestampNanos, frame.exposureNanos),
            frame.rollingShutterSkewNanos
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        // A 30-frame full-resolution RAW ring can take much longer to fill at slow exposures.
        private const val MAX_FRAME_AGE_NANOS = 30_000_000_000L

        /**
         * Motion blur (full-resolution pixels) above which a frame is not
         * merged when cleaner frames exist: past ~1.5 px the blur exceeds
         * the half-pixel alignment precision the merge relies on (Wronski
         * et al. 2019 align to sub-pixel; blur wider than the kernel
         * footprint cannot be undone by the merge).
         */
        const val BLUR_LIMIT_PX = 1.5

        /** [BLUR_LIMIT_PX] as angular travel for a lens of [focalLengthPx]. */
        fun blurLimitRadians(focalLengthPx: Double): Double =
            if (focalLengthPx.isFinite() && focalLengthPx > 0) BLUR_LIMIT_PX / focalLengthPx
            else Double.POSITIVE_INFINITY
    }
}
