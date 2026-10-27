// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** A fit and the low-resolution geometry that defines its grid coordinates. */
internal data class BguFittedGrid(val seq: Long, val lowW: Int, val lowH: Int, val grid: BguGrid)

/**
 * Single fit producer, single render consumer. Three buffers give the producer
 * a writable grid even when the consumer holds an old grid indefinitely.
 * Only ownership changes under the monitor; fitting and GL uploads run outside
 * it. The consumer's lease lasts until its next [acquireLatest] call, so a slow
 * upload cannot race a later fit. Publication provides Java happens-before.
 * See docs/refactor/bgu-study.md for the ownership invariant and references.
 */
internal class BguGridExchange {
    @Volatile var generation = 0L
        private set

    private var buffers = emptyArray<BguGrid>()
    private var latest: BguFittedGrid? = null
    private var reading: BguGrid? = null

    /** Producer only. The returned coefficients may be written until publish. */
    @Synchronized fun beginFit(gw: Int, gh: Int, gz: Int): BguGrid {
        if (buffers.firstOrNull()?.let { it.gw != gw || it.gh != gh || it.gz != gz } != false) {
            buffers = Array(3) { BguGrid(gw, gh, gz, FloatArray(gw * gh * gz * 12)) }
        }
        return buffers.first { it !== latest?.grid && it !== reading }
    }

    /** Producer only; this grid becomes read-only until beginFit returns it. */
    @Synchronized fun publish(fitted: BguFittedGrid, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        check(fitted.grid !== reading)
        latest = fitted
    }

    /** Consumer only; releases the previous lease and holds the latest grid. */
    @Synchronized fun acquireLatest(): BguFittedGrid? {
        val fitted = latest
        reading = fitted?.grid
        return fitted
    }

    /** Discard pending publication; an in-progress consumer keeps its lease. */
    @Synchronized fun clear() {
        generation++
        latest = null
    }
}
