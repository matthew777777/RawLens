// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Debug
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight production diagnostics for memory/resource lifetime regressions.
 *
 * This does not pretend to prove that a Java object is leaked. Instead it emits enough stable
 * process + GL lifetime data to Logcat to spot the common failure modes in this camera pipeline:
 * monotonically growing PSS/native heap, GL textures that never return to zero, programs or EGL
 * contexts surviving shutdown, and full-frame texture accounting that grows between captures.
 *
 * Filter with:  adb logcat -s RawLensMemory RawLensDevelop
 */
object MemoryLeakDiagnostics {
    private const val TAG = "RawLensMemory"
    private const val HISTORY = 6
    private const val PSS_GROWTH_WARNING_KB = 32 * 1024
    private const val NATIVE_GROWTH_WARNING_BYTES = 24L * 1024L * 1024L

    private val liveTextureCount = AtomicLong()
    private val liveTextureBytes = AtomicLong()
    private val liveProgramCount = AtomicLong()
    private val liveFramebufferCount = AtomicLong()
    private val liveEglContextCount = AtomicLong()
    private val histories = ConcurrentHashMap<String, ArrayDeque<Sample>>()

    fun glTextureAllocated(bytes: Long) {
        liveTextureCount.incrementAndGet()
        liveTextureBytes.addAndGet(bytes)
    }

    fun glTextureReleased(bytes: Long) {
        liveTextureCount.decrementAndGet()
        liveTextureBytes.addAndGet(-bytes)
    }

    fun glProgramAllocated() { liveProgramCount.incrementAndGet() }
    fun glProgramReleased() { liveProgramCount.decrementAndGet() }
    fun glFramebufferAllocated() { liveFramebufferCount.incrementAndGet() }
    fun glFramebufferReleased() { liveFramebufferCount.decrementAndGet() }
    fun eglContextAllocated() { liveEglContextCount.incrementAndGet() }
    fun eglContextReleased() { liveEglContextCount.decrementAndGet() }

    fun sample(label: String, expectGlReleased: Boolean = false) {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeAllocated = Debug.getNativeHeapAllocatedSize()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val rss = readProcStatus()
        val sample = Sample(
            totalPssKb = info.totalPss,
            dalvikPssKb = info.dalvikPss,
            nativePssKb = info.nativePss,
            otherPssKb = info.otherPss,
            javaUsedBytes = javaUsed,
            javaCommittedBytes = runtime.totalMemory(),
            javaMaxBytes = runtime.maxMemory(),
            nativeAllocatedBytes = nativeAllocated,
            rssKb = rss.first,
            rssAnonKb = rss.second,
            textureCount = liveTextureCount.get(),
            textureBytes = liveTextureBytes.get(),
            programCount = liveProgramCount.get(),
            framebufferCount = liveFramebufferCount.get(),
            eglContextCount = liveEglContextCount.get()
        )

        Log.i(TAG, buildString {
            append("MEM[").append(label).append("] ")
            append("pss=").append(mbFromKb(sample.totalPssKb)).append("MB ")
            append("dalvikPss=").append(mbFromKb(sample.dalvikPssKb)).append("MB ")
            append("nativePss=").append(mbFromKb(sample.nativePssKb)).append("MB ")
            append("otherPss=").append(mbFromKb(sample.otherPssKb)).append("MB ")
            append("javaUsed=").append(mb(sample.javaUsedBytes)).append("MB/")
                .append(mb(sample.javaMaxBytes)).append("MB ")
            append("nativeAlloc=").append(mb(sample.nativeAllocatedBytes)).append("MB ")
            if (sample.rssKb >= 0) {
                append("rss=").append(mbFromKb(sample.rssKb)).append("MB ")
                append("rssAnon=").append(mbFromKb(sample.rssAnonKb)).append("MB ")
            }
            append("glTex=").append(sample.textureCount).append('/')
                .append(mb(sample.textureBytes)).append("MB ")
            append("glProg=").append(sample.programCount).append(' ')
            append("fbo=").append(sample.framebufferCount).append(' ')
            append("egl=").append(sample.eglContextCount)
        })

        if (sample.textureCount < 0 || sample.textureBytes < 0 || sample.programCount < 0 ||
            sample.framebufferCount < 0 || sample.eglContextCount < 0) {
            Log.e(TAG, "RESOURCE_ACCOUNTING_ERROR[$label] negative GL/EGL lifetime counter")
        }

        if (expectGlReleased && (sample.textureCount != 0L || sample.textureBytes != 0L ||
                sample.programCount != 0L || sample.framebufferCount != 0L ||
                sample.eglContextCount != 0L)) {
            Log.w(
                TAG,
                "POTENTIAL_GL_LEAK[$label] resources remain after shutdown: " +
                    "textures=${sample.textureCount}/${mb(sample.textureBytes)}MB " +
                    "programs=${sample.programCount} fbo=${sample.framebufferCount} " +
                    "egl=${sample.eglContextCount}"
            )
        }

        val history = histories.getOrPut(label) { ArrayDeque(HISTORY) }
        synchronized(history) {
            if (history.size == HISTORY) history.removeFirst()
            history.addLast(sample)
            if (history.size == HISTORY) evaluateTrend(label, history)
        }
    }

