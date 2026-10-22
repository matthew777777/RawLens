// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Serialized still-burst chain: the fallback when a pipelined `captureBurst`
 * has failed on this device (see `stillBurstPipelined`). Stills are submitted
 * one at a time and each pair completion (or lost frame) submits the next,
 * so ~2 buffers are in flight instead of N. Prebuilt requests wait here;
 * the controller clears the chain in `finishCapture`, the choke point of
 * every chain end.
 *
 * Generic over the request and callback types so the queue logic runs in
 * JVM tests; the controller uses `CaptureRequest` / `CaptureCallback`.
 */
internal class StillChain<R : Any, C : Any> {
    private val pending = ArrayDeque<R>()

    /** Callback of the running capture; null once the chain has ended. */
    var callback: C? = null
        private set

    /** A new capture starts: its callback is active and nothing is queued. */
    fun begin(callback: C) {
        this.callback = callback
        pending.clear()
    }

    /** Queues the prebuilt stills of a serialized capture, in order. */
    fun enqueue(requests: Collection<R>) {
        pending.addAll(requests)
    }

    /** Removes and returns the next still to submit, or null when none remain. */
    fun takeNext(): R? = pending.removeFirstOrNull()

    /** Ends the chain: no further chained submit may run after this. */
    fun clear() {
        pending.clear()
        callback = null
    }
}
