// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** A scheduling spike must not permanently remove an encoder worker. */
internal class RawVideoWorkerHealth(count: Int, private val slowMs: Long = 250) {
    private val timeouts=IntArray(count)
    @Synchronized fun timedOut(index: Int): Boolean = ++timeouts[index]>=3
    @Synchronized fun completed(index: Int, elapsedMs: Long) {
        if(elapsedMs<slowMs)timeouts[index]=0
    }
}
