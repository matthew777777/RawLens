// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * From-scratch BGU viewfinder engine: RAW_SENSOR only, one render path, no
 * tier fallbacks (failure degrades to the legacy engine at the controller).
 *
 * Per frame, on the camera thread: sparse point-sample of the low-res quads
 * (~12k reads from the already-mapped Bayer plane) plus a synchronous Vulkan
 * superpixel dispatch into an engine-owned export (compute must finish before
 * the Image closes, like every zero-copy consumer). A fit worker then runs
 * the full look + bilateral fit on the tiny pair; a render worker imports the
 * export as the hi-res guide texture, uploads the 12 KB grid, slices, and
 * presents vsync-paced. No GPU->CPU readback anywhere; CPU and GPU overlap.
 *
 * Halide filters live in rawLensBgu ([BguLook], [BguFit]); the Vulkan
 * superpixel ([VfVulkan]) and EGL import bridge ([VfEglImport]) are reused as
 * sealed HAL-facing stages. Everything else — threads, queues, geometry,
 * sampling, slicing, stats — is new in this file and its [BguGeometry],
 * [BguSample], [BguSlice] companions.
 */
class BguViewfinder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, VfEngine {

    // ------------------------------------------------------------------ seam

    @Volatile private var targetLongEdge = 1080
    @Volatile private var recordMode = false
    @Volatile private var powerSave = false
    @Volatile private var renderJpeg = true
    @Volatile private var evStrength = 1f
    @Volatile private var agx = floatArrayOf(1f, 1f, 1f, 0f, 10f, 6.5f, 0f, 1f)
    @Volatile private var frameIntervalNs = 1_000_000_000L / 30
    override var onStarvation: (() -> Unit)? = null

    override fun setTargetLongEdge(longEdge: Int) {
        targetLongEdge = longEdge.coerceIn(240, 2160)
    }

    override fun setRecordMode(active: Boolean) {
        recordMode = active
    }

    override fun setPowerSave(active: Boolean) {
        powerSave = active
    }

    override fun setRenderJpeg(jpeg: Boolean) {
        renderJpeg = jpeg
    }

    override fun setPreviewExposureStrength(strength: Float) {
        evStrength = strength
    }

    override fun setAgx(settings: JpegOutputSettings) {
        val r = settings.resolvedForPlatform()
        agx = floatArrayOf(
            r.agxContrast, r.agxSaturation, r.agxPurityBoost, r.agxHuePreservation,
            r.agxShadowEv, r.agxHighlightEv, r.agxGamutCompression, r.highlightShoulder
        )
    }

    override fun setEngineMode(mode: VfEngineMode) {
        // The overlay-tap BGU_CPU override pins the NEON guide path (the
        // manual form of the automatic CPU fallback); every other mode
        // releases the pin. A sticky user override: sessions never reset it.
        val forced = mode == VfEngineMode.BGU_CPU
        if (forced != cpuForced) {
            cpuForced = forced
            Log.i(TAG, "BGU CPU guide override ${if (forced) "engaged" else "released"}")
        }
    }

    override fun expectFrameInterval(nanos: Long) {
        if (nanos > 0) frameIntervalNs = nanos
    }

    // ------------------------------------------------------------ job pipes

    private data class LowJob(
        val seq: Long,
        val quad: ByteArray, val lowW: Int, val lowH: Int,
        val lens: FloatArray, val lensCols: Int, val lensRows: Int,
        val wb: FloatArray, val ccm: FloatArray, val aces: FloatArray, val white: FloatArray,
        val fparams: FloatArray, val iparams: IntArray
    )

    private data class RenderJob(
        val seq: Long, val slot: Int, val geo: BguGuideGeo,
        val lowW: Int, val lowH: Int, val rotation: Int, val mirrored: Boolean,
        // True when the CPU fallback produced this job's guide: render
        // uploads the job's cpuGuides bytes instead of binding its EGL image.
        val cpu: Boolean
    )

    private data class GridJob(val seq: Long, val grid: BguGrid)

    private fun <T : Any> ArrayBlockingQueue<T>.offerOrDrop(job: T) {
        var offered = offer(job)
        while (!offered) {
            poll()
            offered = offer(job)
        }
    }

    private val fitQueue = ArrayBlockingQueue<Any>(2)
    private val fitBuffers = BguFitBuffers()
    private val renderQueue = ArrayBlockingQueue<Any>(4)
    private object Poison

    @Volatile private var latestGrid: GridJob? = null

    // ------------------------------------------------------- mutable state

    @Volatile private var disposed = false
    @Volatile private var fatal: String? = null
    @Volatile private var surfaceAlive = false

    @Volatile private var lastOfferMs = 0L
    @Volatile private var firstOfferMs = 0L
    private var seq = 0L
    private var busyStreak = 0
    private var failStreak = 0
    private var nullBufferStreak = 0
    private var lookFailStreak = 0
    private var appliedEv = 0f
    private var evCounter = 0
    private var lastEvTarget = 0f

    private data class GeoKey(val imgW: Int, val imgH: Int, val crop: String, val longEdge: Int)
    private var geoCacheKey: GeoKey? = null
    private var geoCache: BguGuideGeo? = null

    private data class LensCache(
        val key: String, val gains: FloatArray, val cols: Int, val rows: Int, val atMs: Long
    )
    private var lensCache: LensCache? = null

    // Dual-illuminant statics (6 matrices + 2 illuminants) never change per
    // camera; re-fetching them per frame is pure waste (8 gets + allocs).
    // Cached by CameraCharacteristics identity, like lensCache (offer only).
    private data class CalibStatics(
        val static: List<DoubleArray?>, val ill1: Int?, val ill2: Int?
    )
    private var calibStaticsCam: CameraCharacteristics? = null
    private var calibStatics: CalibStatics? = null

    private val calibratedCache = VfCalibratedColorCache()
    private var vulkanReady = false

    // Export triple-buffer: offer() computes into one slot while render
    // samples the latest completed one. Two fresher frames always separate a
    // slot reuse, and the render thread drains GL after every swap, so no
    // slot is ever written while sampled.
    private val exportLock = Any()
    private val exports = arrayOfNulls<HardwareBuffer>(3)
    private val exportW = IntArray(3)
    private val exportH = IntArray(3)
    private val exportSeq = LongArray(3)

    // Step-7 CPU fallback (no working Vulkan): same triple-slot discipline
    // for CPU guide bytes. One-way latch per session; fresh sessions retry
    // the GPU path. Render keeps one reusable slice-output buffer.
    @Volatile private var cpuFallback = false
    // Manual BGU_CPU override (overlay tap): pins the CPU guide path even
    // with a healthy GPU. Sticky across sessions like the global mode.
    @Volatile private var cpuForced = false
    private val cpuGuides = arrayOfNulls<java.nio.ByteBuffer>(3)
    private val cpuGuideW = IntArray(3)
    private val cpuGuideH = IntArray(3)
    private val cpuGuideSeq = LongArray(3)
    private var cpuSliced: java.nio.ByteBuffer? = null
    private var cpuSlicedW = 0
    private var cpuSlicedH = 0
    private var identityUploaded = false
    private val identityGrid = BguSliceCpu.identityGrid()

