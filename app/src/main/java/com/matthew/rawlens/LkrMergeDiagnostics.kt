// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One bounded monitor per active merge; survives Activity teardown with the merge. */
internal class LkrMergeDiagnostics(context: Context) : AutoCloseable {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val packageName = context.packageName
    private val started = SystemClock.elapsedRealtime()
    private val uptime = SystemClock.uptimeMillis()
    private val cpu = Process.getElapsedCpuTime()
    private val workerTid = Process.myTid()
    private val monitor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "LkrMergeDiagnostics").apply { isDaemon = true }
    }

    init {
        sample("start")
        monitor.scheduleWithFixedDelay({ sample("running") }, 10, 10, TimeUnit.SECONDS)
    }

    private fun sample(phase: String) {
        // Diagnostics must never interrupt processing, including restricted /proc on OEM ROMs.
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            val elapsed = SystemClock.elapsedRealtime() - started
            val awake = SystemClock.uptimeMillis() - uptime
            val scheduling = runCatching {
                File("/proc/self/task/$workerTid/schedstat").readText().trim()
            }.getOrDefault("unavailable")
            val cgroup = runCatching { File("/proc/self/cgroup").readLines().joinToString(";") }
                .getOrDefault("unavailable")
            Log.i("RawLensCamera", "LK-R runtime phase=$phase elapsedMs=$elapsed " +
                "awakeMs=$awake sleepMs=${(elapsed - awake).coerceAtLeast(0)} " +
                "processCpuMs=${Process.getElapsedCpuTime() - cpu} importance=${info.importance} " +
                "workerTid=$workerTid priority=${Process.getThreadPriority(workerTid)} " +
                "schedstatNs=$scheduling cgroup=$cgroup " +
                "interactive=${power.isInteractive} idle=${power.isDeviceIdleMode} " +
                "powerSave=${power.isPowerSaveMode} thermal=${power.currentThermalStatus} " +
                "batteryExempt=${power.isIgnoringBatteryOptimizations(packageName)} " +
                "nativeMB=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}")
        }
    }

    override fun close() {
        monitor.shutdownNow()
        sample("end")
    }
}
