// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Printer
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Device-citizenship probes: every way this app can slow down the phone, in the
 * foreground, in the background, or after closing.
 *
 * - Foreground: UI jank + frozen frames (FrameMetrics, which only fires
 *   for really drawn frames and never forces vsync), slow main-thread
 *   dispatches plus busyness/offender aggregation (Looper printer), self CPU
 *   + top threads (`/proc`), thermal status, GC pressure. One
 *   `PERF[heartbeat]` line per minute.
 * - Background: [auditBackground] snapshots what is still alive shortly after
 *   the activity leaves (threads, camera, GPS, service), plus storage use and
 *   the trim-memory levels the system reports (see [noteTrimMemory]).
 * - After close: [auditShutdown] proves nothing is left running (no alarms,
 *   jobs, receivers, or lingering threads beyond process reclamation).
 *
 * Steady-state cost is negligible: nanoseconds per frame/message, one short
 * `/proc` scan per minute on a daemon thread, audits only on transitions.
 * Filter with:  adb logcat -s RawLensPerf RawLensMemory
 */
object DeviceImpactProbe {
    private const val TAG = "RawLensPerf"
    const val HEARTBEAT_INTERVAL_MS = 60_000L
    const val MAIN_SLOW_MS = 200L
    const val JANKY_FRAME_MS = 32L
    const val FROZEN_FRAME_MS = 700L
    const val STUCK_SERVICE_MS = 15 * 60_000L
    const val BG_REAUDIT_DELAY_MS = 8_000L
    /** Assumption behind [cpuPercent]; true on every Android kernel. */
    const val USER_HZ = 100L

    @Volatile private var appContext: Context? = null
    @Volatile private var foreground = true
    @Volatile private var heartbeatThread: Thread? = null
    @Volatile private var watchdogInstalled = false

    // Frame tracking: listener callbacks arrive on the main thread.
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private var frameWindow: Window? = null
    private var windowFrames = 0L
    private var windowJanky = 0L
    private var windowFrozen = 0L
    private var windowWorstMs = 0L
    private var windowStartMs = 0L

    // Main-looper aggregation: atomics updated per dispatch, read by heartbeat.
    private val mainDispatchCount = AtomicLong()
    private val mainDispatchMs = AtomicLong()
    private var lastMainCount = 0L
    private var lastMainMs = 0L
    private val slowOffenders = ConcurrentHashMap<String, LongArray>()

    // Heartbeat state: heartbeat thread only.
    private var lastSelfTicks = -1L
    private var lastHeartbeatMs = 0L
    private var lastThreadTicks = emptyMap<Long, Pair<String, Long>>()
    private var lastThermal = Int.MIN_VALUE
    private var lastGcCount = -1L
    private var lastGcTimeMs = -1L

    private val liveServices = ConcurrentHashMap<String, Long>()
    private val stuckWarned = ConcurrentHashMap<String, Boolean>()
    private val lastSlowLogMs = AtomicLong(0)

    /** Frame class for one vsync interval. Unit-tested. */
    internal enum class FrameClass { OK, JANKY, FROZEN }

    internal fun classifyFrame(deltaMs: Long): FrameClass = when {
        deltaMs >= FROZEN_FRAME_MS -> FrameClass.FROZEN
        deltaMs >= JANKY_FRAME_MS -> FrameClass.JANKY
        else -> FrameClass.OK
    }

