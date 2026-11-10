// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Retains each controller's GL thread while serializing allocation peaks across activities. */
internal class PhotoDevelopmentExecutor(queueSlots: Int) : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(queueSlots)
) {
    override fun beforeExecute(thread: Thread, task: Runnable) {
        super.beforeExecute(thread, task)
        // Destroyed controllers still finish their accepted saves and ordered GL teardown.
        developmentLock.lock()
    }

    override fun afterExecute(task: Runnable, failure: Throwable?) {
        try { super.afterExecute(task, failure) }
        finally { developmentLock.unlock() }
    }

    private companion object {
        val developmentLock = ReentrantLock(true)
    }
}
