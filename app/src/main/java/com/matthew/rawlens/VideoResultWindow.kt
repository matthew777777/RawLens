// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Bounded sensor-time lookup. A future result is never calibration for an older image. */
internal class VideoResultWindow<T>(private val capacity: Int = 64) {
    data class Match<T>(val value: T, val exact: Boolean)
    private val results = java.util.TreeMap<Long, T>()

    @Synchronized fun offer(timestamp: Long, value: T) {
        results[timestamp] = value
        while (results.size > capacity) results.pollFirstEntry()
    }

    @Synchronized fun hasExact(timestamp: Long): Boolean = results.containsKey(timestamp)

    @Synchronized fun match(timestamp: Long, maxAgeNanos: Long): Match<T>? {
        val candidate = results.floorEntry(timestamp) ?: return null
        val age = timestamp - candidate.key
        if (age < 0 || age > maxAgeNanos) return null
        return Match(candidate.value, age == 0L)
    }

    @Synchronized fun clear() = results.clear()

    companion object {
        /** One requested frame, capped at 50 ms even for low-rate takes. */
        fun maxAgeNanos(fps: Int): Long = minOf(50_000_000L, 1_000_000_000L / fps.coerceAtLeast(1))
    }
}
