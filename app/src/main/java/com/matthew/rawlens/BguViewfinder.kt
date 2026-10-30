// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.opengl.EGL14
import android.os.SystemClock
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * RAW_SENSOR BGU viewfinder: Vulkan import/upload tiers, with a CPU guide
 * fallback and controller-owned legacy fallback on persistent failure.
 * Algorithm and platform provenance: docs/refactor/bgu-study.md.
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
    @Volatile private var previewHeadroom = 1f
    @Volatile private var previewSoftHeadroom = 0.85f
    @Volatile private var previewMiddleGreyTarget = 0.18
    @Volatile private var previewShoulder = 1f
    @Volatile private var previewCenterWeight = 0f
    @Volatile private var previewFocusX: Float? = null
    @Volatile private var previewFocusY: Float? = null
    @Volatile private var agx = floatArrayOf(1f, 1f, 1f, 0f, 10f, 6.5f, 0f, 1f, 0f)
    @Volatile private var frameIntervalNs = 1_000_000_000L / 30
    override var onStarvation: (() -> Unit)? = null
    /**
     * WYSIWYG content rect (surface pixels: aspect-fill cover, overflowing
     * symmetrically on the cropped axis), same contract as the legacy
     * engine: fired on the render worker only when the rect moves.
     */
    var onContentRectChanged: ((VfContentGeometry) -> Unit)? = null
    private var lastContentRect = Rect()

    override fun setTargetLongEdge(longEdge: Int) {
        targetLongEdge = longEdge.coerceIn(240, 2160)
    }

    /**
     * Effective guide long edge for the GPU path. Log evidence
     * (2026-10-13): blurry-BGU reporters were pinned at the 480 bucket —
     * a 408-wide guide upscaled ~3.5x to the screen — on GPUs with
     * vkMs ~1 ms, i.e. 90% headroom unused, while legacy/CPU paths looked
     * sharp. A user resolution choice made for the legacy engine should
     * not strand BGU below the screen's own size: lift the GPU target to
     * the surface long edge, capped at the proven 1080 envelope. Record
     * and power-save caps stay authoritative, and the CPU guide (whose
     * cost scales brutally with guide size) keeps the raw user target.
     */
    internal fun effectiveTargetLongEdge(): Int {
        val base = bguEffectiveTarget(
            userTarget = targetLongEdge,
            surfaceLongEdge = maxOf(width, height),
            gpuPath = !cpuFallback && !cpuForced,
            recordMode = recordMode,
            powerSave = powerSave
        )
        // Detail boost (log-driven, 2026-10-14): the step-4 guide is the
        // last structural softness vs legacy (point-sampled quads upscaled
        // ~1.9x). A step-2 guide quadruples guide texels and dispatch
        // cost, so it engages only where measured vkMs headroom proves
        // the GPU can pay it — blurry-log devices sat at vkMs ~1 ms,
        // mid-range phones at 6-11 ms stay on step-4. [guideBoost] is
        // latched with hysteresis in offer(); record/power-save and the
        // CPU guide never boost.
        return if (guideBoost && !cpuFallback && !cpuForced && !recordMode && !powerSave)
            maxOf(base, BOOST_TARGET) else base
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
            r.effectiveAgxShadowEv, r.agxHighlightEv, r.agxGamutCompression, r.highlightShoulder, if (r.agxLook == AgxLook.PUNCHY) 1f else 0f
        )
        // The VF meter runs the save's tuning, so VF EV == save EV: same
        // derived broad guard and shoulder as AdaptiveExposureTuning.
        previewHeadroom = r.highlightHeadroom
        val meterTuning = AdaptiveExposureTuning.fromOutputSettings(settings)
        previewSoftHeadroom = meterTuning.highlightSoftHeadroom.toFloat()
        previewMiddleGreyTarget = meterTuning.middleGreyTarget
        previewShoulder = meterTuning.highlightShoulder
        previewCenterWeight = meterTuning.centerWeight
    }

    override fun setMeteringFocus(x: Float?, y: Float?) {
        previewFocusX = x
        previewFocusY = y
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
        val seq: Long, val generation: Long,
        val quad: ByteArray, val lowW: Int, val lowH: Int,
        val lens: FloatArray, val lensCols: Int, val lensRows: Int,
        val wb: FloatArray, val ccm: FloatArray, val aces: FloatArray, val white: FloatArray,
        val fparams: FloatArray, val iparams: IntArray
    )

    private data class RenderJob(
        val seq: Long, val generation: Long, val slot: Int, val geo: BguGuideGeo,
        val lowW: Int, val lowH: Int, val rotation: Int, val mirrored: Boolean,
        // True when the CPU fallback produced this job's guide: render
        // uploads the job's cpuGuides bytes instead of binding its EGL image.
        val cpu: Boolean,
        // Sensor timestamp (boot-clock ns) of the source frame, for
        // motion-to-photon age telemetry in the stats line.
        val sensorTsNs: Long = 0L
    )

    private fun <T : Any> ArrayBlockingQueue<T>.offerOrDrop(job: T) {
        var offered = offer(job)
        while (!offered) {
            val dropped = poll()
            if (dropped is LowJob) quadPool.release(dropped.quad)
            offered = offer(job)
        }
    }

    private val fitQueue = ArrayBlockingQueue<Any>(2)
    private val fitBuffers = BguFitBuffers()
    private val renderQueue = ArrayBlockingQueue<Any>(4)
    private object Poison

    private val gridExchange = BguGridExchange()
    /** Throttled look-params snapshot (handler-owned; see offer). */
    private var cachedParams: LookParams? = null
    private var cachedParamsSeq = 0L
    private var cachedParamsCam: CameraCharacteristics? = null

    // ------------------------------------------------------- mutable state

    @Volatile private var disposed = false
    @Volatile private var fatal: String? = null
    @Volatile private var surfaceAlive = false

    @Volatile private var lastOfferMs = 0L
    @Volatile private var firstOfferMs = 0L
    /** Next due time for the throttled `bgu-vf-preview` memory sample. */
    @Volatile private var memSampleNextMs = 0L
    private var seq = 0L
    private var busyStreak = 0
    private var failStreak = 0
    private var nullBufferStreak = 0
    private var lookFailStreak = 0
    private var appliedEv = 0f
    private var evCounter = 0
    private var lastEvTarget = 0f
    private val evWorkspace = AdaptiveDevelopmentExposure.workspace()
    // Sensor-AE normalization: log2 reference of (exposureNs * ISO) that
    // follows slow drift but not instant AE steps; aeNormEv compensates the
    // steps feed-forward so the VF never jumps when the HAL re-exposes.
    private var aeRefLog2 = Double.NaN
    private var aeNormEv = 0f
    // Last-good color hold for result-less offers (recording). Published
    // only as immutable snapshots; keyed by camera identity, and NOT reset
    // by invalidateSession (the stop->record transition must keep preview's
    // color, not flash green).
    @Volatile private var colorHold: ColorHold? = null
    private var colorHoldCam: CameraCharacteristics? = null
    // Last-good black/white normalization. Same recording gap as color:
    // the dynamic per-frame levels vanish with the result, and the static
    // pattern fallback differs per channel by a few DN — after WB and the
    // shadow lift that reads as a greenish cast. Keyed by colorHoldCam.
    @Volatile private var levelsHold: LevelsHold? = null

    private data class GeoKey(val imgW: Int, val imgH: Int, val crop: String, val longEdge: Int)
    private var geoCacheKey: GeoKey? = null
    private var geoCache: BguGuideGeo? = null

    private data class LensCache(
        val key: String, val gains: FloatArray, val raw: FloatArray,
        val cols: Int, val rows: Int, val atMs: Long
    )
    private var lensCache: LensCache? = null
    private var lensMapRevision = 0L

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
    // Tier ladder: sampled-image tier is primary (Adreno-safe), the
    // TRANSFER bridge tier copies via the transfer engine (zero CPU copies),
    // the CPU-plane upload tier covers streams they reject (no import, no
    // lock), and the lock+memcpy tier is the last GPU rung. Per-session
    // latches.
    private var imagePipelineReady = false
    private var imageTierDead = false
    private var bridgeTierDead = false
    private var planeTierDead = false
    private var imageVerified = false
    private var bridgeVerified = false
    private var planeVerified = false
    private var copyVerified = false

    /** GPU sub-tiers with black-output probation (offer thread only). */
    private enum class BguGpuTier { IMAGE, BRIDGE, PLANE, COPY }

    // Export triple-buffer: offer() computes into one slot while render
    // samples the latest completed one. Two fresher frames always separate a
    // slot reuse, and the render thread drains GL after every swap, so no
    // slot is ever written while sampled.
    private val exportLock = Any()
    private val guideSlots = BguGuideSlots()
    private val exports = arrayOfNulls<HardwareBuffer>(3)
    private val exportW = IntArray(3)
    private val exportH = IntArray(3)
    private val exportSeq = LongArray(3)

    // Step-7 CPU fallback (no working Vulkan): same triple-slot discipline
    // for CPU guide bytes. One-way latch per session; fresh sessions retry
    // the GPU path. Render keeps one reusable slice-output buffer.
    @Volatile private var cpuFallback = false
    /** Gray-world WB for streams whose HAL runs no AWB; offer thread only. */
    private val wbFallback = RawWhiteBalanceFallback.Tracker()
    private var wbFallbackLogged = false
    // Manual BGU_CPU override (overlay tap): pins the CPU guide path even
    // with a healthy GPU. Sticky across sessions like the global mode.
    @Volatile private var cpuForced = false
    private val cpuGuides = arrayOfNulls<java.nio.ByteBuffer>(3)
    private val cpuGuideW = IntArray(3)
    private val cpuGuideH = IntArray(3)
    private val cpuGuideSeq = LongArray(3)

    // Stats (render-owned, read by snapshot()).
    @Volatile private var statFps = 0f
    private val quadPool = BguQuadPool()
    @Volatile private var guideBoost = false
    private var boostOffers = 0L
    @Volatile private var statSharp = 0f
    @Volatile private var statEffTarget = 1080
    @Volatile private var statAgeMs = 0f
    @Volatile private var statAgeMaxMs = 0f
    @Volatile private var statMs = 0f
    @Volatile private var statFitMs = 0f
    @Volatile private var statVkMs = 0f
    @Volatile private var statCpuMs = 0f
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
        val generation = gridExchange.generation
        val now = SystemClock.elapsedRealtime()
        val floorMs = when {
            recordMode -> 100L
            powerSave -> 66L
            else -> 15L
        }
        if (now - lastOfferMs < floorMs) return
        if (firstOfferMs == 0L) firstOfferMs = now
        lastOfferMs = now
        // Backlog guard: the reader drains FIFO, so when the render thread
        // falls far behind, every further frame is stale before it can
        // present — and the low-sample + guide dispatch below (the expensive
        // part of offer) would bury the camera handler deeper. Skip cheap,
        // before any sampling, until the presenter catches up. Threshold is
        // generous (5 frames): at 2-3 the guard itself juddered pans by
        // dropping frames the presenter was about to show (field report).
        if (seq - lastRenderedSeq > 5) {
            statBusy++
            return
        }
        // Stale-frame skip: the shared RAW reader drains FIFO (ZSL needs
        // every frame), so when offer cost exceeds the frame interval —
        // panning pushes smpMs to ~20 ms — queued frames arrive here
        // already old, and sampling each one in full keeps the drain
        // permanently behind (measured age 780 ms at 30 fps, 2026-10-13).
        // A frame older than ~4 frame intervals can never be the freshest
        // thing the presenter could show; skip its sampling so the drain
        // races back to live frames. VF-only: the caller's image lifecycle
        // (ZSL retention, scopes, peaking) is unaffected by returning here.
        val frameAgeMs = (SystemClock.elapsedRealtimeNanos() - image.timestamp) / 1_000_000L
        // Upper bound guards non-boot-clock timestamp sources: an absurd
        // age means the clocks do not match on this device, not staleness.
        if (frameAgeMs in 121L..5_000L) {
            statBusy++
            return
        }
        MemoryLeakDiagnostics.bguVfFrame()
        if (now >= memSampleNextMs) {
            memSampleNextMs = now + MemoryLeakDiagnostics.PERIODIC_INTERVAL_MS
            MemoryLeakDiagnostics.sample("bgu-vf-preview", extra = "offers=$seq")
        }
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
        boostOffers++
        val nextBoost = guideBoostNext(guideBoost, statVkMs, boostOffers)
        if (guideBoost && !nextBoost) boostOffers = 0 // re-warm after a release
        guideBoost = nextBoost
        val effTarget = effectiveTargetLongEdge()
        statEffTarget = effTarget
        val key = GeoKey(image.width, image.height, crop?.joinToString(",") ?: "full", effTarget)
        var geo = geoCache
        if (geo == null || geoCacheKey != key) {
            geo = try {
                BguGeometry.guideGeometry(image.width, image.height, crop, effTarget)
            } catch (_: Exception) {
                return
            }
            // Blur diagnosis: geometry changes are rare and always causal
            // (target, crop, or stream size moved) — log each transition.
            Log.i(TAG, "geo ${geoCacheKey?.let { "${it.imgW}x${it.imgH}/${it.crop}/${it.longEdge}" } ?: "init"} -> " +
                "${image.width}x${image.height}/${key.crop}/${effTarget} = ${geo.width}x${geo.height} step=${geo.step}")
            geoCacheKey = key
            geoCache = geo
            cachedParams = null // lens coordinates must follow geometry immediately
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
        // Normalization hold: recording offers no result, so the dynamic
        // per-frame black/white levels would fall back to statics and tint
        // the take greenish. Fresh result levels prime the hold; result-less
        // offers reuse the last-good preview values (same scene, seconds
        // old); a cold engine with no hold yet keeps the static fallback.
        if (colorHoldCam !== c) {
            colorHold = null
            levelsHold = null
            colorHoldCam = c
        }
        val freshLevels = if (result != null) {
            LevelsHold(blackLevels(c, result), whiteLevel(c, result))
        } else {
            null
        }
        val norm = resolveLevelsHold(
            freshLevels, levelsHold,
            LevelsHold(blackLevels(c, null), whiteLevel(c, null))
        )
        if (freshLevels != null) levelsHold = LevelsHold(norm.levels.copyOf(), norm.white)
        val levels = norm.levels.copyOf()
        val white = norm.white
        val inv = FloatArray(4) { i ->
            val den = white - levels[i]
            if (den > 1f) 1f / den else 1f / 1023f
        }
        val quadSize = lowW * lowH * 4
        val quad = quadPool.acquire(quadSize) ?: return
        var transferred = false
        try {
            val smpT0 = SystemClock.elapsedRealtimeNanos()
            val sampled = BguSample.sampleLowFrame(
                buf, rowStride, pixelStride, geo.left, geo.top, geo.step * BguGeometry.LOW_DF,
                lowW, lowH, channels, levels, inv, quad
            )
            if (!sampled) return
            val sharp = BguSample.guideSharpness(quad, lowW, lowH)
            statSharp = if (statSharp == 0f) sharp else statSharp * 0.9f + sharp * 0.1f
            val smpMs = (SystemClock.elapsedRealtimeNanos() - smpT0) / 1e6f
            statSmpMs = if (statSmpMs == 0f) smpMs else statSmpMs * 0.9f + smpMs * 0.1f

            val parT0 = SystemClock.elapsedRealtimeNanos()
            // Tone inputs (WB/CCM/ACES/lens map) move slowly; rebuilding them
            // every frame costs ~7 ms of camera-handler time on slower phones
            // (pure motion-to-photon latency). Refresh every 3rd offer (~100 ms
            // at 30 fps — invisible tone lag), reuse the immutable snapshot
            // between (fit jobs consume them sequentially on one thread).
            val params = if (cachedParams == null || cachedParamsCam !== c ||
                seq - cachedParamsSeq >= 3
            ) {
                snapshotParams(
                    c, result, buf, rowStride, pixelStride, image.width, image.height,
                    levels, geo, white
                )?.also {
                    cachedParams = it
                    cachedParamsSeq = seq
                    cachedParamsCam = c
                } ?: return
            } else {
                cachedParams!!
            }
            val parMs = (SystemClock.elapsedRealtimeNanos() - parT0) / 1e6f
            statParMs = if (statParMs == 0f) parMs else statParMs * 0.9f + parMs * 0.1f

            val slot = (seq % 3).toInt()
            if (!guideSlots.tryWrite(slot)) { statBusy++; return }
            try {
                // Per-offer evaluation: the automatic fallback and the manual
                // BGU_CPU override take the identical CPU path, and each job
                // records which guide it carries, so mid-stream taps flip cleanly.
                val useCpu = cpuFallback || cpuForced
                val guideOk = if (!useCpu) {
                    offerGpuGuide(image, buf, rowStride, pixelStride, geo, slot, channels, levels, white, offerT0)
                } else {
                    offerCpuGuide(buf, rowStride, pixelStride, geo, slot, channels, levels, white, offerT0)
                }
                if (!guideOk) return
                seq++
                statRawW = image.width
                statRawH = image.height
                synchronized(exportLock) { exportSeq[slot] = seq; if (useCpu) cpuGuideSeq[slot] = seq }
                guideSlots.publish(slot, seq)
                fitQueue.offerOrDrop(
                    LowJob(seq, generation, quad, lowW, lowH, params.lens, params.lensCols, params.lensRows,
                        params.wb, params.ccm, params.aces, params.white, params.fparams, params.iparams)
                )
                transferred = true
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
                guideSlots.finishWrite(slot) // release before waking the consumer
                renderQueue.offerOrDrop(RenderJob(seq, generation, slot, geo, lowW, lowH, rotation, mirrored, useCpu, image.timestamp))
            } finally {
                guideSlots.finishWrite(slot)
            }
        } finally {
            if (!transferred) quadPool.release(quad)
        }
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
        // GPU-sized geometry (boosted 2048x1536) reaches here when the
        // BGU_CPU tap lands between this offer's target and branch reads
        // (field log 2026-10-29: fatal cpu-guide 3 ms after the override).
        // Drop the frame; the next offer recomputes the CPU-capped geometry.
        if (geo.width > VfCpuNeon.MAX_EDGE || geo.height > VfCpuNeon.MAX_EDGE) return false
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
        image: android.media.Image, buf: ByteBuffer, rowStride: Int, pixelStride: Int,
        geo: BguGuideGeo,
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
        // Normalize LUT for this frame's levels (bit-exact vs the formula;
        // uploaded before compute so the shader never divides).
        val (lutMax, lutBytes) = try {
            val (blackQ, denQ) = VfLevels.toFixedQ6(levels, white)
            VfLevels.lutFor(blackQ, denQ)
        } catch (_: Exception) {
            return false
        }
        // GL->Vulkan ordering for this slot's export: wait in the kernel (
        // 250 ms cap) for the render thread's sample fence before the
        // dispatch reuses the buffer. Instant in the common case; the cap
        // keeps a wedged GL from wedging offers (swap-fail path stays the
        // fatal backstop).
        val fenceFd = synchronized(exportLock) { takeSlotFence(fenceFds, slot) }
        if (fenceFd >= 0) {
            var signaled = false
            try {
                signaled = try {
                    VfEglImport.awaitSyncFd(fenceFd, 250)
                } catch (_: Exception) {
                    false
                }
            } finally {
                if (signaled) {
                    try {
                        VfEglImport.closeSyncFd(fenceFd)
                    } catch (_: Exception) {
                    }
                } else {
                    // Keep the unsignalled fence for the next offer; closing
                    // it and writing anyway races the GPU's previous sample.
                    synchronized(exportLock) { storeSlotFence(fenceFds, slot, fenceFd) }
                }
            }
            if (signaled) {
                fenceWaitStreak = 0
            } else if (++fenceWaitStreak == 1 || fenceWaitStreak % 30 == 0) {
                Log.w(TAG, "guide fence wait timed out, dropping frame (%d)".format(fenceWaitStreak))
            }
            if (!signaled) return false
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
        if (vulkanReady && !imagePipelineReady && !imageTierDead) {
            val imgSpv = try {
                context.assets.open("shaders/vf/vf_superpixel_img.spv").use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            val imgCode = if (imgSpv == null) VfVulkan.IMAGE_UNSUPPORTED else try {
                VfVulkan.initImageNative(imgSpv)
            } catch (_: Exception) {
                VfVulkan.IMAGE_UNSUPPORTED
            }
            if (imgCode == VfVulkan.OK) {
                imagePipelineReady = true
            } else {
                imageTierDead = true
                Log.w(TAG, "BGU image tier unavailable (${VfVulkan.describe(imgCode)}); upload tier covers")
            }
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
        // Bridge-then-plane-then-copy upload ladder: the bridge rung copies
        // the imported camera allocation via the transfer engine (zero CPU
        // copies) and latches on any rejection; the plane tier uploads the
        // CPU plane (no import, no lock); the lock+memcpy tier is the last
        // GPU rung. Offer thread only.
        var gpuTier = BguGpuTier.COPY
        fun runUploadLadder(): Int {
            // The bridge runs the img pipeline: a pipeline that failed to
            // init dooms it too (skip the doomed JNI); a rejected image tier
            // does not (the transfer engine is a separate read path).
            if (imagePipelineReady && !bridgeTierDead) {
                val bridgeCode = VfVulkan.computeBridgeNative(hb, iparams, fparams)
                when (vfBridgeAction(bridgeCode, blackOutput = false)) {
                    VfTierAction.SKIP_FRAME -> return bridgeCode
                    VfTierAction.PRESENT -> {
                        gpuTier = BguGpuTier.BRIDGE
                        if (tierBlackOutput(buf, image.width, image.height, rowStride, pixelStride, levels, white, BguGpuTier.BRIDGE)) {
                            bridgeTierDead = true
                            Log.w(TAG, "BGU bridge tier black output; upload tier covers")
                        } else {
                            return bridgeCode
                        }
                    }
                    else -> {
                        bridgeTierDead = true
                        Log.w(TAG, "BGU bridge tier rejected (${VfVulkan.describe(bridgeCode)}); upload tier covers")
                    }
                }
            }
            if (!planeTierDead) {
                val planeCode = runPlaneTier(buf, rowStride, image.height, iparams, fparams)
                when (vfPlaneAction(planeCode, blackOutput = false)) {
                    VfTierAction.SKIP_FRAME -> return planeCode
                    VfTierAction.PRESENT -> {
                        gpuTier = BguGpuTier.PLANE
                        if (tierBlackOutput(buf, image.width, image.height, rowStride, pixelStride, levels, white, BguGpuTier.PLANE)) {
                            planeTierDead = true
                            Log.w(TAG, "BGU plane tier black output; lock-copy tier covers")
                        } else {
                            return planeCode
                        }
                    }
                    else -> {
                        planeTierDead = true
                        Log.w(TAG, "BGU plane tier rejected (${VfVulkan.describe(planeCode)}); lock-copy tier covers")
                    }
                }
            }
            gpuTier = BguGpuTier.COPY
            return VfVulkan.computeCopyNative(hb, iparams, fparams)
        }
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
                val up = VfVulkan.uploadLutNative(lutMax, lutBytes)
                if (up != VfVulkan.OK) {
                    up
                } else if (!imageTierDead) {
                    val imgCode = VfVulkan.computeImageNative(hb, iparams, fparams)
                    when (vfImageAction(imgCode, blackOutput = false)) {
                        VfTierAction.SKIP_FRAME -> imgCode
                        VfTierAction.PRESENT -> {
                            if (tierBlackOutput(buf, image.width, image.height, rowStride, pixelStride, levels, white, BguGpuTier.IMAGE)) {
                                imageTierDead = true
                                Log.w(TAG, "BGU image tier black output; bridge/upload tiers cover")
                                runUploadLadder()
                            } else {
                                gpuTier = BguGpuTier.IMAGE
                                imgCode
                            }
                        }
                        else -> {
                            imageTierDead = true
                            Log.w(TAG, "BGU image tier rejected (${VfVulkan.describe(imgCode)}); bridge/upload tiers cover")
                            if (imgCode == VfVulkan.BUSY) imgCode
                            else runUploadLadder()
                        }
                    }
                } else {
                    runUploadLadder()
                }
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
            // Upload tier is the last GPU rung: its failure drops to the
            // NEON guide instead of failing over to legacy.
            if (imageTierDead && BguSliceCpu.available) {
                if (!cpuFallback) {
                    cpuFallback = true
                    Log.w(TAG, "BGU upload tier failed (vulkan-$rc); CPU fallback engaged")
                }
                return false
            }
            if (++failStreak >= 5) fail("vulkan-$rc")
            return false
        }
        // Copy-tier probation: a black upload output drops to NEON guide.
        // Only the copy rung's own output convicts it (image/plane outputs
        // probate inline at dispatch).
        if (gpuTier == BguGpuTier.COPY && !copyVerified &&
            tierBlackOutput(buf, image.width, image.height, rowStride, pixelStride, levels, white, BguGpuTier.COPY)
        ) {
            if (BguSliceCpu.available) {
                cpuFallback = true
                Log.w(TAG, "BGU upload tier black output; CPU fallback engaged")
            } else {
                fail("vulkan-copy-black")
            }
            return false
        }
        busyStreak = 0
        failStreak = 0
        return true
    }

    /**
     * CPU-plane upload dispatch: a rowStride*height slice of the Camera2
     * plane into staging, then superpixel. No hardware-buffer import or
     * lock, so streams the image tier rejects still render.
     * COPY_UPLOAD_FAILED when no sliceable plane is available (the caller
     * latches the tier and the lock-copy rung covers).
     */
    private fun runPlaneTier(
        buf: ByteBuffer, rowStride: Int, imgH: Int, iparams: IntArray, fparams: FloatArray
    ): Int {
        val bytes = rowStride.toLong() * imgH
        if (bytes <= 0 || bytes > Int.MAX_VALUE || bytes > buf.remaining()) {
            return VfVulkan.COPY_UPLOAD_FAILED
        }
        val view = buf.duplicate()
        view.position(buf.position())
        view.limit(buf.position() + bytes.toInt())
        val slice = view.slice().order(buf.order())
        return try {
            VfVulkan.computePlaneNative(slice, bytes.toInt(), iparams, fparams)
        } catch (_: Exception) {
            VfVulkan.SUBMIT_FAILED
        }
    }

    /**
     * Black-output probation for one GPU tier (offer thread only). Lit
     * output verifies the tier at once; near-zero output convicts only
     * with a strong sparse input signal, so dark scenes never latch.
     * Unsampleable exports (no CPU-read usage) assume healthy.
     */
    private fun tierBlackOutput(
        buf: ByteBuffer, imgW: Int, imgH: Int, rowStride: Int, pixelStride: Int,
        levels: FloatArray, white: Float, tier: BguGpuTier
    ): Boolean {
        if (tierVerified(tier)) return false
        val outputMax = try {
            VfVulkan.sampleOutputNative()
        } catch (_: Exception) {
            -1
        }
        if (outputMax < 0 || outputMax > 2) {
            markTierVerified(tier)
            return false
        }
        var inputMax = 0
        try {
            val dup = buf.duplicate()
            dup.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val stepY = max(1, imgH / 16)
            val stepX = max(1, imgW / 16)
            var y = 0
            while (y < imgH) {
                var x = 0
                while (x < imgW) {
                    val off = y * rowStride + x * pixelStride
                    if (off + 1 < dup.capacity()) {
                        val v = dup.getShort(off).toInt() and 0xFFFF
                        if (v > inputMax) inputMax = v
                    }
                    x += stepX
                }
                y += stepY
            }
        } catch (_: Exception) {
            return false
        }
        val black = levels.maxOrNull()?.toInt() ?: 0
        val verdict = RawViewfinder.probeVfTierOutput(inputMax, black, white.toInt(), outputMax)
        if (verdict == RawViewfinder.Companion.VfTierProbe.VERIFIED) {
            markTierVerified(tier)
            return false
        }
        return verdict == RawViewfinder.Companion.VfTierProbe.BROKEN
    }

    private fun tierVerified(tier: BguGpuTier): Boolean = when (tier) {
        BguGpuTier.IMAGE -> imageVerified
        BguGpuTier.BRIDGE -> bridgeVerified
        BguGpuTier.PLANE -> planeVerified
        BguGpuTier.COPY -> copyVerified
    }

    private fun markTierVerified(tier: BguGpuTier) {
        when (tier) {
            BguGpuTier.IMAGE -> imageVerified = true
            BguGpuTier.BRIDGE -> bridgeVerified = true
            BguGpuTier.PLANE -> planeVerified = true
            BguGpuTier.COPY -> copyVerified = true
        }
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
        buf: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int,
        imgW: Int, imgH: Int,
        levels: FloatArray, geo: BguGuideGeo,
        white: Float
    ): LookParams? {
        val cfa = try {
            c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        } catch (_: Exception) {
            return null
        }
        val greenRow = if (cfa == 2 || cfa == 3) 1 else 0
        // Fresh color parts from this result (null when unreported): HAL WB
        // gains first (what the scene actually used), as-shot neutral
        // inversion next. Missing parts resolve from the last-good hold
        // (recording offers no result; unity would flash green), unity last.
        val halGains = try {
            result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        } catch (_: Exception) {
            null
        }
        val neutralPts = try {
            result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        } catch (_: Exception) {
            null
        }
        // Unity gains + NaN neutral (direct-opened Xiaomi aux lenses: no
        // AWB on the stream) render green: estimate from the frame instead.
        val fallbackNeutral = wbFallback.update(SystemClock.elapsedRealtime(),
            RawWhiteBalanceFallback.halAwbMissing(
                neutralPts?.let { n -> DoubleArray(n.size) { n[it].toDouble() } },
                halGains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) })
        ) {
            runCatching {
                RawWhiteBalanceFallback.estimate(buf, rowStride, pixelStride, imgW, imgH, cfa, levels, white,
                    RawWhiteBalanceFallback.locusFor(c))
            }.getOrNull()
        }
        if (fallbackNeutral != null && !wbFallbackLogged) {
            wbFallbackLogged = true
            Log.w(TAG, "HAL reported no white balance; gray-world neutral=" +
                ColorDiagnostics.values(fallbackNeutral))
        }
        val fallbackGains = fallbackNeutral?.let(RawWhiteBalanceFallback::gainsFor)
        val freshWb: FloatArray? = fallbackGains?.let { g -> floatArrayOf(g[0], 1f, 1f, g[3]) } ?: halGains?.let { gains ->
            val sensor = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            val mapped = floatArrayOf(sensor[0], sensor[if (greenRow == 0) 1 else 2],
                sensor[if (greenRow == 0) 2 else 1], sensor[3])
            FloatArray(4) { k ->
                mapped[k].takeIf { it.isFinite() && it > 0f }?.coerceIn(1f / 16, 16f) ?: 1f
            }
        } ?: neutralPts?.takeIf { it.size >= 3 }?.let { neutral ->
            val n = DoubleArray(3) { neutral[it].toDouble() }
            val inv = doubleArrayOf(1 / n[0], 1 / n[1], 1 / n[2])
            FloatArray(4) { k ->
                val v = inv[if (k == 0) 0 else if (k == 3) 2 else 1]
                v.takeIf { it.isFinite() && it > 0 }?.toFloat()?.coerceIn(1f / 16, 16f) ?: 1f
            }
        }
        // RAW-branch CCM: the HAL transform (row-major rationals) to the
        // look's column-major floats; null unless every element is usable.
        val halCcm = try {
            result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        } catch (_: Exception) {
            null
        }
        val freshCcm: FloatArray? = halCcm?.let { matrix ->
            val out = FloatArray(9)
            for (col in 0..2) for (row in 0..2) {
                val v = matrix.getElement(col, row).toDouble()
                if (!v.isFinite()) return@let null
                out[col * 3 + row] = v.toFloat()
            }
            out
        }
        val freshNeutral: DoubleArray? = fallbackNeutral?.copyOf()
            ?: neutralPts?.takeIf { it.size >= 3 }?.let { n -> DoubleArray(3) { n[it].toDouble() } }
        val freshGains: FloatArray? = fallbackGains
            ?: halGains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
        if (colorHoldCam !== c) {
            colorHold = null
            levelsHold = null
            colorHoldCam = c
        }
        val color = resolveColorHold(freshWb, freshCcm, freshNeutral, freshGains, colorHold)
        if (result != null) colorHold = color
        val wb = color.wb
        val ccm = color.ccm
        // JPEG-branch calibration: same save-path contract (dual-illuminant
        // solve with HAL-CCM fallback), cached across repeats.
        val calibrated = try {
            calibratedColor(c, color.neutral, color.gainsSensor, wb, ccm)
        } catch (_: Exception) {
            null
        }
        val aces = calibrated?.cameraToAcescgColumnMajor ?: ccm.copyOf()
        val camWhite = calibrated?.cameraWhiteNormalized ?: floatArrayOf(1f, 1f, 1f)
        // Lens map first: the EV meter below must run in the look's develop
        // domain (throttled re-parse with content-keyed reuse, cheap here).
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
        // Sensor-AE feed-forward: the HAL re-exposes in instant steps
        // (1/49 <-> 1/100 observed) that used to slam the VF before the slow
        // meter could chase them. The reference follows drift but not steps,
        // so aeNormEv cancels each step on the same offer it lands.
        val expNs = try {
            result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        } catch (_: Exception) {
            null
        }
        val iso = try {
            result?.get(CaptureResult.SENSOR_SENSITIVITY)
        } catch (_: Exception) {
            null
        }
        if (expNs != null && iso != null) {
            val cur = aeExposureLog2(expNs, iso)
            if (cur.isFinite()) {
                var (ref, norm) = aeUpdate(aeRefLog2, cur, AE_REF_ALPHA)
                if (kotlin.math.abs(norm) > 2.5f && aeRefLog2.isFinite()) {
                    // Anti-windup: compensation near saturation (a multi-EV
                    // swing) — fast-forward the reference so the saturated
                    // leak lasts offers, not seconds. Still exact: the shift
                    // below covers the whole reference move at any rate.
                    ref += (cur - ref) * 0.3
                    norm = (ref - cur).toFloat()
                }
                // Exactness shift: the reference moved by dRef, so the
                // scene-only chase state moves equally opposite — render (=
                // applied + norm) is preserved across reference adaptation by
                // construction, at any adapt rate. Only the REFERENCE part
                // shifts, never the exposure part: sensor steps must still
                // punch through norm into the render to cancel the
                // brighter/darker RAW on the same offer.
                val dRef = if (aeRefLog2.isFinite()) (ref - aeRefLog2).toFloat() else 0f
                aeRefLog2 = ref
                aeNormEv = norm.coerceIn(-3f, 3f)
                if (dRef != 0f) {
                    lastEvTarget = (lastEvTarget - dRef).coerceIn(-1.5f, 1.5f)
                    appliedEv -= dRef
                }
            }
        }
        // Adaptive preview EV at ~3 Hz: the SAVE'S OWN dense meter
        // (AdaptiveDevelopmentExposure.analyzeRaw) over the shown region —
        // same 65k population, lens model, rule, and tuning the capture
        // develops through, so VF EV == save EV by construction. Coarse
        // sparse meters (3k points, legacy's included) undershoot the dense
        // save meter by ~0.2 EV (missed sub-grid shadows); ~2 ms at 3 Hz is
        // negligible. Exponential chase with a deadband (no snap-on-seed:
        // the one-pole below ramps from 0 in ~10 frames, smooth by itself).
        if (++evCounter >= 10) {
            evCounter = 0
            val correction = try {
                val model = lens?.let {
                    LensShadingModel(
                        it.rows, it.cols, it.raw,
                        IntRectSnapshot(aL, aT, aR, aB), false
                    )
                }
                val pattern = BayerPattern.fromCamera2(cfa)
                AdaptiveDevelopmentExposure.analyzeRaw(
                    buf,
                    RawPlaneLayout(imgW, imgH, rowStride, pixelStride),
                    RawNormalization(pattern, levels.toList(), white),
                    RawCrop(geo.left, geo.top, geo.width * geo.step, geo.height * geo.step),
                    model, evWorkspace,
                    AdaptiveExposureTuning(
                        highlightHeadroom = previewHeadroom.toDouble(),
                        highlightSoftHeadroom = previewSoftHeadroom.toDouble(),
                        middleGreyTarget = previewMiddleGreyTarget,
                        highlightShoulder = previewShoulder,
                        centerWeight = previewCenterWeight,
                        focusX = previewFocusX,
                        focusY = previewFocusY
                    ).bounded()
                ).correctionEv
            } catch (_: Exception) {
                Double.NaN
            }
            if (correction.isFinite()) {
                // Scene-only target: subtract the feed-forward term so the
                // meter never chases sensor-AE steps (it read RAW the sensor
                // brightened by -aeNormEv; adding it back yields the scene
                // value). Render adds aeNormEv back below, so their sum is
                // exact at every offer — steps cancel, drift absorbs.
                val stepped = ((correction * evStrength.coerceIn(0f, 1f)).toFloat() - aeNormEv)
                    .coerceIn(-1.5f, 1.5f)
                // Exponential chase with a deadband: big scene moves snap the
                // target in one update (the one-pole below still glides the
                // render smoothly — no delay, no staircase), small errors ease
                // in, averaging flicker out. No snap-on-seed: the one-pole
                // ramps from 0 in ~10 frames, smooth by itself.
                lastEvTarget = AdaptiveDevelopmentExposure.chaseToward(
                    lastEvTarget, stepped, 0.05f
                )
            }
        }
        appliedEv += (lastEvTarget - appliedEv) * 0.3f
        // Total rendered EV: slow scene glide + instant AE-step cancellation.
        val totalEv = appliedEv + aeNormEv
        statEv = totalEv
        val snapAgx = agx
        val fparams = floatArrayOf(
            totalEv, snapAgx[0], snapAgx[1], snapAgx[2], snapAgx[3],
            snapAgx[4], snapAgx[5], snapAgx[6], snapAgx[7]
        )
        val iparams = intArrayOf(
            if (renderJpeg) (if (snapAgx[8] > 0.5f) 2 else 1) else 0, 0, if (lens != null) 1 else 0, greenRow,
            geo.left, geo.top, geo.step * BguGeometry.LOW_DF, aL, aT, aR, aB
        )
        return LookParams(
            lens?.gains ?: FloatArray(4) { 1f }, lens?.cols ?: 1, lens?.rows ?: 1,
            wb, ccm, aces, camWhite, fparams, iparams
        )
    }

    private data class LensSnap(
        val gains: FloatArray, val raw: FloatArray, val cols: Int, val rows: Int
    )

    // Lens crossfade: a freshly arrived (or changed) shading map glides in
    // over LENS_GLIDE_OFFERS offers instead of popping. Keyed by map content;
    // steady state returns cached references (zero alloc), blending copies
    // transiently. Prev arrays hold the last EMITTED gains (crossfade start).
    private var lensGlideKey: String? = null
    private var lensGlide = 1f
    private var lensPrevGains: FloatArray? = null
    private var lensPrevRaw: FloatArray? = null
    private var lensDiagDone = false

    private fun snapshotLens(c: CameraCharacteristics, result: CaptureResult?): LensSnap? {
        // HAL pre-applied shading: no map to apply (meter and look both run
        // unlensed, like the legacy path). A static read, cheap every frame.
        val preApplied = try {
            c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) == true
        } catch (_: Exception) {
            false
        }
        if (preApplied) {
            if (!lensDiagDone) {
                lensDiagDone = true
                Log.i(TAG, "lens HAL pre-applied: meter+look run unlensed")
            }
            return null
        }
        val now = SystemClock.elapsedRealtime()
        val cached = lensCache
        if (cached == null || now - cached.atMs >= 1000) {
            val map = try {
                result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            } catch (_: Exception) {
                null
            }
            if (map != null) {
                try {
                    val rows = map.rowCount
                    val cols = map.columnCount
                    if (rows > 0 && cols > 0 && map.gainFactorCount == rows * cols * 4) {
                        val raw = FloatArray(rows * cols * 4)
                        map.copyGainFactors(raw, 0)
                        if (raw.all { it.isFinite() && it >= 1f }) {
                            // Every map element affects the operator. Comparing
                            // three sentinels missed changes in other cells.
                            if (cached != null && cached.cols == cols && cached.rows == rows &&
                                cached.raw.contentEquals(raw)
                            ) {
                                lensCache = cached.copy(atMs = now)
                            } else {
                                // Transpose cell-interleaved [R, Ge, Go, B] to
                                // the planar layout the AOT filter requires
                                // (dim-0 stride 1); ~1 Hz, tiny. The raw
                                // interleaved array is kept for the EV meter's
                                // lens model (shared reference, no copy).
                                val cells = rows * cols
                                val gains = FloatArray(cells * 4)
                                for (cell in 0 until cells) for (ch in 0..3) {
                                    gains[ch * cells + cell] = raw[cell * 4 + ch]
                                }
                                val key = "$cols,$rows,${++lensMapRevision}"
                                lensCache = LensCache(key, gains, raw, cols, rows, now)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Keep the previous cache.
                }
            }
        }
        val snap = lensCache ?: return null
        if (!lensDiagDone) {
            lensDiagDone = true
            Log.i(TAG, "lens live map ${snap.cols}x${snap.rows} key=${snap.key}")
        }
        // Crossfade to the cached map: a fresh arrival fades in from unity, a
        // map switch glides from the last emitted gains. Both the shader gains
        // AND the meter's raw model fade together, so render and EV chase
        // agree at every step — no edge pop.
        if (lensGlideKey != snap.key) {
            lensGlideKey = snap.key
            lensGlide = 0f
            if (lensPrevGains?.size != snap.gains.size ||
                lensPrevRaw?.size != snap.raw.size
            ) {
                lensPrevGains = FloatArray(snap.gains.size) { 1f }
                lensPrevRaw = FloatArray(snap.raw.size) { 1f }
            }
        }
        if (lensGlide >= 1f) {
            lensPrevGains = snap.gains
            lensPrevRaw = snap.raw
            return LensSnap(snap.gains, snap.raw, snap.cols, snap.rows)
        }
        lensGlide = (lensGlide + 1f / LENS_GLIDE_OFFERS).coerceAtMost(1f)
        val t = lensGlide
        val fromG = lensPrevGains!!
        val fromR = lensPrevRaw!!
        val g = FloatArray(snap.gains.size) { glideGain(fromG[it], snap.gains[it], t) }
        val r = FloatArray(snap.raw.size) { glideGain(fromR[it], snap.raw[it], t) }
        lensPrevGains = g.copyOf()
        lensPrevRaw = r.copyOf()
        return LensSnap(g, r, snap.cols, snap.rows)
    }

    private fun calibratedColor(
        c: CameraCharacteristics,
        neutral: DoubleArray?, gainsSensor: FloatArray?,
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
            if (job === Poison) return
            job as LowJob
            try {
                if (disposed) return
                if (job.generation != gridExchange.generation) continue
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
                val back = gridExchange.beginFit(dims[0], dims[1], dims[2])
                val rcFit = try {
                    BguFit.fitInto(frame.guide, frame.developed, job.lowW, job.lowH, back.coeffs)
                } catch (_: Exception) {
                    -1
                }
                if (rcFit != 0) {
                    if (++lookFailStreak >= 20) fail("fit-failed")
                    continue
                }
                lookFailStreak = 0
                statFitMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
                gridExchange.publish(BguFittedGrid(job.seq, job.lowW, job.lowH, back), job.generation)
            } finally {
                quadPool.release(job.quad)
            }
        }
    }

    // --------------------------------------------------------------- render
    //
    // Owns the EGL context and all GL objects. Drains to the latest guide job
    // (stale frames never render), uploads the latest grid, slices, presents.

    private var egl: BguEglCore? = null
    private var gl: BguGlSlice? = null
    private val eglImages = LongArray(3)
    private val eglImageSlot = arrayOfNulls<HardwareBuffer>(3)
    // Per-slot GL->Vulkan handoff fences (dup'd native fds, -1 = none),
    // exportLock-guarded: render exports after swap, offer waits before
    // dispatch reuse. Common case is instant (fence signaled ~99 ms ago).
    private val fenceFds = IntArray(3) { -1 }
    private var fenceWaitStreak = 0
    @Volatile private var lastRenderedSeq = 0L
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
            if (latest.generation != gridExchange.generation || latest.seq <= lastRenderedSeq) {
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
        if (job.generation != gridExchange.generation || !guideSlots.tryRead(job.slot, job.seq)) return
        try {
            renderLeasedGuide(job)
        } finally {
            guideSlots.finishRead(job.slot)
        }
    }

    private fun renderLeasedGuide(job: RenderJob) {
        if (egl == null) {
            val surface = holder.surface
            if (surface == null || !surface.isValid) return
            val core = try {
                BguEglCore(surface)
            } catch (e: Exception) {
                fail("egl-init: ${e.message}")
                return
            }
            egl = core
            gl = try {
                BguGlSlice()
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
        val gridJob = gridExchange.acquireLatest()
        if (gridJob != null && gridJob.seq > lastGridSeq) {
            try {
                slice.uploadGrid(gridJob)
            } catch (e: Exception) {
                fail("grid-upload: ${e.message}")
                return
            }
            synchronized(gridExchange) {
                if (job.generation == gridExchange.generation) lastGridSeq = gridJob.seq
            }
        }
        if (lastGridSeq == 0L) {
            // No fit yet: wait for the first grid instead of slicing zeros.
            if (lastSwapMs == 0L && job.seq > 90) fail("no-grid")
            return
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        // WYSIWYG aspect-fill shared with every other present path; the
        // cover rect doubles as the overlay-alignment contract below.
        val fit = VfAspectFit.fill(width, height, job.geo.width, job.geo.height, job.rotation)
        val contentRect = Rect(fit[0], fit[1], fit[0] + fit[2], fit[1] + fit[3])
        if (contentRect != lastContentRect) {
            lastContentRect.set(contentRect)
            try {
                onContentRectChanged?.invoke(
                    VfContentGeometry(Rect(contentRect), job.geo.width, job.geo.height, job.rotation)
                )
            } catch (_: Exception) {
                // A failing listener must never break the present path.
            }
        }
        val ok = try {
            val drew = slice.draw(
                eglImage, cpuBuf, job.geo,
                job.rotation, job.mirrored, width, height, fit
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
        synchronized(gridExchange) {
            if (job.generation != gridExchange.generation) return
            lastSwapMs = now
            lastRenderedSeq = job.seq
        }
        // Frame age at present: sensor timestamp -> swap completion. This
        // is the motion-to-photon proxy the fps number cannot show: a
        // pipeline can hold 30 fps while displaying 200 ms-old frames.
        if (job.sensorTsNs > 0L) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - job.sensorTsNs) / 1e6f
            if (ageMs in 0f..2000f) {
                statAgeMs = if (statAgeMs == 0f) ageMs else statAgeMs * 0.9f + ageMs * 0.1f
                if (ageMs > statAgeMaxMs) statAgeMaxMs = ageMs
            }
        }
        statVfW = job.geo.width
        statVfH = job.geo.height
        // Steady-state telemetry (legacy VF_STATS_LOG_INTERVAL_MS convention):
        // presented fps + draw/swap cost + fit cost + guide size, 10 s cadence.
        if (now - lastStatsMs >= 10_000L) {
            lastStatsMs = now
            // Blur diagnosis: guide size alone is ambiguous — pair it with the
            // target that produced it, the surface it stretches to, and the
            // upscale factor (up ~1.0 = crisp, ~3x = visibly soft).
            // Displayed upscale: rotation swaps the guide axes on portrait
            // phones, so the honest factor is the worse axis ratio (the old
            // width-only math understated it ~1.4x vs the real ~1.9x).
            val quarter = job.rotation % 180 != 0
            val dispW = if (quarter) job.geo.height else job.geo.width
            val dispH = if (quarter) job.geo.width else job.geo.height
            val up = if (dispW > 0 && dispH > 0)
                maxOf(width.toFloat() / dispW, height.toFloat() / dispH) else 0f
            Log.i(TAG, "stats fps=%.1f age=%.0f ageMax=%.0f sharp=%.3f frameMs=%.1f drawMs=%.1f swapMs=%.1f fenMs=%.1f offerMs=%.1f smpMs=%.1f parMs=%.1f vkMs=%.1f fitMs=%.1f cpuMs=%.1f ev=%+.2f vf=%dx%d tgt=%d surf=%dx%d up=%.1fx seq=%d busy=%d".format(
                statFps, statAgeMs, statAgeMaxMs, statSharp, statMs, statDrawMs, statSwapMs, statFenceMs, statOfferMs, statSmpMs, statParMs,
                statVkMs, statFitMs, statCpuMs, statEv, job.geo.width, job.geo.height,
                statEffTarget, width, height, up, job.seq, statBusy))
            statAgeMaxMs = 0f
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

    override fun invalidateSession() = synchronized(gridExchange) {
        wbFallback.reset()
        wbFallbackLogged = false
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
        imagePipelineReady = false
        imageTierDead = false
        bridgeTierDead = false
        planeTierDead = false
        imageVerified = false
        bridgeVerified = false
        planeVerified = false
        copyVerified = false
        synchronized(exportLock) { for (i in 0..2) cpuGuideSeq[i] = 0L }
        cachedParams = null
        cachedParamsCam = null
        geoCacheKey = null
        geoCache = null
        lensCache = null
        aeRefLog2 = Double.NaN
        aeNormEv = 0f
        lensDiagDone = false
        calibStaticsCam = null
        calibStatics = null
        gridExchange.clear()
        guideSlots.clear()
        while (true) {
            val dropped = fitQueue.poll() ?: break
            if (dropped is LowJob) quadPool.release(dropped.quad)
        }
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
        MemoryLeakDiagnostics.sampleAsync("bgu-vf-disposed")
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

        /** BGU GPU guide floor cap: the proven full-rate envelope. */
        internal const val GPU_TARGET_CAP = 1080

        /** Step-2 guide target (sensor/2) when the detail boost engages. */
        internal const val BOOST_TARGET = 2160

        /** Boost engages below this sustained dispatch cost (ms). */
        internal const val BOOST_ON_VK_MS = 2.5f

        /** Boost releases above this sustained dispatch cost (ms). */
        internal const val BOOST_OFF_VK_MS = 7f

        /** Offers to observe before the boost may engage (~3 s at 30 fps). */
        internal const val BOOST_WARMUP_OFFERS = 90L

        /**
         * Pure boost latch (unit-tested): warm up first, engage on proven
         * headroom, release well past the engage point so the guide size
         * cannot flap between geometries.
         */
        internal fun guideBoostNext(current: Boolean, vkMs: Float, offers: Long): Boolean {
            if (offers < BOOST_WARMUP_OFFERS || vkMs <= 0f) return current
            return if (current) vkMs < BOOST_OFF_VK_MS else vkMs < BOOST_ON_VK_MS
        }

        /**
         * Pure floor policy behind [effectiveTargetLongEdge] (unit-tested):
         * GPU path only, never in record/power-save, lifted to the surface
         * long edge but never above [GPU_TARGET_CAP] and never below the
         * user's own target.
         */
        internal fun bguEffectiveTarget(
            userTarget: Int, surfaceLongEdge: Int, gpuPath: Boolean,
            recordMode: Boolean, powerSave: Boolean
        ): Int {
            // The NEON guide rejects extents above its edge cap: a 1440/2160
            // user target on the CPU path would throw every frame (fatal
            // cpu-guide, BGU dead for the session).
            if (!gpuPath) return minOf(userTarget, VfCpuNeon.MAX_EDGE)
            if (recordMode || powerSave) return userTarget
            if (surfaceLongEdge <= 0) return userTarget
            return maxOf(userTarget, minOf(surfaceLongEdge, GPU_TARGET_CAP))
        }

        /**
         * One lens-crossfade step: lerp from the last emitted gain toward the
         * new target. First arrival fades in from unity, map switches glide
         * from the old map — edges never pop. t in 0..1.
         */
        internal fun glideGain(from: Float, to: Float, t: Float): Float = from + (to - from) * t

        /** Offers (~30 Hz) to glide a fresh shading map in. */
        internal const val LENS_GLIDE_OFFERS = 24

        /** Per-offer follow rate of the AE reference (tau ~3 s at 30 Hz). */
        internal const val AE_REF_ALPHA = 0.01

        /** log2 sensor exposure (ns * ISO); NaN when unreported/unusable. */
        internal fun aeExposureLog2(expNs: Long, iso: Int): Double =
            if (expNs > 0 && iso > 0) kotlin.math.log2(expNs.toDouble() * iso) else Double.NaN

        /**
         * One AE-reference step. Returns (newRef, normEv): the reference
         * one-pole-follows [cur] (seeds on first call), and normEv = ref - cur
         * is the instant feed-forward compensation — a +1 EV sensor step
         * yields normEv ~= -1 on that same offer, so render stays put while
         * the slow meter never sees the step. Self-balancing: as ref absorbs
         * the step, the meter target absorbs the same amount (see
         * snapshotParams), so their sum never moves at any adapt rate.
         */
        internal fun aeUpdate(refLog2: Double, curLog2: Double, alpha: Double): Pair<Double, Float> {
            val ref = if (refLog2.isFinite()) refLog2 + (curLog2 - refLog2) * alpha else curLog2
            return ref to (ref - curLog2).toFloat()
        }

        /** Unity WB when no result and no hold ever seeded. */
        internal val UNITY_WB = floatArrayOf(1f, 1f, 1f, 1f)

        /** Identity CCM (column-major) when no result and no hold ever seeded. */
        internal val IDENTITY_CCM = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

        /**
         * Merge result-derived color parts with the last-good hold: fresh
         * parts win; missing parts fall back to [held], then the unity /
         * identity defaults. Returned arrays are always fresh copies, so a
         * published hold is never mutated downstream.
         */
        internal fun resolveColorHold(
            freshWb: FloatArray?, freshCcm: FloatArray?,
            freshNeutral: DoubleArray?, freshGains: FloatArray?,
            held: ColorHold?
        ): ColorHold = ColorHold(
            wb = (freshWb ?: held?.wb ?: UNITY_WB).copyOf(),
            ccm = (freshCcm ?: held?.ccm ?: IDENTITY_CCM).copyOf(),
            neutral = (freshNeutral ?: held?.neutral)?.copyOf(),
            gainsSensor = (freshGains ?: held?.gainsSensor)?.copyOf()
        )

        /**
         * Normalization precedence: fresh result levels win, else the
         * last-good preview hold, else the static fallback. Defensive copy.
         */
        internal fun resolveLevelsHold(
            fresh: LevelsHold?, held: LevelsHold?, fallback: LevelsHold
        ): LevelsHold {
            val pick = fresh ?: held ?: fallback
            return LevelsHold(pick.levels.copyOf(), pick.white)
        }
    }
}

/**
 * Last-good color inputs for offers without a capture result (RAW video
 * recording). Unity WB + identity CCM would flash green, so the engine
 * holds the last result-derived values (same scene, seconds old).
 */
/**
 * Last-good black/white normalization for offers without a capture result
 * (RAW video recording). The dynamic per-frame levels vanish with the
 * result; the static pattern fallback differs per channel and would tint
 * the take greenish, so the engine holds preview's last values.
 */
internal data class LevelsHold(
    /** Per-channel black levels in CFA phase order. */
    val levels: FloatArray,
    /** White level. */
    val white: Float
)

internal data class ColorHold(
    /** Canonical [R, Gr, Gb, B] WB, sanitized. */
    val wb: FloatArray,
    /** HAL CCM, column-major, sanitized. */
    val ccm: FloatArray,
    /** SENSOR_NEUTRAL_COLOR_POINT or null. */
    val neutral: DoubleArray?,
    /** HAL RggbChannelVector in sensor order or null. */
    val gainsSensor: FloatArray?
)

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
