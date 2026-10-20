// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock

/**
 * Health policy for the repeating RAW ZSL stream: startup/refill watchdog
 * windows, transient cooldown, the "never productive" burst-mode latch,
 * ImageReader overflow-storm escalation, and the fruitless-cooldown session
 * rebuild. Decisions only — the controller owns the stream, the handler and
 * the actions each verdict asks for. Extracted from RawCameraController;
 * see docs/refactor/zsl-study.md.
 *
 * Why overflow needs a held-slot release before draining: ImageReader throws
 * IllegalStateException from acquireNextImage once maxImages are acquired,
 * "until more images are released with Image#close" (AOSP ImageReader.java).
 *
 * [clockMs] is injectable so the policy is unit-testable without Android.
 */
internal class ZslStreamHealth(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /**
     * Transient ZSL outage (RawLens policy: failures are per-shot, never
     * session-fatal). Overflow storms, watchdog misses and isolated repeating-
     * request failures park the ring until this wall-clock deadline instead of
     * latching the controller's session fallback; every later press re-attempts
     * ZSL once it passes. Only a rejected stream combination (which can never
     * succeed) still latches the session fallback.
     */
    var cooldownUntilMs = 0L
        private set

    /**
     * Repeating-RAW-stream health latch (degrade to forward bursts). Set when
     * a freshly started stream never becomes productive (overflow storm or
     * watchdog trip with zero paired frames): this HAL cannot sustain the
     * continuous RAW stream, so presses fire instant forward bursts under the
     * ZSL stem instead of waiting on a ring that will never fill. A stream that
     * filled and only later sickened is NOT marked — it keeps per-shot retry.
     * Cleared on session open and on ZSL (re-)entry.
     */
    var streamBroken = false
        private set

    /** The ring reached capacity on the current stream (ZSL ACTIVE). */
    var filled = false

    /** Ring admissions in the current watchdog window; the burst-mode latch
     * fires only on a window with zero of them. Camera thread only. */
    var epochPairs = 0
        private set

    /** Consecutive slow-but-productive watchdog windows on this epoch. */
    private var slowWindows = 0

    /**
     * Consecutive transient cooldowns with zero paired frames since. A single
     * park is prudence (lag spike); a second consecutive fruitless one proves
     * request-level recovery cannot fix this HAL state — only a session
     * rebuild can (user-proven via manual frame-count changes). Reset on any
     * pair, save-completion rearm, and session open.
     */
    private var fruitlessCooldowns = 0

    private val overflow = ZslOverflowTracker(clockMs)

    /** True while the ring must not be attempted: latched session fallback,
     * burst-mode degradation, or a transient cooldown that has not expired. */
    fun isBlocked(disabledForSession: Boolean): Boolean =
        disabledForSession || streamBroken || clockMs() < cooldownUntilMs

    fun cooldownExpired(): Boolean = clockMs() >= cooldownUntilMs

    /** Camera open: a fresh session deserves a fresh verdict on the stream. */
    fun resetForSession() {
        cooldownUntilMs = 0L
        streamBroken = false
        fruitlessCooldowns = 0
        filled = false
    }

    /** ZSL (re-)entry by the user: drop the cooldown and burst-mode latch. */
    fun clearLatches() {
        cooldownUntilMs = 0L
        streamBroken = false
    }

    /** New stream with an empty ring: fresh window counters and stall clock. */
    fun startStream() {
        epochPairs = 0
        slowWindows = 0
        overflow.reset()
    }

    /** Resumed stream around a kept ring: fresh window counters only. */
    fun rearmWindow() {
        epochPairs = 0
        slowWindows = 0
    }

    /** A frame was admitted to the ring. */
    fun onRingAdmission() {
        epochPairs++
        overflow.onPaired()
        fruitlessCooldowns = 0
    }

    /** A forward (non-ring) capture paired: the HAL is producing again. */
    fun onForwardPair() {
        fruitlessCooldowns = 0
    }

    enum class OverflowAction {
        /** Brief stall: free one held slot and drain the queue. */
        DRAIN,
        /** Stalled past the limit on a stream that had filled: transient cooldown. */
        COOLDOWN,
        /** Stalled before ever filling: burst-mode latch set, then cooldown. */
        LATCH_AND_COOLDOWN,
    }

    /** The RAW ImageReader hit maxImages while the ZSL stream ran. */
    fun onReaderOverflow(): OverflowAction {
        if (!overflow.onOverflow()) return OverflowAction.DRAIN
        // A storm on a stream that never filled means this HAL cannot sustain
        // repeating RAW right now: degrade to burst-mode instead of parking a
        // stream that will only storm again. A filled-then-sickened stream
        // keeps transient per-shot retry.
        if (!filled) {
            streamBroken = true
            return OverflowAction.LATCH_AND_COOLDOWN
        }
        return OverflowAction.COOLDOWN
    }

    sealed class WatchdogVerdict {
        /** Zero pairs in the whole window: burst-mode latch set, then cooldown. */
        object LatchAndCooldown : WatchdogVerdict()
        /** Productive but slow: keep stream and ring, re-arm another window. */
        data class Extend(val pairsInWindow: Int, val window: Int) : WatchdogVerdict()
        /** Repeatedly slow: transient (retryable, never latching) cooldown. */
        object Cooldown : WatchdogVerdict()
    }

    /** A watchdog window expired on a live stream whose ring has not filled. */
    fun onWatchdogWindowExpired(): WatchdogVerdict {
        if (epochPairs <= 0) {
            // Zero pairs in the whole startup window: same never-productive
            // signature as a stillborn storm — burst-mode, not blind retry.
            streamBroken = true
            return WatchdogVerdict.LatchAndCooldown
        }
        if (slowWindows < SLOW_WINDOW_EXTENSIONS) {
            // Productive but too slow to fill (reader pressure, save drain):
            // keep the stream and its partial ring and re-arm for another
            // window. The counter resets, so a wedged stream (zero NEW
            // pairs) still latches on the next window.
            slowWindows++
            val pairs = epochPairs
            epochPairs = 0
            return WatchdogVerdict.Extend(pairs, slowWindows)
        }
        slowWindows = 0
        epochPairs = 0
        return WatchdogVerdict.Cooldown
    }

    enum class OutageAction { REBUILD_SESSION, PARK }

    /**
     * Transient outage: park the ring for [cooldownMs], or — on the second
     * consecutive fruitless park, with no capture executing — ask for a
     * session rebuild, since request rebuilds provably cannot fix that HAL
     * state.
     */
    fun onTransientOutage(cooldownMs: Long, captureBusy: Boolean): OutageAction {
        fruitlessCooldowns++
        if (fruitlessCooldowns >= FRUITLESS_COOLDOWN_REBUILDS && !captureBusy) {
            fruitlessCooldowns = 0
            return OutageAction.REBUILD_SESSION
        }
        cooldownUntilMs = clockMs() + cooldownMs
        return OutageAction.PARK
    }

    companion object {
        /** Consecutive fruitless ZSL cooldowns before a session rebuild. */
        const val FRUITLESS_COOLDOWN_REBUILDS = 2
        /** Slow-but-productive watchdog windows tolerated per epoch before a
         * transient (retryable, never latching) cooldown. Zero-pair windows
         * still latch burst-mode immediately. */
        const val SLOW_WINDOW_EXTENSIONS = 2
        const val STARTUP_TIMEOUT_MS = 4_000L
        const val FRAME_FILL_ALLOWANCE_MS = 1_000L

        /** Watchdog window: startup floor, two slow exposures plus slack, or ring fill time. */
        fun watchdogTimeoutMs(lastExposureNanos: Long, ringFrames: Int): Long = maxOf(
            STARTUP_TIMEOUT_MS,
            (lastExposureNanos / 1_000_000L).coerceAtMost(4_000L) * 2L + 1_500L,
            ringFrames * FRAME_FILL_ALLOWANCE_MS
        )
    }
}
