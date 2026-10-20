// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import java.util.concurrent.ConcurrentHashMap

/**
 * Matches each RAW [Image] with the [TotalCaptureResult] of the same capture.
 * Camera2 guarantees the key: SENSOR_TIMESTAMP "will be identical on all the
 * outputs" of a capture (AOSP metadata_definitions.xml, sensor.timestamp);
 * MotionCam pairs the same way (RawImageConsumer mPendingBuffers).
 *
 * Two result kinds share one image map: forward-capture results (stills,
 * bursts, top-ups) and ZSL ring results from the repeating stream. Whichever
 * half arrives second completes the pair; a half that waits too long expires
 * through [Host.onPairExpired]. Completed pairs are handed to the host,
 * which owns routing (ring admission, saves, top-up, HDR). Extracted from
 * RawCameraController; see docs/refactor/capture-study.md.
 *
 * Collections are concurrent: [stop]-time teardown runs on the main thread
 * while camera-thread callbacks may still land. Timeouts run on [handler].
 */
internal class FramePairing(
    private val handler: Handler,
    private val host: Host,
) {
    interface Host {
        /** Controller still running; completions after stop are dropped. */
        val running: Boolean
        /** A forward-capture image and its exact result. Ownership transfers. */
        fun onForwardPair(timestamp: Long, image: Image, result: TotalCaptureResult)
        /** A ZSL image and its pending result. Ownership transfers. */
        fun onZslPair(timestamp: Long, image: Image, pending: PendingZslResult)
        /**
         * A pairing window ran out. [image]/[result]/[zslPending] are whatever
         * halves were waiting (already removed here); the host owns [image].
         * [forward] is the kind the timeout was scheduled for; [shot] the
         * capture sequence when it was scheduled.
         */
        fun onPairExpired(
            timestamp: Long,
            image: Image?,
            result: TotalCaptureResult?,
            zslPending: PendingZslResult?,
            forward: Boolean,
            shot: Int,
        )
        /** Frees the oldest ZSL ring frame; true when one was evicted. */
        fun evictOldestRingFrame(): Boolean
    }

    private val images = ConcurrentHashMap<Long, Image>()
    private val forwardResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val zslResults = ConcurrentHashMap<Long, PendingZslResult>()
    private val timeouts = ConcurrentHashMap<Long, Runnable>()
    /** RAW timestamps of repeating results that must never fill a still slot. */
    private val previewRawTimestamps = ConcurrentHashMap.newKeySet<Long>()

    val pendingImageCount: Int get() = images.size

    fun hasImage(timestamp: Long): Boolean = images.containsKey(timestamp)
    fun hasZslResult(timestamp: Long): Boolean = zslResults.containsKey(timestamp)

    /** Holds [image] until its result arrives; a displaced duplicate is released. */
    fun putImage(timestamp: Long, image: Image) {
        images.put(timestamp, image)?.let(RawImageOwnership::release)
    }

    fun putForwardResult(timestamp: Long, result: TotalCaptureResult) {
        forwardResults[timestamp] = result
    }

    fun putZslResult(timestamp: Long, pending: PendingZslResult) {
        zslResults[timestamp] = pending
    }

    /** Completes the pair for [timestamp] if both halves are present. */
    fun pairAvailable(timestamp: Long) {
        if (forwardResults.containsKey(timestamp)) pairForward(timestamp)
        else if (zslResults.containsKey(timestamp)) pairZsl(timestamp)
    }

    private fun pairForward(timestamp: Long) {
        if (!host.running) return
        val result = forwardResults[timestamp] ?: return
        val image = images.remove(timestamp) ?: return
        forwardResults.remove(timestamp)
        cancelTimeout(timestamp)
        host.onForwardPair(timestamp, image, result)
    }

    private fun pairZsl(timestamp: Long) {
        if (!host.running) return
        val pending = zslResults[timestamp] ?: return
        val image = images.remove(timestamp) ?: return
        zslResults.remove(timestamp)
        cancelTimeout(timestamp)
        host.onZslPair(timestamp, image, pending)
    }

    /**
     * (Re)arms the pairing window for [timestamp]: [forward] results wait
     * [forwardTimeoutMs], ZSL halves [zslTimeoutMs]. [shot] and [isCurrent]
     * let the host ignore windows from an older capture or session.
     */
    fun scheduleTimeout(
        timestamp: Long,
        forward: Boolean,
        shot: Int,
        isCurrent: () -> Boolean,
        forwardTimeoutMs: Long,
        zslTimeoutMs: Long,
    ) {
        cancelTimeout(timestamp)
        val timeout = Runnable {
            if (!isCurrent()) return@Runnable
            timeouts.remove(timestamp)
            host.onPairExpired(
                timestamp,
                images.remove(timestamp),
                forwardResults.remove(timestamp),
                zslResults.remove(timestamp),
                forward,
                shot,
            )
        }
        timeouts[timestamp] = timeout
        handler.postDelayed(timeout, if (forward) forwardTimeoutMs else zslTimeoutMs)
    }

    /**
     * Forgets the forward result of HAL frame [frameNumber] and cancels its
     * timeout (its RAW buffer was lost). True when such a result was waiting.
     */
    fun dropForwardResultForFrame(frameNumber: Long): Boolean {
        val timestamp = forwardResults.entries.firstOrNull { it.value.frameNumber == frameNumber }?.key
            ?: return false
        forwardResults.remove(timestamp)
        cancelTimeout(timestamp)
        return true
    }

    /** A repeating result's RAW frame: release its image, or remember the timestamp. */
    fun dropPreviewRaw(timestamp: Long) {
        val previewImage = images.remove(timestamp)
        cancelTimeout(timestamp)
        if (previewImage != null) {
            RawImageOwnership.release(previewImage)
        } else {
            previewRawTimestamps.add(timestamp)
            if (previewRawTimestamps.size > PREVIEW_RAW_MEMORY) {
                previewRawTimestamps.minOrNull()?.let(previewRawTimestamps::remove)
            }
        }
    }

    /** True (and forgotten) when [timestamp] belongs to a repeating result. */
    fun consumePreviewRaw(timestamp: Long): Boolean = previewRawTimestamps.remove(timestamp)

    /**
     * Frees one held gralloc slot when the ImageReader reports maxImages
     * outstanding. Prefers the oldest result-less image (it would time out
     * unpaired anyway) over evicting a paired ring frame.
     */
    fun relievePressure(): Boolean {
        val unmatched = images.keys
            .filter { !forwardResults.containsKey(it) && !zslResults.containsKey(it) }
            .minOrNull()
        if (unmatched != null) {
            images.remove(unmatched)?.let(RawImageOwnership::release)
            timeouts.remove(unmatched)?.let(handler::removeCallbacks)
            return true
        }
        if (host.evictOldestRingFrame()) return true
        // Last resort: every held image has a result waiting, but the HAL still
        // needs a free slot. Drop the oldest pairing rather than wedging the
        // queue into persistent Mali/Adreno unlock errors.
        val oldest = images.keys.minOrNull()
        if (oldest != null) {
            images.remove(oldest)?.let(RawImageOwnership::release)
            forwardResults.remove(oldest)
            zslResults.remove(oldest)
            timeouts.remove(oldest)?.let(handler::removeCallbacks)
            return true
        }
        return false
    }

    /** Releases images that have no result of either kind waiting. */
    fun closeUnmatched() {
        images.keys.toList().forEach { timestamp ->
            if (!forwardResults.containsKey(timestamp) && !zslResults.containsKey(timestamp)) {
                images.remove(timestamp)?.let(RawImageOwnership::release)
                timeouts.remove(timestamp)?.let(handler::removeCallbacks)
            }
        }
    }

    /** Drops every ZSL pairing (result, its image, its timeout). */
    fun clearZsl() {
        zslResults.keys.toList().forEach { timestamp ->
            zslResults.remove(timestamp)
            images.remove(timestamp)?.let(RawImageOwnership::release)
            timeouts.remove(timestamp)?.let(handler::removeCallbacks)
        }
    }

    /** Capture end: release every held image and forget every pairing. */
    fun clearAll() {
        images.values.forEach(RawImageOwnership::release)
        images.clear()
        forwardResults.clear()
        zslResults.clear()
        previewRawTimestamps.clear()
        timeouts.values.forEach(handler::removeCallbacks)
        timeouts.clear()
    }

    /**
     * Teardown: release held images and forget pairings (repeating-result
     * timestamps are kept, as before). Before the ImageReader closes, pass
     * [ignoreReleaseFailures] so teardown always completes: closing the
     * reader with acquired gralloc buffers still held wedges Mali/Adreno
     * queues.
     */
    fun clearForTeardown(ignoreReleaseFailures: Boolean) {
        images.values.forEach {
            if (ignoreReleaseFailures) runCatching { RawImageOwnership.release(it) }
            else RawImageOwnership.release(it)
        }
        images.clear()
        forwardResults.clear()
        zslResults.clear()
        timeouts.values.forEach(handler::removeCallbacks)
        timeouts.clear()
    }

    private fun cancelTimeout(timestamp: Long) {
        timeouts.remove(timestamp)?.let(handler::removeCallbacks)
    }

    private companion object {
        /** Bound on remembered repeating-result RAW timestamps. */
        const val PREVIEW_RAW_MEMORY = 64
    }
}
