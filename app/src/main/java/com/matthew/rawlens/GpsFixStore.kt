// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Capture reads only immutable data. All fix ages use Android's boot-time clock. */
internal class GpsFixStore {
    private data class Fix(val value: GpsLocation, val measuredNanos: Long, val provider: String?)
    private var generation = 0L
    private var active = false
    private var candidate: Fix? = null

    @Synchronized fun reset(enabled: Boolean): Long {
        active = enabled
        candidate = null
        return ++generation
    }

    @Synchronized fun offer(token: Long, value: GpsLocation, measuredNanos: Long, nowNanos: Long, provider: String?) {
        if (!active || token != generation || !fresh(measuredNanos, nowNanos)) return
        val accuracy = value.accuracyMeters
        if (accuracy != null && (!accuracy.isFinite() || accuracy < 0)) return
        val old = candidate?.takeIf { fresh(it.measuredNanos, nowNanos) }
        if (old != null) {
            val delta = measuredNanos - old.measuredNanos
            // Movement wins over an older accurate fix after 30 seconds.
            if (delta < -RECENCY_WINDOW_NANOS) return
            if (delta <= RECENCY_WINDOW_NANOS) {
                val previous = old.value.accuracyMeters
                val improves = accuracy != null && (previous == null || accuracy < previous)
                val newerComparable = delta > 0 && (accuracy == previous ||
                    (accuracy != null && previous != null && provider == old.provider &&
                        accuracy <= previous + SAME_PROVIDER_TOLERANCE_METERS))
                if (!improves && !newerComparable) return
            }
        }
        candidate = Fix(value, measuredNanos, provider)
    }

    @Synchronized fun snapshot(nowNanos: Long): GpsLocation? {
        if (!active) return null
        val fix = candidate ?: return null
        if (!fresh(fix.measuredNanos, nowNanos)) {
            candidate = null
            return null
        }
        return fix.value
    }

    companion object {
        const val MAX_AGE_NANOS = 120_000_000_000L
        const val RECENCY_WINDOW_NANOS = 30_000_000_000L
        const val SAME_PROVIDER_TOLERANCE_METERS = 25f
        private fun fresh(measured: Long, now: Long) = measured > 0 && measured <= now && now - measured <= MAX_AGE_NANOS
    }
}