    // Stats (render-owned, read by snapshot()).
    @Volatile private var statFps = 0f
    @Volatile private var statMs = 0f
    @Volatile private var statFitMs = 0f
    @Volatile private var statVkMs = 0f
    @Volatile private var statCpuMs = 0f
    @Volatile private var statSliMs = 0f
    @Volatile private var statOfferMs = 0f
    @Volatile private var statSmpMs = 0f
    @Volatile private var statParMs = 0f
    @Volatile private var statDrawMs = 0f
    @Volatile private var statSwapMs = 0f
    @Volatile private var statFenceMs = 0f
    @Volatile private var statVfW = 0
    @Volatile private var statVfH = 0
    @Volatile private var statRawW = 0
    @Volatile private var statRawH = 0
    @Volatile private var statEv = 0f
    @Volatile private var statBusy: Long = 0
    @Volatile private var statGl = false

    private val fitThread = Thread(::fitLoop, "BguFit").apply { isDaemon = true }
    private val renderThread = Thread(::renderLoop, "BguRender").apply { isDaemon = true }

    init {
        holder.setFormat(PixelFormat.RGBX_8888)
        holder.addCallback(this)
        fitThread.start()
        renderThread.start()
    }

    // ----------------------------------------------------------------- offer
    //
    // Runs inline on the camera handler. Samples the low frame, snapshots the
    // look params, runs the guide dispatch synchronously (the Image closes on
    // return, so zero-copy compute cannot outlive this call), then posts one
    // job per worker. Anything unexpected drops the frame; persistent failure
    // degrades to the legacy engine via onStarvation.

    override fun offer(image: Image, c: CameraCharacteristics, result: CaptureResult?) {
        if (disposed || fatal != null) return
        val now = SystemClock.elapsedRealtime()
        val floorMs = when {
            recordMode -> 100L
            powerSave -> 66L
            else -> 15L
        }
        if (now - lastOfferMs < floorMs) return
        if (firstOfferMs == 0L) firstOfferMs = now
        lastOfferMs = now
        val offerT0 = SystemClock.elapsedRealtimeNanos()
        if (!VfVulkan.available) {
            fail("vulkan-unavailable")
            return
        }
        val plane = image.planes.firstOrNull() ?: return
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = plane.buffer
        if (rowStride <= 0 || pixelStride <= 0) return

        val cropRect = try {
            result?.get(CaptureResult.SCALER_CROP_REGION)
        } catch (_: Exception) {
            null
        }
        val crop = cropRect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        val key = GeoKey(image.width, image.height, crop?.joinToString(",") ?: "full", targetLongEdge)
        var geo = geoCache
        if (geo == null || geoCacheKey != key) {
            geo = try {
                BguGeometry.guideGeometry(image.width, image.height, crop, targetLongEdge)
            } catch (_: Exception) {
                return
            }
            geoCacheKey = key
            geoCache = geo
        }
        val low = BguGeometry.lowDims(geo.width, geo.height)
        val lowW = low[0]
        val lowH = low[1]

        val cfa = try {
            c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        } catch (_: Exception) {
            return
        }
        val channels = try {
            BguGeometry.cfaSites(cfa)
        } catch (_: Exception) {
            return
        }
        val levels = blackLevels(c, result)
        val white = whiteLevel(c, result)
        val inv = FloatArray(4) { i ->
            val den = white - levels[i]
            if (den > 1f) 1f / den else 1f / 1023f
        }
        val quad = ByteArray(lowW * lowH * 4)
        val smpT0 = SystemClock.elapsedRealtimeNanos()
        val sampled = BguSample.sampleLowFrame(
            buf, rowStride, pixelStride, geo.left, geo.top, geo.step * BguGeometry.LOW_DF,
            lowW, lowH, channels, levels, inv, quad
        )
        if (!sampled) return
        val smpMs = (SystemClock.elapsedRealtimeNanos() - smpT0) / 1e6f
        statSmpMs = if (statSmpMs == 0f) smpMs else statSmpMs * 0.9f + smpMs * 0.1f

        val parT0 = SystemClock.elapsedRealtimeNanos()
        val params = snapshotParams(c, result, quad, lowW, lowH, geo, channels, white) ?: return
        val parMs = (SystemClock.elapsedRealtimeNanos() - parT0) / 1e6f
        statParMs = if (statParMs == 0f) parMs else statParMs * 0.9f + parMs * 0.1f

        val slot = (seq % 3).toInt()
        // Per-offer evaluation: the automatic fallback and the manual
        // BGU_CPU override take the identical CPU path, and each job
        // records which guide it carries, so mid-stream taps flip cleanly.
        val useCpu = cpuFallback || cpuForced
        val guideOk = if (!useCpu) {
            offerGpuGuide(image, rowStride, pixelStride, geo, slot, channels, levels, white, offerT0)
        } else {
            offerCpuGuide(buf, rowStride, pixelStride, geo, slot, channels, levels, white, offerT0)
        }
        if (!guideOk) return
        seq++
        statRawW = image.width
        statRawH = image.height
        synchronized(exportLock) { exportSeq[slot] = seq; if (useCpu) cpuGuideSeq[slot] = seq }
        fitQueue.offerOrDrop(
            LowJob(seq, quad, lowW, lowH, params.lens, params.lensCols, params.lensRows,
                params.wb, params.ccm, params.aces, params.white, params.fparams, params.iparams)
        )
        val rotation = try {
            c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }
        val mirrored = try {
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } catch (_: Exception) {
            false
        }
        renderQueue.offerOrDrop(RenderJob(seq, slot, geo, lowW, lowH, rotation, mirrored, useCpu))
    }

    /**
     * One-way CPU-fallback entry: latches [cpuFallback] for GPU-path
     * breakage (see [cpuFallbackWhen]) and drops this frame; the next offer
     * retries on the CPU path. Anything else fails over to legacy as before.
     */
    private fun enterCpuFallback(reason: String): Boolean {
        if (!cpuFallbackWhen(reason, BguSliceCpu.available)) {
            fail(reason)
            return false
        }
        if (!cpuFallback) {
            cpuFallback = true
            Log.w(TAG, "BGU GPU path broken ($reason); CPU fallback engaged")
        }
        return true
    }

