// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

/** Cooperative per-worker deadlines; the OS retains power and thermal control. */
internal class RawVideoCpuHint private constructor(private val session: PerformanceHintManager.Session?) {
    fun report(durationNs: Long) { if(durationNs>0)runCatching { session?.reportActualWorkDuration(durationNs) } }
    fun close() { runCatching { session?.close() } }
    companion object {
        fun create(context: Context?, fps: Int, workers: Int): RawVideoCpuHint {
            if(Build.VERSION.SDK_INT<31 || fps<=30 || context==null)return RawVideoCpuHint(null)
            val session=runCatching {
                context.getSystemService(PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(Process.myTid()),workers*1_000_000_000L/fps)
            }.getOrNull()
            return RawVideoCpuHint(session)
        }
    }
}
