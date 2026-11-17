// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Measured image -> capture-result latency for one take (camera thread only).
 *
 * The recorder holds each image until its exact CaptureResult arrives. A fixed
 * hold (2 frames, one-frame wait) was sized for 30 fps; at 60 fps the HAL's
 * result lag exceeds two frame periods and ~25 % of frames were graded before
 * their result existed (resultMiss == framesDropped). Hold depth and wait are
 * derived from the lag actually observed on this device instead.
 *
 * Lag is a decaying peak: a new worst case is adopted at once, older peaks
 * fade (x0.98 per sample) so one HAL hiccup does not inflate latency forever.
 */
internal class VideoResultLag(private val frameNs: Long) {
    private val arrivals = LinkedHashMap<Long, Long>()
    /** Decaying peak lag (ns); starts at the legacy two-frame assumption. */
    var lagNs: Long = 2 * frameNs
        private set
    var samples = 0
        private set

    fun onImage(timestamp: Long, nowNs: Long) {
        arrivals[timestamp] = nowNs
        if (arrivals.size > ARRIVAL_CAP) arrivals.remove(arrivals.keys.first())
    }

    fun onResult(timestamp: Long, nowNs: Long) {
        val arrived = arrivals.remove(timestamp) ?: return
        // Results that beat their image cost nothing to wait for.
        val sample = (nowNs - arrived).coerceAtLeast(0L)
        lagNs = maxOf(sample, (lagNs * DECAY).toLong())
        samples++
    }

    /** Images to hold awaiting results: covers the measured lag plus one frame, within [2, cap]. */
    fun holdFrames(cap: Int): Int =
        ((lagNs + frameNs - 1) / frameNs + 1).toInt().coerceIn(2, cap.coerceAtLeast(2))

    /** How long a held head may wait for its exact result: measured lag plus one frame. */
    fun waitNs(): Long = lagNs + frameNs

    fun clear() { arrivals.clear(); lagNs = 2 * frameNs; samples = 0 }

    private companion object {
        const val ARRIVAL_CAP = 32
        const val DECAY = 0.98
    }
}
