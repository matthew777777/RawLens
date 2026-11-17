// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log

/**
 * Android Dynamic Performance Framework (ADPF) hint session wrapper.
 *
 * Advisory only: the OS keeps full power/thermal control and may ignore it.
 * Every call is a no-op below API 31, when the service is missing, or when the
 * session is rejected. A session binds thread ids of THIS process; create it on
 * (or with the tid of) the thread that does the work.
 *
 * - Per-frame loops: [create] with the frame budget, then [report] each frame
 *   (or wrap with [measure]). Over-budget reports make the OS raise clocks /
 *   migrate to big cores before frames drop; under-budget lets it relax.
 * - One-shot bursts (merges): [boosted] reports early busy time so clocks
 *   ramp at the start instead of after the governor's ramp.
 * - Background batch: [preferPowerEfficiency] (API 35) for non-urgent work.
 */
internal class AdpfHint private constructor(private val session: PerformanceHintManager.Session?) : AutoCloseable {
    val active: Boolean get() = session != null

    fun report(durationNs: Long) {
        if (durationNs > 0) runCatching { session?.reportActualWorkDuration(durationNs) }
    }

    inline fun <T> measure(block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { report(System.nanoTime() - t0) }
    }

    fun updateTarget(targetNs: Long) {
        if (targetNs > 0) runCatching { session?.updateTargetWorkDuration(targetNs) }
    }

    fun preferPowerEfficiency(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) runCatching { session?.setPreferPowerEfficiency(enabled) }
    }

    override fun close() { runCatching { session?.close() } }

    companion object {
        private const val TAG = "AdpfHint"
        private val NONE = AdpfHint(null)

        /** A/B switch: `adb shell setprop debug.rawlens.adpf 0` disables every session (read per create). */
        private fun enabled(): Boolean = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.rawlens.adpf") as String != "0"
        }.getOrDefault(true)

        fun frameNs(fps: Int): Long = 1_000_000_000L / fps.coerceIn(1, 480)

        /** Session for the calling thread (plus optional extra tids of this process). */
        fun create(context: Context?, targetNs: Long, extraTids: IntArray = IntArray(0)): AdpfHint {
            if (Build.VERSION.SDK_INT < 31 || context == null || targetNs <= 0 || !enabled()) return NONE
            val tids = (intArrayOf(Process.myTid()) + extraTids).distinct().toIntArray()
            val s = runCatching {
                context.applicationContext.getSystemService(PerformanceHintManager::class.java)
                    ?.takeIf { it.preferredUpdateRateNanos > 0 }
                    ?.createHintSession(tids, targetNs)
            }.onFailure { Log.w(TAG, "createHintSession failed: ${it.message}") }.getOrNull()
            return if (s == null) NONE else AdpfHint(s)
        }

        private const val RAMP_TICK_MS = 100L
        private const val RAMP_TICKS = 5

        /**
         * One-shot heavy job (a burst merge) on this thread. The public SDK has
         * no CPU_LOAD_UP, so for the first RAMP_TICKS x 100 ms a ticker reports
         * the thread's real busy time against an 80 % budget: clocks ramp at the
         * start instead of after the governor's ramp. After that it goes quiet,
         * so no boost is held for the whole merge and the OS's own policy applies.
         */
        fun <T> boosted(context: Context?, block: () -> T): T {
            val tickNs = RAMP_TICK_MS * 1_000_000L
            val h = create(context, tickNs * 4 / 5)
            if (!h.active) return block()
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val ticker = Thread({
                var n = 0
                while (!done.get() && n++ < RAMP_TICKS) {
                    try { Thread.sleep(RAMP_TICK_MS) } catch (_: InterruptedException) { break }
                    if (!done.get()) h.report(tickNs)
                }
            }, "AdpfRamp").apply { isDaemon = true; start() }
            try { return block() } finally {
                done.set(true); ticker.interrupt(); h.close()
            }
        }
    }
}

/**
 * Per-frame hint for a loop whose thread may change (camera callbacks, GL
 * worker restarts): the session is created lazily on the calling thread and
 * rebuilt if a different thread reports. Not thread-safe; one loop per instance.
 */
internal class AdpfFrameHint(private val context: Context?, private val targetNs: Long) : AutoCloseable {
    private var hint: AdpfHint? = null
    private var tid = 0

    fun current(): AdpfHint {
        val me = Process.myTid()
        val h = hint
        if (h != null && tid == me) return h
        h?.close()
        tid = me
        return AdpfHint.create(context, targetNs).also { hint = it }
    }

    inline fun <T> measure(block: () -> T): T = current().measure(block)

    override fun close() { hint?.close(); hint = null; tid = 0 }
}
