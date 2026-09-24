// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ExecutionException
import kotlin.math.max
import kotlin.math.min

/**
 * Process-wide shared CPU pool for the HDR bracket path (align, merge, deghost).
 *
 * Previously each stage built and destroyed its own thread pool (or spawned raw
 * Threads) on every call: several pool create/teardown cycles and dozens of
 * thread spawns per bracket, each paying OS thread-creation latency and
 * allocator churn on the writer thread. That cost lands in the inter-stage
 * gaps no per-stage timer accounts for. Sharing one pool keeps the exact same
 * row-strip partitioning and disjoint-write determinism while removing the
 * churn; no pixel math changes.
 */
internal object HdrPools {
    private val cores: Int get() = max(1, Runtime.getRuntime().availableProcessors())

    /** Merge/deghost width (matches the old per-call pools: max 8 threads). */
    val shared: java.util.concurrent.ExecutorService by lazy {
        Executors.newFixedThreadPool(max(1, min(8, cores))) { r ->
            Thread(r, "hdr-shared").also { it.isDaemon = true }
        }
    }

    /**
     * Runs [block] over [height] rows split into at most [maxParts] disjoint
     * strips on the shared pool, waiting for all strips. Identical strip
     * boundaries to the old per-call pools (`t * height / parts`); output stays
     * bit-deterministic. Falls back to inline when there is nothing to split.
     */
    fun runStriped(height: Int, maxParts: Int, block: (y0: Int, y1: Int) -> Unit) {
        val parts = max(1, min(maxParts, cores))
        if (parts == 1 || height < parts * 2) {
            block(0, height)
            return
        }
        val futures = ArrayList<Future<*>>(parts)
        for (t in 0 until parts) {
            val y0 = t * height / parts
            val y1 = (t + 1) * height / parts
            futures += shared.submit(Callable { block(y0, y1) })
        }
        awaitWorkers(futures)
    }

    /**
     * Evaluates [fn] over [offsets] in list order onto a [FloatArray], spreading
     * indices round-robin over at most [maxParts] workers. Same values as the
     * old per-call thread fan-out; the caller argmins sequentially.
     */
    fun <T> evalEach(
        offsets: List<T>,
        maxParts: Int,
        fn: (T) -> Float
    ): FloatArray {
        val out = FloatArray(offsets.size)
        val parts = max(1, min(maxParts, cores))
        if (parts == 1 || offsets.size <= 1) {
            offsets.forEachIndexed { i, o -> out[i] = fn(o) }
            return out
        }
        val futures = ArrayList<Future<*>>(parts)
        for (t in 0 until parts) {
            futures += shared.submit(Callable {
                var i = t
                while (i < offsets.size) {
                    out[i] = fn(offsets[i])
                    i += parts
                }
            })
        }
        awaitWorkers(futures)
        return out
    }

    /**
     * A failed strip does not stop its siblings. Drain every worker before the
     * caller can unwind and release/reuse their shared image storage. Future
     * cancellation is insufficient: it can mark completion while code still runs.
     */
    internal fun awaitWorkers(futures: List<Future<*>>) {
        var failure: Exception? = null
        var interrupted = false
        for (future in futures) {
            while (true) {
                try {
                    future.get()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                    if (failure == null) failure = e
                    // get() clears the interrupt flag. Restore it only after all
                    // writes have finished, otherwise the next wait spins/throws.
                } catch (e: ExecutionException) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                    break
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }
}