    /**
     * CPU guide for the fallback path: superpixel quads via [VfCpuNeon] into
     * the job's triple-buffer slot. Same geo/channels/levels contract as the
     * Vulkan dispatch; the fit/look pipeline downstream is unchanged.
     */
    private fun offerCpuGuide(
        buf: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, geo: BguGuideGeo,
        slot: Int, channels: IntArray, levels: FloatArray, white: Float, offerT0: Long
    ): Boolean {
        val dst = synchronized(exportLock) {
            val cur = cpuGuides[slot]
            if (cur != null && cpuGuideW[slot] == geo.width && cpuGuideH[slot] == geo.height) {
                cur
            } else {
                val fresh = java.nio.ByteBuffer.allocateDirect(geo.width * geo.height * 4)
                cpuGuides[slot] = fresh
                cpuGuideW[slot] = geo.width
                cpuGuideH[slot] = geo.height
                fresh
            }
        }
        val cpuT0 = SystemClock.elapsedRealtimeNanos()
        val offerMs = (cpuT0 - offerT0) / 1e6f
        statOfferMs = if (statOfferMs == 0f) offerMs else statOfferMs * 0.9f + offerMs * 0.1f
        try {
            dst.clear()
            // Duplicate: VfCpuNeon reads source.position() as the base offset
            // and the shared plane buffer's position is not ours to move.
            val src = buf.duplicate()
            src.rewind()
            VfCpuNeon.copy(
                src, rowStride, pixelStride, geo.left, geo.top,
                geo.width, geo.height, geo.step, channels, levels, white, dst
            )
        } catch (_: Exception) {
            fail("cpu-guide")
            return false
        }
        val cpuMs = (SystemClock.elapsedRealtimeNanos() - cpuT0) / 1e6f
        statCpuMs = if (statCpuMs == 0f) cpuMs else statCpuMs * 0.9f + cpuMs * 0.1f
        // The GPU branch is idle while this one runs: pin its EMA at zero so
        // the stats line never shows a frozen vkMs (a tap-time reset would
        // race an in-flight GPU offer and re-seed from a single frame).
        statVkMs = 0f
        return true
    }

