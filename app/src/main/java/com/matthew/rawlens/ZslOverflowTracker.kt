// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock

/**
 * Bounds transient [ImageReader] pressure on the 30 fps RAW ZSL stream. A brief
 * stall (lagging capture results, a slow handler turn) only drops the stalled
 * batch via drain-and-continue; the stream falls back only when no frame has
 * paired for longer than [maxStallMs]. Time-based, so the policy means the same
 * at any frame rate: six dropped frames are 200 ms at 30 fps but 600 ms at
 * 10 fps, and only the former should never have retired a healthy stream.
 *
 * [clockMs] is injectable so the policy stays unit-testable without the
 * Android framework.
 */
internal class ZslOverflowTracker(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val maxStallMs: Long = DEFAULT_MAX_STALL_MS
) {
    private var lastPairedMs: Long = clockMs()

    /** Records one overflow; returns true when the stream has stalled past the limit. */
    fun onOverflow(nowMs: Long = clockMs()): Boolean = nowMs - lastPairedMs > maxStallMs

    /** Records one successfully paired frame. */
    fun onPaired(nowMs: Long = clockMs()) {
        lastPairedMs = nowMs
    }

    /** Clears the stall clock, e.g. when a fresh repeating request starts. */
    fun reset(nowMs: Long = clockMs()) {
        lastPairedMs = nowMs
    }

    companion object {
        const val DEFAULT_MAX_STALL_MS = 2_000L
    }
}
