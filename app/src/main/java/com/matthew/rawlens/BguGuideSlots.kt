// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** CPU ownership of guide slots; GPU completion is separately protected by fd fences. */
internal class BguGuideSlots(count: Int = 3) {
    private val writing = BooleanArray(count)
    private val reading = BooleanArray(count)
    private val sequences = LongArray(count)

    @Synchronized fun tryWrite(slot: Int): Boolean {
        if (writing[slot] || reading[slot]) return false
        writing[slot] = true
        sequences[slot] = 0 // the old frame is invalid as soon as writing starts
        return true
    }

    @Synchronized fun publish(slot: Int, seq: Long) {
        check(writing[slot])
        sequences[slot] = seq
    }

    @Synchronized fun finishWrite(slot: Int) { writing[slot] = false }

    @Synchronized fun tryRead(slot: Int, seq: Long): Boolean {
        if (writing[slot] || reading[slot] || sequences[slot] != seq) return false
        reading[slot] = true
        return true
    }

    @Synchronized fun finishRead(slot: Int) { reading[slot] = false }

    /** Invalidates queued jobs without revoking any in-progress read/write lease. */
    @Synchronized fun clear() { sequences.fill(0) }
}
