// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Debug
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight production diagnostics for memory/resource lifetime regressions.
 *
 * This does not pretend to prove that a Java object is leaked. Instead it emits enough stable
 * process + lifetime data to Logcat to spot the common failure modes in this camera pipeline:
 * monotonically growing PSS/native heap, GL textures that never return to zero, programs or EGL
 * contexts surviving shutdown, malloc-backed native buffers outliving a merge, camera Images
 * pinned by GPU borrows, and Vulkan handles created more than once.
 *
 * Every `MEM[...]` line also carries monotonic event counters (`amaze= hdr= still= vf= bgu=
 * vid= dlog=`), so PSS growth between two samples is attributable to the module that ran
 * between them. The periodic sampler (see [startPeriodic]) plus the per-module start/stop
 * probes mean a plain session log is enough to triage a suspected leak — no repro build.
 *
 * Filter with:  adb logcat -s RawLensMemory RawLensDevelop
 */
object MemoryLeakDiagnostics {
    private const val TAG = "RawLensMemory"
    private const val HISTORY = 6
    private const val PSS_GROWTH_WARNING_KB = 32 * 1024
    private const val NATIVE_GROWTH_WARNING_BYTES = 24L * 1024L * 1024L
    private const val GL_GROWTH_WARNING_BYTES = 16L * 1024L * 1024L
    private const val IMAGE_GROWTH_WARNING = 2L
    const val PERIODIC_INTERVAL_MS = 30_000L

    private val liveTextureCount = AtomicLong()
    private val liveTextureBytes = AtomicLong()
    private val liveProgramCount = AtomicLong()
    private val liveFramebufferCount = AtomicLong()
    private val liveEglContextCount = AtomicLong()
    private val liveNativeBufferCount = AtomicLong()
    private val liveNativeBufferBytes = AtomicLong()
    private val liveVulkanHandleCount = AtomicLong()

    /** Monotonic per-boot event counters; printed on every sample for attribution. */
    private val amazeCaptures = AtomicLong()
    private val hdrMerges = AtomicLong()
    private val stillCaptures = AtomicLong()
    private val vfFrames = AtomicLong()
    private val bguVfFrames = AtomicLong()
    private val rawVideoFrames = AtomicLong()
    private val directLogFrames = AtomicLong()

    private val histories = ConcurrentHashMap<String, ArrayDeque<Sample>>()
    @Volatile private var periodicThread: Thread? = null

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

    /** malloc-backed native buffers (HDR+ packed frames/merge output). */
    fun nativeBufferAllocated(bytes: Long) {
        liveNativeBufferCount.incrementAndGet()
        liveNativeBufferBytes.addAndGet(bytes)
    }

    fun nativeBufferReleased(bytes: Long) {
        liveNativeBufferCount.decrementAndGet()
        liveNativeBufferBytes.addAndGet(-bytes)
    }

    /** Outstanding malloc-backed bytes; HDR+ merges must leave this unchanged. */
    fun nativeBufferOutstandingBytes(): Long = liveNativeBufferBytes.get()

    fun vulkanHandleAllocated() { liveVulkanHandleCount.incrementAndGet() }
    fun vulkanHandleReleased() { liveVulkanHandleCount.decrementAndGet() }

    fun amazeCapture() { amazeCaptures.incrementAndGet() }
    fun hdrMerge() { hdrMerges.incrementAndGet() }
    fun stillCapture() { stillCaptures.incrementAndGet() }
    fun vfFrame() { vfFrames.incrementAndGet() }
    fun bguVfFrame() { bguVfFrames.incrementAndGet() }
    fun rawVideoFrame() { rawVideoFrames.incrementAndGet() }
    fun directLogFrame() { directLogFrames.incrementAndGet() }

