// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Save inputs share the reader with preview, pairing and GPU borrows. Sidecars own no input. */
internal object CaptureQueueCapacity {
    fun accepts(pendingFrames: Int, requestedFrames: Int, formatLimit: Int,
                readerSlots: Int, previewReserve: Int): Boolean {
        val limit = minOf(formatLimit, (readerSlots - previewReserve).coerceAtLeast(0))
        return requestedFrames > 0 && pendingFrames >= 0 && requestedFrames <= limit - pendingFrames
    }

    fun ringFits(pendingFrames: Int, ringCapacity: Int, readerSlots: Int, previewReserve: Int): Boolean =
        ringCapacity > 0 && pendingFrames >= 0 &&
            pendingFrames.toLong() + ringCapacity + previewReserve <= readerSlots
}
