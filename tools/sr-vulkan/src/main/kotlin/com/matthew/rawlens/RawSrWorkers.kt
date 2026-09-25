// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Shared CPU worker pool for the mosaic (CPU) merge chain: unpack, alignment,
 * robustness, precision and accumulation are all row/tile-sharded here.
 *
 * Shards are contiguous index ranges run in order; every shard executes the
 * same serial code as the single-threaded path, so sharding never changes a
 * single floating-point operation or its order — output is bitwise-identical
 * for any worker count. Callers keep their own buffers (disjoint writes,
 * shared reads); the join provides the only needed visibility edge.
 */
internal object RawSrWorkers {
    /** Test override for the worker count (exact-output equivalence checks). */
    @Volatile var overrideCount: Int? = null

    val count: Int get() = overrideCount ?: defaultCount

    private val defaultCount: Int by lazy {
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }

    private val pool by lazy {
        Executors.newFixedThreadPool(defaultCount) { task ->
            Thread(task, "rawsr-worker").apply { isDaemon = true }
        }
    }

    /**
     * Runs [block] over the contiguous shard partition of [0, total).
     * Inline when a single worker is selected. Failures join first: every
     * submitted shard runs to completion, then the first failure is rethrown.
     */
    fun forEachShard(total: Int, block: (start: Int, endExclusive: Int) -> Unit) {
        val workers = count.coerceIn(1, total.coerceAtLeast(1))
        if (workers == 1) {
            block(0, total)
            return
        }
        val span = (total + workers - 1) / workers
        val pending = ArrayList<Future<*>>(workers)
        var start = 0
        while (start < total) {
            val end = minOf(start + span, total)
            val s = start
            val e = end
            pending.add(pool.submit { block(s, e) })
            start = end
        }
        var first: Throwable? = null
        for (future in pending) {
            try {
                future.get()
            } catch (thrown: Throwable) {
                if (first == null) first = thrown.cause ?: thrown
            }
        }
        if (first != null) throw first
    }
}
