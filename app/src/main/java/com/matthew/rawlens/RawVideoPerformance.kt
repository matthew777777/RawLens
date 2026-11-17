// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

internal object RawVideoPerformance {
    fun workers(fps: Int, cores: Int): Int =
        if(fps>30 && cores>=8) 4 else cores.coerceIn(1,3)

    /** Throttle monitoring submissions, never recorded camera frames. */
    fun offerPreview(last: Long, timestamp: Long, fps: Int): Boolean =
        fps<=30 || last==Long.MIN_VALUE || timestamp<last || timestamp-last>=32_000_000L
}
