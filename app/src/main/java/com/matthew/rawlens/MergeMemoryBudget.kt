// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.ActivityManager
import android.content.Context

/**
 * Measured memory headroom for the multiframe merges (HDR+, MFSR). Neither
 * merge tiles its working set, and on unified memory a merge that outgrows
 * free RAM drives lmkd to kill launcher and SystemUI before RawLens itself
 * (logcat 2026-11-12: 50 MP HDR+ x8, RawLens RSS 3.3 GB at kill). Same idea
 * as rawr's MergeArenaEstimate gate, but the budget is measured
 * headroom, not a constant.
 */
internal object MergeMemoryBudget {
    /** Allocator rounding/alignment and driver overhead on top of an itemized estimate. */
    const val ESTIMATE_MARGIN = 1.1

    /** Bytes available before the kernel's low-memory kill threshold; null if unknown. */
    fun systemHeadroomBytes(context: Context): Long? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem - info.threshold
    }

    /** Java heap still allocatable by this process (ART ceiling minus live use). */
    fun javaHeapHeadroomBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    fun mb(bytes: Double): Long = (bytes / (1 shl 20)).toLong()
}
