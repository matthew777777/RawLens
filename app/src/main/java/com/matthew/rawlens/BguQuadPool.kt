// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Bounded low-frame leases: queued or fitting arrays cannot be sampled again. */
internal class BguQuadPool(private val capacity: Int = 6) {
    private val free = ArrayDeque<ByteArray>()
    private val leased = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<ByteArray, Boolean>()
    )

    @Synchronized fun acquire(size: Int): ByteArray? {
        if (leased.size >= capacity) return null
        val matching = free.firstOrNull { it.size == size }
        val frame = if (matching != null) {
            free.remove(matching)
            matching
        } else {
            // Retire an idle buffer when geometry changes; keep memory bounded.
            if (free.size + leased.size >= capacity) free.removeFirst()
            ByteArray(size)
        }
        check(leased.add(frame))
        return frame
    }

    @Synchronized fun release(frame: ByteArray) {
        check(leased.remove(frame))
        free.addLast(frame)
    }
}
