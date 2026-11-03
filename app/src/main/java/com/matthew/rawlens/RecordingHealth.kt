// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Rolling recording health. Historical drops remain visible without keeping the warning lit forever. */
internal class RecordingHealth {
    private var previousDrops = 0
    private var lastDropMs = Long.MIN_VALUE
    private var previousBytes = 0L
    private var lastWriteMs = 0L

    fun reset() {
        previousDrops = 0
        lastDropMs = Long.MIN_VALUE
        previousBytes = 0L
        lastWriteMs = 0L
    }

    fun update(elapsedMs: Long, drops: Int, bytes: Long, fps: Float, targetFps: Int,
        pressure: Boolean, thermal: Int, freeBytes: Long?): String {
        if (drops > previousDrops) lastDropMs = elapsedMs
        previousDrops = drops
        if (bytes > previousBytes) lastWriteMs = elapsedMs
        previousBytes = bytes
        val reason = when {
            freeBytes != null && freeBytes < 512L * 1024 * 1024 -> "LOW SPACE"
            elapsedMs >= 5_000 && elapsedMs - lastWriteMs >= 3_000 -> "NO OUTPUT"
            thermal >= 3 -> "HOT"
            lastDropMs != Long.MIN_VALUE && elapsedMs - lastDropMs < 5_000 -> "FRAME DROPS"
            pressure -> "BUSY"
            thermal >= 2 -> "WARM"
            elapsedMs >= 5_000 && fps < targetFps * .9f -> "SLOW"
            elapsedMs < 3_000 -> "STARTING"
            else -> "OK"
        }
        return "HEALTH $reason · D$drops"
    }
}

/** Smoothstep in focus-distance units, with an exact final endpoint. */
internal object FocusPull {
    fun value(start: Long, end: Long, elapsedMs: Long, durationMs: Long): Long {
        val t = (elapsedMs.toDouble() / durationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return (start + (end - start) * eased).toLong()
    }
}