    private fun evaluateTrend(label: String, history: ArrayDeque<Sample>) {
        val values = history.toList()
        val pssMostlyRising = values.zipWithNext().count { (a, b) -> b.totalPssKb >= a.totalPssKb } >= HISTORY - 2
        val nativeMostlyRising = values.zipWithNext().count {
            (a, b) -> b.nativeAllocatedBytes >= a.nativeAllocatedBytes
        } >= HISTORY - 2
        val pssGrowth = values.last().totalPssKb - values.first().totalPssKb
        val nativeGrowth = values.last().nativeAllocatedBytes - values.first().nativeAllocatedBytes
        val glGrowth = values.last().textureBytes - values.first().textureBytes

        if ((pssMostlyRising && pssGrowth >= PSS_GROWTH_WARNING_KB) ||
            (nativeMostlyRising && nativeGrowth >= NATIVE_GROWTH_WARNING_BYTES) ||
            glGrowth > 16L * 1024L * 1024L) {
            Log.w(
                TAG,
                "POTENTIAL_MEMORY_LEAK[$label] over last $HISTORY samples: " +
                    "pssDelta=${mbFromKb(pssGrowth)}MB nativeDelta=${mb(nativeGrowth)}MB " +
                    "glTextureDelta=${mb(glGrowth)}MB. Verify after repeating identical captures."
            )
        }
    }

    private fun readProcStatus(): Pair<Int, Int> = runCatching {
        var rss = -1
        var anon = -1
        File("/proc/self/status").forEachLine { line ->
            when {
                line.startsWith("VmRSS:") -> rss = line.kbValue()
                line.startsWith("RssAnon:") -> anon = line.kbValue()
            }
        }
        rss to anon
    }.getOrDefault(-1 to -1)

    private fun String.kbValue(): Int = trim().split(Regex("\\s+"))[1].toIntOrNull() ?: -1
    private fun mb(bytes: Long): Long = bytes / (1024L * 1024L)
    private fun mbFromKb(kb: Int): Long = kb.toLong() / 1024L

    private data class Sample(
        val totalPssKb: Int,
        val dalvikPssKb: Int,
        val nativePssKb: Int,
        val otherPssKb: Int,
        val javaUsedBytes: Long,
        val javaCommittedBytes: Long,
        val javaMaxBytes: Long,
        val nativeAllocatedBytes: Long,
        val rssKb: Int,
        val rssAnonKb: Int,
        val textureCount: Long,
        val textureBytes: Long,
        val programCount: Long,
        val framebufferCount: Long,
        val eglContextCount: Long
    )
}