    /**
     * GPU guide: the Vulkan superpixel dispatch into the job's export slot
     * (moved verbatim from offer(); only the CPU fallback can bypass it).
     * Returns false to drop the frame (fatal or transient).
     */
    private fun offerGpuGuide(
        image: android.media.Image, rowStride: Int, pixelStride: Int, geo: BguGuideGeo,
        slot: Int, channels: IntArray, levels: FloatArray, white: Float, offerT0: Long
    ): Boolean {
        val export = synchronized(exportLock) {
            val cur = exports[slot]
            if (cur != null && exportW[slot] == geo.width && exportH[slot] == geo.height) {
                cur
            } else {
                // Do NOT close cur here: the render thread may still sample it
                // through its EGL image. It closes retired buffers itself after
                // destroying their images (close() is idempotent anyway).
                val fresh = try {
                    HardwareBuffer.create(
                        geo.width, geo.height, HardwareBuffer.RGBA_8888, 1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    )
                } catch (_: Exception) {
                    null
                } ?: return false
                exports[slot] = fresh
                exportW[slot] = geo.width
                exportH[slot] = geo.height
                fresh
            }
        }
        val pitch = rowStride / max(1, pixelStride)
        val (iparams, fparams) = try {
            VfVulkan.packParams(channels, geo.left, geo.top, geo.width, geo.height, geo.step, pitch, levels, white)
        } catch (_: Exception) {
            return false
        }
        // GL->Vulkan ordering for this slot's export: wait (sleep-poll,
        // 250 ms cap) for the render thread's sample fence before the
        // dispatch reuses the buffer. Instant in the common case; the cap
        // keeps a wedged GL from wedging offers (swap-fail path stays the
        // fatal backstop).
        val fenceFd = synchronized(exportLock) { takeSlotFence(fenceFds, slot) }
        if (fenceFd >= 0) {
            val fenceT0 = SystemClock.elapsedRealtime()
            var signaled = false
            try {
                while (SystemClock.elapsedRealtime() - fenceT0 < 250L) {
                    try {
                        if (VfEglImport.pollSyncFd(fenceFd)) {
                            signaled = true
                            break
                        }
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                try {
                    VfEglImport.closeSyncFd(fenceFd)
                } catch (_: Exception) {
                }
            }
            if (signaled) {
                fenceWaitStreak = 0
            } else if (++fenceWaitStreak == 1 || fenceWaitStreak % 30 == 0) {
                Log.w(TAG, "guide fence wait timed out, proceeding (%d)".format(fenceWaitStreak))
            }
        }
        if (!vulkanReady) {
            val spv = try {
                context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
            } catch (_: Exception) {
                enterCpuFallback("spv-missing")
                return false
            }
            VfVulkan.setPipelineCachePathNative(
                VulkanPipelineCache.pathFor(context.cacheDir, VulkanPipelineCache.HOST_VF))
            if (VfVulkan.initNative(spv) != VfVulkan.OK) {
                enterCpuFallback("vulkan-init")
                return false
            }
            vulkanReady = true
        }
        // The HardwareBuffer handle is a per-offer reference: acquire it next
        // to its only use and close it once the synchronous dispatch returns
        // (the finalizer warns on every leaked handle otherwise).
        val hb = try {
            image.hardwareBuffer
        } catch (_: Exception) {
            null
        }
        if (hb == null) {
            if (++nullBufferStreak >= 30) fail("no-hardware-buffer")
            return false
        }
        nullBufferStreak = 0
        val rc: Int
        try {
            if (VfVulkan.ensureOutputNative(export) != VfVulkan.OK) {
                fail("vulkan-output")
                return false
            }
            val vkT0 = SystemClock.elapsedRealtimeNanos()
            val offerMs = (vkT0 - offerT0) / 1e6f
            statOfferMs = if (statOfferMs == 0f) offerMs else statOfferMs * 0.9f + offerMs * 0.1f
            rc = try {
                VfVulkan.computeNative(hb, iparams, fparams)
            } catch (_: Exception) {
                fail("vulkan-exception")
                return false
            }
            val vkMs = (SystemClock.elapsedRealtimeNanos() - vkT0) / 1e6f
            statVkMs = if (statVkMs == 0f) vkMs else statVkMs * 0.9f + vkMs * 0.1f
            // Symmetric with the CPU branch: pin the idle path's EMA at zero.
            statCpuMs = 0f
        } finally {
            try {
                hb.close()
            } catch (_: Exception) {
            }
        }
        if (rc == VfVulkan.BUSY) {
            statBusy++
            if (++busyStreak >= 120) fail("vulkan-busy")
            return false
        }
        if (rc != VfVulkan.OK) {
            if (++failStreak >= 5) fail("vulkan-$rc")
            return false
        }
        busyStreak = 0
        failStreak = 0
        return true
    }

    private data class LookParams(
        val lens: FloatArray, val lensCols: Int, val lensRows: Int,
        val wb: FloatArray, val ccm: FloatArray, val aces: FloatArray, val white: FloatArray,
        val fparams: FloatArray, val iparams: IntArray
    )

    private fun blackLevels(c: CameraCharacteristics, result: CaptureResult?): FloatArray {
        val dynamic = try {
            result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        } catch (_: Exception) {
            null
        }
        if (dynamic != null && dynamic.size >= 4) {
            return FloatArray(4) { dynamic[it] }
        }
        val pattern = try {
            c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        } catch (_: Exception) {
            null
        }
        if (pattern != null) {
            return FloatArray(4) { pattern.getOffsetForIndex(it % 2, it / 2).toFloat() }
        }
        return FloatArray(4) { 64f }
    }

    private fun whiteLevel(c: CameraCharacteristics, result: CaptureResult?): Float {
        // Dynamic first like the legacy engine: multi-mode HALs (Vivo X300
        // Ultra) park the real white level in the per-frame tag while the
        // static reports a placeholder the floor below would mask as 1023.
        val dynamic = try {
            result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        } catch (_: Exception) {
            null
        }
        val white = try {
            dynamic ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        } catch (_: Exception) {
            null
        } ?: 1023
        return if (white > 255) white.toFloat() else 1023f
    }

    private fun snapshotParams(
        c: CameraCharacteristics, result: CaptureResult?,
        quad: ByteArray, lowW: Int, lowH: Int, geo: BguGuideGeo,
        channels: IntArray, white: Float
    ): LookParams? {
        val cfa = try {
            c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        } catch (_: Exception) {
            return null
        }
        val greenRow = if (cfa == 2 || cfa == 3) 1 else 0
        // WB gains in canonical [R, Gr, Gb, B]: HAL-applied gains first (what
        // the scene actually used), as-shot neutral inversion next, unity last.
        val wb = FloatArray(4) { 1f }
        val halGains = try {
            result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        } catch (_: Exception) {
            null
        }
        if (halGains != null) {
            val sensor = floatArrayOf(halGains.red, halGains.greenEven, halGains.greenOdd, halGains.blue)
            val mapped = floatArrayOf(sensor[0], sensor[if (greenRow == 0) 1 else 2],
                sensor[if (greenRow == 0) 2 else 1], sensor[3])
            for (k in 0..3) {
                wb[k] = mapped[k].takeIf { it.isFinite() && it > 0f }?.coerceIn(1f / 16, 16f) ?: 1f
            }
        } else {
            val neutral = try {
                result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            } catch (_: Exception) {
                null
            }
            if (neutral != null && neutral.size >= 3) {
                val n = DoubleArray(3) { neutral[it].toDouble() }
                val inv = doubleArrayOf(1 / n[0], 1 / n[1], 1 / n[2])
                for (k in 0..3) {
                    val v = inv[if (k == 0) 0 else if (k == 3) 2 else 1]
                    wb[k] = v.takeIf { it.isFinite() && it > 0 }?.toFloat()?.coerceIn(1f / 16, 16f) ?: 1f
                }
            }
        }
        // RAW-branch CCM: the HAL transform (row-major rationals) to the
        // look's column-major floats, identity when unreported or unusable.
        val ccm = FloatArray(9)
        val halCcm = try {
            result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        } catch (_: Exception) {
            null
        }
        var ccmOk = halCcm != null
        if (halCcm != null) {
            for (col in 0..2) for (row in 0..2) {
                val v = halCcm.getElement(col, row).toDouble()
                if (!v.isFinite()) {
                    ccmOk = false
                    break
                }
                ccm[col * 3 + row] = v.toFloat()
            }
        }
        if (!ccmOk) {
            for (i in 0..8) ccm[i] = if (i % 4 == 0) 1f else 0f
        }
        // JPEG-branch calibration: same save-path contract (dual-illuminant
        // solve with HAL-CCM fallback), cached across repeats.
        val calibrated = try {
            calibratedColor(c, result, halGains, wb, ccm)
        } catch (_: Exception) {
            null
        }
        val aces = calibrated?.cameraToAcescgColumnMajor ?: ccm.copyOf()
        val camWhite = calibrated?.cameraWhiteNormalized ?: floatArrayOf(1f, 1f, 1f)
        // Adaptive preview EV: estimate at ~3 Hz on a strided subset (the
        // sort dominates), same save-path rule, one-pole smoothing.
        if (++evCounter >= 10) {
            evCounter = 0
            lastEvTarget = estimateEv(quad, lowW, lowH)
        }
        val target = (lastEvTarget * evStrength.coerceIn(0f, 1f)).coerceIn(-3f, 3f)
        appliedEv += (target - appliedEv) * 0.3f
        statEv = appliedEv
        // Lens map: throttled re-parse with content-keyed reuse.
        val lens = snapshotLens(c, result)
        val active = try {
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        } catch (_: Exception) {
            null
        }
        val aL = active?.left ?: 0
        val aT = active?.top ?: 0
        val aR = active?.right ?: geo.left + geo.width * geo.step
        val aB = active?.bottom ?: geo.top + geo.height * geo.step
        val snapAgx = agx
        val fparams = floatArrayOf(
            appliedEv, snapAgx[0], snapAgx[1], snapAgx[2], snapAgx[3],
            snapAgx[4], snapAgx[5], snapAgx[6], snapAgx[7]
        )
        val iparams = intArrayOf(
            if (renderJpeg) 1 else 0, 0, if (lens != null) 1 else 0, greenRow,
            geo.left, geo.top, geo.step * BguGeometry.LOW_DF, aL, aT, aR, aB
        )
        return LookParams(
            lens?.gains ?: FloatArray(4) { 1f }, lens?.cols ?: 1, lens?.rows ?: 1,
            wb, ccm, aces, camWhite, fparams, iparams
        )
    }

    private fun estimateEv(quad: ByteArray, lowW: Int, lowH: Int): Float {
        val plane = lowW * lowH
        val samples = FloatArray(plane) // strided subset below fills a prefix
        var count = 0
        var i = 0
        while (i < plane && count < samples.size) {
            // Merged normalized value of low texel i (planar channels).
            val r = (quad[i].toInt() and 0xff) / 255f
            val gr = (quad[plane + i].toInt() and 0xff) / 255f
            val gb = (quad[2 * plane + i].toInt() and 0xff) / 255f
            val b = (quad[3 * plane + i].toInt() and 0xff) / 255f
            val v = (r + gr + gb + b) * 0.25f
            if (v.isFinite() && v > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                samples[count++] = v
            }
            i += 4
        }
        if (count == 0) return 0f
        return try {
            AdaptiveDevelopmentExposure.analyzeSamples(samples, count).correctionEv.toFloat()
        } catch (_: Exception) {
            0f
        }
    }

    private data class LensSnap(val gains: FloatArray, val cols: Int, val rows: Int)

    private fun snapshotLens(c: CameraCharacteristics, result: CaptureResult?): LensSnap? {
        val now = SystemClock.elapsedRealtime()
        val cached = lensCache
        if (cached != null && now - cached.atMs < 1000) return cached.let {
            LensSnap(it.gains, it.cols, it.rows)
        }
        val map = try {
            result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (_: Exception) {
            null
        } ?: return cached?.let { LensSnap(it.gains, it.cols, it.rows) }
        return try {
            val rows = map.rowCount
            val cols = map.columnCount
            if (rows <= 0 || cols <= 0 || map.gainFactorCount != rows * cols * 4) {
                return cached?.let { LensSnap(it.gains, it.cols, it.rows) }
            }
            val raw = FloatArray(rows * cols * 4)
            map.copyGainFactors(raw, 0)
            if (raw.any { !it.isFinite() || it < 1f }) {
                return cached?.let { LensSnap(it.gains, it.cols, it.rows) }
            }
            val mid = raw.size / 2
            val key = "$cols,$rows,${raw[0]},${raw[mid]},${raw[raw.size - 1]}"
            if (cached != null && cached.key == key) {
                lensCache = cached.copy(atMs = now)
                return LensSnap(cached.gains, cached.cols, cached.rows)
            }
            // Transpose cell-interleaved [R, Ge, Go, B] to the planar layout
            // the AOT filter requires (dim-0 stride 1); ~1 Hz, tiny.
            val cells = rows * cols
            val gains = FloatArray(cells * 4)
            for (cell in 0 until cells) for (ch in 0..3) {
                gains[ch * cells + cell] = raw[cell * 4 + ch]
            }
            lensCache = LensCache(key, gains, cols, rows, now)
            LensSnap(gains, cols, rows)
        } catch (_: Exception) {
            cached?.let { LensSnap(it.gains, it.cols, it.rows) }
        }
    }

    private fun calibratedColor(
        c: CameraCharacteristics, result: CaptureResult?,
        halGains: android.hardware.camera2.params.RggbChannelVector?,
        canonicalGains: FloatArray, ccm: FloatArray
    ): VfCalibratedColor {
        fun mat(key: CameraCharacteristics.Key<android.hardware.camera2.params.ColorSpaceTransform>): DoubleArray? {
            val t = try {
                c.get(key)
            } catch (_: Exception) {
                null
            }
            // Same converter as saves/legacy (column-major "Camera2 storage
            // order"); the resolver un-transposes exactly once.
            return t.toImmutableDoubles()?.toDoubleArray()
        }
        val st = if (calibStaticsCam === c) {
            calibStatics
        } else {
            null
        } ?: run {
            val fresh = CalibStatics(
                listOf(
                    mat(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),
                    mat(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),
                    mat(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
                    mat(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
                    mat(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
                    mat(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
                ),
                try {
                    c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
                } catch (_: Exception) {
                    null
                },
                try {
                    c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
                } catch (_: Exception) {
                    null
                }
            )
            calibStaticsCam = c
            calibStatics = fresh
            fresh
        }
        val neutral = try {
            result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { n ->
                if (n.size >= 3) DoubleArray(3) { n[it].toDouble() } else null
            }
        } catch (_: Exception) {
            null
        }
        val gainsSensor = halGains?.let {
            floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
        }
        return calibratedCache.resolve(st.static, st.ill1, st.ill2, neutral, gainsSensor, canonicalGains, ccm)
    }

    // ------------------------------------------------------------------ fit
    //
    // Latest-only: slow fits skip stale frames instead of queueing latency.

    private fun fitLoop() {
        while (true) {
            val job = try {
                fitQueue.take()
            } catch (_: InterruptedException) {
                return
            }
            if (job === Poison || disposed) return
            job as LowJob
            if (fatal != null) continue
            if (!BguLook.available) {
                fail("halide-missing")
                continue
            }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val frame = fitBuffers.frame(job.lowW, job.lowH)
            val rcLook = try {
                BguLook.runInto(job.quad, job.lowW, job.lowH, job.lens, job.lensCols, job.lensRows,
                    job.wb, job.ccm, job.aces, job.white, job.fparams, job.iparams,
                    frame.guide, frame.developed)
            } catch (_: Exception) {
                -1
            }
            if (rcLook != 0) {
                if (++lookFailStreak >= 20) fail("look-failed")
                continue
            }
            val dims = BguFit.gridDims(job.lowW, job.lowH)
            val back = fitBuffers.backGrid(dims[0], dims[1], dims[2])
            val rcFit = try {
                BguFit.fitInto(frame.guide, frame.developed, job.lowW, job.lowH, back)
            } catch (_: Exception) {
                -1
            }
            if (rcFit != 0) {
                if (++lookFailStreak >= 20) fail("fit-failed")
                continue
            }
            fitBuffers.flip()
            lookFailStreak = 0
            statFitMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
            latestGrid = GridJob(job.seq, BguGrid(dims[0], dims[1], dims[2], back))
        }
    }

    // --------------------------------------------------------------- render
    //
    // Owns the EGL context and all GL objects. Drains to the latest guide job
    // (stale frames never render), uploads the latest grid, slices, presents.

    private var egl: EglCore? = null
    private var gl: GlSlice? = null
    private val eglImages = LongArray(3)
    private val eglImageSlot = arrayOfNulls<HardwareBuffer>(3)
    // Per-slot GL->Vulkan handoff fences (dup'd native fds, -1 = none),
    // exportLock-guarded: render exports after swap, offer waits before
    // dispatch reuse. Common case is instant (fence signaled ~99 ms ago).
    private val fenceFds = IntArray(3) { -1 }
    private var fenceWaitStreak = 0
    private var lastRenderedSeq = 0L
    private var lastGridSeq = 0L
    private var lastSwapMs = 0L
    private var lastStatsMs = 0L
    private var swapFailStreak = 0
    private val fpsWindow = BguFpsWindow()

    private fun renderLoop() {
        while (true) {
            val job = try {
                renderQueue.poll(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (disposed) break
            if (job === Poison) break
            if (!surfaceAlive || fatal != null) {
                if (!surfaceAlive) tearDownGl()
                checkWatchdog()
                continue
            }
            if (job == null) {
                checkWatchdog()
                continue
            }
            // Drain to the latest guide job; render at most one per wake.
            var latest = job as RenderJob
            while (true) {
                val next = renderQueue.poll() ?: break
                if (next === Poison) {
                    renderQueue.offer(Poison)
                    break
                }
                latest = next as RenderJob
            }
            if (latest.seq <= lastRenderedSeq) {
                checkWatchdog()
                continue
            }
            renderOne(latest)
        }
        tearDownGl()
    }

    private fun checkWatchdog() {
        if (disposed || fatal != null || !surfaceAlive) return
        val intervalMs = frameIntervalNs / 1_000_000
        val limitMs = max(2500L, intervalMs * 8)
        if (renderStarveVerdict(
                SystemClock.elapsedRealtime(), firstOfferMs, lastOfferMs, lastSwapMs, limitMs
            )
        ) {
            fail("render-starve")
        }
    }

    private fun renderOne(job: RenderJob) {
        if (egl == null) {
            val surface = holder.surface
            if (surface == null || !surface.isValid) return
            val core = try {
                EglCore(surface)
            } catch (e: Exception) {
                fail("egl-init: ${e.message}")
                return
            }
            egl = core
            gl = try {
                GlSlice()
            } catch (e: Exception) {
                fail("gl-init: ${e.message}")
                return
            }
            statGl = true
        }
        val core = egl ?: return
        val slice = gl ?: return
        // CPU-fallback jobs carry their guide as bytes: capture the slot's
        // buffer (duplicate: the offer thread owns the original's position)
        // and skip the EGL-image import entirely. A stalled render may find
        // its slot reused by a newer frame: skip, never sample another
        // frame's guide.
        val cpuBuf: java.nio.ByteBuffer? = if (job.cpu) {
            synchronized(exportLock) {
                if (cpuGuideSeq[job.slot] != job.seq) null else cpuGuides[job.slot]
            }?.let {
                val dup = it.duplicate() as java.nio.ByteBuffer
                dup.rewind()
                dup
            }
        } else null
        if (job.cpu && cpuBuf == null) return
        // Import the job's export slot (cached per buffer identity; retired
        // buffers are closed here, after their EGL image dies).
        val eglImage: Long
        if (job.cpu) {
            eglImage = 0L
        } else {
            val export = synchronized(exportLock) {
                if (exportSeq[job.slot] != job.seq) null else exports[job.slot]
            }
            if (export == null) return
            if (eglImageSlot[job.slot] !== export) {
                val old = eglImages[job.slot]
                if (old != 0L) {
                    try {
                        VfEglImport.destroyEGLImage(old)
                    } catch (_: Exception) {
                    }
                    eglImages[job.slot] = 0L
                }
                val stale = eglImageSlot[job.slot]
                if (stale != null && stale !== export) {
                    try {
                        stale.close()
                    } catch (_: Exception) {
                    }
                }
                val image = try {
                    VfEglImport.createEGLImage(export)
                } catch (_: Exception) {
                    0L
                }
                if (image == 0L) {
                    // The GL context is alive (only the Vulkan-export import
                    // failed), so the CPU guide — a plain byte upload, no EGL
                    // image — keeps this engine running. Falls over to legacy
                    // only when the CPU slice itself is unavailable.
                    enterCpuFallback("egl-import")
                    return
                }
                eglImages[job.slot] = image
                eglImageSlot[job.slot] = export
            }
            eglImage = eglImages[job.slot]
        }
        // Upload the latest grid (reuse the last one while the fit catches up).
        val gridJob = latestGrid
        if (gridJob != null && gridJob.seq > lastGridSeq) {
            try {
                slice.uploadGrid(gridJob.grid)
            } catch (e: Exception) {
                fail("grid-upload: ${e.message}")
                return
            }
            lastGridSeq = gridJob.seq
        }
        if (lastGridSeq == 0L) {
            // No fit yet: wait for the first grid instead of slicing zeros.
            if (lastSwapMs == 0L && job.seq > 90) fail("no-grid")
            return
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val ok = try {
            val drew = slice.draw(
                eglImage, cpuBuf, job.geo, job.lowW, job.lowH,
                job.rotation, job.mirrored, width, height
            )
            val t1 = SystemClock.elapsedRealtimeNanos()
            val drawMs = (t1 - t0) / 1e6f
            statDrawMs = if (statDrawMs == 0f) drawMs else statDrawMs * 0.9f + drawMs * 0.1f
            val swapped = drew && core.swap()
            val tSwap = SystemClock.elapsedRealtimeNanos()
            val swapMs = (tSwap - t1) / 1e6f
            statSwapMs = if (statSwapMs == 0f) swapMs else statSwapMs * 0.9f + swapMs * 0.1f
            swapped
        } catch (e: Exception) {
            fail("draw: ${e.message}")
            return
        }
        if (!ok) {
            // A lone swap/display failure is usually a surface race (the
            // route hid this surface mid-frame): drop GL and retry fresh on
            // the next frame. Only a sustained streak (~1 s of consecutive
            // failures) is fatal; the render-starve watchdog stays the
            // backstop for silent stalls.
            val eglErr = try {
                EGL14.eglGetError()
            } catch (_: Exception) {
                -1
            }
            if (++swapFailStreak == 1 || swapFailStreak % 30 == 0) {
                Log.w(TAG, "swap failed (egl=0x%04x), retrying (%d)".format(eglErr, swapFailStreak))
            }
            tearDownGl()
            if (swapFailStreak >= 30) fail("swap: egl=0x%04x".format(eglErr))
            return
        }
        swapFailStreak = 0
        // GL->Vulkan ordering for export reuse (the only cross-API handoff):
        // export a fence fd instead of stalling here on glFinish. The offer
        // thread waits on it before reusing this slot (usually already
        // signaled); GPU work overlaps the CPU from here on.
        val t2 = SystemClock.elapsedRealtimeNanos()
        val fenceFd = try {
            VfEglImport.exportFenceFd()
        } catch (_: Exception) {
            -1
        }
        val fenMs = (SystemClock.elapsedRealtimeNanos() - t2) / 1e6f
        statFenceMs = if (statFenceMs == 0f) fenMs else statFenceMs * 0.9f + fenMs * 0.1f
        if (fenceFd >= 0) {
            val stale = synchronized(exportLock) { storeSlotFence(fenceFds, job.slot, fenceFd) }
            if (stale >= 0) {
                try {
                    VfEglImport.closeSyncFd(stale)
                } catch (_: Exception) {
                }
            }
        }
        val dtMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        statMs = if (statMs == 0f) dtMs else statMs * 0.9f + dtMs * 0.1f
        val now = SystemClock.elapsedRealtime()
        // Windowed present rate (exact swaps/second), not an EMA of
        // instantaneous rates: bursty delivery made the EMA swing 20-50.
        fpsWindow.onSwap(now)
        statFps = fpsWindow.fps
        lastSwapMs = now
        lastRenderedSeq = job.seq
        statVfW = job.geo.width
        statVfH = job.geo.height
        // Steady-state telemetry (legacy VF_STATS_LOG_INTERVAL_MS convention):
        // presented fps + draw/swap cost + fit cost + guide size, 10 s cadence.
        if (now - lastStatsMs >= 10_000L) {
            lastStatsMs = now
            Log.i(TAG, "stats fps=%.1f frameMs=%.1f drawMs=%.1f swapMs=%.1f fenMs=%.1f offerMs=%.1f smpMs=%.1f parMs=%.1f vkMs=%.1f fitMs=%.1f cpuMs=%.1f vf=%dx%d seq=%d busy=%d".format(
                statFps, statMs, statDrawMs, statSwapMs, statFenceMs, statOfferMs, statSmpMs, statParMs,
                statVkMs, statFitMs, statCpuMs, job.geo.width, job.geo.height, job.seq, statBusy))
        }
    }

    private fun tearDownGl() {
        val slice = gl
        gl = null
        if (slice != null) {
            try {
                slice.destroy()
            } catch (_: Exception) {
            }
        }
        for (i in 0..2) {
            val image = eglImages[i]
            if (image != 0L) {
                try {
                    VfEglImport.destroyEGLImage(image)
                } catch (_: Exception) {
                }
                eglImages[i] = 0L
            }
            eglImageSlot[i] = null
        }
        val core = egl
        egl = null
        if (core != null) {
            try {
                core.destroy()
            } catch (_: Exception) {
            }
        }
        statGl = false
        lastGridSeq = 0L
        fpsWindow.reset()
    }

    // ---------------------------------------------------------- EGL + slice

    /** Minimal ES3-only EGL owner: display, config, context, window surface. */
    private class EglCore(surface: android.view.Surface) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display === EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) ||
                count[0] == 0 || configs[0] == null
            ) {
                EGL14.eglTerminate(display)
                throw IllegalStateException("no ES3 config")
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context === EGL14.EGL_NO_CONTEXT) {
                EGL14.eglTerminate(display)
                throw IllegalStateException("no ES3 context")
            }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface === EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                throw IllegalStateException("no window surface")
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                destroy()
                throw IllegalStateException("makeCurrent failed")
            }
            EGL14.eglSwapInterval(display, 1)
        }

        fun swap(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

        fun destroy() {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglDestroySurface(display, eglSurface)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglDestroyContext(display, context)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglTerminate(display)
            } catch (_: Exception) {
            }
        }
    }

    /** Slice program + textures + quad. All calls run on the render thread. */
    private class GlSlice {
        private val program: Int
        private val aPosition: Int
        private val aUv: Int
        private val uGuide: Int
        private val uGrid0: Int
        private val uGrid1: Int
        private val uGrid2: Int
        private val uScale: Int
        private val uGridDim: Int
        private val vboPos: Int
        private val vboUv: Int
        private val texGuide: Int
        // Separate texture for CPU-fallback guides: a texture with an EGL
        // image bound must never see glTexImage2D, so the byte-upload path
        // gets its own name with identical sampling parameters.
        private val texCpuGuide: Int
        private var cpuGuideW = 0
        private var cpuGuideH = 0
        private val texGrid = IntArray(3)
        private var gridW = 0
        private var gridH = 0
        private var gridZ = 0
        private var uvKey = ""

        init {
            program = link(BguSlice.vertexShader(), BguSlice.fragmentShader())
            aPosition = GLES30.glGetAttribLocation(program, "position")
            aUv = GLES30.glGetAttribLocation(program, "uv")
            uGuide = GLES30.glGetUniformLocation(program, "uGuide")
            uGrid0 = GLES30.glGetUniformLocation(program, "uGrid0")
            uGrid1 = GLES30.glGetUniformLocation(program, "uGrid1")
            uGrid2 = GLES30.glGetUniformLocation(program, "uGrid2")
            uScale = GLES30.glGetUniformLocation(program, "uScale")
            uGridDim = GLES30.glGetUniformLocation(program, "uGridDim")
            val vbos = IntArray(2)
            GLES30.glGenBuffers(2, vbos, 0)
            vboPos = vbos[0]
            vboUv = vbos[1]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, floatBufferOf(
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
            ), GLES30.GL_STATIC_DRAW)
            val tex = IntArray(5)
            GLES30.glGenTextures(5, tex, 0)
            texGuide = tex[0]
            texGrid[0] = tex[1]
            texGrid[1] = tex[2]
            texGrid[2] = tex[3]
            texCpuGuide = tex[4]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texGuide)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCpuGuide)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            for (i in 0..2) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        fun uploadGrid(grid: BguGrid) {
            val packed = BguSlice.packGrid(grid)
            for (i in 0..2) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
                val buf = ByteBuffer.allocateDirect(packed[i].size * 2)
                    .order(ByteOrder.nativeOrder())
                for (s in packed[i]) buf.putShort(s)
                buf.flip()
                if (grid.gw != gridW || grid.gh != gridH || grid.gz != gridZ) {
                    GLES30.glTexImage3D(
                        GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F,
                        grid.gw, grid.gh, grid.gz, 0,
                        GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf
                    )
                } else {
                    GLES30.glTexSubImage3D(
                        GLES30.GL_TEXTURE_3D, 0, 0, 0, 0,
                        grid.gw, grid.gh, grid.gz,
                        GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf
                    )
                }
            }
            gridW = grid.gw
            gridH = grid.gh
            gridZ = grid.gz
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        }

        /**
         * CPU-fallback guide upload: RGBA8 bytes into the dedicated texture
         * (reallocates only on size change). Same sampling contract as the
         * EGL-imported Vulkan export, so the slice shader is untouched.
         */
        fun uploadCpuGuide(buf: ByteBuffer, w: Int, h: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCpuGuide)
            if (w != cpuGuideW || h != cpuGuideH || w <= 0 || h <= 0) {
                if (w <= 0 || h <= 0) return
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
                )
                cpuGuideW = w
                cpuGuideH = h
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
                )
            }
        }

