// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Save inputs share the reader with preview, pairing and GPU borrows. Sidecars own no input. */
internal object CaptureQueueCapacity {
    fun accepts(pendingFrames: Int, requestedFrames: Int, formatLimit: Int,
                readerSlots: Int, previewReserve: Int): Boolean {
        // Minimal readers carry no ZSL ring: admit one forward still while
        // retaining all other slots for preview and outstanding GPU borrows.
        val readerLimit = if (readerSlots in 4..previewReserve) 1
            else (readerSlots - previewReserve).coerceAtLeast(0)
        val limit = minOf(formatLimit, readerLimit)
        return requestedFrames > 0 && pendingFrames >= 0 && requestedFrames <= limit - pendingFrames
    }

    /** Merge jobs serialize development; their source images occupy reader slots only until merge. */
    fun acceptsMerge(pendingJobs: Int, pendingFrames: Int, requestedFrames: Int,
                     readerSlots: Int, previewReserve: Int): Boolean =
        pendingJobs in 0 until 3 && accepts(pendingFrames, requestedFrames,
            Int.MAX_VALUE, readerSlots, previewReserve)

    fun ringFits(pendingFrames: Int, ringCapacity: Int, readerSlots: Int, previewReserve: Int): Boolean =
        ringCapacity > 0 && pendingFrames >= 0 &&
            pendingFrames.toLong() + ringCapacity + previewReserve <= readerSlots
}
