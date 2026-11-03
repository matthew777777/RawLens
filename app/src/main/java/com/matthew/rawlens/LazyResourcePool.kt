// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Allocate only on demand, while keeping the same hard concurrency ceiling.
 * clear() is terminal and must be called only after every borrower exits. */
internal class LazyResourcePool<T : Any>(private val capacity: Int, private val create: () -> T) {
    private val available = ArrayBlockingQueue<T>(capacity)
    private var allocated = 0

    fun poll(timeout: Long, unit: TimeUnit): T? {
        available.poll()?.let { return it }
        val reserved = synchronized(this) {
            if (allocated < capacity) { allocated++; true } else false
        }
        if (reserved) {
            try { return create() } catch (failure: Throwable) {
                synchronized(this) { allocated-- }
                throw failure
            }
        }
        return available.poll(timeout, unit)
    }

    fun offer(value: T): Boolean = available.offer(value)
    fun clear() = available.clear()
}