    /** Human name for a [ComponentCallbacks2] trim level. Unit-tested. */
    internal fun trimName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        // Literals: TRIM_MEMORY_MODERATE/COMPLETE/RUNNING_* are deprecated
        // since API 35, but old devices still deliver these levels. The
        // values are stable platform constants (60/80/10/5/15), so match
        // them directly instead of touching the deprecated fields.
        60 -> "MODERATE"
        80 -> "COMPLETE"
        10 -> "RUNNING_LOW"
        5 -> "RUNNING_MODERATE"
        15 -> "RUNNING_CRITICAL"
        else -> "level=$level"
    }

    /**
     * utime+stime from `/proc/.../stat` text (fields 14+15, after the
     * parenthesized comm), or null when unparsable. Unit-tested.
     */
    internal fun parseStatTicks(stat: String): Long? {
        val rest = stat.substringAfterLast(") ").split(' ').filter { it.isNotEmpty() }
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** Device-normalized CPU percent for a tick delta. Unit-tested. */
    internal fun cpuPercent(deltaTicks: Long, elapsedMs: Long, cores: Int): Double {
        if (elapsedMs <= 0 || cores <= 0 || deltaTicks < 0) return 0.0
        return deltaTicks * 1000.0 / USER_HZ / elapsedMs / cores * 100.0
    }

    /** Top [n] tids by tick growth, with names. Unit-tested. */
    internal fun topByDelta(
        prev: Map<Long, Pair<String, Long>>,
        cur: Map<Long, Pair<String, Long>>,
        n: Int
    ): List<Triple<Long, String, Long>> = cur.mapNotNull { (tid, named) ->
        val old = prev[tid]?.second ?: return@mapNotNull null
        Triple(tid, named.first, named.second - old)
    }.filter { it.third > 0 }.sortedByDescending { it.third }.take(n)

    /** Main-thread busyness percent for a dispatch-time delta. Unit-tested. */
    internal fun mainBusyPercent(dispatchMs: Long, elapsedMs: Long): Double {
        if (elapsedMs <= 0 || dispatchMs < 0) return 0.0
        return dispatchMs * 100.0 / elapsedMs
    }

    /**
     * Aggregation key for a Looper dispatch line: identity hashes and object
     * addresses stripped so the same message maps to one bucket. Unit-tested.
     */
    internal fun slowKey(line: String): String = line
        .replace(Regex("@[0-9a-fA-F]+|\\{[0-9a-fA-F]+\\}"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(64)

    fun install(context: Context) {
        appContext = context.applicationContext
        installMainWatchdog()
    }

    fun setForeground(inForeground: Boolean) {
        foreground = inForeground
    }

    /**
     * Logs main-thread dispatches slower than [MAIN_SLOW_MS] with the guilty
     * message, and aggregates busyness + slow offenders for the heartbeat.
     * Installed once; per-message cost is two clock reads plus two atomics.
     */
    @Synchronized
    fun installMainWatchdog() {
        if (watchdogInstalled) return
        watchdogInstalled = true
        runCatching {
            val main = Looper.getMainLooper()
            var dispatchStartNs = 0L
            var dispatchWhat: String? = null
            main.setMessageLogging(Printer { line ->
                if (line.startsWith(">>>>> Dispatching")) {
                    dispatchStartNs = SystemClock.elapsedRealtimeNanos()
                    dispatchWhat = line.take(90)
                } else if (line.startsWith("<<<<< Finished")) {
                    val tookMs = (SystemClock.elapsedRealtimeNanos() - dispatchStartNs) / 1_000_000
                    mainDispatchCount.incrementAndGet()
                    mainDispatchMs.addAndGet(tookMs)
                    if (tookMs >= MAIN_SLOW_MS) {
                        val key = slowKey(dispatchWhat ?: "?")
                        slowOffenders.compute(key) { _, old ->
                            longArrayOf((old?.get(0) ?: 0L) + 1, (old?.get(1) ?: 0L) + tookMs)
                        }
                        // Flood guard: one slow-dispatch line per 5 s max.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSlowLogMs.get() >= 5_000L) {
                            lastSlowLogMs.set(now)
                            Log.w(TAG, "PERF[main-slow] took=${tookMs}ms ${dispatchWhat ?: ""}")
                        }
                    }
                }
            })
        }
    }

    /**
     * Starts FrameMetrics jank tracking on [window]; safe to call repeatedly,
     * any thread. The listener only fires for really drawn frames, so unlike
     * a Choreographer chain it never forces vsync or skews the measurement.
     */
    fun startFrameTracking(window: Window) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { startFrameTracking(window) }
            return
        }
        if (frameListener != null) return
        windowStartMs = SystemClock.elapsedRealtime()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            onFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000)
        }
        frameListener = listener
        frameWindow = window
        runCatching {
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
    }

    /** Stops tracking and logs the trailing window summary. */
    fun stopFrameTracking(window: Window) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stopFrameTracking(window) }
            return
        }
        val listener = frameListener ?: return
        frameListener = null
        frameWindow = null
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        logJankWindow("foreground")
    }

    private fun onFrame(deltaMs: Long) {
        windowFrames++
        when (classifyFrame(deltaMs)) {
            FrameClass.OK -> Unit
            FrameClass.JANKY -> windowJanky++
            FrameClass.FROZEN -> {
                windowFrozen++
                Log.w(TAG, "PERF[frozen-frame] gap=${deltaMs}ms")
            }
        }
        if (deltaMs > windowWorstMs) windowWorstMs = deltaMs
        if (SystemClock.elapsedRealtime() - windowStartMs >= HEARTBEAT_INTERVAL_MS) {
            logJankWindow("1min")
        }
    }

    private fun logJankWindow(reason: String) {
        if (windowFrames > 0) {
            val jankyPct = windowJanky * 100 / windowFrames
            Log.i(TAG, "PERF[jank-$reason] frames=$windowFrames " +
                "janky=$windowJanky(${jankyPct}%) frozen=$windowFrozen worst=${windowWorstMs}ms")
        }
        windowFrames = 0
        windowJanky = 0
        windowFrozen = 0
        windowWorstMs = 0
        windowStartMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun startHeartbeat() {
        if (heartbeatThread != null) return
        heartbeatThread = Thread({
            // Prime baselines so the first logged line already has deltas.
            primeCpuBaseline()
            primeGcBaseline()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { heartbeat() }
            }
        }, "rawlens-perf-heartbeat").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    fun serviceStarted(name: String) {
        liveServices[name] = SystemClock.elapsedRealtime()
    }

    fun serviceStopped(name: String) {
        val started = liveServices.remove(name) ?: return
        stuckWarned.remove(name)
        val durSec = (SystemClock.elapsedRealtime() - started) / 1000
        Log.i(TAG, "PERF[service] $name stopped after=${durSec}s")
        if (SystemClock.elapsedRealtime() - started >= STUCK_SERVICE_MS) {
            Log.w(TAG, "PERF[service-stuck] $name ran ${durSec}s without stopping")
        }
    }

    /**
     * Proves the background footprint: which app threads are alive plus the
     * caller-supplied resource state (camera/GPS/video). Runs now and once
     * more after [BG_REAUDIT_DELAY_MS] to catch late releases.
     */
    fun auditBackground(extra: String) {
        auditOffThread("bg-now", extra)
        Thread({
            try {
                Thread.sleep(BG_REAUDIT_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            runCatching { auditOffThread("bg-late", extra, alreadyOffThread = true) }
        }, "rawlens-perf-audit").apply { isDaemon = true; start() }
    }

    /** Final roll call at process teardown (threads + caller state). */
    fun auditShutdown(extra: String) = auditOffThread("shutdown", extra)

    private fun auditOffThread(label: String, extra: String, alreadyOffThread: Boolean = false) {
        val run = {
            val threads = liveThreadNames()
            val services = liveServices.keys.toList().ifEmpty { listOf("none") }
            Log.i(TAG, "PERF[$label] $extra threads=${threads.size} " +
                "[${threads.take(24).joinToString(",")}] services=${services.joinToString(",")}")
        }
        if (alreadyOffThread) runCatching(run) else {
            Thread({ runCatching(run) }, "rawlens-perf-audit").apply { isDaemon = true; start() }
        }
    }

    /** Storage the app leaves behind (cache, files, session logs). */
    fun auditStorage(context: Context) {
        val app = context.applicationContext
        Thread({
            runCatching {
                val cache = dirSize(app.cacheDir)
                val files = dirSize(app.filesDir)
                val ext = app.getExternalFilesDir(null)?.let(::dirSize) ?: (0L to 0)
                Log.i(TAG, "PERF[storage] cache=${mb(cache.first)}MB(${cache.second} files) " +
                    "files=${mb(files.first)}MB(${files.second} files) " +
                    "ext=${mb(ext.first)}MB(${ext.second} files)")
            }
        }, "rawlens-perf-storage").apply { isDaemon = true; start() }
    }

    /** Logs the trim level; the probe itself releases nothing (policy stays in UI code). */
    fun noteTrimMemory(level: Int) {
        Log.i(TAG, "PERF[trim] ${trimName(level)}")
    }

    private fun heartbeat() {
        val now = SystemClock.elapsedRealtime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val selfLine = selfCpuLine(now, cores)
        val topLine = topThreadsLine()
        val thermalLine = thermalLine()
        val gcLine = gcLine()
        val mainLine = mainLine(now)
        warnStuckServices(now)
        Log.i(TAG, "PERF[heartbeat] state=${if (foreground) "fg" else "bg"} " +
            "$selfLine $topLine $thermalLine $gcLine $mainLine")
    }

    private var lastMainLineMs = 0L

    private fun mainLine(now: Long): String {
        val count = mainDispatchCount.get()
        val ms = mainDispatchMs.get()
        val elapsed = now - lastMainLineMs
        val line = if (lastMainLineMs > 0 && elapsed > 0) {
            val busy = mainBusyPercent(ms - lastMainMs, elapsed)
            val offenders = slowOffenders.entries
                .sortedByDescending { it.value[1] }.take(3)
                .joinToString(",") { "${it.key}x${it.value[0]}/${it.value[1]}ms" }
            slowOffenders.clear()
            "main=${"%.1f".format(busy)}@${count - lastMainCount}msg" +
                (if (offenders.isNotEmpty()) " slowTop=[$offenders]" else "")
        } else "main=warming"
        lastMainLineMs = now
        lastMainCount = count
        lastMainMs = ms
        return line
    }

    private fun selfCpuLine(now: Long, cores: Int): String {
        val ticks = runCatching {
            parseStatTicks(File("/proc/self/stat").readText())
        }.getOrNull() ?: return "cpuSelf=?"
        val line = if (lastSelfTicks >= 0 && now > lastHeartbeatMs) {
            val pct = cpuPercent(ticks - lastSelfTicks, now - lastHeartbeatMs, cores)
            "cpuSelf=${"%.1f".format(pct)}%"
        } else "cpuSelf=warming"
        lastSelfTicks = ticks
        lastHeartbeatMs = now
        return line
    }

    private fun topThreadsLine(): String {
        val cur = threadTicks()
        val top = topByDelta(lastThreadTicks, cur, 5)
        lastThreadTicks = cur
        if (top.isEmpty()) return "threads=${cur.size} top=—"
        val total = top.sumOf { it.third }.coerceAtLeast(1)
        return "threads=${cur.size} top=" + top.joinToString(",") {
            "${it.second}:${it.third * 100 / total}%"
        }
    }

    private fun threadTicks(): Map<Long, Pair<String, Long>> {
        val out = HashMap<Long, Pair<String, Long>>()
        runCatching {
            File("/proc/self/task").listFiles()?.forEach { task ->
                val tid = task.name.toLongOrNull() ?: return@forEach
                val ticks = runCatching {
                    parseStatTicks(File(task, "stat").readText())
                }.getOrNull() ?: return@forEach
                val name = runCatching {
                    File(task, "comm").readText().trim().take(32)
                }.getOrDefault("?")
                out[tid] = name to ticks
            }
        }
        return out
    }

    private fun primeCpuBaseline() {
        runCatching { parseStatTicks(File("/proc/self/stat").readText()) }?.getOrNull()?.let {
            lastSelfTicks = it
            lastHeartbeatMs = SystemClock.elapsedRealtime()
        }
        lastThreadTicks = threadTicks()
    }

    private fun thermalLine(): String {
        val status = runCatching {
            appContext?.getSystemService(PowerManager::class.java)?.currentThermalStatus
        }.getOrNull() ?: return "thermal=?"
        if (status != lastThermal) {
            if (lastThermal != Int.MIN_VALUE) {
                Log.i(TAG, "PERF[thermal] $lastThermal -> $status")
            }
            lastThermal = status
        }
        return "thermal=$status"
    }

    private fun gcLine(): String {
        val count = runtimeStat("art.gc.gc-count")
        val timeMs = runtimeStat("art.gc.gc-time")
        if (count == null && timeMs == null) return "gc=?"
        val line = if (lastGcCount >= 0 && count != null) {
            "gc=+${count - lastGcCount}" +
                (if (timeMs != null && lastGcTimeMs >= 0) "/+${timeMs - lastGcTimeMs}ms" else "")
        } else "gc=warming"
        if (count != null) lastGcCount = count
        if (timeMs != null) lastGcTimeMs = timeMs
        return line
    }

    private fun primeGcBaseline() {
        runtimeStat("art.gc.gc-count")?.let { lastGcCount = it }
        runtimeStat("art.gc.gc-time")?.let { lastGcTimeMs = it }
    }

    private fun runtimeStat(key: String): Long? = runCatching {
        Debug.getRuntimeStat(key)?.toLongOrNull()
    }.getOrNull()

    private fun warnStuckServices(now: Long) {
        liveServices.forEach { (name, started) ->
            if (now - started >= STUCK_SERVICE_MS && stuckWarned.putIfAbsent(name, true) == null) {
                Log.w(TAG, "PERF[service-stuck] $name alive ${(now - started) / 1000}s and counting")
            }
        }
    }

    private fun liveThreadNames(): List<String> {
        val threads = runCatching {
            val active = Thread.activeCount().coerceAtLeast(8) + 8
            Array(active) { Thread() }.let { buf ->
                val n = Thread.enumerate(buf)
                buf.take(n).mapNotNull { it.name?.takeUnless { name -> name.isEmpty() } }
            }
        }.getOrDefault(emptyList())
        // Framework noise out; app + worker threads stay (sorted for stable diffing).
        return threads.filterNot {
            it.startsWith("binder:") || it == "Jit thread pool" || it.startsWith("RenderThread") ||
                it == "hwuiTask1" || it == "GPU completion" || it == "Profile Saver"
        }.sorted()
    }

    private fun dirSize(root: File?): Pair<Long, Int> {
        if (root == null || !root.isDirectory) return 0L to 0
        var bytes = 0L
        var files = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        // Bounded: stats must never cost more than the footprint they report.
        while (stack.isNotEmpty() && files < 4000) {
            val dir = stack.removeLast()
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (kid in kids) {
                if (files >= 4000) break
                if (kid.isDirectory) stack.add(kid)
                else {
                    files++
                    bytes += runCatching { kid.length() }.getOrDefault(0L)
                }
            }
        }
        return bytes to files
    }

    private fun mb(bytes: Long): Long = bytes / (1024L * 1024L)

    /** Test-only reset; production state is process-lifetime. */
    internal fun resetForTest() {
        liveServices.clear()
        stuckWarned.clear()
    }
}