        fun draw(
            eglImage: Long, cpuGuide: ByteBuffer?, geo: BguGuideGeo, lowW: Int, lowH: Int,
            rotation: Int, mirrored: Boolean, viewW: Int, viewH: Int
        ): Boolean {
            if (viewW <= 0 || viewH <= 0) return true
            val guideTex = if (cpuGuide != null) {
                uploadCpuGuide(cpuGuide, geo.width, geo.height)
                texCpuGuide
            } else {
                if (eglImage == 0L) return true
                if (VfEglImport.bindEGLImageToTexture2D(eglImage, texGuide) != 0) return false
                texGuide
            }
            val key = "$rotation,$mirrored"
            if (key != uvKey) {
                uvKey = key
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUv)
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER, 8 * 4,
                    floatBufferOf(*BguGeometry.displayUv(rotation, mirrored)),
                    GLES30.GL_DYNAMIC_DRAW
                )
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, guideTex)
            GLES30.glUniform1i(uGuide, 0)
            val units = intArrayOf(uGrid0, uGrid1, uGrid2)
            for (i in 0..2) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texGrid[i])
                GLES30.glUniform1i(units[i], 1 + i)
            }
            val s = BguFit.DEFAULT_S.toFloat()
            val r = BguFit.DEFAULT_R
            val dims = BguFit.gridDims(lowW, lowH)
            GLES30.glUniform3f(uScale, lowW / s, lowH / s, 1f / r)
            GLES30.glUniform3f(uGridDim, dims[0].toFloat(), dims[1].toFloat(), dims[2].toFloat())
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPos)
            GLES30.glEnableVertexAttribArray(aPosition)
            GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboUv)
            GLES30.glEnableVertexAttribArray(aUv)
            GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPosition)
            GLES30.glDisableVertexAttribArray(aUv)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            return true
        }

        fun destroy() {
            try {
                GLES30.glDeleteProgram(program)
            } catch (_: Exception) {
            }
            try {
                GLES30.glDeleteBuffers(2, intArrayOf(vboPos, vboUv), 0)
            } catch (_: Exception) {
            }
            try {
                GLES30.glDeleteTextures(5, intArrayOf(texGuide, texGrid[0], texGrid[1], texGrid[2], texCpuGuide), 0)
            } catch (_: Exception) {
            }
        }

        companion object {
            private fun floatBufferOf(vararg values: Float): java.nio.FloatBuffer {
                val buf = ByteBuffer.allocateDirect(values.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(values)
                buf.flip()
                return buf
            }

            private fun compile(type: Int, src: String): Int {
                val shader = GLES30.glCreateShader(type)
                if (shader == 0) throw IllegalStateException("createShader failed")
                GLES30.glShaderSource(shader, src)
                GLES30.glCompileShader(shader)
                val status = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(shader)
                    GLES30.glDeleteShader(shader)
                    throw IllegalStateException("compile failed: $log")
                }
                return shader
            }

            private fun link(vs: String, fs: String): Int {
                val program = GLES30.glCreateProgram()
                if (program == 0) throw IllegalStateException("createProgram failed")
                val v = compile(GLES30.GL_VERTEX_SHADER, vs)
                val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
                GLES30.glAttachShader(program, v)
                GLES30.glAttachShader(program, f)
                GLES30.glLinkProgram(program)
                GLES30.glDeleteShader(v)
                GLES30.glDeleteShader(f)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(program)
                    GLES30.glDeleteProgram(program)
                    throw IllegalStateException("link failed: $log")
                }
                return program
            }
        }
    }

    // ----------------------------------------------- surface / seam lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAlive = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // No Poison: the loop must survive surface loss (visibility toggles).
        // It tears down GL on its next poll tick and re-inits on recreate.
        surfaceAlive = false
    }

    override fun invalidateSession() {
        fatal = null
        seq = 0
        lastRenderedSeq = 0
        lastGridSeq = 0
        lastSwapMs = 0
        lastOfferMs = 0
        firstOfferMs = 0
        busyStreak = 0
        failStreak = 0
        nullBufferStreak = 0
        lookFailStreak = 0
        statBusy = 0
        statFps = 0f
        fpsWindow.reset()
        statMs = 0f
        statFitMs = 0f
        statVkMs = 0f
        statOfferMs = 0f
        statSmpMs = 0f
        statParMs = 0f
        statDrawMs = 0f
        statSwapMs = 0f
        statFenceMs = 0f
        statCpuMs = 0f
        // Fresh retry per session: the next session re-probes the GPU path.
        cpuFallback = false
        synchronized(exportLock) { for (i in 0..2) cpuGuideSeq[i] = 0L }
        geoCacheKey = null
        geoCache = null
        lensCache = null
        calibStaticsCam = null
        calibStatics = null
        latestGrid = null
        fitQueue.clear()
        renderQueue.clear()
    }

    override fun snapshot(): RawVfStats = RawVfStats(
        fps = statFps,
        frameMs = statMs,
        vfWidth = statVfW,
        vfHeight = statVfH,
        rawWidth = statRawW,
        rawHeight = statRawH,
        glActive = statGl,
        starved = fatal != null,
        busySkips = statBusy,
        gpu = !cpuFallback && !cpuForced,
        jpeg = renderJpeg,
        exposureEv = statEv,
        engine = VfEngineMode.BGU
    )

    override fun dispose() {
        if (disposed) return
        disposed = true
        try {
            holder.removeCallback(this)
        } catch (_: Exception) {
        }
        onStarvation = null
        try {
            fitQueue.offerOrDrop(Poison)
        } catch (_: Exception) {
        }
        try {
            renderQueue.offerOrDrop(Poison)
        } catch (_: Exception) {
        }
        try {
            fitThread.join(2000)
        } catch (_: Exception) {
        }
        try {
            renderThread.join(2000)
        } catch (_: Exception) {
        }
        synchronized(exportLock) {
            for (i in 0..2) {
                try {
                    exports[i]?.close()
                } catch (_: Exception) {
                }
                exports[i] = null
                try {
                    eglImageSlot[i]?.close()
                } catch (_: Exception) {
                }
                eglImageSlot[i] = null
                cpuGuides[i] = null
                val fd = takeSlotFence(fenceFds, i)
                if (fd >= 0) {
                    try {
                        VfEglImport.closeSyncFd(fd)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun fail(reason: String) {
        if (fatal != null) return
        fatal = reason
        Log.w(TAG, "bgu viewfinder fatal: $reason")
        try {
            onStarvation?.invoke()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "BguViewfinder"

        /** EGL_RENDERABLE_TYPE bit for OpenGL ES 3.x configs (EGL_KHR_create_context). */
        private const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}

/**
 * Pure render-starvation verdict: true only when a started stream stops
 * presenting. Offer freshness gates the whole check (stale offers across a
 * background gap or session restart mean silence is healthy, not
 * starvation), and the never-presented case gets a full grace window after
 * first light (cold first frames legitimately take hundreds of ms). Pure
 * and unit-tested.
 */
internal fun renderStarveVerdict(
    nowMs: Long, firstOfferMs: Long, lastOfferMs: Long, lastSwapMs: Long, limitMs: Long
): Boolean {
    if (firstOfferMs == 0L || nowMs - lastOfferMs > limitMs) return false
    if (lastSwapMs == 0L) return nowMs - firstOfferMs > limitMs
    return nowMs - lastSwapMs > limitMs
}

/**
 * Per-slot fence-fd bookkeeping for the GL->Vulkan handoff. [fds] is the
 * 3-slot table (callers hold the lock); [takeSlotFence] claims a slot's fd
 * for the waiter (slot back to -1, fd ownership transfers to caller),
 * [storeSlotFence] publishes a fresh fd and returns the stale one for the
 * caller to close. Pure and unit-tested.
 */
internal fun takeSlotFence(fds: IntArray, slot: Int): Int {
    val fd = fds[slot]
    fds[slot] = -1
    return fd
}

internal fun storeSlotFence(fds: IntArray, slot: Int, fd: Int): Int {
    val stale = fds[slot]
    fds[slot] = fd
    return stale
}

/**
 * Step-7 CPU-fallback selector: [reason] is the would-be fatal code. True
 * only for GPU-path breakage (missing shader, dead Vulkan, dead EGL import)
 * while the CPU slice is loadable; a missing native lib, mid-session GPU
 * faults, and all non-GPU fatals still fail over to legacy. One-way per
 * session (fresh sessions retry the GPU path). Pure and unit-tested.
 */
internal fun cpuFallbackWhen(reason: String, cpuAvailable: Boolean): Boolean {
    if (!cpuAvailable) return false
    return reason == "spv-missing" || reason == "vulkan-init" || reason == "egl-import"
}

/**
 * Windowed swap-rate meter: counts presented swaps per fixed window instead
 * of smoothing instantaneous rates, so bursty delivery reads exact (an EMA
 * of 1000/dt swings 20-50 on the same stream). Render thread only; [fps]
 * holds the last closed window (0 until the first closes).
 */
internal class BguFpsWindow(private val windowMs: Long = 2000L) {
    var fps = 0f
        private set
    private var count = 0L
    private var startMs = -1L

    fun onSwap(nowMs: Long) {
        if (startMs < 0L) startMs = nowMs
        count++
        val elapsed = nowMs - startMs
        if (elapsed >= windowMs) {
            fps = if (elapsed > 0L) count * 1000f / elapsed else 0f
            count = 0L
            startMs = nowMs
        }
    }

    fun reset() {
        fps = 0f
        count = 0L
        startMs = -1L
    }
}
