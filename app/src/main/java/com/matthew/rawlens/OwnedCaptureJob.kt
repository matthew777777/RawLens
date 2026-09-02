// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Immutable collection ownership with an idempotent terminal release operation. */
internal class CloseOnceOwner<T>(items: Collection<T>, private val release: (T) -> Unit) : AutoCloseable {
    val items: List<T> = items.toList()
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var firstFailure: Throwable? = null
        items.forEach { item ->
            try {
                release(item)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure!!.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}

/**
 * A queue-safe job which transfers one owner from PENDING to RUNNING to RELEASED. Cancellation is
 * intentionally limited to a job which has not started, so an Image can never be closed under an
 * active DNG writer or GPU upload.
 */
internal class OwnedCaptureJob(
    private val owner: AutoCloseable,
    private val work: () -> Unit
) : Runnable {
    private val state = AtomicInteger(PENDING)

    override fun run() {
        if (!state.compareAndSet(PENDING, RUNNING)) return
        try {
            work()
        } finally {
            try {
                owner.close()
            } finally {
                state.set(RELEASED)
            }
        }
    }

    /** Returns true only when this call prevented the queued work from starting. */
    fun cancelBeforeRun(): Boolean {
        if (!state.compareAndSet(PENDING, RELEASED)) return false
        owner.close()
        return true
    }

    internal fun stateForTest(): Int = state.get()

    companion object {
        internal const val PENDING = 0
        internal const val RUNNING = 1
        internal const val RELEASED = 2
    }
}
