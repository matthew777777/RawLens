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

    /**
     * Budget for the one merge about to run: free memory down to HALF the low-memory threshold.
     * The threshold is where Android starts trimming cached background apps, not where it kills the
     * foreground app, so the running merge may use part of it. The full-threshold gate skipped HDR+
     * by 2 MB and MFSR on an 8 GB Mi 10 Ultra with 1.8 GB free (logcat 2026-11-27). Queue and staging
     * decisions keep [systemHeadroomBytes]; 50 MP-class merges (GBs) are still refused.
     */
    fun mergeRunHeadroomBytes(context: Context): Long? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem - info.threshold / 2
    }

    /** availMem / threshold / totalMem behind [systemHeadroomBytes], for the skip logs. */
    fun describeSystem(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return "system memory unknown"
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return "avail ${mb(info.availMem.toDouble())}MB threshold ${mb(info.threshold.toDouble())}MB " +
            "total ${mb(info.totalMem.toDouble())}MB lowMemory=${info.lowMemory}"
    }

    /** Java heap still allocatable by this process (ART ceiling minus live use). */
    fun javaHeapHeadroomBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    /**
     * Peak bytes of whichever merge runs a burst of [frames] at [px] pixels:
     * the larger of HDR+ HQ (20 fixed + 2 per frame + 56 scratch B/px, see
     * HdrPlusMerge.memoryPlan) and the sharp merge (80 B/px native, see
     * MfsrMerge.fitsMemory), plus the estimate margin.
     *
     * LK-R Lite works per tile, so its peak follows the OUTPUT size, not the
     * frame count: 12 B/px float RGB out (lkrl_full rgb_out) + 12 B/px
     * develop float input + 4 B/px bitmap = 28 B per output pixel. At 1x that
     * sits inside the figures above; Full 2x ([outputScale] 2) is 4x the
     * pixels (112 B per sensor pixel) and dominates.
     */
    fun mergeWorkingSetBytes(px: Long, frames: Int, outputScale: Int = 1): Long =
        (px * maxOf(20.0 + 2.0 * frames + 56.0, 80.0,
            LKR_BYTES_PER_OUTPUT_PX * outputScale * outputScale) * ESTIMATE_MARGIN).toLong()

    private const val LKR_BYTES_PER_OUTPUT_PX = 28.0

    /** RAW16 copy of one burst held while it waits ([MergeInputStaging]). */
    fun stagedBurstBytes(px: Long, frames: Int): Long = px * 2L * frames

    /**
     * How many merges may be in flight (running + queued), from measured
     * headroom instead of a fixed count. Merges run one at a time, so free
     * memory has to cover one merge's full working set (the next one to run
     * may not have allocated yet) and every extra queued burst costs only its
     * staged copy: limit = in flight now + (headroom - working set) / copy.
     * At least 1, so an idle camera always admits a shot (the merge's own gate
     * still decides); unknown headroom keeps the old fixed 3.
     */
    fun mergeQueueLimit(headroomBytes: Long?, pendingJobs: Int, px: Long, frames: Int, outputScale: Int = 1): Int {
        if (headroomBytes == null || px <= 0 || frames <= 0) return FALLBACK_MERGE_QUEUE
        val extra = (headroomBytes - mergeWorkingSetBytes(px, frames, outputScale)) / stagedBurstBytes(px, frames)
        return maxOf(1, pendingJobs + extra.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    const val FALLBACK_MERGE_QUEUE = 3

    fun mb(bytes: Double): Long = (bytes / (1 shl 20)).toLong()
}
