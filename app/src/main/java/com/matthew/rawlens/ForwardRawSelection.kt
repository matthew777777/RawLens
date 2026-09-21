// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Reserves exactly N post-shutter repeating RAW results for a continuous-stream top-up. */
internal class ForwardRawSelection(val captureId: Int, private val cutoffNanos: Long, count: Int) {
    private var remaining = count
    private var lastTimestamp = cutoffNanos
    fun accept(timestamp: Long): Boolean {
        if (remaining <= 0 || timestamp <= lastTimestamp || timestamp <= cutoffNanos) return false
        remaining--
        lastTimestamp = timestamp
        return true
    }
}