    /**
     * Whole-app heartbeat: one `MEM[periodic]` line every [PERIODIC_INTERVAL_MS] on a daemon
     * thread. Started once from MainActivity; safe to call repeatedly.
     */
    @Synchronized
    fun startPeriodic(label: String = "periodic", intervalMs: Long = PERIODIC_INTERVAL_MS) {
        if (periodicThread != null) return
        periodicThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { sample(label) }
            }
        }, "rawlens-mem-periodic").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopPeriodic() {
        periodicThread?.interrupt()
        periodicThread = null
    }

    /**
     * Off-thread [sample] for UI-thread call sites. PSS/`/proc` reads on the main thread are
     * a suspected jank source, so lifecycle and record-start probes must use this.
     */
    fun sampleAsync(label: String, extra: String? = null) {
        Thread({ runCatching { sample(label, extra = extra) } }, "rawlens-mem-diag").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * One parseable summary block: live counters plus the newest sample per label. Cheap
     * (no PSS reads), so it is safe to call on the UI thread when backgrounding the app.
     */
    fun dumpReport() {
        val counters = countersForTest()
        Log.i(TAG, "REPORT live glTex=${counters.textureCount}/${mb(counters.textureBytes)}MB " +
            "glProg=${counters.programCount} fbo=${counters.framebufferCount} " +
            "egl=${counters.eglContextCount} natBuf=${counters.nativeBufferCount}/" +
            "${mb(counters.nativeBufferBytes)}MB vk=${counters.vulkanHandles} " +
            "img=${RawImageOwnership.liveCount()}/${RawImageOwnership.gpuOnlyCount()}gpu " +
            "amaze=${counters.amaze} hdr=${counters.hdr} still=${counters.still} " +
            "vf=${counters.vf} bgu=${counters.bgu} vid=${counters.rawVideo} dlog=${counters.directLog}")
        histories.forEach { (label, history) ->
            val last = synchronized(history) { history.lastOrNull() } ?: return@forEach
            val size = synchronized(history) { history.size }
            Log.i(TAG, "REPORT[$label] n=$size lastPss=${mbFromKb(last.totalPssKb)}MB " +
                "nativeAlloc=${mb(last.nativeAllocatedBytes)}MB " +
                "gfx=${if (last.graphicsKb >= 0) "${mb(last.graphicsKb * 1024)}MB" else "?"} " +
                "natBuf=${mb(last.nativeBufferBytes)}MB img=${last.imageLive}")
        }
    }

    fun sample(
        label: String,
        expectGlReleased: Boolean = false,
        expectNativeReleased: Boolean = false,
        extra: String? = null
    ) {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeAllocated = Debug.getNativeHeapAllocatedSize()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val rss = readProcStatus()
        val graphicsKb = graphicsKb(info.getMemoryStats())
        val counters = countersForTest()
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
            graphicsKb = graphicsKb,
            textureCount = counters.textureCount,
            textureBytes = counters.textureBytes,
            programCount = counters.programCount,
            framebufferCount = counters.framebufferCount,
            eglContextCount = counters.eglContextCount,
            nativeBufferCount = counters.nativeBufferCount,
            nativeBufferBytes = counters.nativeBufferBytes,
            vulkanHandles = counters.vulkanHandles,
            imageLive = RawImageOwnership.liveCount().toLong(),
            imageGpuPinned = RawImageOwnership.gpuOnlyCount().toLong(),
            amaze = counters.amaze,
            hdr = counters.hdr,
            still = counters.still,
            vf = counters.vf,
            bgu = counters.bgu,
            rawVideo = counters.rawVideo,
            directLog = counters.directLog
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
            // Graphics (gralloc/Vulkan-driver/EGL) is the attribution key for
            // otherPss: it separates camera/Vulkan graphics from the rest.
            if (sample.graphicsKb >= 0) {
                append("gfx=").append(mb(sample.graphicsKb * 1024)).append("MB ")
            }
            if (sample.rssKb >= 0) {
                append("rss=").append(mbFromKb(sample.rssKb)).append("MB ")
                append("rssAnon=").append(mbFromKb(sample.rssAnonKb)).append("MB ")
            }
            append("glTex=").append(sample.textureCount).append('/')
                .append(mb(sample.textureBytes)).append("MB ")
            append("glProg=").append(sample.programCount).append(' ')
            append("fbo=").append(sample.framebufferCount).append(' ')
            append("egl=").append(sample.eglContextCount).append(' ')
            append("natBuf=").append(sample.nativeBufferCount).append('/')
                .append(mb(sample.nativeBufferBytes)).append("MB ")
            append("vk=").append(sample.vulkanHandles).append(' ')
            append("img=").append(sample.imageLive).append('/')
                .append(sample.imageGpuPinned).append("gpu ")
            append("amaze=").append(sample.amaze).append(' ')
            append("hdr=").append(sample.hdr).append(' ')
            append("still=").append(sample.still).append(' ')
            append("vf=").append(sample.vf).append(' ')
            append("bgu=").append(sample.bgu).append(' ')
            append("vid=").append(sample.rawVideo).append(' ')
            append("dlog=").append(sample.directLog)
            if (!extra.isNullOrEmpty()) append(' ').append(extra)
        })

        if (sample.textureCount < 0 || sample.textureBytes < 0 || sample.programCount < 0 ||
            sample.framebufferCount < 0 || sample.eglContextCount < 0 ||
            sample.nativeBufferCount < 0 || sample.nativeBufferBytes < 0 ||
            sample.vulkanHandles < 0) {
            Log.e(TAG, "RESOURCE_ACCOUNTING_ERROR[$label] negative lifetime counter")
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

        if (expectNativeReleased && (sample.nativeBufferCount != 0L || sample.nativeBufferBytes != 0L)) {
            Log.w(
                TAG,
                "POTENTIAL_NATIVE_LEAK[$label] malloc buffers remain after shutdown: " +
                    "buffers=${sample.nativeBufferCount}/${mb(sample.nativeBufferBytes)}MB"
            )
        }

        val history = histories.getOrPut(label) { ArrayDeque(HISTORY) }
        synchronized(history) {
            if (history.size == HISTORY) history.removeFirst()
            history.addLast(sample)
            if (history.size == HISTORY) {
                trendWarning(history.map { it.trendPoint() })?.let { detail ->
                    Log.w(
                        TAG,
                        "POTENTIAL_MEMORY_LEAK[$label] over last $HISTORY samples: $detail. " +
                            "Verify after repeating identical captures."
                    )
                }
            }
        }
    }

    /**
     * Pure trend verdict over the oldest-to-newest points of one label. Returns the warning
     * detail, or null when the window looks flat. Unit-tested; [sample] only formats and logs.
     */
    internal fun trendWarning(points: List<TrendPoint>): String? {
        if (points.size < HISTORY) return null
        val values = points.takeLast(HISTORY)
        fun rising(get: (TrendPoint) -> Long): Boolean =
            values.zipWithNext().count { (a, b) -> get(b) >= get(a) } >= HISTORY - 2
        val pssGrowthKb = values.last().pssKb - values.first().pssKb
        val nativeGrowth = values.last().nativeBytes - values.first().nativeBytes
        val glGrowth = values.last().textureBytes - values.first().textureBytes
        val bufGrowth = values.last().nativeBufBytes - values.first().nativeBufBytes
        val imageGrowth = values.last().images - values.first().images
        val firing = (rising { it.pssKb.toLong() } && pssGrowthKb >= PSS_GROWTH_WARNING_KB) ||
            (rising { it.nativeBytes } && nativeGrowth >= NATIVE_GROWTH_WARNING_BYTES) ||
            glGrowth > GL_GROWTH_WARNING_BYTES ||
            bufGrowth > NATIVE_GROWTH_WARNING_BYTES ||
            (rising { it.images } && imageGrowth >= IMAGE_GROWTH_WARNING)
        if (!firing) return null
        return "pssDelta=${mbFromKb(pssGrowthKb.toInt())}MB nativeDelta=${mb(nativeGrowth)}MB " +
            "glTextureDelta=${mb(glGrowth)}MB natBufDelta=${mb(bufGrowth)}MB " +
            "imageDelta=$imageGrowth"
    }

    /**
     * Graphics PSS in KB from [Debug.MemoryInfo.getMemoryStats] (same snapshot,
     * no extra I/O), or -1 when the device omits the summary key. Unit-tested.
     */
    internal fun graphicsKb(stats: Map<String, String>): Long =
        stats["summary.graphics"]?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

    internal data class TrendPoint(
        val pssKb: Long,
        val nativeBytes: Long,
        val textureBytes: Long,
        val nativeBufBytes: Long,
        val images: Long
    )

    internal data class CounterSnapshot(
        val textureCount: Long,
        val textureBytes: Long,
        val programCount: Long,
        val framebufferCount: Long,
        val eglContextCount: Long,
        val nativeBufferCount: Long,
        val nativeBufferBytes: Long,
        val vulkanHandles: Long,
        val amaze: Long,
        val hdr: Long,
        val still: Long,
        val vf: Long,
        val bgu: Long,
        val rawVideo: Long,
        val directLog: Long
    )

    internal fun countersForTest(): CounterSnapshot = CounterSnapshot(
        textureCount = liveTextureCount.get(),
        textureBytes = liveTextureBytes.get(),
        programCount = liveProgramCount.get(),
        framebufferCount = liveFramebufferCount.get(),
        eglContextCount = liveEglContextCount.get(),
        nativeBufferCount = liveNativeBufferCount.get(),
        nativeBufferBytes = liveNativeBufferBytes.get(),
        vulkanHandles = liveVulkanHandleCount.get(),
        amaze = amazeCaptures.get(),
        hdr = hdrMerges.get(),
        still = stillCaptures.get(),
        vf = vfFrames.get(),
        bgu = bguVfFrames.get(),
        rawVideo = rawVideoFrames.get(),
        directLog = directLogFrames.get()
    )

    /** Test-only reset; production counters are monotonic per boot. */
    internal fun resetForTest() {
        liveTextureCount.set(0)
        liveTextureBytes.set(0)
        liveProgramCount.set(0)
        liveFramebufferCount.set(0)
        liveEglContextCount.set(0)
        liveNativeBufferCount.set(0)
        liveNativeBufferBytes.set(0)
        liveVulkanHandleCount.set(0)
        amazeCaptures.set(0)
        hdrMerges.set(0)
        stillCaptures.set(0)
        vfFrames.set(0)
        bguVfFrames.set(0)
        rawVideoFrames.set(0)
        directLogFrames.set(0)
        histories.clear()
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
        val graphicsKb: Long,
        val textureCount: Long,
        val textureBytes: Long,
        val programCount: Long,
        val framebufferCount: Long,
        val eglContextCount: Long,
        val nativeBufferCount: Long,
        val nativeBufferBytes: Long,
        val vulkanHandles: Long,
        val imageLive: Long,
        val imageGpuPinned: Long,
        val amaze: Long,
        val hdr: Long,
        val still: Long,
        val vf: Long,
        val bgu: Long,
        val rawVideo: Long,
        val directLog: Long
    ) {
        fun trendPoint() = TrendPoint(
            pssKb = totalPssKb.toLong(),
            nativeBytes = nativeAllocatedBytes,
            textureBytes = textureBytes,
            nativeBufBytes = nativeBufferBytes,
            images = imageLive
        )
    }
}
