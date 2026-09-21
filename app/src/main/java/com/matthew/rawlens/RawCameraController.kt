// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ManualControl { ISO, SHUTTER, WHITE_BALANCE, FOCUS_DISTANCE, EXPOSURE_COMPENSATION }

/** DSLR-style capture intent exposed by the viewfinder mode switcher. */
enum class CaptureExposureMode { AUTO, PROGRAM, ZSL, MANUAL }

/**
 * Adaptive development-exposure strength shared by saves and the JPEG VF
 * preview, so the preview shows the exposure the capture will develop with.
 */
internal fun adaptivePreviewStrength(mode: CaptureExposureMode, settings: JpegOutputSettings): Float =
    when (mode) {
        CaptureExposureMode.AUTO, CaptureExposureMode.ZSL ->
            if (settings.adaptiveExposureAuto) 1f else 0f
        CaptureExposureMode.PROGRAM -> settings.adaptiveExposureProgramStrength
        CaptureExposureMode.MANUAL -> 0f
    }

/**
 * VF overlay effect bits. AGX+ shows only when the JPEG tonemap actually
 * renders the sliders — the RAW Reinhard path ignores them, so flagging AGX+
 * there would claim an effect the displayed frame does not have.
 */
internal fun vfOverlayEffectBits(
    jpegTonemap: Boolean,
    settings: JpegOutputSettings
): List<String> = buildList {
    if (settings.ultraHdr) add("UHDR")
    if (jpegTonemap && (settings.agxContrast != 1f ||
        settings.agxSaturation != 1f ||
        settings.agxPurityBoost != 1f)
    ) add("AGX+")
}

enum class AeMeteringMode(val preferenceValue: Int, val label: String) {
    AUTO(-1, "AUTO"),
    CENTER_WEIGHTED(0, "CENTER"),
    FRAME_AVERAGE(1, "AVERAGE"),
    SPOT(2, "SPOT");

    companion object {
        fun fromPreference(value: Int): AeMeteringMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AUTO
    }
}

data class ManualControlRange(
    val minimum: Long,
    val maximum: Long,
    val current: Long,
    val automatic: Boolean
)

data class LensOption(val cameraId: String, val label: String, val selected: Boolean)

enum class RawZslState { OFF, WARMING_UP, ACTIVE, FALLBACK }

data class RawZslStatus(val state: RawZslState, val detail: String,
                       val bufferedFrames: Int = 0)

data class DynamicExposureSettings(
    val enabled: Boolean = false,
    val balance: Float = 1f,
    val isoLimit: Int = 0,
    val shutterLimitNanos: Long = 0L,
    val useAutoSafeShutter: Boolean = true
)

private data class PendingZslResult(
    val result: TotalCaptureResult,
    val motionRadiansPerSecond: Float,
    val requestEpoch: Long
)

private data class PendingHdrFrame(val image: Image, val result: TotalCaptureResult)

class RawCameraController(
    private val context: Context,
    private val viewfinder: AutoFitTextureView,
    private val onState: (String) -> Unit,
    private val onMetadata: (iso: Int, shutter: Long, wb: Int) -> Unit,
    private val onInfo: (dngInfo: String, sensorInfo: String) -> Unit,
    private val onCaptureEnabled: (Boolean) -> Unit,
    private val onControls: (iso: String, shutter: String, wb: String, focus: String, ev: String, lens: String, flash: String, flashEnabled: Boolean) -> Unit,
    private val enabledCameraIds: () -> Set<String>,
    private val preferredCameraId: () -> String?,
    initialOisEnabled: Boolean,
    initialRawZslEnabled: Boolean,
    initialRawZslFrameCount: Int,
    initialDynamicExposureSettings: DynamicExposureSettings,
    initialEttrSettings: EttrSettings,
    initialAeMeteringMode: AeMeteringMode,
    initialRawHistogramEnabled: Boolean,
    initialHistogramSourceRaw: Boolean,
    initialProgramAeProfile: ProgramAeProfile = ProgramAeProfile(),
    private val dngWriterBackend: () -> DngWriterBackend,
    private val dngMetadataOverrides: (cameraId: String?) -> DngMetadataOverrides,
    private val onRawZslStatus: (RawZslStatus) -> Unit,
    private val onDebugState: (String) -> Unit,
    private val onMeteringReleased: () -> Unit,
    private val onFocusLock: (locked: Boolean, indefinite: Boolean, deadlineMs: Long) -> Unit,
    private val onRawHistogram: (RgbHistogram) -> Unit,
    private val gpsLocation: () -> GpsLocation? = { null },
    private val onActiveCameraChanged: (cameraId: String?) -> Unit = { },
    private val rawViewfinder: RawViewfinder? = null,
    private val onRawVfDebug: (String) -> Unit = { },
    initialRawStreamCompatMode: Boolean = false,
    private val onRawStreamCompatMode: () -> Unit = { }
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraExecutor = Executor { command ->
        if (!cameraHandler.post(command)) throw RejectedExecutionException("RawCamera handler is stopped")
    }
    private val cameraStateLock = Any()
    @Volatile private var running = false
    @Volatile private var destroyed = false
    @Volatile private var opening = false
    @Volatile private var lifecycleGeneration = 0
    // Serial worker for JPEG development, HDR merges and AI-denoised saves: one at a
    // time so full-resolution CPU/GPU allocation peaks never overlap. The physical
    // queue accepts a complete ZSL DNG selection; hasProcessingCapacity() applies
    // the smaller JPEG-development safety bound.
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_QUEUED_SAVES)
    )
    // Parallel worker for plain DNG-only saves (no JPEG develop, no AI inference:
    // each job owns an independent DngCreator + MediaStore entry). A 30-frame ZSL
    // burst drains ~2x faster than serially, so the gap between back-to-back
    // bursts is the short ring refill, not the whole queue. Kept at 2 threads:
    // 3 concurrent full-res native writes spiked CPU/binder load enough to stall
    // capture results on MediaTek HALs (overflow storm -> session fallback).
    // JPEG/HDR/AI work stays on the serial writer above and never contends with
    // these threads on the GPU.
    private val dngWriter = ThreadPoolExecutor(
        DNG_WRITER_THREADS, DNG_WRITER_THREADS,
        0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(DNG_WRITER_QUEUE_SLOTS)
    )
    // Metering sampler worker: full-resolution Bayer sampling costs hundreds of ms on
    // some sensors and must never run on the camera handler (see dispatchMeteringSample).
    // Below-normal priority so it never contends with acquisition, pairing, or saves.
    private val meteringExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawLensMetering").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val meteringInFlight = AtomicBoolean(false)
    @Volatile private var rawDeveloper: RawDevelopmentCoordinator? = null
    private val pendingImages = ConcurrentHashMap<Long, Image>()
    private val pendingResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pendingZslResults = ConcurrentHashMap<Long, PendingZslResult>()
    private val pendingTimeouts = ConcurrentHashMap<Long, Runnable>()
    private val captureInProgress = AtomicBoolean(false)
    // True only while Camera2 is physically executing a still sequence. Unlike captureInProgress,
    // this becomes false before RAW/JPEG pairing/development completes and is the correct guard
    // for AF trigger safety.
    private var cameraCaptureSequenceActive = false
    private val captureSequence = AtomicInteger(0)
    private var streamCapture: ForwardRawSelection? = null
    private val previewRawTimestamps = ConcurrentHashMap.newKeySet<Long>()

    private val activeFramesRemaining = AtomicInteger(0)
    private var activeHdrBracket = false
    private var activeHdrSaveEachBracket = false
    private var activeHdrSaveDebugFrames = false
    private var activeHdrStops = 2
    private val pendingHdrFrames = ArrayList<PendingHdrFrame>(3)
    /**
     * Hybrid top-up (GCam ZSL+PSL style): ring frames held aside while fresh
     * forward stills complete the burst under one stem. Empty unless a top-up
     * capture is in flight; guarded by [topupActive]. Fresh arrivals accumulate
     * in [topupFrames] via pairAndSave until the remainder is filled.
     */
    private var topupActive = false
    private var topupHoldout: List<BufferedRawFrame> = emptyList()
    private val topupFrames = ArrayList<BufferedRawFrame>()
    /**
     * Last take size observed by selectAndSaveRawZsl (selected.size, or -1 when
     * blocked before touching the ring). Drives the wait-loop empty-ring
     * escalation counter; -1 pauses it so queue-full waits don't force bursts.
     */
    private var lastZslTakeSize: Int = -1
    /**
     * Serialized still-burst chain (MediaTek HAL hardening). A pipelined
     * N-frame captureBurst overruns this HAL's gralloc pool (reader full with
     * ~2 frames paired, 5 frames aborted in flight, log-proven), so stills are
     * submitted one at a time: each pair completion submits the next. In flight
     * stays at ~2 buffers instead of N+. Prebuilt requests wait here; cleared
     * in finishCapture (the choke point of every chain end).
     */
    private val pendingStillRequests = ArrayDeque<CaptureRequest>()
    private var activeStillCallback: CameraCaptureSession.CaptureCallback? = null
    /**
     * Consecutive forward chains that paired zero frames before dying
     * (failed/aborted/timed-out/overflowed with nothing arrived). Any paired
     * frame proves the HAL is producing and resets this: only a HAL that
     * produces NOTHING twice in a row triggers a session rebuild, the sole
     * recovery proven (log: MediaTek P2 heap errors persisting across aborts).
     * [chainPairedAny] tracks the in-flight chain.
     */
    private var consecutiveDeadChains = 0
    private var chainPairedAny = false
    /**
     * Consecutive transient cooldowns with zero paired frames since. A single
     * park is prudence (lag spike); a second consecutive fruitless one proves
     * request-level recovery cannot fix this HAL state — only a session
     * rebuild can (user-proven via manual frame-count changes). Reset on any
     * pair, save-completion rearm, and session open.
     */
    private var fruitlessCooldowns = 0
    private val pendingSaveCount = AtomicInteger(0)
    // Acquired RAW inputs awaiting development; excludes sidecar-only jobs.
    private val pendingFrameSaveCount = AtomicInteger(0)
    private val pendingJpegCount = AtomicInteger(0)
    private val jpegServiceLock = Any()
    @Volatile private var captureTimeout: Runnable? = null
    private var characteristics: CameraCharacteristics? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private val retiredRawReaders = java.util.concurrent.ConcurrentLinkedQueue<ImageReader>()

    private fun closeRetiredRawReaders() {
        retiredRawReaders.toList().forEach { reader ->
            if (RawImageOwnership.count(reader) == 0 && retiredRawReaders.remove(reader)) reader.close()
        }
    }
    private var rawReaderMaxImages = 0
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var selectedCameraId: String? = null
    private var rawCameraIds: List<String> = emptyList()
    private var activePhysicalCameraId: String? = null
    private var selectedIso: Int? = null
    private var selectedExposureNanos: Long? = null
    private var selectedWbKelvin: Int? = null
    private var selectedFocusDistanceDiopters: Float? = null
    private var exposureCompensation = 0
    @Volatile private var dynamicExposureSettings = initialDynamicExposureSettings
    @Volatile private var programAeProfile = initialProgramAeProfile.validated()
    private var dynamicIso: Int? = null
    private var dynamicShutterNanos: Long? = null
    private var dynamicIsoLimited = false
    private var dynamicShutterLimited = false
    private var dynamicExposureProbe: Runnable? = null
    // PROGRAM custom-AE live state: RAW brightness drives sensor ISO + shutter directly
    // (AE_OFF repeating + still). ETTR is AUTO-only and never touches this pair.
    private var programStreaming = false
    private var lastProgramUpdateMs = Long.MIN_VALUE
    private var programBrightness = Float.NaN
    /** EMA of sampled brightness; single-sample noise must not yank the target. */
    private var programBrightnessEma = Float.NaN
    private var programConverged = false
    // Glide target: the solver output the applied pair (dynamicIso/dynamicShutterNanos)
    // eases toward in adaptive steps (fast far away, gentle up close).
    private var programTargetIso: Int? = null
    private var programTargetShutterNanos: Long? = null
    private var programTargetLimits: ExposureBalanceLimits? = null
    private var programTargetLockMode: ProgramLockMode = ProgramLockMode.NONE
    private var programTargetLockedIso: Int = 0
    private var programTargetLockedShutterNanos: Long = 0L
    private var programRampCallback: Runnable? = null
    /**
     * Fast start: the first metering result after (re)entry applies immediately
     * instead of gliding in, so the preview never sits overexposed while the ramp
     * would otherwise walk stops toward the target. Tracking glides from there.
     */
    private var programFastStart = true
    // RAW-based ETTR single-exposure state. The repeating preview keeps running under
    // hardware AE; every sampled RAW frame refreshes this pair, which the next still
    // capture (and HDR bracket base) uses instead of the Camera2 meter.
    @Volatile private var ettrSettings = initialEttrSettings
    private var ettrIso: Int? = null
    private var ettrShutterNanos: Long? = null
    private var ettrConverged = false
    private var ettrHottest = Float.NaN
    /** Measured clipped fractions (hottest/second) of the latest sampled frame. */
    private var ettrClipHot = Float.NaN
    private var ettrClipSecond = Float.NaN
    private var lastEttrUpdateMs = Long.MIN_VALUE
    /** Wall time of the last metering sample; slow samplers back off (see dispatch). */
    @Volatile private var lastMeteringSampleMs = 0L
    private var ettrStreaming = false
    private var torchEnabled = false
    private var oisEnabled = initialOisEnabled
    @Volatile private var aeMeteringMode = initialAeMeteringMode
    @Volatile private var rawZslRequested = initialRawZslEnabled
    @Volatile private var rawZslFrameCount = initialRawZslFrameCount.coerceIn(1, MAX_ZSL_FRAMES)
    private var rawZslDisabledForSession = false
    private var rawZslFallbackDetail: String? = null
    /**
     * Transient ZSL outage (PhotonCamera/GCam style: failures are per-shot, never
     * session-fatal). Overflow storms, watchdog misses and isolated repeating-
     * request failures park the ring here instead of latching
     * [rawZslDisabledForSession]; every later press re-attempts ZSL once this
     * wall-clock deadline passes. Only a rejected stream combination (which can
     * never succeed) still latches the session fallback.
     */
    private var rawZslCooldownUntilMs = 0L
    /**
     * Repeating-RAW-stream health latch (GCam non-ZSL style degradation). Set when
     * a freshly started stream never becomes productive (overflow storm or
     * watchdog trip with zero paired frames): this HAL cannot sustain the
     * continuous RAW stream, so presses fire instant forward bursts under the
     * ZSL stem instead of waiting on a ring that will never fill. A stream that
     * filled and only later sickened is NOT marked — it keeps per-shot retry.
     * Cleared on session open and on ZSL (re-)entry.
     */
    private var rawZslStreamBroken = false
    /** Hybrid top-up (GCam ZSL+PSL style): complete a thin ring with fresh forward
     * captures under one stem instead of refusing the shutter. User-toggled. */
    @Volatile private var zslHybridTopupEnabled = true
    @Volatile private var vfPreviewMode: VfPreviewMode = VfPreviewMode.FOLLOW
    @Volatile private var vfTargetLongEdge: Int = VfResolution.MAX
    private var rawViewfinderStreaming = false
    private var rawZslStreaming = false
    private var rawZslRequestEpoch = 0L
    private var rawZslCapacity = 0
    private var rawZslTargetFpsRange: Range<Int>? = null
    private var rawZslRealtimeTimestamps = false
    private var rawZslBuffer: RawZslBuffer? = null
    private var rawZslWatchdog: Runnable? = null
    private var rawZslHasFrame = false
    private var rawZslReportedSize = 0
    private val zslOverflowTracker = ZslOverflowTracker()
    private var lastRawZslStatus: RawZslStatus? = null
    @Volatile private var rawHistogramEnabled = initialRawHistogramEnabled
    @Volatile private var histogramSourceRaw = initialHistogramSourceRaw
    /** Live RAW histogram-only repeating stream used when the user selects the RAW source
     * outside ZSL (AUTO/PROGRAM/MANUAL). Frames are sampled for the histogram and closed;
     * nothing is buffered and ZSL status is unaffected. */
    private var rawHistogramStreaming = false
    private var rawHistogramDisabledForSession = false
    private var lastRawHistogramSampleMs = Long.MIN_VALUE
    @Volatile private var lastPreviewSensorTimestamp = Long.MIN_VALUE
    private var latestPreviewResult: TotalCaptureResult? = null
    private val motionTracker = CameraMotionTracker(context)
    private var lastIso = 100
    private var lastExposureNanos = 10_000_000L
    private var lastWbKelvin: Int? = null
    private var hasPreviewMetadata = false
    private var lastPreviewMetadataPublishMs = 0L
    private var afRegion: MeteringRectangle? = null
    private var aeRegion: MeteringRectangle? = null

    // Open Camera continuous-picture autofocus port.
    // Touch focus is held (AF_MODE_AUTO + regions) until an explicit release:
    // retap elsewhere, double-tap/button reset, manual focus, lens switch, or stop().
    private var openCameraTouchFocusActive = false
    private var openCameraTouchFocusCompleted = false
    private var openCameraTouchFocusTimeout: Runnable? = null
    private var openCameraContinuousFocusReset: Runnable? = null
    private var pendingTouchFocusStart: Runnable? = null
    private var touchFocusStartElapsedMs = 0L
    private var touchFocusStartSent = false
    /** True once FOCUSED_LOCKED is held (badge-worthy); false while scanning or on failure. */
    private var touchFocusLockedSharp = false
    /** Long-press locks ignore the auto-release timer until double-tap. */
    private var focusLockIndefinite = false
    /** Elapsed-realtime expiry of a timed lock; 0 when indefinite or unlocked. */
    private var focusLockDeadlineMs = 0L

    private var lastDebugUpdateMs = 0L
    private var lastRawVfDebugMs = 0L
    @Volatile private var deviceOrientationDegrees = 0
    @Volatile private var captureFormat = CaptureFormat.DNG_ONLY
    private var activeCaptureFormat = CaptureFormat.DNG_ONLY
    private var activeOutputOrientation = 1
    @Volatile private var captureExposureMode = CaptureExposureMode.AUTO
    private var activeCaptureExposureMode = CaptureExposureMode.AUTO
    private var activeAdaptiveExposure = SharedAdaptiveExposure()
    @Volatile private var jpegOutputSettings = JpegOutputSettings()
    private var activeJpegOutputSettings = JpegOutputSettings()
    @Volatile private var denoiseSettings = DenoiseSettings()

    private var activeDenoiseSettings = DenoiseSettings()
    private val previewLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                                       oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            configurePreviewTransform(right - left, bottom - top)
        }
    }

    fun start() {
        if (destroyed || running) return
        running = true
        lifecycleGeneration++
        viewfinder.removeOnLayoutChangeListener(previewLayoutListener)
        viewfinder.addOnLayoutChangeListener(previewLayoutListener)
        viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val openedCamera = camera
                if (openedCamera != null && session == null) {
                    createSession(openedCamera, lifecycleGeneration)
                } else {
                    open()
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configurePreviewTransform(width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (viewfinder.isAvailable) {
            Log.i(LOG_TAG, "Viewfinder surface ready at start; opening camera")
            open()
        } else {
            Log.i(LOG_TAG, "Viewfinder surface not yet available; waiting for surface callback")
            onState("WAITING FOR VIEWFINDER")
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!running || destroyed || opening || camera != null) return
        opening = true
        val generation = lifecycleGeneration
        try {
        val configuredIds = enabledCameraIds()
        val candidates = if (configuredIds.isEmpty()) cameraManager.cameraIdList.toList() else configuredIds.toList()
        rawCameraIds = candidates.filter { cameraId ->
            resolveOpenCameraId(cameraId) != null
        }.sortedBy(::opticalMetric)
        val id = selectedCameraId?.takeIf(rawCameraIds::contains)
            ?: preferredCameraId()?.takeIf(rawCameraIds::contains)
            ?: rawCameraIds.firstOrNull()
        if (id == null) {
            opening = false
            onState("NO RAW CAMERA")
            return
        }
        selectedCameraId = id
        val route = resolveCameraRoute(id)
        if (route == null) {
            opening = false
            onState("RAW CAMERA $id UNAVAILABLE")
            return
        }
        val openCameraId = route.openCameraId
        activePhysicalCameraId = route.physicalCameraId
        characteristics = route.characteristics
        hasPreviewMetadata = false
        lastPreviewMetadataPublishMs = 0L
        lastWbKelvin = null
        lastPreviewSensorTimestamp = Long.MIN_VALUE
        latestPreviewResult = null
        rawZslDisabledForSession = false
        rawZslFallbackDetail = null
        rawZslCooldownUntilMs = 0L
        rawPreviewRetryUntilMs = 0L
        rawPreviewFailures = 0
        rawViewfinder?.invalidateSession()
        // A fresh session deserves a fresh verdict on the repeating stream.
        rawZslStreamBroken = false
        consecutiveDeadChains = 0
        chainPairedAny = false
        fruitlessCooldowns = 0
        rawZslHasFrame = false
        rawHistogramDisabledForSession = false
        rawHistogramStreaming = false
        clampControlsToCamera()
        
        val pixelArraySize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorInfo = String.format(java.util.Locale.US, "RAW\n%s", 
            if (pixelArraySize != null) "${pixelArraySize.width}x${pixelArraySize.height}" else "SENSOR")
        
        val maxDepth = characteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val dngInfo = String.format(java.util.Locale.US, "DNG\n%d-bit RAW", 
            if (maxDepth != null) 32 - Integer.numberOfLeadingZeros(maxDepth) else 10)
        
        onInfo(dngInfo, sensorInfo)
        publishControls()
        onActiveCameraChanged(id)
        
        onState("OPENING RAW")
        Log.i(LOG_TAG, "Opening camera $openCameraId (selected $id)")
        cameraManager.openCamera(openCameraId, deviceCallback(generation), cameraHandler)
        } catch (failure: CameraAccessException) {
            opening = false
            Log.w(LOG_TAG, "openCamera failed: ${cameraAccessReason(failure)}")
            if (running) onState("CAMERA UNAVAILABLE")
        } catch (failure: SecurityException) {
            opening = false
            Log.w(LOG_TAG, "openCamera denied", failure)
            if (running) onState("CAMERA PERMISSION NEEDED")
        }
    }

    private fun deviceCallback(generation: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            synchronized(cameraStateLock) {
                if (!isCurrent(generation)) {
                    device.close()
                    return
                }
                opening = false
                camera = device
            }
            Log.i(LOG_TAG, "Camera device opened: ${device.id}")
            createSession(device, generation)
        }
        override fun onDisconnected(device: CameraDevice) {
            synchronized(cameraStateLock) {
                device.close()
                if (camera === device) camera = null
            }
            if (!isCurrent(generation)) return
            opening = false
            Log.w(LOG_TAG, "Camera device disconnected: ${device.id}")
            onState("CAMERA DISCONNECTED")
        }
        override fun onError(device: CameraDevice, error: Int) {
            synchronized(cameraStateLock) {
                device.close()
                if (camera === device) camera = null
            }
            if (!isCurrent(generation)) return
            opening = false
            Log.w(LOG_TAG, "Camera device error on ${device.id}: $error")
            onState(if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) "CAMERA IN USE" else "CAMERA ERROR")
        }
    }

    private var lastSessionWaitStatusMs = 0L

    private fun createSession(device: CameraDevice, generation: Int) {
        if (!isCurrent(generation) || camera !== device || session != null) return
        // Camera open and TextureView availability are independent asynchronous events. On a
        // cold launch the device can open first; wait for the surface and let its listener call
        // us again instead of leaving an open camera with no session until the next Activity.
        if (!viewfinder.isAvailable || viewfinder.surfaceTexture == null) {
            Log.i(LOG_TAG, "Session deferred: viewfinder surface not available " +
                "(available=${viewfinder.isAvailable} visibility=${viewfinder.visibility})")
            onState("WAITING FOR VIEWFINDER")
            return
        }
        if (!viewfinder.isLaidOut || viewfinder.width <= 0 || viewfinder.height <= 0) {
            // Reposts every frame while unlaid-out: throttle the user-visible status.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSessionWaitStatusMs > 500L) {
                lastSessionWaitStatusMs = now
                Log.i(LOG_TAG, "Session deferred: viewfinder not laid out " +
                    "(laidOut=${viewfinder.isLaidOut} ${viewfinder.width}x${viewfinder.height})")
                onState("WAITING FOR LAYOUT")
            }
            viewfinder.post { createSession(device, generation) }
            return
        }
        val c = characteristics ?: run {
            Log.e(LOG_TAG, "Session aborted: no camera characteristics")
            onState("NO SENSOR DATA")
            return
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
            Log.e(LOG_TAG, "Session aborted: no stream configuration map")
            onState("NO SENSOR DATA")
            return
        }
        val rawSize = largestRawSize(map) ?: run {
            Log.e(LOG_TAG, "Session aborted: no RAW_SENSOR size")
            onState("RAW UNAVAILABLE")
            return
        }
        rawZslCapacity = calculateRawZslCapacity(rawSize)
        rawZslRealtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        rawZslBuffer?.clear()
        rawZslBuffer = rawZslCapacity.takeIf { it > 0 }?.let(::RawZslBuffer)
        publishRawZslStatus()

        // The activity is deliberately portrait-locked. Match Photon's preview geometry: camera
        // stream sizes stay in their native landscape order while the view uses the swapped
        // dimensions (3:4 for a 4:3 stream), irrespective of the phone's physical orientation.
        val requiredBufferWidth = viewfinder.height
        val requiredBufferHeight = viewfinder.width
        val previewSize = choosePreviewSize(
            map = map,
            rawSize = rawSize,
            requiredWidth = requiredBufferWidth,
            requiredHeight = requiredBufferHeight
        )
        this.previewSize = previewSize
        rawZslTargetFpsRange = chooseRawZslFpsRange(c, rawSize)
        viewfinder.post {
            viewfinder.setAspectRatio(previewSize.height, previewSize.width)
            configurePreviewTransform(viewfinder.width, viewfinder.height)
        }

        // maxImages must fit the whole ring plus frames still waiting for their
        // capture result (~5 at 30 fps with ZSL_PAIR_TIMEOUT_MS) plus transition
        // slack, plus a small rearm overlap so the ring refills during the drain
        // tail of a burst (see resumeRawZslIfIdle). Without the pairing headroom
        // a full 30-frame ring plus normal result lag exceeds maxImages and wedges
        // the gralloc queue into overflow + Mali unlock errors.
        val readerMaxImages = maxOf(
            MIN_ACQUIRED_RAW_IMAGES,
            maxOf(rawZslCapacity, MAX_IN_FLIGHT_JPEG_SAVES) + RAW_PREVIEW_RESERVED_SLOTS +
                ZSL_REARM_OVERLAP_SLOTS
        )
        // NOTE (Step 2 finding): adding USAGE_GPU_SAMPLED_IMAGE here starves this
        // reader completely on the test HAL (Xiaomi 25080RABDG / MT6878) — session
        // configures but no RAW images are ever delivered, killing ZSL + VF + RAW
        // histogram. Zero-copy EGL import of these buffers (4-arg default usage) fails
        // with EGL_BAD_ALLOC, so the GPU VF path stays fenced behind runtime probing
        // with the CPU sampler as the healthy default. See VfGpuImport.
        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            android.graphics.ImageFormat.RAW_SENSOR,
            readerMaxImages
        ).also {
            rawReaderMaxImages = readerMaxImages
            it.setOnImageAvailableListener({ reader ->
                if (!isCurrent(generation)) return@setOnImageAvailableListener
                try {
                    // Consume every queued RAW in timestamp order while ZSL is running.
                    // acquireLatestImage() implicitly discards intermediate RAW buffers. At a
                    // 30 fps repeating stream that can also discard the exact image whose
                    // TotalCaptureResult is waiting in pendingZslResults, delaying both ring
                    // fill and the first RAW histogram until a later capture. Keeping the loop
                    // on this camera Handler drains the ImageReader quickly; RawZslBuffer owns
                    // only the configured ring and closes its oldest frame on every overflow.
                    // A continuously replenished reader must yield to result, shutter and
                    // timeout messages. Sampling makes an unbounded drain loop self-starve.
                    var drained = 0
                    do {
                        drained++
                        val image = reader.acquireNextImage() ?: break
                        if (activeFramesRemaining.get() <= 0 && rawStreamImages++ == 0) {
                            Log.i(LOG_TAG, "First RAW stream image ${image.width}x${image.height} ts=${image.timestamp}")
                        }
                        RawImageOwnership.adopt(image, reader) {
                            cameraHandler.post { closeRetiredRawReaders() }
                        }
                        characteristics?.let { rawViewfinder?.offer(image, it, latestPreviewResult) }
                        if (previewRawTimestamps.remove(image.timestamp)) {
                            RawImageOwnership.release(image)
                            continue
                        }
                        if (rawZslStreaming && activeFramesRemaining.get() <= 0) {
                            // Bound outstanding gralloc buffers before they hit maxImages:
                            // each unmatched image waits up to ZSL_PAIR_TIMEOUT_MS for its
                            // result, so a lagging HAL can otherwise hold a full timeout
                            // window on top of the ring and wedge Mali/Adreno
                            // BufferQueues into unlock errors.
                            relieveZslReaderPressureIfNeeded()
                            val timestamp = image.timestamp
                            pendingImages.put(timestamp, image)?.let(RawImageOwnership::release)
                            if (pendingZslResults.containsKey(timestamp)) {
                                pairAvailableFrame(timestamp)
                            } else {
                                schedulePairTimeout(timestamp, reportCaptureFailure = false)
                            }
                            continue
                        }
                        if ((rawHistogramStreaming || ettrStreaming || programStreaming) && activeFramesRemaining.get() <= 0) {
                            val cameraCharacteristics = characteristics
                            if (cameraCharacteristics == null) {
                                RawImageOwnership.release(image)
                                continue
                            }
                            // Histogram sampling is small (~8k blocks) and stays inline.
                            // AE metering (~32k blocks, hundreds of ms on some sensors)
                            // runs on the metering executor: blocking the camera handler
                            // here stalls RAW acquisition and the HAL backpressures the
                            // whole preview pipeline down to ~1 fps.
                            if (rawHistogramStreaming) publishRawHistogramIfDue(image, cameraCharacteristics)
                            dispatchMeteringSample(image, cameraCharacteristics)
                            continue
                        }
                        if (activeFramesRemaining.get() <= 0) {
                            RawImageOwnership.release(image)
                            continue
                        }
                        val timestamp = image.timestamp
                        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                            Log.d(
                                LOG_TAG,
                                "RAW image timestamp=$timestamp remaining=${activeFramesRemaining.get()} " +
                                    "zsl=$rawZslStreaming pending=${pendingImages.size}"
                            )
                        }
                        pendingImages.put(timestamp, image)?.let(RawImageOwnership::release)
                        schedulePairTimeout(timestamp, reportCaptureFailure = false)
                        pairAvailableFrame(timestamp)
                    } while (drained < 2 && (rawViewfinderStreaming || rawZslStreaming || rawHistogramStreaming || ettrStreaming || programStreaming) && activeFramesRemaining.get() <= 0)
                } catch (_: IllegalStateException) {
                    if (rawZslStreaming) {
                        // At 30 fps a brief handler stall (or lagging capture results) can
                        // fill the small ImageReader queue once. maxImages are already
                        // outstanding at this point, so acquiring-and-closing queued
                        // images alone frees nothing: drop one held slot first (oldest
                        // unmatched, else oldest ring frame) and then drain. Only a
                        // persistently sick stream falls back.
                        if (zslOverflowTracker.onOverflow()) {
                            // A storm on a stream that never filled means this HAL
                            // cannot sustain repeating RAW right now: degrade to
                            // burst-mode instead of parking a stream that will
                            // only storm again. A filled-then-sickened stream
                            // keeps transient per-shot retry.
                            if (!rawZslHasFrame) {
                                rawZslStreamBroken = true
                                Log.w(LOG_TAG, "ZSL repeating stream never productive; burst-mode on")
                            }
                            cooldownRawZslForRetry("RAW buffer limit reached")
                        } else {
                            Log.w(LOG_TAG, "RAW ImageReader overflowed during ZSL stream; draining")
                            relieveZslReaderPressure()
                            drainQueuedRawImages(rawReader)
                        }
                    }
                    else if (rawHistogramStreaming || ettrStreaming || programStreaming) {
                        Log.w(LOG_TAG, "RAW ImageReader overflowed during metering stream; draining")
                        drainQueuedRawImages(rawReader)
                    } else {
                        Log.e(LOG_TAG, "RAW ImageReader reached maxImages during forward capture")
                        activeFramesRemaining.set(0)
                        cameraCaptureSequenceActive = false
                        captureSequence.incrementAndGet()
                        try {
                            session?.abortCaptures()
                        } catch (_: CameraAccessException) {
                            // The normal capture error path below restores preview/ZSL if possible.
                        }
                        // Release HAL-queued frames too, so the next press does
                        // not inherit a full reader from this aborted burst.
                        drainQueuedRawImages(rawReader)
                        closeAllPendingPairs()
                        // A top-up chain keeps its holdout plus arrivals as a
                        // partial burst; anything else was already closed above.
                        if (topupActive) finishTopupBurst()
                        else {
                            finishCapture("CAPTURE ERROR: RAW buffer limit reached")
                            resumeRawZslIfIdle()
                        }
                        noteChainDeadAndMaybeRecover()
                    }
                }
            }, cameraHandler)
        }
        rawStreamImages = 0
        rawStreamResults = 0
        rawStreamFailures = 0
        logRawStreamCombo(map, rawSize, previewSize)
        val texture = viewfinder.surfaceTexture ?: run {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            rawReaderMaxImages = 0
            return
        }
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        synchronized(cameraStateLock) {
            if (!isCurrent(generation) || camera !== device) {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                rawReaderMaxImages = 0
                surface.release()
                return
            }
            rawReader = reader
            previewSurface = surface
            try {
                createPerformanceSession(device, surface, reader.surface, generation)
            } catch (failure: CameraAccessException) {
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                if (isCurrent(generation)) onState("SESSION ERROR")
            } catch (failure: IllegalArgumentException) {
                Log.w(LOG_TAG, "Session surfaces rejected by HAL", failure)
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                if (isCurrent(generation)) onState("SESSION ERROR")
            } catch (failure: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Session creation unsupported by HAL", failure)
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                if (isCurrent(generation)) onState("SESSION ERROR")
            } catch (_: IllegalStateException) {
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                if (camera === device) camera = null
                if (isCurrent(generation)) onState("CAMERA CLOSED")
            }
        }
    }

    /**
     * Camera2's modern session API lets the HAL see the intended stream roles before it allocates
     * buffers.  We only attach stream-use-case hints when the exact configuration is reported as
     * supported; otherwise the same modern session is submitted without hints.
     */
    /**
     * One-line stream inventory per session: the sizes, queue depth and HAL
     * timing the RAW repeating stream runs under. This is the field evidence
     * that separates a stillborn stream (unsatisfiable combo) from a dead
     * viewfinder (delivery works, rendering fails).
     */
    private fun logRawStreamCombo(map: StreamConfigurationMap, rawSize: Size, previewSize: Size) {
        val minFrameNs = runCatching {
            map.getOutputMinFrameDuration(android.graphics.ImageFormat.RAW_SENSOR, rawSize)
        }.getOrNull()
        val stallNs = runCatching {
            map.getOutputStallDuration(android.graphics.ImageFormat.RAW_SENSOR, rawSize)
        }.getOrNull()
        Log.i(
            LOG_TAG,
            "RAW stream combo raw=${rawSize.width}x${rawSize.height} preview=${previewSize.width}x${previewSize.height} " +
                "maxImages=$rawReaderMaxImages minFrameNs=${minFrameNs ?: "?"} stallNs=${stallNs ?: "?"} " +
                "compat=$rawStreamCompatMode"
        )
    }

    @Suppress("DEPRECATION")
    private fun createPerformanceSession(
        device: CameraDevice,
        preview: Surface,
        raw: Surface,
        generation: Int
    ) {
        val callback = sessionCallback(device, generation)
        val plan = chooseSessionPerformancePlan(device, preview, raw, callback)
        lastSessionPlanLabel = plan.label
        try {
            device.createCaptureSession(plan.configuration)
            Log.i(LOG_TAG, "Camera session configured: ${plan.label}")
        } catch (failure: UnsupportedOperationException) {
            // Legacy / vendor HALs (e.g. Snapdragon 685 on Redmi Note 13 4G) accept
            // SessionConfiguration creation but not execution, or reject the modern
            // entry point outright. The deprecated surface-list API is equivalent
            // when no stream-use-case / physical-routing hints are attached.
            Log.w(LOG_TAG, "Modern session API unsupported (${plan.label}); using legacy surfaces", failure)
            device.createCaptureSession(listOf(preview, raw), callback, cameraHandler)
            lastSessionPlanLabel = "LEGACY fallback for ${plan.label}"
            Log.i(LOG_TAG, "Camera session configured: $lastSessionPlanLabel")
        } catch (failure: IllegalArgumentException) {
            Log.w(LOG_TAG, "Modern session config rejected (${plan.label}); using legacy surfaces", failure)
            device.createCaptureSession(listOf(preview, raw), callback, cameraHandler)
            lastSessionPlanLabel = "LEGACY fallback for ${plan.label}"
            Log.i(LOG_TAG, "Camera session configured: $lastSessionPlanLabel")
        }
    }

    private data class SessionPerformancePlan(
        val configuration: SessionConfiguration,
        val label: String
    )

    private fun chooseSessionPerformancePlan(
        device: CameraDevice,
        preview: Surface,
        raw: Surface,
        callback: CameraCaptureSession.StateCallback
    ): SessionPerformancePlan {
        val availableUseCases = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)
        ) {
            characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.toSet()
                ?: emptySet()
        } else {
            emptySet()
        }

        fun configuration(previewUseCase: Long?, rawUseCase: Long?): SessionConfiguration {
            val previewOutput = OutputConfiguration(preview)
            val rawOutput = OutputConfiguration(raw)
            // Standard Camera2 logical -> physical routing: keep the logical CameraDevice open,
            // but bind both streams to the selected physical member. Standalone/vendor-direct
            // routes leave this null and behave exactly as before.
            activePhysicalCameraId?.let { physicalId ->
                previewOutput.setPhysicalCameraId(physicalId)
                rawOutput.setPhysicalCameraId(physicalId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                previewUseCase?.let(previewOutput::setStreamUseCase)
                rawUseCase?.let(rawOutput::setStreamUseCase)
            }
            return SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewOutput, rawOutput),
                cameraExecutor,
                callback
            )
        }

        if (rawStreamCompatMode) {
            // Stillborn-stream fallback (Samsung): no stream-use-case hints at
            // all. A RAW output tagged STILL_CAPTURE can stall repeating
            // delivery on HALs that schedule it as a still-only stream; DEFAULT
            // is the documented compatibility baseline.
            return SessionPerformancePlan(configuration(null, null), "DEFAULT (compat)")
        }

        val candidates = buildList<Triple<Long?, Long?, String>> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val previewUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
                val stillUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()
                if (previewUseCase in availableUseCases && stillUseCase in availableUseCases) {
                    add(Triple(previewUseCase, stillUseCase, "PREVIEW + STILL_CAPTURE"))
                }
                if (previewUseCase in availableUseCases) {
                    add(Triple(previewUseCase, null, "PREVIEW"))
                }
            }
            add(Triple(null, null, "DEFAULT"))
        }
        for ((previewUseCase, rawUseCase, label) in candidates) {
            val candidate = configuration(previewUseCase, rawUseCase)
            try {
                if (device.isSessionConfigurationSupported(candidate)) {
                    return SessionPerformancePlan(candidate, label)
                }
            } catch (failure: CameraAccessException) {
                Log.w(LOG_TAG, "Could not probe session plan $label", failure)
            } catch (failure: UnsupportedOperationException) {
                // Vendor HALs without session-query support (e.g. Redmi Note 13 4G /
                // Snapdragon 685 throws "Session configuration query not supported").
                // Probing can never succeed here: stop and use DEFAULT unverified.
                Log.w(LOG_TAG, "Session query unsupported; using DEFAULT without hints", failure)
                break
            } catch (failure: IllegalArgumentException) {
                Log.w(LOG_TAG, "Session plan $label rejected during probe", failure)
            }
        }
        // DEFAULT is required to be our compatibility baseline. Avoid a deprecated session API
        // fallback even when a vendor declines capability probing.
        return SessionPerformancePlan(configuration(null, null), "DEFAULT (unverified)")
    }

    private fun sessionCallback(device: CameraDevice, generation: Int) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            synchronized(cameraStateLock) {
                if (!isCurrent(generation) || camera !== device) {
                    configured.close()
                    return
                }
                try {
                    session = configured
                    // A fresh session gets fresh RAW-preview retries: starvation
                    // callbacks during the open gap must not consume them.
                    rawPreviewFailures = 0
                    rawPreviewRetryUntilMs = 0L
                    updateRepeatingRequest()
                    // stop() disables the shutter while the Activity is backgrounded. A new
                    // camera session must explicitly restore it when the Activity resumes.
                    refreshCaptureAvailability()
                } catch (failure: CameraAccessException) {
                    configured.close()
                    if (session === configured) session = null
                    Log.w(LOG_TAG, "Repeating request failed after configure: ${cameraAccessReason(failure)}")
                    if (isCurrent(generation)) onState("SESSION ERROR")
                    return
                } catch (failure: IllegalStateException) {
                    configured.close()
                    if (session === configured) session = null
                    Log.w(LOG_TAG, "Session raced a closed camera", failure)
                    if (isCurrent(generation)) onState("CAMERA CLOSED")
                    return
                }
            }
            Log.i(LOG_TAG, "Session ready (zsl=$rawZslStreaming vf=$rawViewfinderStreaming)")
            onState(if (rawZslStreaming) "READY • ZSL WARMING" else "READY")
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (!isCurrent(generation)) return
            Log.e(LOG_TAG, "Camera session configuration rejected by HAL")
            onState("SESSION ERROR")
        }
    }

    private var rawPreviewRetryUntilMs = 0L
    private var rawPreviewFailures = 0
    /**
     * Stillborn-stream detection: repeating-RAW health for the current session.
     * [rawStreamImages] counts repeating-path buffers only — an in-flight
     * forward capture proves nothing about the repeating stream the VF needs.
     * All three reset in [createSession]; all are camera-thread only.
     */
    private var rawStreamImages = 0
    private var rawStreamResults = 0
    private var rawStreamFailures = 0
    private var lastSessionPlanLabel = "?"
    /**
     * Session-level compat: DEFAULT plan without stream-use-case hints and a
     * HAL-default frame rate (no forced 30 fps). Engaged once a repeating RAW
     * stream proves stillborn; sticky afterwards and persisted by the host so
     * the next cold start skips the stillborn attempt entirely.
     */
    private var rawStreamCompatMode = initialRawStreamCompatMode

    private fun recoverRawPreview(reason: String) {
        if (!running || captureInProgress.get()) return
        // A repeating RAW stream that delivered zero buffers cannot recover at
        // the request level: the stall is the session/stream configuration
        // itself (Samsung: request retries 1..4 change nothing while zero RAW
        // buffers arrive). Escalate once to a compat session, then let
        // request-level recovery run its course there.
        if (shouldEscalateRawPreviewToCompatSession(rawPreviewFailures, rawStreamImages, rawStreamCompatMode)) {
            rawStreamCompatMode = true
            Log.w(
                LOG_TAG,
                "RAW preview stillborn (images=0 results=$rawStreamResults failures=$rawStreamFailures " +
                    "plan=$lastSessionPlanLabel); rebuilding compat session"
            )
            onState("RECOVERING PREVIEW")
            onRawStreamCompatMode()
            restartCameraForConfigurationChange()
            return
        }
        val generation = lifecycleGeneration
        val shot = captureSequence.get()
        rawPreviewFailures++
        val delayMs = 1000L * rawPreviewFailures
        rawPreviewRetryUntilMs = if (rawPreviewFailures <= 3) SystemClock.elapsedRealtime() + delayMs else Long.MAX_VALUE
        Log.w(
            LOG_TAG,
            "RAW preview recovery $rawPreviewFailures: $reason " +
                "(images=$rawStreamImages results=$rawStreamResults failures=$rawStreamFailures plan=$lastSessionPlanLabel)"
        )
        updateRepeatingRequest(allowRawZsl = false)
        if (rawPreviewFailures <= 3) cameraHandler.postDelayed({
            if (isCurrent(generation) && shot == captureSequence.get() && !captureInProgress.get()) updateRepeatingRequest()
        }, delayMs)
        else if (rawStreamImages == 0) onState("RAW VF UNAVAILABLE")
    }

    init {
        rawViewfinder?.onStarvation = { cameraHandler.post { recoverRawPreview("No fresh RAW display frame") } }
    }

    private val shutterDispatchPending = AtomicBoolean(false)

    private fun postShutter(action: () -> Unit) {
        if (!running || captureInProgress.get() ||
            !shutterDispatchPending.compareAndSet(false, true)) return
        val generation = lifecycleGeneration
        if (!cameraHandler.post {
            try {
                if (isCurrent(generation) && !captureInProgress.get()) action()
            } finally { shutterDispatchPending.set(false) }
        }) shutterDispatchPending.set(false)
    }

    fun capture() {
        val pressElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val sensorCutoffSnapshot = lastPreviewSensorTimestamp
        val outputFormat = captureFormat
        // Freeze physical orientation with the shutter press. ZSL selection and asynchronous RAW
        // saving may run later; they must never observe a newer handset orientation.
        val orientationSnapshot = deviceOrientationDegrees
        postShutter {
            beginSingleCapture(pressElapsedNanos, sensorCutoffSnapshot, outputFormat, orientationSnapshot)
        }
    }

    fun captureBurst() {
        val outputFormat = captureFormat
        val orientationSnapshot = deviceOrientationDegrees
        Log.i(LOG_TAG, "Burst requested frames=$BURST_FRAME_COUNT")
        postShutter {
            captureFrames(
                BURST_FRAME_COUNT,
                outputFormat = outputFormat,
                orientationSnapshot = orientationSnapshot
            )
        }
    }

    /** Captures the default handheld bracket (-2, 0, +2 EV) around the latest metered pair. */
    fun captureHdrBracket(saveEachBracket: Boolean = false, bracketStops: Int = 2,
                          saveDebugFrames: Boolean = false) {
        require(bracketStops == 2 || bracketStops == 4) { "HDR bracket must be ±2 or ±4 EV" }
        val outputFormat = captureFormat
        val orientationSnapshot = deviceOrientationDegrees
        postShutter {
            if (captureInProgress.get() || !hasPreviewMetadata) {
                onState("HDR WAIT")
                return@postShutter
            }
            if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                onState("HDR NEEDS MANUAL")
                return@postShutter
            }
            activeHdrBracket = true
            activeHdrSaveEachBracket = saveEachBracket && !saveDebugFrames
            activeHdrSaveDebugFrames = saveDebugFrames
            activeHdrStops = bracketStops
            // Warm-start FlowNet now (process-wide singleton, background init):
            // first-bracket merges were paying the ~10s model init inside the
            // align window while runInference blocked on waitReady.
            runCatching { FlowNetNcnnProcessor.start(context.applicationContext) }
            captureFrames(3, outputFormat = outputFormat, orientationSnapshot = orientationSnapshot)
        }
    }

    /** Format changes apply only between captures so every frame has one output contract. */
    fun setCaptureFormat(format: CaptureFormat): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setCaptureFormat(format) }
            return true
        }
        captureFormat = format
        // FOLLOW may flip the tonemap (DNG_ONLY <-> JPEG): push so the next sampled
        // frame renders the contract being saved.
        pushVfRenderState()
        return true
    }

    /** JPEG output choices are frozen with the shutter press, just like capture format. */
    fun setJpegOutputSettings(settings: JpegOutputSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setJpegOutputSettings(settings) }
            return true
        }
        jpegOutputSettings = settings.resolvedForPlatform()
        // Live-link AgX sliders + adaptive strength to the JPEG preview: the next
        // sampled frame carries them.
        pushVfRenderState()
        return true
    }

    /** Denoise controls are frozen at shutter press and cannot change during queued saves. */
    fun setDenoiseSettings(settings: DenoiseSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setDenoiseSettings(settings) }
            return true
        }
        denoiseSettings = settings
        return true
    }

    private fun beginSingleCapture(
        pressElapsedNanos: Long,
        sensorCutoffSnapshot: Long,
        outputFormat: CaptureFormat,
        orientationSnapshot: Int
    ) {
        // ZSL purity: when ZSL is requested and usable, reserve the full burst
        // up front. A 30-frame burst needs 30 slots; checking only 1 would lock
        // the shutter, spin the 3s recovery wait with no capacity, then degrade
        // to a single that further delays the rearm. With the full reservation,
        // a busy queue fails fast with QUEUE FULL and the shutter stays free.
        if (rawZslRequested) {
            val need = zslSelectedFrameCount(outputFormat)
            if (!beginCapture(outputFormat, requiredSaveSlots = need, orientationSnapshot = orientationSnapshot)) return
            if (zslHybridTopupEnabled) {
                if (!selectAndSaveRawZsl(pressElapsedNanos, sensorCutoffSnapshot, forceTopup = true)) {
                    startTopupBurst(emptyList(), need)
                }
                return
            }
            if (rawZslStreaming) {
                onState("SELECTING RAW ZSL")
                if (selectAndSaveRawZsl(pressElapsedNanos, sensorCutoffSnapshot)) return
                val deadlineMs = SystemClock.elapsedRealtime() + ZSL_RECOVERY_WAIT_MS
                retryZslSelectionUntil(deadlineMs)
            } else {
                // ZSL wanted but the ring is warming or re-arming after saves:
                // wait for recovery instead of degrading to a forward single.
                // Never falls back to single; releases with NOT READY on timeout.
                onState("ZSL QUEUED")
                Log.i(
                    LOG_TAG,
                    "ZSL not streaming at shutter (saves=${pendingSaveCount.get()} " +
                        "buffered=${rawZslBuffer?.size ?: 0}/$rawZslCapacity need=$need); waiting for ring"
                )
                val deadlineMs = SystemClock.elapsedRealtime() + ZSL_RECOVERY_WAIT_MS
                retryZslSelectionUntil(deadlineMs)
            }
        } else {
            if (!beginCapture(outputFormat, requiredSaveSlots = 1, orientationSnapshot = orientationSnapshot)) return
            captureFrames(1, captureAlreadyStarted = true)
        }
    }

    /**
     * Retries a ZSL selection until [deadlineMs]. Every tick uses a fresh cutoff
     * (now), because this loop exists to cover ring refill happening after the
     * press: post-press frames would carry negative ages against a press-time
     * cutoff and be rejected forever. The initial attempt with the true
     * press-time cutoff happens in beginSingleCapture before this loop starts.
     *
     * With hybrid top-up ON the loop always ends in a capture: ~1s of
     * consecutive empty takes escalates to a pure forward burst (empty
     * holdout), the GCam bottom rung. NOT READY only survives with the toggle
     * OFF. [emptyStreak] counts those consecutive empties.
     */
    private fun retryZslSelectionUntil(
        deadlineMs: Long,
        restartAttempted: Boolean = false,
        emptyStreak: Int = 0
    ) {
        val shot = captureSequence.get()
        val generation = lifecycleGeneration
        cameraHandler.postDelayed({
            if (!isCurrent(generation) || shot != captureSequence.get() || !captureInProgress.get()) return@postDelayed
            if (rawZslDisabledForSession && zslHybridTopupEnabled) {
                Log.w(
                    LOG_TAG,
                    "ZSL disabled for session (${rawZslFallbackDetail ?: "unknown"}); " +
                        "falling back to single capture"
                )
                startTopupBurst(emptyList(), zslSelectedFrameCount(activeCaptureFormat))
                return@postDelayed
            }
            // Per-shot recovery (PhotonCamera/GCam style): the stream may be down
            // from a transient outage whose cooldown has expired. Every press
            // re-attempts the ZSL stream once instead of wedging until reopen.
            // Restarting alongside save-held Images would re-wedge the gralloc
            // queue, so the restart is allowed only when a full ring plus the
            // remaining saves provably fit maxImages (same headroom rule as the
            // reader sizing); otherwise the wait rides out the drain and the
            // save completions rearm via resumeRawZslIfIdle.
            var restartDone = restartAttempted
            if (!rawZslStreaming && !restartAttempted && !isRawZslBlocked() &&
                zslRestartFitsReader()
            ) {
                Log.i(LOG_TAG, "ZSL stream down at shutter; attempting per-shot restart")
                updateRepeatingRequest(allowRawZsl = true)
                restartDone = true
            }
            // Refresh the cutoff every tick: the retry exists to cover ring
            // refill AFTER the press (restart, rearm), and frames completing
            // after a stale press-time cutoff carry negative ages that the
            // eligibility filter would reject forever — observed as an
            // eternal empty selection on a filling ring (buffered 3/8, never
            // eligible) ending in ZSL NOT READY.
            val tickElapsedNanos = SystemClock.elapsedRealtimeNanos()
            val tickSensorCutoff = lastPreviewSensorTimestamp
            if (selectAndSaveRawZsl(tickElapsedNanos, tickSensorCutoff)) return@postDelayed
            // Empty-ring escalation (P0 liveness): a ring that yields nothing
            // for ~1s is not going to refill (wedged HAL, dead stream), so fire
            // the full burst forward instead of riding out the whole deadline
            // into NOT READY. Capacity was reserved up front, so slots exist.
            val nextEmptyStreak = if (lastZslTakeSize == 0) emptyStreak + 1 else 0
            if (nextEmptyStreak >= EMPTY_TOPUP_ESCALATION_TICKS && zslHybridTopupEnabled) {
                Log.i(LOG_TAG, "ZSL ring empty for ~1s; escalating to forward burst")
                if (selectAndSaveRawZsl(tickElapsedNanos, tickSensorCutoff, forceTopup = true)) {
                    return@postDelayed
                }
            }
            if (SystemClock.elapsedRealtime() >= deadlineMs) {
                // ZSL purity: never degrade to a forward single on timeout.
                // A timeout single would add queue pressure, flush/abort the
                // session (Xiaomi code-3 errors) and push the rearm even later.
                // With top-up ON this line is nearly unreachable (empty-ring
                // escalation fires first); reaching it means the session is
                // sick, so count it toward rebuild like any dead chain.
                Log.w(LOG_TAG, "ZSL ring did not recover in time; releasing (no single fallback)")
                if (zslHybridTopupEnabled) {
                    startTopupBurst(emptyList(), zslSelectedFrameCount(activeCaptureFormat))
                    return@postDelayed
                }
                finishCapture("ZSL NOT READY")
                noteChainDeadAndMaybeRecover()
                return@postDelayed
            }
            retryZslSelectionUntil(deadlineMs, restartDone, nextEmptyStreak)
        }, ZSL_SELECTION_WAIT_MS)
    }

    /**
     * True when restarting the ZSL stream right now provably fits the reader:
     * held saves + a full ring + pairing window + transition slack stay within
     * maxImages. Guards the per-shot restart against re-creating the overflow
     * storms that the cooldown just parked.
     */
    private fun zslRestartFitsReader(): Boolean = CaptureQueueCapacity.ringFits(
        pendingFrameSaveCount.get(), rawZslCapacity, rawReaderMaxImages, RAW_PREVIEW_RESERVED_SLOTS
    )

    private fun zslSelectedFrameCount(outputFormat: CaptureFormat): Int {
        val requestedFrameCount = rawZslFrameCount
        return if (outputFormat.includesJpeg) {
            minOf(requestedFrameCount, MAX_IN_FLIGHT_JPEG_SAVES)
        } else {
            requestedFrameCount
        }
    }

    private fun beginCapture(
        outputFormat: CaptureFormat,
        requiredSaveSlots: Int,
        orientationSnapshot: Int = deviceOrientationDegrees
    ): Boolean {
        if (captureInProgress.get()) return false
        if (!hasProcessingCapacity(requiredSaveSlots, outputFormat)) {
            onState("QUEUE FULL")
            refreshCaptureAvailability()
            return false
        }
        if (!running || !captureInProgress.compareAndSet(false, true)) {
            if (captureInProgress.get()) onState("CAPTURE IN PROGRESS")
            return false
        }
        // Every capture starts unproven: dead-chain accounting (session rebuild
        // after consecutive zero-pair captures) keys off pairs produced by THIS
        // press, never a previous one.
        captureSequence.incrementAndGet()
        chainPairedAny = false
        onCaptureEnabled(false)
        activeCaptureFormat = outputFormat
        activeOutputOrientation = dngOrientation(characteristics, orientationSnapshot)
        Log.i(
            LOG_TAG,
            "Capture orientation frozen device=$orientationSnapshot exif=$activeOutputOrientation"
        )
        activeCaptureExposureMode = captureExposureMode
        activeAdaptiveExposure = SharedAdaptiveExposure()
        activeJpegOutputSettings = jpegOutputSettings
        activeDenoiseSettings = denoiseSettings
        return true
    }

    private fun selectAndSaveRawZsl(
        pressElapsedNanos: Long,
        sensorCutoffSnapshot: Long,
        forceTopup: Boolean = false
    ): Boolean {
        val cutoff = if (rawZslRealtimeTimestamps) pressElapsedNanos else sensorCutoffSnapshot
        if (cutoff == Long.MIN_VALUE) {
            lastZslTakeSize = -1
            return false
        }
        val selectedFrameCount = zslSelectedFrameCount(activeCaptureFormat)
        // A ZSL request saves the configured selection as one logical capture. Do not remove
        // frames from the ring unless all of them fit in the bounded development queue.
        val requiredSaveSlots = selectedFrameCount
        if (!hasProcessingCapacity(requiredSaveSlots, activeCaptureFormat)) {
            onState("ZSL QUEUED")
            lastZslTakeSize = -1
            return false
        }
        // PhotonCamera partial take: use whatever the ring holds instead of
        // all-or-nothing. A full ring saves immediately; a thin ring either tops
        // up with fresh captures (hybrid, below) or keeps waiting for refill.
        // An empty ring with forceTopup fires a pure forward burst (GCam bottom
        // rung): the wait loop escalates here after ~1s of empties so every
        // press ends in DNGs, never NOT READY, while the toggle is ON.
        val selected = rawZslBuffer?.takeForCapture(cutoff, rawZslRealtimeTimestamps,
            selectedFrameCount, zslHybridTopupEnabled).orEmpty()
        lastZslTakeSize = selected.size
        if (selected.isEmpty()) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(
                    LOG_TAG,
                    "ZSL selection empty buffered=${rawZslBuffer?.size ?: 0}/$rawZslCapacity " +
                        "need=$selectedFrameCount streaming=$rawZslStreaming " +
                        "saves=${pendingSaveCount.get()}"
                )
            }
            if (forceTopup && zslHybridTopupEnabled) {
                return startTopupBurst(emptyList(), selectedFrameCount)
            }
            return false
        }
        if (selected.size < selectedFrameCount && zslHybridTopupEnabled) {
            return startTopupBurst(selected, selectedFrameCount)
        }
        if (selected.size < selectedFrameCount) return false
        // Taking ownership from the ring does not change the sensor request.
        // Keep the stream and its epoch intact when refill and saves fit together.
        if (!CaptureQueueCapacity.ringFits(pendingFrameSaveCount.get() + selected.size,
                rawZslCapacity, rawReaderMaxImages, RAW_PREVIEW_RESERVED_SLOTS)) {
            updateRepeatingRequest(allowRawZsl = false)
        }
        activeFramesRemaining.set(0)

        saveSelectedZslBurst(selected)
        finishCapture()
        publishRawZslStatus()
        return true
    }

    /**
     * Saves one ZSL burst (ring frames, top-up mix, or full ring alike) under a
     * single stem: DNGs/JPEGs plus best-effort gyro sidecars sort together.
     * Every frame is handed to the bounded writer with its own ownership; the
     * caller must already hold any queue capacity the burst needs.
     */
    private fun saveSelectedZslBurst(selected: List<BufferedRawFrame>) {
        if (selected.isEmpty()) return
        onState("SAVING ZSL ×${selected.size}")
        val approximateCount = selected.count { it.metadataApproximate }
        if (approximateCount > 0) {
            Log.w(
                LOG_TAG,
                "ZSL burst holds $approximateCount/${selected.size} frames with approximate " +
                    "metadata (capture-result stall admitted on grace); pixels are exact, " +
                    "AE-dependent tags come from the latest preview result"
            )
        }
        // One shared stem so the burst's DNGs/JPEGs (DCIM/RawLens/<stem>)
        // and gyro CSVs/burst.json sort together. With a granted photo-folder
        // Uri (Settings → General → sidecars) the sidecars land next to the
        // DNGs via SAF; otherwise scoped storage forces the Downloads split
        // (Download/RawLens/<stem>, copied next to the DNGs for import).
        // Gyro windows are snapshotted synchronously here (the motion ring
        // holds ~3s; writer jobs run later), while file names stay
        // deterministic so the sidecar matches whatever actually finishes.
        val stemMillis = System.currentTimeMillis()
        val stem = CaptureFileNames.stem(stemMillis)
        val sidecar = runCatching { snapshotBurstSidecar(selected, stemMillis, stem) }
            .onFailure { Log.w(LOG_TAG, "Could not snapshot burst sidecar", it) }.getOrNull()
        selected.forEachIndexed { index, frame ->
            val suffix = "F%02d".format(index)
            saveRawFrame(frame.image, frame.result, "ZSL ${index + 1}/${selected.size}",
                captureTimeMillis = stemMillis, fileNameSuffix = suffix, subfolder = stem)
        }
        // Snapshot above is camera-thread work; SAF/file I/O must not block RAW delivery.
        if (sidecar != null) {
            pendingSaveCount.incrementAndGet()
            try {
                writer.execute {
                    try { writeZslSidecar(stem, sidecar) } finally { postSaveCompletion() }
                }
            } catch (failure: RejectedExecutionException) {
                Log.w(LOG_TAG, "Sidecar queue rejected; RAW artifacts remain saved", failure)
                postSaveCompletion()
            }
        }
    }

    private fun writeZslSidecar(stem: String, sidecar: BurstSidecarPayload) {
            try {
                val tree = SidecarTreeAccess.savedTreeUri(context)
                if (tree != null && SidecarTreeAccess.hasWriteAccess(context, tree)) {
                    try {
                        SidecarTreeAccess.writeViaTree(
                            context, tree, stem, sidecar.metaJson, sidecar.gyroCsvByName)
                        Log.i(LOG_TAG, "Burst sidecar written same-folder " +
                            "DCIM/RawLens/$stem frames=${sidecar.frameCount}")
                    } catch (treeFailure: Exception) {
                        Log.w(LOG_TAG, "Same-folder sidecar failed; falling back to Downloads", treeFailure)
                        BurstSidecar.writeFiles(context, stem, sidecar.metaJson, sidecar.gyroCsvByName)
                        Log.i(LOG_TAG, "Burst sidecar written dir=${BurstSidecar.sidecarDir(stem)} " +
                            "dngs=DCIM/RawLens/$stem frames=${sidecar.frameCount}")
                    }
                } else {
                    BurstSidecar.writeFiles(context, stem, sidecar.metaJson, sidecar.gyroCsvByName)
                    Log.i(LOG_TAG, "Burst sidecar written dir=${BurstSidecar.sidecarDir(stem)} " +
                        "dngs=DCIM/RawLens/$stem frames=${sidecar.frameCount}")
                }
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "Burst sidecar write failed (DNGs unaffected)", failure)
            }
    }

    /** Runs only after an accepted save/sidecar has released everything it owns. */
    private fun postSaveCompletion(frameSlots: Int = 0) {
        cameraHandler.post {
            if (frameSlots > 0) pendingFrameSaveCount.addAndGet(-frameSlots)
            pendingSaveCount.decrementAndGet()
            closeRetiredRawReaders()
            if (running && !destroyed) {
                refreshCaptureAvailability()
                resumeRawZslIfIdle()
                publishRawZslStatus()
            }
        }
    }

    /**
     * Hybrid top-up (GCam ZSL+PSL style): the ring held [holdout] of [need]
     * frames, so capture the remainder as fresh forward stills and save the
     * mix under one stem. The holdout is already removed from the ring and is
     * owned here until the combined save (or an abort) releases it.
     */
    private fun startTopupBurst(holdout: List<BufferedRawFrame>, need: Int): Boolean {
        val remainder = need - holdout.size
        if (remainder <= 0) return false
        topupActive = true
        topupHoldout = holdout
        topupFrames.clear()
        Log.i(
            LOG_TAG,
            "ZSL hybrid top-up: ring held ${holdout.size}/$need, capturing $remainder fresh frames"
        )
        onState("ZSL TOP-UP ×$need")
        // The capture timeout already scales with chain length inside
        // captureFrames, so slow shutters degrade to a partial save instead of
        // a false timeout here.
        if (rawViewfinderStreaming && session != null && lastPreviewSensorTimestamp != Long.MIN_VALUE &&
            SystemClock.elapsedRealtime() >= rawPreviewRetryUntilMs) {
            // These are genuinely forward frames, but the sensor already delivers them.
            // Do not flush a healthy 30 Hz stream just to submit identical RAW stills.
            val captureId = captureSequence.incrementAndGet()
            streamCapture = ForwardRawSelection(captureId, lastPreviewSensorTimestamp, remainder)
            activeFramesRemaining.set(remainder)
            // Callback routing already diverts forward frames from the ring. Replacing
            // the identical repeating request drains preview and discards its metadata.
            if (!CaptureQueueCapacity.ringFits(pendingFrameSaveCount.get() + need,
                    rawZslCapacity, rawReaderMaxImages, RAW_PREVIEW_RESERVED_SLOTS)) {
                updateRepeatingRequest(allowRawZsl = false)
            }
            scheduleCaptureTimeout(captureId, remainder * maxOf(lastExposureNanos / 1_000_000L, 100L))
            Log.i(LOG_TAG, "Continuous RAW top-up id=$captureId frames=$remainder")
        } else {
            captureFrames(remainder, captureAlreadyStarted = true)
        }
        return true
    }

    /** Takes ownership of one freshly captured top-up frame (Image + result). */
    private fun accumulateTopupFrame(image: Image, result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
        // The motion tracker is stopped across forward captures, so per-frame
        // gyro integration is unavailable here; scoring already happened at
        // selection time and sidecars key off timestamps, making 0 safe.
        topupFrames += BufferedRawFrame(
            image,
            result,
            timestamp,
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
            0f
        )
    }

    /**
     * Completes a hybrid top-up: ring holdout + arrived fresh frames, timestamp
     * ordered, saved under one stem. Partial on timeout/failure (GCam's bottom
     * rung is a picture, never a wedge); empty only when nothing arrived, which
     * releases the shutter with an error instead of hanging it.
     */
    private fun finishTopupBurst() {
        val holdout = topupHoldout
        val fresh = topupFrames.toList()
        topupActive = false
        topupHoldout = emptyList()
        topupFrames.clear()
        val combined = (holdout + fresh).sortedBy { it.timestampNanos }
        if (combined.isEmpty()) {
            finishCapture("CAPTURE ERROR")
            resumeRawZslIfIdle()
            return
        }
        Log.i(
            LOG_TAG,
            "ZSL hybrid top-up complete ring=${holdout.size} fresh=${fresh.size} " +
                "total=${combined.size}"
        )
        saveSelectedZslBurst(combined)
        finishCapture()
        publishRawZslStatus()
    }

    /** Releases a top-up in flight without saving (session teardown paths). */
    private fun abortTopupFrames() {
        topupActive = false
        (topupHoldout + topupFrames).forEach { runCatching { RawImageOwnership.release(it.image) } }
        topupHoldout = emptyList()
        topupFrames.clear()
    }

    /** Preassembled sidecar payload: meta JSON plus CSV text by file name. */
    private data class BurstSidecarPayload(
        val metaJson: String,
        val gyroCsvByName: Map<String, String>,
        val frameCount: Int
    )

    /**
     * Snapshots gyro windows + per-frame timing/ISO and renders burst.json.
     * Returns null (caller skips sidecars, DNGs save exactly as legacy)
     * when the burst cannot be described coherently: missing characteristics
     * (no axis mapping or intrinsics), missing ISO/exposure on any frame,
     * or no usable gyro on every frame (a burst with zero gyro windows
     * carries no sync value; desktop treats missing files as no-gyro).
     */
    private fun snapshotBurstSidecar(
        selected: List<BufferedRawFrame>,
        stemMillis: Long,
        stem: String
    ): BurstSidecarPayload? {
        if (selected.size < 2) {
            Log.w(LOG_TAG, "Burst sidecar skipped: ${selected.size} frame(s), need 2+")
            return null
        }
        val c = characteristics
        if (c == null) {
            Log.w(LOG_TAG, "Burst sidecar skipped: no camera characteristics")
            return null
        }
        val sensorDeg = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = c.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        data class Row(val file: String, val ts: Long, val exp: Long, val skew: Long, val iso: Int, val csv: String?)
        val rows = ArrayList<Row>(selected.size)
        selected.forEachIndexed { index, frame ->
            val iso = frame.result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }
            if (iso == null) {
                Log.w(LOG_TAG, "Burst sidecar skipped: frame $index missing ISO")
                return null
            }
            if (frame.exposureNanos <= 0L || frame.rollingShutterSkewNanos < 0L) {
                Log.w(LOG_TAG, "Burst sidecar skipped: frame $index bad timing " +
                    "exp=${frame.exposureNanos} skew=${frame.rollingShutterSkewNanos}")
                return null
            }
            val file = CaptureFileNames.fileName(stemMillis, "F%02d".format(index), "dng")
            val csv = try {
                motionTracker.gyroCsvForFrame(
                    frame.timestampNanos, frame.exposureNanos,
                    frame.rollingShutterSkewNanos, rawZslRealtimeTimestamps,
                    sensorDeg, front)
            } catch (_: IllegalArgumentException) {
                null
            }
            rows.add(Row(file, frame.timestampNanos, frame.exposureNanos,
                frame.rollingShutterSkewNanos, iso, csv))
        }
        if (rows.none { it.csv != null }) {
            Log.w(LOG_TAG, "Burst sidecar skipped: no gyro windows " +
                "(trackerRunning=${motionTracker.isRunning()} " +
                "buffered=${motionTracker.bufferedSampleCount()} " +
                "realtimeTs=$rawZslRealtimeTimestamps)")
            return null
        }
        val first = selected.first()
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val intrinsics = BurstSidecar.intrinsicsOrNull(
            focal, phys?.width, phys?.height,
            first.image.width, first.image.height, activeOutputOrientation)
        val gyroByName = LinkedHashMap<String, String>()
        val metas = rows.map { r ->
            val gyroFile = r.csv?.let {
                val name = BurstSidecar.gyroFileName(r.file)
                gyroByName[name] = it
                name
            }
            BurstSidecar.FrameMeta(r.file, r.ts, r.exp, r.skew, r.iso, gyroFile)
        }
        val json = BurstSidecar.buildMetaJson(
            burstName = stem, sensorOrientationDeg = sensorDeg, frontFacing = front,
            deviceOrientationDeg = deviceOrientationDegrees,
            exifOrientation = activeOutputOrientation,
            intrinsics = intrinsics, frames = metas)
        val gyroFrames = gyroByName.size
        Log.i(LOG_TAG, "Burst sidecar ready stem=$stem frames=${metas.size} " +
            "gyro=$gyroFrames/${metas.size} intrinsics=${intrinsics != null}")
        return BurstSidecarPayload(json, gyroByName, metas.size)
    }

    private fun captureFrames(
        frameCount: Int,
        captureAlreadyStarted: Boolean = false,
        outputFormat: CaptureFormat = activeCaptureFormat,
        orientationSnapshot: Int = deviceOrientationDegrees
    ) {
        if (!captureAlreadyStarted && captureInProgress.get()) return
        if (!captureAlreadyStarted &&
            !beginCapture(outputFormat, requiredSaveSlots = frameCount, orientationSnapshot = orientationSnapshot)
        ) {
            activeHdrBracket = false
            return
        }
        val currentSession = session
        val device = camera
        val reader = rawReader
        if (currentSession == null || device == null || reader == null) {
            finishCapture("CAPTURE ERROR: camera not ready")
            return
        }
        // Repeating RAW stays attached. Preview-result timestamps are discarded
        // independently from exact still-result pairing below.
        closeAllPendingPairs()
        activeFramesRemaining.set(frameCount)
        updateRepeatingRequest(allowRawZsl = false)
        val generation = lifecycleGeneration
        val captureId = captureSequence.incrementAndGet()
        chainPairedAny = false
        Log.i(LOG_TAG, "Submitting RAW capture id=$captureId frames=$frameCount (serialized)")
        try {
            val requests = List(frameCount) { frameIndex ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { addTarget(it) }
                    setTag(CaptureTag(captureId, frameIndex + 1, frameCount))
                    // Same split as PhotonCamera Photo mode: preview meters under hardware AE;
                    // the shutter-first pair is applied only to the submitted still request.
                    applyCameraControls(this, applyDynamicCurve = true)
                    if (activeHdrBracket) applyHdrExposure(this, frameIndex)
                    requestLensShadingMap(this)
                }.build()
            }
            onState(if (frameCount == 1) "CAPTURING" else "BURST ×$frameCount")
            // Serialized chains take frameCount × exposure instead of one pipeline
            // burst: scale the timeout up from the 8s base (never down), so slow
            // shutters degrade to a partial save instead of a false timeout.
            val bracketDurationMs = if (activeHdrBracket) requests.sumOf {
                (it.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 0L) / 1_000_000L
            } else 0L
            val chainMs = frameCount * maxOf(lastExposureNanos / 1_000_000L, 100L) + 2_000L
            scheduleCaptureTimeout(
                captureId, maxOf(bracketDurationMs, chainMs - CAPTURE_TIMEOUT_MS).coerceAtLeast(0L))
            val callback = captureCallback(generation, captureId)
            activeStillCallback = callback
            pendingStillRequests.clear()
            pendingStillRequests.addAll(requests)
            submitNextStill()
            Log.i(LOG_TAG, "RAW capture submitted id=$captureId frames=$frameCount")

        } catch (failure: CameraAccessException) {
            cameraCaptureSequenceActive = false
            finishCapture("CAPTURE ERROR")
            resumeRawZslIfIdle()
            noteChainDeadAndMaybeRecover()
        } catch (failure: IllegalArgumentException) {
            cameraCaptureSequenceActive = false
            finishCapture("NOT SUPPORTED")
            resumeRawZslIfIdle()
            noteChainDeadAndMaybeRecover()
        } catch (_: IllegalStateException) {
            cameraCaptureSequenceActive = false
            finishCapture("CAPTURE ERROR")
            noteChainDeadAndMaybeRecover()
        }
    }

    /**
     * Records a dead chain end (zero paired frames): after two in a row the
     * HAL is wedged past what aborts can clear, so rebuild the whole camera
     * session instead of failing red forever. No-op when the chain paired
     * anything or another capture is already running.
     */
    private fun noteChainDeadAndMaybeRecover() {
        if (chainPairedAny) return
        consecutiveDeadChains++
        Log.w(LOG_TAG, "Forward chain produced zero frames " +
            "($consecutiveDeadChains consecutive)")
        if (consecutiveDeadChains >= 2 && !captureInProgress.get()) {
            Log.w(LOG_TAG, "Recovering camera session after consecutive dead chains")
            onState("RECOVERING CAMERA")
            consecutiveDeadChains = 0
            chainPairedAny = false
            restartCameraForConfigurationChange()
        }
    }

    /** Submits the next prebuilt still of the serialized chain, if any remain. */
    private fun submitNextStill() {
        if (!running) return
        val next = pendingStillRequests.removeFirstOrNull() ?: return
        val currentSession = session
        val callback = activeStillCallback
        if (currentSession == null || callback == null) {
            if (topupActive) {
                Log.w(LOG_TAG, "Still chain lost its session; saving partial burst")
                finishTopupBurst()
            } else {
                finishCapture("CAPTURE ERROR")
                resumeRawZslIfIdle()
            }
            noteChainDeadAndMaybeRecover()
            return
        }
        cameraCaptureSequenceActive = true
        try {
            currentSession.capture(next, callback, cameraHandler)
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                val tag = next.tag as? CaptureTag
                Log.d(LOG_TAG, "Chained still submitted frame=${tag?.frameNumber}/${tag?.frameCount}")
            }
        } catch (failure: CameraAccessException) {
            cameraCaptureSequenceActive = false
            if (topupActive) finishTopupBurst() else {
                finishCapture("CAPTURE ERROR")
                resumeRawZslIfIdle()
            }
            noteChainDeadAndMaybeRecover()
        } catch (_: IllegalArgumentException) {
            cameraCaptureSequenceActive = false
            if (topupActive) finishTopupBurst() else {
                finishCapture("NOT SUPPORTED")
                resumeRawZslIfIdle()
            }
            noteChainDeadAndMaybeRecover()
        } catch (_: IllegalStateException) {
            cameraCaptureSequenceActive = false
            if (topupActive) finishTopupBurst() else finishCapture("CAPTURE ERROR")
            noteChainDeadAndMaybeRecover()
        }
    }

    private fun applyHdrExposure(builder: CaptureRequest.Builder, frameIndex: Int) {
        val stops = intArrayOf(-activeHdrStops, 0, activeHdrStops)[frameIndex]
        val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val timeRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val useProgram = captureExposureMode == CaptureExposureMode.PROGRAM && dynamicExposureSettings.enabled
        val useEttr = ettrCaptureActive()
        val iso = (selectedIso ?: (if (useEttr) ettrIso else null)
            ?: (if (useProgram) dynamicIso else null) ?: lastIso).let { value ->
            value.coerceIn(isoRange?.lower ?: value, isoRange?.upper ?: value)
        }
        val base = selectedExposureNanos ?: (if (useEttr) ettrShutterNanos else null)
            ?: (if (useProgram) dynamicShutterNanos else null) ?: lastExposureNanos
        val factor = if (stops < 0) 1.0 / (1 shl -stops) else (1 shl stops).toDouble()
        val time = (base * factor).toLong().let { value ->
            value.coerceIn(timeRange?.lower ?: value, timeRange?.upper ?: value)
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, time)

        // DSLR-style exposure bracketing changes shutter only. Freeze the last settled focus
        // position and AWB state so focus breathing or per-frame colour adaptation cannot become
        // false motion/chroma during FlowNet registration and radiance merging.
        val focus = selectedFocusDistanceDiopters
            ?: latestPreviewResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (focus != null && focus.isFinite()) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        if (selectedWbKelvin == null &&
            characteristics?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        Log.i(LOG_TAG, "HDR bracket frame=${frameIndex + 1}/3 ev=$stops iso=$iso shutterNs=$time")
    }

    /**
     * AF-only drag: moves the AF square, keeps the AE square where it was,
     * and restarts the AF scan. A single tap elsewhere while locked routes
     * through [setFocusAndMeteringPoint] instead and moves both together.
     */
    fun setAfPoint(viewX: Float, viewY: Float) {
        startTouchFocus(viewX, viewY, indefinite = false, updateAe = false)
    }

    /** AE-only drag: moves the metering square without touching the AF scan/lock. */
    fun setAfPointOnly(viewX: Float, viewY: Float) = setAfPoint(viewX, viewY)

    /**
     * Coalesced tap-to-focus + tap-to-meter: installs the AF and AE regions
     * together, issues a single repeating update, then a single AF START.
     * Same visible behavior as the old split setAePoint()+setAfPoint() pair,
     * but 2-3 fewer HAL round-trips per tap. Every tap refocuses — including
     * a single tap while locked, which cancels the old scan/lock and starts
     * a new one at the new point; the sharp lock auto-releases after
     * [TOUCH_FOCUS_LOCK_TIMEOUT_MS].
     */
    fun setFocusAndMeteringPoint(viewX: Float, viewY: Float) {
        startTouchFocus(viewX, viewY, indefinite = false, updateAe = true)
    }

    /**
     * Press-and-hold variant: focuses at the point like a tap but the sharp
     * lock ignores the auto-release timer and holds until double-tap reset,
     * manual focus, lens switch, or stop.
     */
    fun setFocusAndMeteringHold(viewX: Float, viewY: Float) {
        startTouchFocus(viewX, viewY, indefinite = true, updateAe = true)
    }

    private fun startTouchFocus(
        viewX: Float,
        viewY: Float,
        indefinite: Boolean,
        updateAe: Boolean = true
    ) {
        if (!isOnCameraThread()) {
            cameraHandler.post { startTouchFocus(viewX, viewY, indefinite, updateAe) }
            return
        }
        if (camera == null || session == null || previewSurface == null) return
        val hasAfRegions = (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        if (!hasAfRegions) {
            // Matches Open Camera: AE metering may still have been installed even when the
            // camera has no AF regions, and setFocusAndMeteringArea() reports no focus area.
            if (isAeMeteringSupported()) {
                aeRegion = meteringRegion(viewX, viewY)
                if (aeRegion != null) updateRepeatingRequest(preserveRawZslBuffer = true)
            } else {
                onState("FOCUS AREA NOT SUPPORTED")
            }
            return
        }

        val wasTouchActive = openCameraTouchFocusActive
        removeOpenCameraContinuousFocusReset()
        removeOpenCameraTouchFocusTimeout()
        removePendingTouchFocusStart()
        // A CANCEL is only needed to interrupt a previous touch scan/lock.
        // First taps skip it entirely: one less capture + one less repeating
        // rebuild on the critical path.
        if (wasTouchActive) sendAfCancelCapture()
        selectedFocusDistanceDiopters = null
        val region = meteringRegion(viewX, viewY) ?: return
        afRegion = region
        if (updateAe && isAeMeteringSupported()) aeRegion = region
        openCameraTouchFocusActive = true
        openCameraTouchFocusCompleted = false
        touchFocusStartSent = false
        touchFocusLockedSharp = false
        focusLockIndefinite = indefinite
        focusLockDeadlineMs = 0L
        onFocusLock(false, false, 0L)
        publishControls()

        if (!supportsOpenCameraTouchFocus()) {
            // Open Camera still applies the focus/metering rectangles in modes where an explicit
            // AUTO trigger is unavailable; continuous AF then keeps running with the new region.
            openCameraTouchFocusActive = false
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onState("FOCUS AREA SET")
            return
        }

        if (captureInProgress.get() || cameraCaptureSequenceActive) {
            // An AF trigger issued mid-still would be ignored or fail. Keep the
            // new regions/mode in the repeating stream and retry START shortly.
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onState("FOCUSING")
            scheduleOpenCameraTouchFocusTimeout()
            pendingTouchFocusStart = Runnable {
                pendingTouchFocusStart = null
                if (openCameraTouchFocusActive && !openCameraTouchFocusCompleted) sendTouchFocusStart()
            }.also { cameraHandler.postDelayed(it, TOUCH_FOCUS_START_RETRY_MS) }
            return
        }

        try {
            updateRepeatingRequest(preserveRawZslBuffer = true)
            // Let the AUTO-mode repeating commit before START is queued so the
            // HAL cannot observe START while still in CONT_PICTURE.
            onState("FOCUSING")
            scheduleOpenCameraTouchFocusTimeout()
            pendingTouchFocusStart = Runnable {
                pendingTouchFocusStart = null
                if (openCameraTouchFocusActive && !openCameraTouchFocusCompleted) sendTouchFocusStart()
            }.also { cameraHandler.postDelayed(it, TOUCH_FOCUS_START_SETTLE_MS) }
        } catch (failure: CameraAccessException) {
            openCameraTouchFocusActive = false
            touchFocusLockedSharp = false
            focusLockIndefinite = false
            focusLockDeadlineMs = 0L
            onFocusLock(false, false, 0L)
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onState("FOCUS ERROR")
        }
    }

    /** One-shot AF START built from the current repeating state (AUTO + regions). */
    private fun sendTouchFocusStart() {
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        try {
            val start = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                if (dynamicExposureSettings.enabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            touchFocusStartSent = true
            touchFocusStartElapsedMs = SystemClock.elapsedRealtime()
            currentSession.capture(start.build(), debugCaptureCallback, cameraHandler)
        } catch (failure: CameraAccessException) {
            openCameraTouchFocusActive = false
            touchFocusStartSent = false
            touchFocusLockedSharp = false
            focusLockIndefinite = false
            focusLockDeadlineMs = 0L
            onFocusLock(false, false, 0L)
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onState("FOCUS ERROR")
        }
    }

    /** Single CANCEL capture with no repeating rebuild; caller re-asserts state once. */
    private fun sendAfCancelCapture() {
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        try {
            val cancel = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }
            currentSession.capture(cancel.build(), debugCaptureCallback, cameraHandler)
        } catch (_: CameraAccessException) {
        }
    }

    private fun removePendingTouchFocusStart() {
        pendingTouchFocusStart?.let(cameraHandler::removeCallbacks)
        pendingTouchFocusStart = null
    }

    fun setAePoint(viewX: Float, viewY: Float) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setAePoint(viewX, viewY) }
            return
        }
        if ((characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) == 0) {
            onState("AE AREA NOT SUPPORTED")
            return
        }
        aeRegion = meteringRegion(viewX, viewY) ?: return
        updateRepeatingRequest(preserveRawZslBuffer = true)
        onState("METERING EXPOSURE")
    }

    fun isAeMeteringSupported(): Boolean =
        (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0

    private fun continuousPictureAfMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ->
                CaptureRequest.CONTROL_AF_MODE_MACRO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF) ->
                CaptureRequest.CONTROL_AF_MODE_EDOF
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun supportsOpenCameraTouchFocus(): Boolean {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
    }

    fun getAeMeteringMode(): AeMeteringMode = aeMeteringMode

    fun setAeMeteringMode(mode: AeMeteringMode): Boolean {
        if (!isOnCameraThread()) {
            if (mode != AeMeteringMode.AUTO && !isAeMeteringSupported()) {
                onState("AE NOT SUPPORTED")
                return false
            }
            cameraHandler.post { setAeMeteringMode(mode) }
            return true
        }
        if (mode != AeMeteringMode.AUTO && !isAeMeteringSupported()) {
            onState("AE NOT SUPPORTED")
            return false
        }
        aeMeteringMode = mode
        if (openCameraTouchFocusActive) {
            continuousFocusResetOpenCamera()
        } else {
            aeRegion = null
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onMeteringReleased()
        }
        onState("AE METERING • ${mode.label}")
        return true
    }

    fun resetMeteringTargets() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::resetMeteringTargets)
            return
        }
        if (openCameraTouchFocusActive) {
            continuousFocusResetOpenCamera()
        }
        val hadTargets = afRegion != null || aeRegion != null
        afRegion = null
        aeRegion = null
        if (hadTargets) updateRepeatingRequest(preserveRawZslBuffer = true)
        onMeteringReleased()
    }

    private fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle? {
        val active = latestPreviewResult?.get(CaptureResult.SCALER_CROP_REGION)
            ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (viewfinder.width == 0 || viewfinder.height == 0) return null
        val x = (viewX / viewfinder.width).coerceIn(0f, 1f)
        val y = (viewY / viewfinder.height).coerceIn(0f, 1f)
        val relativeRotation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val mirrored = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sensorPoint = RawPreviewGeometry.sensorPoint(x, y, relativeRotation, mirrored)
        val centerX = active.left + (sensorPoint.first * active.width()).toInt()
        val centerY = active.top + (sensorPoint.second * active.height()).toInt()
        // Open Camera uses a 100x100 area in its -1000..1000 coordinate system: 5% of
        // each sensor dimension either side of the touched point.
        val halfWidth = (active.width() * 0.05f).toInt().coerceAtLeast(1)
        val halfHeight = (active.height() * 0.05f).toInt().coerceAtLeast(1)
        val rect = Rect(
            (centerX - halfWidth).coerceIn(active.left, active.right - 1),
            (centerY - halfHeight).coerceIn(active.top, active.bottom - 1),
            (centerX + halfWidth).coerceIn(active.left + 1, active.right),
            (centerY + halfHeight).coerceIn(active.top + 1, active.bottom)
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    fun cycleIso() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleIso)
            return
        }
        if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            onState("ISO N/A")
            return
        }
        val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val values = ISO_STEPS.filter(range::contains)
        selectedIso = nextValue(selectedIso, values)
        controlsChanged()
    }

    fun cycleShutter() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleShutter)
            return
        }
        if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            onState("SHUTTER N/A")
            return
        }
        val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        val values = SHUTTER_STEPS.filter(range::contains)
        selectedExposureNanos = nextValue(selectedExposureNanos, values)
        controlsChanged()
    }

    fun cycleWhiteBalance() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleWhiteBalance)
            return
        }
        if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            onState("WB N/A")
            return
        }
        selectedWbKelvin = nextValue(selectedWbKelvin, WB_STEPS)
        controlsChanged()
    }

    fun cycleExposureCompensation() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleExposureCompensation)
            return
        }
        if (selectedIso != null || selectedExposureNanos != null) {
            onState("EV NEEDS AUTO")
            return
        }
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        val values = exposureCompensationSteps(range.lower, range.upper)
        exposureCompensation = nextValue(exposureCompensation, values, includeAuto = false) ?: 0
        controlsChanged()
    }

    fun toggleTorch() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::toggleTorch)
            return
        }
        if (characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            onState("FLASH N/A")
            return
        }
        torchEnabled = !torchEnabled
        controlsChanged()
    }

    fun isOisSupported(): Boolean = characteristics
        ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

    fun isOisEnabled(): Boolean = oisEnabled && isOisSupported()

    fun toggleOis() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::toggleOis)
            return
        }
        if (!isOisSupported()) {
            onState("OIS N/A")
            return
        }
        oisEnabled = !oisEnabled
        controlsChanged()
        onState(if (oisEnabled) "OIS ON" else "OIS OFF")
    }

    fun isRawZslEnabled(): Boolean = rawZslRequested

    /** Avoid full-resolution RAW histogram work while the histogram UI is hidden. */
    fun setRawHistogramEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setRawHistogramEnabled(enabled) }
            return
        }
        rawHistogramEnabled = enabled
        if (enabled) lastRawHistogramSampleMs = Long.MIN_VALUE
        updateRepeatingRequest(preserveRawZslBuffer = true)
    }

    /** YUV (false) shows the processed preview; RAW (true) streams live sensor histograms
     * in every capture mode. Persisted by the Activity; toggled by tapping the histogram. */
    fun setHistogramSourceRaw(raw: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setHistogramSourceRaw(raw) }
            return
        }
        histogramSourceRaw = raw
        if (raw) {
            rawHistogramDisabledForSession = false
            lastRawHistogramSampleMs = Long.MIN_VALUE
        }
        updateRepeatingRequest(preserveRawZslBuffer = true)
    }

    fun setDynamicExposureSettings(settings: DynamicExposureSettings) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setDynamicExposureSettings(settings) }
            return
        }
        dynamicExposureSettings = settings.copy(balance = settings.balance.coerceIn(0.25f, 4f))
        dynamicIso = null
        dynamicShutterNanos = null
        dynamicIsoLimited = false
        dynamicShutterLimited = false
        cancelDynamicExposureProbe()
        updateRepeatingRequest()
    }

    fun getProgramAeProfile(): ProgramAeProfile = programAeProfile

    /** False on cameras without manual sensor control: PROGRAM falls back to Android AE. */
    fun hasManualSensorControl(): Boolean =
        supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

    fun setProgramAeProfile(profile: ProgramAeProfile) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setProgramAeProfile(profile) }
            return
        }
        programAeProfile = profile.validated()
        // Re-seed the live pair so a new balance/limits/lock takes effect immediately;
        // the next sampled RAW frame reconverges from the current sensor exposure.
        dynamicIso = null
        dynamicShutterNanos = null
        cancelProgramRampTick()
        programTargetIso = null
        programTargetShutterNanos = null
        programTargetLimits = null
        programConverged = false
        programBrightness = Float.NaN
        programBrightnessEma = Float.NaN
        programFastStart = true
        lastProgramUpdateMs = Long.MIN_VALUE
        cancelDynamicExposureProbe()
        updateRepeatingRequest()
    }

    private fun programCustomActive(): Boolean =
        captureExposureMode == CaptureExposureMode.PROGRAM && dynamicExposureSettings.enabled &&
            !rawZslRequested && selectedIso == null && selectedExposureNanos == null &&
            captureExposureMode != CaptureExposureMode.MANUAL

    fun getEttrSettings(): EttrSettings = ettrSettings

    fun setEttrSettings(settings: EttrSettings) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setEttrSettings(settings) }
            return
        }
        ettrSettings = settings.copy(headroomEv = settings.headroomEv.coerceIn(0f, 1f))
        if (!ettrSettings.enabled) clearEttrLiveState()
        lastEttrUpdateMs = Long.MIN_VALUE
        updateRepeatingRequest()
    }

    /** Drops the converged ETTR pair; the enabled preference is preserved. */
    private fun clearEttrLiveState() {
        ettrIso = null
        ettrShutterNanos = null
        ettrConverged = false
        ettrHottest = Float.NaN
        ettrClipHot = Float.NaN
        ettrClipSecond = Float.NaN
    }

    /**
     * True when a RAW-measured ETTR pair is ready for the next forward capture.
     * ETTR is an AUTO-mode feature only: PROGRAM owns exposure through its own
     * RAW loop, MANUAL freezes the metered pair, and ZSL saves buffered frames
     * as metered.
     */
    private fun ettrModeAvailable(): Boolean =
        captureExposureMode == CaptureExposureMode.AUTO

    private fun ettrCaptureActive(): Boolean =
        ettrSettings.enabled && ettrModeAvailable() && !rawZslRequested &&
            selectedIso == null && selectedExposureNanos == null &&
            ettrIso != null && ettrShutterNanos != null

    /**
     * Dispatches one shared metering sample for the custom-AE loops. The caller is the
     * camera-thread ImageReader drain: it only decides *whether* a sample is due and
     * hands the frame over. Heavy Bayer sampling runs on [meteringExecutor] and the
     * resulting pair is applied back on the camera thread, so metering can never
     * stall RAW acquisition (a ~300 ms sample at 2 Hz used to occupy most of the
     * camera handler and backpressure the HAL into ~1 fps preview).
     *
     * At most one sample is ever in flight: while the executor is busy, frames are
     * closed immediately instead of queueing, bounding held gralloc buffers to one.
     * Stale results (lens switch, mode change, stop) are dropped by generation.
     */
    private fun dispatchMeteringSample(image: Image, cameraCharacteristics: CameraCharacteristics) {
        if (captureInProgress.get()) {
            RawImageOwnership.release(image)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wantProgram = programCustomActive() && !ettrCaptureActive()
        val wantEttr = ettrSettings.enabled && ettrModeAvailable() &&
            selectedIso == null && selectedExposureNanos == null
        if (!wantProgram && !wantEttr) {
            RawImageOwnership.release(image)
            return
        }
        val programDue = wantProgram &&
            RawHistogramThrottle.shouldSample(
                now, lastProgramUpdateMs,
                meteringInterval(if (programConverged) PROGRAM_CONVERGED_INTERVAL_MS else PROGRAM_UPDATE_INTERVAL_MS)
            )
        val ettrDue = wantEttr &&
            RawHistogramThrottle.shouldSample(
                now, lastEttrUpdateMs,
                meteringInterval(if (ettrConverged) ETTR_CONVERGED_INTERVAL_MS else ETTR_UPDATE_INTERVAL_MS)
            )
        if ((!programDue && !ettrDue) || !meteringInFlight.compareAndSet(false, true)) {
            RawImageOwnership.release(image)
            return
        }
        if (programDue) lastProgramUpdateMs = now
        if (ettrDue) lastEttrUpdateMs = now
        val generation = lifecycleGeneration
        try {
        meteringExecutor.execute {
            try {
                val sampleStartMs = SystemClock.elapsedRealtime()
                // ETTR needs full-density tails; PROGRAM alone is served by the
                // sparse region-cropped path, which shares one sample when both
                // loops are due.
                val sample = if (ettrDue) RawEttrSampler.sample(image, cameraCharacteristics)
                else RawEttrSampler.sampleProgram(
                    image, cameraCharacteristics, programAeProfile.metering
                )
                    val sampleMs = SystemClock.elapsedRealtime() - sampleStartMs
                    lastMeteringSampleMs = sampleMs
                    if (sampleMs > SICK_SAMPLE_LOG_MS) {
                        Log.w(LOG_TAG, "Slow RAW metering sample: ${sampleMs}ms (image ${image.width}x${image.height})")
                    } else if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                        Log.d(LOG_TAG, "Metering sample ${sampleMs}ms ${image.width}x${image.height} " +
                            (if (ettrDue) "full" else "sparse"))
                    }
                    if (sample != null) {
                        cameraHandler.post {
                            if (generation == lifecycleGeneration && running && !destroyed) {
                                if (programDue) updateProgramFromSample(sample)
                                if (ettrDue) updateEttrFromSample(sample)
                            }
                        }
                    }
                } finally {
                    runCatching { RawImageOwnership.release(image) }
                    meteringInFlight.set(false)
                    cameraHandler.post { closeRetiredRawReaders() }
                }
            }
        } catch (_: RejectedExecutionException) {
            meteringInFlight.set(false)
            runCatching { RawImageOwnership.release(image) }
        }
    }

    /**
     * Effective metering cadence. Self-tuning: a slow sensor naturally serializes
     * through the single in-flight slot, so the base rate only doubles when one
     * sample costs more than the base interval itself. Fast devices are unaffected.
     */
    private fun meteringInterval(baseMs: Long): Long =
        if (lastMeteringSampleMs > baseMs) baseMs * 2 else baseMs

    /**
     * One PROGRAM custom-AE iteration: RAW brightness (center/average/spot per
     * profile) vs biased mid-gray target gives an EV shift; the closed-loop energy
     * is redistributed by balance/limits/locks directly into sensor ISO + shutter.
     * The repeating preview runs AE_OFF with this pair (see applyCameraControls),
     * so the next sample closes the loop.
     *
     * Runs on the camera thread; callers must sample off-thread (see
     * [dispatchMeteringSample]) and post the result here.
     */
    private fun updateProgramFromSample(sample: EttrRawSample) {
        if (!programCustomActive() || ettrCaptureActive()) return
        val c = characteristics ?: return
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val shutterRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        val profile = programAeProfile
        val limits = resolveProgramLimits(profile, isoRange, shutterRange) ?: return
        val baselineIso = dynamicIso ?: lastIso
        val baselineShutter = dynamicShutterNanos ?: lastExposureNanos
        if (baselineIso <= 0 || baselineShutter <= 0L) return
        val brightness = RawProgramMeter.brightness(sample, profile.metering)
        programBrightnessEma = if (programBrightnessEma.isFinite()) {
            (PROGRAM_EMA_ALPHA * brightness + (1.0 - PROGRAM_EMA_ALPHA) * programBrightnessEma)
                .toFloat().coerceIn(0f, 1f)
        } else brightness
        programBrightness = programBrightnessEma
        // Cropped scans carry a full-frame guard level so a dark metered region
        // cannot blind the highlight guard to bright surroundings; full-frame
        // scans already report the global hottest channel.
        val hottest = sample.guardHottest.takeIf { it.isFinite() } ?: sample.levels.hottest
        // Loop (re)entry measures with full authority so one snap lands on the
        // correct exposure; tracking nudges stay capped for smoothness.
        val stepCap = if (programFastStart) RawProgramMeter.MAX_STEP_EV
        else RawProgramMeter.PROGRAM_MAX_STEP_EV
        val shift = RawProgramMeter.guardShiftEv(
            RawProgramMeter.correctionEv(programBrightnessEma, profile.evBias, stepCap),
            hottest
        )
        val baselineEnergy = baselineIso.toDouble() * baselineShutter
        // Small capped steps only (PROGRAM_MAX_STEP_EV): the ramp below owns
        // smoothness, so the full measured nudge becomes the glide target.
        val targetEnergy = baselineEnergy * 2.0.pow(shift)
        val (lockedIsoEff, lockedShutterEff) = resolveProgramLockedValues(
            profile, limits, baselineIso, baselineShutter
        )
        val result = AutoExposureBalance.applyProgramEnergy(
            targetEnergy.coerceAtLeast(1.0),
            profile.balance,
            limits,
            profile.lockMode,
            lockedIsoEff,
            lockedShutterEff
        )
        programTargetIso = result.iso
        programTargetShutterNanos = result.shutterNanos
        programTargetLimits = limits
        programTargetLockMode = profile.lockMode
        programTargetLockedIso = lockedIsoEff
        programTargetLockedShutterNanos = lockedShutterEff
        dynamicIsoLimited = result.isoLimited
        dynamicShutterLimited = result.shutterLimited
        programConverged = shift == 0.0 && !result.isoLimited && !result.shutterLimited ||
            kotlin.math.abs(shift) <= RawProgramMeter.CONVERGED_TOLERANCE_EV
        if (programFastStart) {
            // Loop (re)entry: land on the measured exposure immediately instead of
            // gliding in from whatever the previous engine left behind. Tracking
            // glides from there.
            programFastStart = false
            dynamicIso = result.iso
            dynamicShutterNanos = result.shutterNanos
            pushProgramPairLive()
            cancelProgramRampTick()
            Log.i(
                LOG_TAG,
                "PROGRAM fast-start ISO ${result.iso} EXP ${formatExposure(result.shutterNanos)} " +
                    "(shift ${String.format(java.util.Locale.US, "%+.2f", shift)} EV)"
            )
        } else if (dynamicIso != result.iso || dynamicShutterNanos != result.shutterNanos) {
            scheduleProgramRampTick()
        } else {
            cancelProgramRampTick()
        }
    }

    /**
     * One glide tick: ease the applied (live) pair toward the solver target with
     * an adaptive step (fast far away, gentle up close), preserving the target's
     * ISO/shutter ratio (or the locked axis), then push the live repeating
     * request. Reschedules itself until the applied pair snaps to target.
     */
    private fun programRampTick() {
        programRampCallback = null
        if (!programCustomActive() || ettrCaptureActive() || captureInProgress.get() ||
            !running || destroyed
        ) return
        if (openCameraTouchFocusActive && !openCameraTouchFocusCompleted) {
            // A ~10 Hz repeating rebuild mid-scan restarts the HAL AF sweep.
            // Defer the exposure glide until the touch lock settles or times out.
            scheduleProgramRampTick()
            return
        }
        val targetIso = programTargetIso ?: return
        val targetShutter = programTargetShutterNanos ?: return
        val limits = programTargetLimits ?: return
        val curIso = dynamicIso ?: targetIso
        val curShutter = dynamicShutterNanos ?: targetShutter
        val steppedEnergy = AutoExposureBalance.rampStepEnergyAdaptive(
            curIso.toDouble() * curShutter,
            targetIso.toDouble() * targetShutter,
            PROGRAM_RAMP_PROPORTION,
            PROGRAM_RAMP_MAX_STEP_EV,
            PROGRAM_RAMP_SNAP_EV
        )
        val targetEnergy = targetIso.toDouble() * targetShutter
        val (iso, shutter) = if (steppedEnergy == targetEnergy) {
            targetIso to targetShutter
        } else when (programTargetLockMode) {
            ProgramLockMode.ISO_LOCK -> {
                val fixedIso = programTargetLockedIso.coerceIn(limits.isoMin, limits.isoMax)
                val glidingShutter = (steppedEnergy / fixedIso)
                    .coerceIn(limits.shutterMinNanos.toDouble(), limits.shutterMaxNanos.toDouble())
                fixedIso to glidingShutter.toLong().coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
            }
            ProgramLockMode.SHUTTER_LOCK -> {
                val fixedShutter = programTargetLockedShutterNanos
                    .coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
                val glidingIso = (steppedEnergy / fixedShutter)
                    .coerceIn(limits.isoMin.toDouble(), limits.isoMax.toDouble())
                glidingIso.roundToInt().coerceIn(limits.isoMin, limits.isoMax) to fixedShutter
            }
            ProgramLockMode.NONE -> AutoExposureBalance.splitEnergyRatio(
                steppedEnergy, targetIso, targetShutter, limits
            )
        }
        val snapped = steppedEnergy == targetEnergy
        if (dynamicIso != iso || dynamicShutterNanos != shutter) {
            dynamicIso = iso
            dynamicShutterNanos = shutter
            pushProgramPairLive()
        }
        if (!snapped) scheduleProgramRampTick()
    }

    private fun scheduleProgramRampTick() {
        cancelProgramRampTick()
        val generation = lifecycleGeneration
        programRampCallback = Runnable {
            programRampCallback = null
            if (generation == lifecycleGeneration) programRampTick()
        }.also { cameraHandler.postDelayed(it, PROGRAM_RAMP_INTERVAL_MS) }
    }

    private fun cancelProgramRampTick() {
        programRampCallback?.let(cameraHandler::removeCallbacks)
        programRampCallback = null
    }

    /** Lightweight live push: rebuild only the repeating request with the gliding
     * pair. Unlike a full [updateRepeatingRequest], it never drains RAW buffers,
     * so ~10 Hz micro-pushes stay cheap. Failures snap straight to target. */
    private fun pushProgramPairLive() {
        try {
            updateRepeatingRequest(preserveRawZslBuffer = true, light = true)
        } catch (_: Exception) {
            cancelProgramRampTick()
            programTargetIso?.let { dynamicIso = it }
            programTargetShutterNanos?.let { dynamicShutterNanos = it }
        }
    }

    private fun resolveProgramLimits(
        profile: ProgramAeProfile,
        isoRange: android.util.Range<Int>,
        shutterRange: android.util.Range<Long>
    ): ExposureBalanceLimits? {
        val isoMinEff = (profile.isoMin.takeIf { it > 0 } ?: isoRange.lower)
            .coerceIn(isoRange.lower, isoRange.upper)
        val isoMaxEff = (profile.isoMax.takeIf { it > 0 } ?: isoRange.upper)
            .coerceIn(isoRange.lower, isoRange.upper)
        if (isoMinEff > isoMaxEff) return null
        val shutterMinEff = (profile.shutterMinNanos.takeIf { it > 0L } ?: shutterRange.lower)
            .coerceIn(shutterRange.lower, shutterRange.upper)
        val shutterMaxEff = when {
            profile.shutterMaxNanos > 0L -> profile.shutterMaxNanos.coerceIn(shutterRange.lower, shutterRange.upper)
            profile.useAutoSafeShutter -> resolveDynamicShutterLimit(
                DynamicExposureSettings(enabled = true), shutterRange.upper
            )
            else -> shutterRange.upper
        }.coerceIn(shutterRange.lower, shutterRange.upper)
        if (shutterMinEff > shutterMaxEff) return null
        val startEff = minOf(PROGRAM_SHUTTER_START_NANOS, shutterMaxEff).coerceIn(shutterMinEff, shutterMaxEff)
        return ExposureBalanceLimits(
            isoMin = isoMinEff,
            isoMax = isoMaxEff,
            shutterMinNanos = shutterMinEff,
            shutterMaxNanos = shutterMaxEff,
            shutterStartNanos = startEff
        )
    }

    /**
     * Effective locked values for the solver. An explicitly stored locked value
     * always wins; otherwise the actual sensor exposure seeds the lock (a stable
     * reference, unlike the moving live pair, which would let a "locked" axis
     * drift with the scene). UI lock engagement is expected to store explicit
     * values via [programProfile]; this fallback only covers legacy profiles.
     */
    private fun resolveProgramLockedValues(
        profile: ProgramAeProfile,
        limits: ExposureBalanceLimits,
        fallbackIso: Int,
        fallbackShutter: Long
    ): Pair<Int, Long> {
        val lockedIsoEff = when (profile.lockMode) {
            ProgramLockMode.ISO_LOCK -> profile.lockedIso.takeIf { it > 0 }
                ?: lastIso.coerceIn(limits.isoMin, limits.isoMax)
            else -> fallbackIso.coerceIn(limits.isoMin, limits.isoMax)
        }.coerceIn(limits.isoMin, limits.isoMax)
        val lockedShutterEff = when (profile.lockMode) {
            ProgramLockMode.SHUTTER_LOCK -> profile.lockedShutterNanos.takeIf { it > 0L }
                ?: lastExposureNanos.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
            else -> fallbackShutter.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        }.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        return lockedIsoEff to lockedShutterEff
    }

    /**
     * One ETTR iteration from a shared RAW sample. The baseline is always the actual
     * sensor exposure ([lastIso]/[lastExposureNanos]): when ETTR is active the
     * repeating preview already runs its pair, and PROGRAM is frozen, so the live
     * PROGRAM pair would be a stale value that was never applied.
     *
     * Runs on the camera thread; callers must sample off-thread (see
     * [dispatchMeteringSample]) and post the result here.
     */
    private fun updateEttrFromSample(sample: EttrRawSample) {
        if (!ettrSettings.enabled || !ettrModeAvailable()) return
        if (captureInProgress.get()) return
        val levels = sample.levels
        val baselineIso = lastIso
        val baselineShutter = lastExposureNanos
        if (baselineIso <= 0 || baselineShutter <= 0L || captureInProgress.get()) return
        val c = characteristics ?: return
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val shutterRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        updateEttrClipState(sample)
        val shift = RawEttrMeter.correctionEv(levels.hottest, ettrSettings.headroomEv)
        val result = RawEttrMeter.solve(
            baselineIso,
            baselineShutter,
            shift,
            EttrLimits(
                isoMin = isoRange.lower,
                isoMax = ettrSettings.isoLimit.takeIf { it > 0 }
                    ?.coerceIn(isoRange.lower, isoRange.upper) ?: isoRange.upper,
                shutterMinNanos = shutterRange.lower,
                shutterMaxNanos = shutterRange.upper,
                safeShutterNanos = ettrSafeShutterCeiling(shutterRange.upper)
            ),
            hottestChannel = levels.hottest
        )
        ettrIso = result.iso
        ettrShutterNanos = result.shutterNanos
        ettrConverged = result.converged
        ettrHottest = levels.hottest
    }

    /**
     * Hottest/second measured clipped fractions for the ETTR debug readout, judged
     * over R, pooled green, B — both greens are one color for reconstruction.
     */
    private fun updateEttrClipState(sample: EttrRawSample) {
        fun fraction(saturated: Int, total: Int): Float =
            if (total > 0) saturated.toFloat() / total else 0f
        val greenTotal = sample.totals.getOrElse(1) { 0 } + sample.totals.getOrElse(2) { 0 }
        val greenSat = sample.saturated.getOrElse(1) { 0 } + sample.saturated.getOrElse(2) { 0 }
        val colors = floatArrayOf(
            fraction(sample.saturated.getOrElse(0) { 0 }, sample.totals.getOrElse(0) { 0 }),
            fraction(greenSat, greenTotal),
            fraction(sample.saturated.getOrElse(3) { 0 }, sample.totals.getOrElse(3) { 0 })
        )
        var hottest = 0f
        var second = 0f
        for (value in colors) {
            if (value > hottest) {
                second = hottest
                hottest = value
            } else if (value > second) {
                second = value
            }
        }
        ettrClipHot = hottest
        ettrClipSecond = second
    }

    /** Auto handheld ceiling for ETTR, further clamped by live gyro motion. */
    private fun ettrSafeShutterCeiling(sensorMax: Long): Long {
        val c = characteristics ?: return sensorMax
        val shutterRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return sensorMax
        // Same focal-scaled 1/15 s auto policy as the Program curve; ETTR exposes
        // no separate ceiling control.
        val ceiling = resolveDynamicShutterLimit(
            DynamicExposureSettings(enabled = true), sensorMax
        )
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 4.75f
        val sensorWidth = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 7.1f
        val equiv = (36f / sensorWidth.coerceAtLeast(0.1f)) * focal
        return RawEttrMeter.safeShutterNanos(
            motionTracker.currentMotion(), equiv, isOisEnabled(),
            ceiling, shutterRange.lower, sensorMax
        )
    }

    fun setRawZslFrameCount(frameCount: Int) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setRawZslFrameCount(frameCount) }
            return
        }
        val selected = frameCount.coerceIn(1, MAX_ZSL_FRAMES)
        if (selected == rawZslFrameCount) return
        rawZslFrameCount = selected
        restartCameraForConfigurationChange()
    }

    fun setRawZslEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setRawZslEnabled(enabled) }
            return
        }
        val wasRawZslRequested = rawZslRequested
        rawZslRequested = enabled
        if (enabled && !wasRawZslRequested) {
            lastRawHistogramSampleMs = Long.MIN_VALUE
        }
        if (!enabled) {
            rawZslStreaming = false
            clearRawZslBuffer()
            updateRepeatingRequest(allowRawZsl = false)
            onState("RAW ZSL OFF")
        } else {
            rawZslDisabledForSession = false
            rawZslFallbackDetail = null
            rawZslCooldownUntilMs = 0L
            rawZslStreamBroken = false
            if (rawZslCapacity == 0) {
                publishRawZslStatus(
                    RawZslState.FALLBACK,
                    "Full-resolution RAW frames exceed the safe memory budget"
                )
                onState("ZSL N/A • RAW")
            } else {
                updateRepeatingRequest()
                onState(if (rawZslStreaming) "ZSL WARMING" else "ZSL WAITING")
            }
        }
        publishRawZslStatus()
    }

    fun setCaptureExposureMode(mode: CaptureExposureMode) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setCaptureExposureMode(mode) }
            return
        }
        captureExposureMode = mode
        val wasRawZslRequested = rawZslRequested
        rawZslRequested = mode == CaptureExposureMode.ZSL
        if (rawZslRequested && !wasRawZslRequested) {
            // The first paired RAW frame should immediately replace the processed-preview
            // histogram when entering ZSL, rather than waiting for the periodic throttle.
            lastRawHistogramSampleMs = Long.MIN_VALUE
        }
        dynamicExposureSettings = dynamicExposureSettings.copy(enabled = mode == CaptureExposureMode.PROGRAM)
        dynamicIso = null
        dynamicShutterNanos = null
        dynamicIsoLimited = false
        dynamicShutterLimited = false
        cancelProgramRampTick()
        programTargetIso = null
        programTargetShutterNanos = null
        programTargetLimits = null
        programBrightness = Float.NaN
        programBrightnessEma = Float.NaN
        programConverged = false
        programFastStart = true
        lastProgramUpdateMs = Long.MIN_VALUE
        cancelDynamicExposureProbe()
        lastEttrUpdateMs = Long.MIN_VALUE
        if (mode != CaptureExposureMode.AUTO) {
            // ETTR is AUTO-only: drop any settled pair so returning to AUTO
            // reconverges from live metering instead of a stale exposure.
            // The enabled preference is kept and resumes automatically.
            clearEttrLiveState()
        }
        when (mode) {
            CaptureExposureMode.MANUAL -> {
                // Entering M freezes the currently metered pair, like a DSLR's manual mode.
                // A settled ETTR pair seeds it so manual starts from the optimal exposure.
                selectedIso = selectedIso ?: (if (ettrCaptureActive()) ettrIso else null) ?: lastIso
                selectedExposureNanos = selectedExposureNanos
                    ?: (if (ettrCaptureActive()) ettrShutterNanos else null) ?: lastExposureNanos
            }
            else -> {
                selectedIso = null
                selectedExposureNanos = null
            }
        }
        if (!rawZslRequested) {
            rawZslStreaming = false
            clearRawZslBuffer()
            updateRepeatingRequest(allowRawZsl = false)
        } else {
            rawZslDisabledForSession = false
            rawZslFallbackDetail = null
            rawZslCooldownUntilMs = 0L
            rawZslStreamBroken = false
            updateRepeatingRequest()
        }
        publishControls()
        publishRawZslStatus()
        // The exposure mode feeds the VF adaptive-strength rule: re-push so the
        // JPEG preview tracks the development exposure of the new mode.
        pushVfRenderState()
    }

    fun cycleLens() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleLens)
            return
        }
        if (rawCameraIds.size < 2 || captureInProgress.get()) {
            if (rawCameraIds.size < 2) onState("NO OTHER RAW LENS")
            return
        }
        val current = rawCameraIds.indexOf(selectedCameraId).coerceAtLeast(0)
        selectedCameraId = rawCameraIds[(current + 1) % rawCameraIds.size]
        afRegion = null; aeRegion = null
        restartCameraForConfigurationChange()
    }

    fun lensOptions(): List<LensOption> = rawCameraIds.map { id ->
        LensOption(id, zoomLabel(id), id == selectedCameraId)
    }

    fun activeCameraId(): String? = selectedCameraId

    /** Camera-provided values shown in the guided DNG override editor. */
    fun dngMetadataDefaults(): DngMetadataDefaults {
        val camera = characteristics
        fun transform(key: CameraCharacteristics.Key<ColorSpaceTransform>): List<Double>? =
            camera?.get(key)?.let { transform ->
                List(9) { index -> transform.getElement(index / 3, index % 3).toDouble() }
            }
        val black = camera?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
            IntArray(4).also { pattern.copyTo(it, 0) }.map(Int::toDouble)
        }
        val noise = latestPreviewResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.flatMap {
            listOf(it.first, it.second)
        }
        return DngMetadataDefaults(
            blackLevels = black,
            whiteLevel = camera?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toDouble(),
            colorMatrix1 = transform(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            colorMatrix2 = transform(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            cameraCalibration1 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),
            cameraCalibration2 = transform(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),
            forwardMatrix1 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
            forwardMatrix2 = transform(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
            noiseProfile = noise
        )
    }

    fun selectLens(cameraId: String) {
        if (!isOnCameraThread()) {
            cameraHandler.post { selectLens(cameraId) }
            return
        }
        if (cameraId == selectedCameraId || cameraId !in rawCameraIds || captureInProgress.get()) return
        selectedCameraId = cameraId
        afRegion = null; aeRegion = null
        restartCameraForConfigurationChange()
    }

    /** Camera-thread restart used by lens and RAW stream configuration changes. */
    private fun restartCameraForConfigurationChange() {
        check(isOnCameraThread())
        val shouldRestart = running || opening || camera != null
        if (!shouldRestart) return
        stop()
        running = true
        lifecycleGeneration++
        open()
    }

    fun manualControlRange(control: ManualControl): ManualControlRange? {
        return when (control) {
        ManualControl.ISO -> {
            if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null
            val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: return null
            ManualControlRange(
                range.lower.toLong(), range.upper.toLong(),
                (selectedIso ?: lastIso).coerceIn(range.lower, range.upper).toLong(),
                selectedIso == null
            )
        }
        ManualControl.SHUTTER -> {
            if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null
            val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: return null
            ManualControlRange(
                range.lower, range.upper,
                (selectedExposureNanos ?: lastExposureNanos).coerceIn(range.lower, range.upper),
                selectedExposureNanos == null
            )
        }
        ManualControl.WHITE_BALANCE -> {
            if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) return null
            ManualControlRange(
                MIN_WB_KELVIN.toLong(), MAX_WB_KELVIN.toLong(),
                (selectedWbKelvin ?: 5500).toLong(), selectedWbKelvin == null
            )
        }
        ManualControl.FOCUS_DISTANCE -> {
            val minimumDistance = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.takeIf { it > 0f } ?: return null
            ManualControlRange(
                0L, (minimumDistance * FOCUS_DISTANCE_SCALE).toLong(),
                ((selectedFocusDistanceDiopters ?: 0f) * FOCUS_DISTANCE_SCALE).toLong(),
                selectedFocusDistanceDiopters == null
            )
        }
        ManualControl.EXPOSURE_COMPENSATION -> {
            val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: return null
            ManualControlRange(
                range.lower.toLong(), range.upper.toLong(), exposureCompensation.toLong(),
                exposureCompensation == 0
            )
        }
        }
    }

    fun setManualControl(control: ManualControl, value: Long?) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setManualControl(control, value) }
            return
        }
        when (control) {
            ManualControl.ISO -> selectedIso = value?.toInt()
            ManualControl.SHUTTER -> selectedExposureNanos = value
            ManualControl.WHITE_BALANCE -> selectedWbKelvin = value?.toInt()
            ManualControl.FOCUS_DISTANCE -> {
                val minimumDistance = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                selectedFocusDistanceDiopters = value?.let { raw ->
                    (raw.toFloat() / FOCUS_DISTANCE_SCALE).coerceIn(0f, minimumDistance)
                }
                if (value != null && openCameraTouchFocusActive) {
                    // Manual focus takes over: drop the held touch lock silently
                    // (no CANCEL needed, AF_MODE_OFF wins in applyCameraControls).
                    removeOpenCameraContinuousFocusReset()
                    removeOpenCameraTouchFocusTimeout()
                    removePendingTouchFocusStart()
                    touchFocusStartSent = false
                    touchFocusLockedSharp = false
                    focusLockIndefinite = false
                    focusLockDeadlineMs = 0L
                    onFocusLock(false, false, 0L)
                    openCameraTouchFocusActive = false
                    openCameraTouchFocusCompleted = false
                }
            }
            ManualControl.EXPOSURE_COMPENSATION -> exposureCompensation = value?.toInt() ?: 0
        }
        clampControlsToCamera()
        controlsChanged()
    }

    fun exposureCompensationStops(index: Long): Float {
        val step = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: return 0f
        return index * step.toFloat()
    }

    fun reloadLenses() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::reloadLenses)
            return
        }
        selectedCameraId = null
        restartCameraForConfigurationChange()
    }

    private fun controlsChanged() {
        publishControls()
        updateRepeatingRequest(preserveRawZslBuffer = true)
    }

    private fun updateRepeatingRequest(
        allowRawZsl: Boolean = true,
        preserveRawZslBuffer: Boolean = false,
        light: Boolean = false
    ) {
        if (!isOnCameraThread()) {
            cameraHandler.post { updateRepeatingRequest(allowRawZsl, preserveRawZslBuffer, light) }
            return
        }
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        val reader = rawReader
        val generation = lifecycleGeneration
        // Queued DNG/JPEG saves must not stop the live RAW streams: their buffers
        // are pre-allocated and bounded, and a 6-frame JPEG burst would otherwise
        // leave the viewfinder without RAW (stale histogram, YUV graph) for the
        // whole development queue. Only an active forward capture stops them.
        val captureActive = captureInProgress.get()
        // Split gate: a reserved capture slot (ZSL burst being assembled in the
        // retry wait) must NOT block the ZSL ring itself — otherwise a per-shot
        // restart during the wait rebuilds preview-only and the ring can never
        // refill (observed as ZSL NOT READY with saves=0). Only a physically
        // executing still sequence blocks it, since interleaved repeating RAW
        // frames would steal reader slots from the in-flight still captures.
        // Metering streams keep the strict gate: they must stay down across any
        // held capture so forward captures keep full reader headroom.
        val stillExecuting = cameraCaptureSequenceActive || activeFramesRemaining.get() > 0
        val rawRetryReady = SystemClock.elapsedRealtime() >= rawPreviewRetryUntilMs
        val readerReady = rawZslCapacity > 0 && reader != null && rawRetryReady
        // A transient cooldown parks ZSL exactly like the latched fallback while
        // it runs; every later press retries from scratch instead (per-shot).
        val zslBlocked = rawZslDisabledForSession || rawZslStreamBroken ||
            SystemClock.elapsedRealtime() < rawZslCooldownUntilMs
        val useRawZsl = allowRawZsl && zslRestartFitsReader() && shouldRunRawStream(
            rawZslRequested, zslBlocked, readerReady, captureActive && stillExecuting
        )
        // Live RAW histogram for every capture mode while the user selects the RAW source.
        // Never competes with the ZSL ring: histogram-only frames are sampled and closed.
        val useRawHistogram = !useRawZsl && shouldRunRawStream(
            rawHistogramEnabled && histogramSourceRaw, rawHistogramDisabledForSession,
            readerReady, captureActive
        )
        // RAW-based ETTR needs the same metering stream even when the histogram is off
        // or showing YUV: sampled frames update the still-capture pair and are closed.
        // ETTR is AUTO-only; PROGRAM drives exposure through its own RAW loop.
        val useRawEttr = !useRawZsl && ettrSettings.enabled && ettrModeAvailable() && shouldRunRawStream(
            true, false, readerReady, captureActive
        ) &&
            selectedIso == null && selectedExposureNanos == null &&
            captureExposureMode != CaptureExposureMode.MANUAL
        // PROGRAM custom AE needs the same metering stream: RAW brightness (center-weighted
        // mean or median per profile) drives sensor ISO + shutter directly (AE_OFF live).
        // Manual exposure wins, ZSL is mutually exclusive.
        val useRawProgram = !useRawZsl && programCustomActive() && shouldRunRawStream(
            true, false, readerReady, captureActive
        )
        // WYSIWYG preview: BOTH modes render our scene-referred pipeline from the RAW
        // feed — raw-clean Reinhard for DNG_ONLY, cheap AgX for JPEG/JPEG_DNG. Never
        // ISP YUV. The tonemap is a per-frame VF uniform, so the stream stays identical.
        val useRawViewfinder = rawViewfinder != null && reader != null && rawRetryReady
        val useRawStream = useRawViewfinder || useRawZsl || useRawHistogram || useRawEttr || useRawProgram
        try {
            val preserveBuffer = preserveRawZslBuffer && rawZslStreaming && useRawZsl
            // Keep existing paired candidates through benign control changes (tap focus and
            // dynamic AE). Only transition frames are discarded; the ring stays ready.
            rawZslStreaming = false
            rawHistogramStreaming = false
            ettrStreaming = false
            programStreaming = false
            val requestEpoch = if (light) rawZslRequestEpoch else ++rawZslRequestEpoch
            // Light pushes only swap the live pair: never disturb buffered/draining RAW.
            if (!light && !preserveBuffer) {
                clearRawZslBuffer()
                if (activeFramesRemaining.get() <= 0) closeUnmatchedRawImages()
                drainQueuedRawImages(reader)
            }
            // Keep the SurfaceTexture on a real PREVIEW request even while RAW ZSL is active.
            // Several Camera2 HALs treat TEMPLATE_ZERO_SHUTTER_LAG as a reprocessing/still path
            // and stop advancing the preview target once a full-resolution RAW target is added.
            // RAW ZSL here is app-operated (our ImageReader + ring buffer), so PREVIEW is the
            // correct repeating template and both targets continue to receive every frame.
            fun previewRequest(includeRaw: Boolean): CaptureRequest =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (includeRaw) addTarget(reader!!.surface)
                setTag(includeRaw)
                applyCameraControls(this)
                // Compat sessions omit the forced range: the HAL runs the
                // RAW+preview combo at its own sustainable rate instead of a
                // fixed 30 fps the full-resolution RAW readout may not meet.
                if (includeRaw && !rawStreamCompatMode) rawZslTargetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (useRawZsl) {
                    // App-operated RAW ZSL still uses TEMPLATE_PREVIEW so the TextureView never
                    // freezes, but tell 3A/HAL scheduling that this repeating request is serving
                    // a zero-shutter-lag capture pipeline. Request the best advertised 30 fps
                    // range whenever the configured RAW+preview streams can physically sustain
                    // it; slower RAW sensors fall back to their measured stream ceiling.
                    set(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        if (includeRaw) CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
                        else CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
                    )
                    if (!rawStreamCompatMode) rawZslTargetFpsRange?.let { range ->
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    }
                    if (includeRaw) requestLensShadingMap(this)
                }
            }.build()
            // Motion-adaptive CAF needs gyro information even when RAW ZSL is disabled or while
            // previous DNG/JPEG saves are still queued. The tracker is lightweight and start() is
            // idempotent, so keep it alive for the whole preview lifetime instead of coupling it
            // to the RAW ZSL stream.
            motionTracker.start(cameraHandler)
            val rawSurface = reader?.surface?.takeIf { useRawStream }
            val callback = previewCaptureCallback(generation, rawSurface, requestEpoch)
            if (useRawStream) {
                // MotionCam RAW-viewfinder port: every repeating request carries the RAW
                // target alongside preview, so the ZSL ring (or live histogram/ETTR sample)
                // refreshes at the full requested rate (30 fps) instead of one RAW frame
                // per interleave cycle. Histogram sampling itself is throttled to a few
                // updates per second downstream.
                val requests = ArrayList<CaptureRequest>(RAW_ZSL_REQUEST_PERIOD).apply {
                    add(previewRequest(includeRaw = true))
                    repeat(RAW_ZSL_REQUEST_PERIOD - 1) {
                        add(previewRequest(includeRaw = false))
                    }
                }
                if (requests.size == 1) {
                    currentSession.setRepeatingRequest(requests.single(), callback, cameraHandler)
                } else {
                    currentSession.setRepeatingBurst(requests, callback, cameraHandler)
                }
            } else {
                currentSession.setRepeatingRequest(
                    previewRequest(includeRaw = false), callback, cameraHandler
                )
            }
            val wasZslStreaming = rawZslStreaming
            rawViewfinderStreaming = useRawViewfinder
            rawZslStreaming = useRawZsl
            rawHistogramStreaming = useRawHistogram
            ettrStreaming = useRawEttr
            programStreaming = useRawProgram
            if (useRawZsl != wasZslStreaming) {
                Log.i(
                    LOG_TAG,
                    "ZSL stream ${if (useRawZsl) "STARTED" else "STOPPED"} " +
                        "(allow=$allowRawZsl epoch=$requestEpoch saves=${pendingSaveCount.get()} " +
                        "buffered=${rawZslBuffer?.size ?: 0}/$rawZslCapacity maxImages=$rawReaderMaxImages)"
                )
            }
            if (useRawZsl) {
                if (!preserveBuffer) rawZslHasFrame = false
                if (!preserveBuffer) {
                    zslOverflowTracker.reset()
                    scheduleRawZslWatchdog(generation, requestEpoch)
                    publishRawZslStatus(RawZslState.WARMING_UP, "Buffering full-resolution RAW frames")
                } else {
                    publishRawZslStatus()
                }
            } else {
                cancelRawZslWatchdog()
                // Do not stop the gyro here: CAF motion tracking is independent of RAW ZSL.
                publishRawZslStatus()
            }
        } catch (failure: CameraAccessException) {
            Log.w(LOG_TAG, "Repeating request failed: ${cameraAccessReason(failure)}")
            if (useRawStream && !captureInProgress.get()) recoverRawPreview(cameraAccessReason(failure))
            else if (useRawZsl) disableRawZslForSession(cameraAccessReason(failure))
            else if (useRawHistogram) disableRawHistogramForSession(cameraAccessReason(failure))
            else if (running) onState("CONTROL ERROR")
        } catch (failure: IllegalArgumentException) {
            Log.w(LOG_TAG, "Repeating request rejected: ${failure.message}", failure)
            if (useRawStream && !captureInProgress.get()) recoverRawPreview(failure.message ?: "stream combination rejected")
            else if (useRawZsl) disableRawZslForSession(failure.message ?: "stream combination rejected")
            else if (useRawHistogram) {
                disableRawHistogramForSession(failure.message ?: "stream combination rejected")
            } else onState("NOT SUPPORTED")
        } catch (failure: IllegalStateException) {
            // stop()/lens replacement can close the device after our initial snapshot.
            if (isCurrent(generation)) {
                Log.w(LOG_TAG, "Preview request raced a closed camera", failure)
                onState("CAMERA CLOSED")
            }
        }
    }

    private fun previewCaptureCallback(generation: Int, rawSurface: Surface?, requestEpoch: Long) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isCurrent(generation) || requestEpoch != rawZslRequestEpoch) return
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                rawViewfinder?.expectFrameInterval(maxOf(shutter, result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L))
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { lastPreviewSensorTimestamp = it }
                if (iso > 0) lastIso = iso
                if (shutter > 0) lastExposureNanos = shutter
                latestPreviewResult = result
                val includesRaw = rawSurface != null && request.tag == true
                if (includesRaw) rawStreamResults++
                val rawTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (includesRaw && rawTimestamp != null && activeFramesRemaining.get() > 0) {
                    val collecting = streamCapture
                    if (collecting != null && collecting.captureId == captureSequence.get() &&
                        collecting.accept(rawTimestamp)) {
                        pendingResults[rawTimestamp] = result
                        schedulePairTimeout(rawTimestamp, reportCaptureFailure = true)
                        pairAvailableFrame(rawTimestamp)
                    } else {
                        // A repeating result can never consume a still capture's slot.
                        val previewImage = pendingImages.remove(rawTimestamp)
                        pendingTimeouts.remove(rawTimestamp)?.let(cameraHandler::removeCallbacks)
                        if (previewImage != null) RawImageOwnership.release(previewImage)
                        else {
                            previewRawTimestamps.add(rawTimestamp)
                            if (previewRawTimestamps.size > 64) previewRawTimestamps.minOrNull()?.let(previewRawTimestamps::remove)
                        }
                    }
                } else if (includesRaw && rawZslStreaming && requestEpoch == rawZslRequestEpoch) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                        val motion = motionTracker.motionForFrame(
                            timestamp,
                            shutter,
                            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                            rawZslRealtimeTimestamps
                        )
                        pendingZslResults[timestamp] = PendingZslResult(result, motion, requestEpoch)
                        // Most HALs deliver image/result very close together. Pair immediately
                        // when the image is already present instead of posting then cancelling a
                        // timeout Runnable on every 30 fps ZSL frame.
                        if (pendingImages.containsKey(timestamp)) {
                            pairAvailableFrame(timestamp)
                        } else {
                            schedulePairTimeout(timestamp, reportCaptureFailure = false)
                        }
                    }
                }
                val wb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let(::estimateKelvin)
                    ?: (selectedWbKelvin ?: 0)
                hasPreviewMetadata = hasPreviewMetadata || (iso > 0 && shutter > 0L)
                if (wb > 0) lastWbKelvin = wb
                if (request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_ON) {
                    updateDynamicExposureFromMetering(iso, shutter)
                }
                // In Program mode the saved RAW uses the calculated pair, not these hardware-AE
                // preview values. Show that pair on the ISO/shutter controls so the main UI does
                // not contradict the capture result. ZSL intentionally keeps showing live AE.
                // In AUTO mode a settled ETTR pair replaces the display (and the capture).
                val showEttr = ettrCaptureActive()
                val showDynamic = !showEttr && dynamicExposureDisplayActive()
                val displayIso = when {
                    showEttr -> ettrIso ?: iso
                    showDynamic -> dynamicIso ?: iso
                    else -> iso
                }
                val displayShutter = when {
                    showEttr -> ettrShutterNanos ?: shutter
                    showDynamic -> dynamicShutterNanos ?: shutter
                    else -> shutter
                }
                publishPreviewMetadata(displayIso, displayShutter, wb)
                publishDebugState(request, result)
                handleOpenCameraTouchFocusState(result.get(CaptureResult.CONTROL_AF_STATE))
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                val includesRaw = rawSurface != null && request.tag == true
                if (isCurrent(generation) && includesRaw &&
                    requestEpoch == rawZslRequestEpoch
                ) {
                    rawStreamFailures++
                    if (rawStreamFailures <= 3) {
                        Log.w(LOG_TAG, "Repeating RAW capture failed (${failure.reason}) #$rawStreamFailures")
                    }
                    if (rawZslStreaming) {
                        cooldownRawZslForRetry("repeating RAW capture failed (${failure.reason})")
                    }
                }
            }
        }

    /**
     * PROGRAM custom AE drives the sensor directly from RAW brightness (AE_OFF live).
     * Preview and still share the same live pair.
     * Legacy hardware-AE rebalance is retained only as a seed before the first RAW
     * sample arrives.
     */
    private fun applyCameraControls(
        builder: CaptureRequest.Builder,
        applyDynamicCurve: Boolean = false
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val manualFocus = selectedFocusDistanceDiopters
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            when {
                manualFocus != null -> CaptureRequest.CONTROL_AF_MODE_OFF
                openCameraTouchFocusActive -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> continuousPictureAfMode()
            }
        )
        manualFocus?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )
        afRegion?.takeIf { (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 }
            ?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        val aeRegions = aeRegion?.let { arrayOf(it) } ?: standardAeMeteringRegions()
        if (aeRegions != null && isAeMeteringSupported()) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions)
        }
        val manualExposure = selectedIso != null || selectedExposureNanos != null
        val programLive = !manualExposure && programCustomActive() &&
            dynamicIso != null && dynamicShutterNanos != null
        val dynamicExposure = (applyDynamicCurve || programLive) && !manualExposure &&
            dynamicExposureSettings.enabled && !rawZslRequested &&
            dynamicIso != null && dynamicShutterNanos != null
        // RAW-measured ETTR (AUTO mode only): the still request exposes so the
        // hottest CFA channel lands just below clipping.
        // While the glide is mid-flight the live (ramping) pair wins over both
        // targets, so every repeating rebuild keeps moving smoothly instead of
        // jumping straight to the endpoint.
        val rampGliding = programTargetIso != null && programTargetShutterNanos != null &&
            (dynamicIso != programTargetIso || dynamicShutterNanos != programTargetShutterNanos)
        val ettrExposure = (applyDynamicCurve || programLive) && !manualExposure && ettrCaptureActive() &&
            !rampGliding
        if ((manualExposure || dynamicExposure || ettrExposure) &&
            supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ) {
            val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val targetIso = selectedIso ?: (if (ettrExposure) ettrIso else null) ?: dynamicIso ?: lastIso
            val targetExposure = selectedExposureNanos ?: (if (ettrExposure) ettrShutterNanos else null)
                ?: dynamicShutterNanos ?: lastExposureNanos
            val iso = targetIso.coerceIn(isoRange?.lower ?: targetIso, isoRange?.upper ?: targetIso)
            val exposure = targetExposure.coerceIn(
                exposureRange?.lower ?: lastExposureNanos,
                exposureRange?.upper ?: lastExposureNanos
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        }
        val kelvin = selectedWbKelvin
        if (kelvin == null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsForKelvin(kelvin))
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        if (isOisSupported()) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (oisEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
    }

    /**
     * Capture metadata still arrives for every sensor frame; pushing TextView updates at that
     * rate competes with preview composition on the UI thread. Eight updates per second keeps
     * the exposure readout responsive while leaving headroom for the viewfinder.
     */
    private fun publishPreviewMetadata(iso: Int, shutter: Long, wb: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastPreviewMetadataPublishMs != 0L &&
            now - lastPreviewMetadataPublishMs < PREVIEW_METADATA_INTERVAL_MS
        ) return
        lastPreviewMetadataPublishMs = now
        onMetadata(iso, shutter, wb)
    }

    /** Request the exact per-frame gain map for both forward RAW and repeating RAW ZSL. */
    private fun requestLensShadingMap(builder: CaptureRequest.Builder) {
        val shadingModes = characteristics?.get(
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES
        ) ?: intArrayOf()
        if (!shadingModes.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)) return
        try {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )
        } catch (failure: IllegalArgumentException) {
            // Some HALs advertise the key but reject it for one request template. Preserve
            // capture and report the missing correction metadata at the save boundary.
            Log.w(LOG_TAG, "Lens-shading map request rejected", failure)
        }
    }

    private fun updateDynamicExposureFromMetering(meteredIso: Int, meteredShutter: Long) {
        val settings = dynamicExposureSettings
        if (!settings.enabled || selectedIso != null || selectedExposureNanos != null ||
            meteredIso <= 0 || meteredShutter <= 0L || captureInProgress.get()
        ) return
        // Seed only: once the RAW custom loop publishes a live pair it owns the exposure
        // and hardware-AE seeding stops mattering. Respect per-lens profile bounds here too.
        if (dynamicIso != null && programStreaming) return
        val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val shutterRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        val profile = programAeProfile
        val limits = resolveProgramLimits(profile, isoRange, shutterRange)
            ?: ExposureBalanceLimits(
                isoRange.lower,
                settings.isoLimit.takeIf { it > 0 }?.coerceIn(isoRange.lower, isoRange.upper) ?: isoRange.upper,
                shutterRange.lower,
                resolveDynamicShutterLimit(settings, shutterRange.upper),
                minOf(PROGRAM_SHUTTER_START_NANOS, resolveDynamicShutterLimit(settings, shutterRange.upper))
            )
        val (lockedIsoEff, lockedShutterEff) = resolveProgramLockedValues(
            profile, limits, meteredIso.coerceIn(limits.isoMin, limits.isoMax),
            meteredShutter.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        )
        val result = AutoExposureBalance.applyProgram(
            meteredIso,
            meteredShutter,
            profile.balance,
            limits,
            profile.lockMode,
            lockedIsoEff,
            lockedShutterEff
        )
        dynamicIso = result.iso
        dynamicShutterNanos = result.shutterNanos
        dynamicIsoLimited = result.isoLimited
        dynamicShutterLimited = result.shutterLimited
        // Hardware-AE seed before the RAW loop converges; afterwards the AE_OFF live
        // pair owns the exposure.
    }

    private fun dynamicExposureDisplayActive(): Boolean =
        dynamicExposureSettings.enabled && !rawZslRequested &&
            selectedIso == null && selectedExposureNanos == null

    private fun resolveDynamicShutterLimit(settings: DynamicExposureSettings, sensorMax: Long): Long {
        if (settings.shutterLimitNanos > 0L) return settings.shutterLimitNanos.coerceAtMost(sensorMax)
        if (!settings.useAutoSafeShutter) return sensorMax
        val focalLength = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 4.75f
        val sensorWidth = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 7.1f
        val equivalentFocalLength = (36f / sensorWidth.coerceAtLeast(0.1f)) * focalLength
        // PhotonCamera Photo mode's default endpoint is 1/15 s, scaled shorter for longer lenses.
        val focalScale = (24.0 / equivalentFocalLength.coerceAtLeast(10f)).coerceIn(0.2, 2.5)
        return (PROGRAM_SHUTTER_END_NANOS * focalScale).toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(sensorMax)
    }

    private fun scheduleDynamicExposureProbe() {
        cancelDynamicExposureProbe()
        if (!dynamicExposureSettings.enabled || captureInProgress.get()) return
        // Probes are one-shot captures; keep them out of the touch-AF scan window.
        if (openCameraTouchFocusActive && !openCameraTouchFocusCompleted) return
        dynamicExposureProbe = Runnable {
            val device = camera ?: return@Runnable
            val currentSession = session ?: return@Runnable
            val surface = previewSurface ?: return@Runnable
            if (captureInProgress.get() || selectedIso != null || selectedExposureNanos != null) return@Runnable
            try {
                val probe = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    applyCameraControls(this)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                currentSession.capture(probe.build(), debugCaptureCallback, cameraHandler)
            } catch (_: CameraAccessException) {
                // The normal repeating stream remains valid; retry after its next result.
            }
        }.also { cameraHandler.postDelayed(it, DYNAMIC_EXPOSURE_PROBE_INTERVAL_MS) }
    }

    private fun cancelDynamicExposureProbe() {
        dynamicExposureProbe?.let(cameraHandler::removeCallbacks)
        dynamicExposureProbe = null
    }

    private fun standardAeMeteringRegions(): Array<MeteringRectangle>? {
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        return when (aeMeteringMode) {
            AeMeteringMode.AUTO -> null
            AeMeteringMode.CENTER_WEIGHTED -> {
                val maxRegions = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                if (maxRegions >= 3) {
                    arrayOf(
                        centeredMeteringRectangle(active, 0.70f, 200),
                        centeredMeteringRectangle(active, 0.45f, 300),
                        centeredMeteringRectangle(active, 0.20f, 500)
                    )
                } else {
                    arrayOf(centeredMeteringRectangle(active, 0.60f, MeteringRectangle.METERING_WEIGHT_MAX))
                }
            }
            AeMeteringMode.FRAME_AVERAGE -> arrayOf(
                MeteringRectangle(Rect(active), MeteringRectangle.METERING_WEIGHT_MAX)
            )
            AeMeteringMode.SPOT -> arrayOf(
                centeredMeteringRectangle(active, 0.158f, MeteringRectangle.METERING_WEIGHT_MAX)
            )
        }
    }

    private fun centeredMeteringRectangle(active: Rect, scale: Float, weight: Int): MeteringRectangle {
        val width = (active.width() * scale).toInt().coerceAtLeast(1)
        val height = (active.height() * scale).toInt().coerceAtLeast(1)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return MeteringRectangle(Rect(left, top, left + width, top + height), weight)
    }

    private val debugCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_ON) {
                updateDynamicExposureFromMetering(
                    result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                )
            }
            publishDebugState(request, result, force = true)
            handleOpenCameraTouchFocusState(result.get(CaptureResult.CONTROL_AF_STATE))
        }
    }

    private fun handleOpenCameraTouchFocusState(state: Int?) {
        if (!openCameraTouchFocusActive || openCameraTouchFocusCompleted) return
        when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                openCameraTouchFocusCompleted = true
                removeOpenCameraTouchFocusTimeout()
                removePendingTouchFocusStart()
                if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                    touchFocusLockedSharp = true
                    if (focusLockIndefinite) {
                        focusLockDeadlineMs = 0L
                        onState("FOCUS LOCKED ∞")
                    } else {
                        focusLockDeadlineMs =
                            SystemClock.elapsedRealtime() + TOUCH_FOCUS_LOCK_TIMEOUT_MS
                        scheduleOpenCameraContinuousFocusReset()
                        onState("FOCUS LOCKED")
                    }
                    onFocusLock(true, focusLockIndefinite, focusLockDeadlineMs)
                } else {
                    completeTouchFocusUnlocked("FOCUS NOT LOCKED")
                    return
                }
                // Re-assert the repeating request once so the HAL keeps
                // IDLE/AUTO instead of drifting; circles stay until cleared.
                updateRepeatingRequest(preserveRawZslBuffer = true)
            }
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> {
                // Some HALs return to INACTIVE after START without ever reporting
                // a locked state. Treat that as a failed lock once START is known
                // to have been sent, instead of hanging until the full timeout.
                if (touchFocusStartSent &&
                    SystemClock.elapsedRealtime() - touchFocusStartElapsedMs > TOUCH_FOCUS_INACTIVE_GRACE_MS
                ) {
                    completeTouchFocusUnlocked("FOCUS NOT LOCKED")
                }
            }
        }
    }

    private fun removeOpenCameraContinuousFocusReset() {
        openCameraContinuousFocusReset?.let(cameraHandler::removeCallbacks)
        openCameraContinuousFocusReset = null
    }

    private fun removeOpenCameraTouchFocusTimeout() {
        openCameraTouchFocusTimeout?.let(cameraHandler::removeCallbacks)
        openCameraTouchFocusTimeout = null
    }

    /**
     * Failed scan that still holds the touch point (AF_MODE_AUTO + regions, no
     * badge): the lock timer returns to continuous unless the user retaps or
     * double-taps first.
     */
    private fun completeTouchFocusUnlocked(status: String) {
        openCameraTouchFocusCompleted = true
        touchFocusStartSent = false
        touchFocusLockedSharp = false
        removeOpenCameraTouchFocusTimeout()
        removePendingTouchFocusStart()
        onState(status)
        focusLockDeadlineMs = SystemClock.elapsedRealtime() + TOUCH_FOCUS_LOCK_TIMEOUT_MS
        scheduleOpenCameraContinuousFocusReset()
        updateRepeatingRequest(preserveRawZslBuffer = true)
    }

    private fun scheduleOpenCameraTouchFocusTimeout() {
        removeOpenCameraTouchFocusTimeout()
        openCameraTouchFocusTimeout = Runnable {
            openCameraTouchFocusTimeout = null
            if (openCameraTouchFocusActive && !openCameraTouchFocusCompleted) {
                completeTouchFocusUnlocked("FOCUS NOT LOCKED")
            }
        }.also { cameraHandler.postDelayed(it, OPEN_CAMERA_AUTOFOCUS_TIMEOUT_MS) }
    }

    private fun cancelOpenCameraAutoFocusForNewTouch() {
        removeOpenCameraTouchFocusTimeout()
        // Lightweight legacy path: single CANCEL, no repeating rebuild.
        // New taps use sendAfCancelCapture() + one shared repeating update.
        sendAfCancelCapture()
    }

    private fun scheduleOpenCameraContinuousFocusReset() {
        removeOpenCameraContinuousFocusReset()
        openCameraContinuousFocusReset = Runnable {
            openCameraContinuousFocusReset = null
            continuousFocusResetOpenCamera()
        }.also { cameraHandler.postDelayed(it, TOUCH_FOCUS_LOCK_TIMEOUT_MS) }
    }

    private fun continuousFocusResetOpenCamera() {
        if (!openCameraTouchFocusActive) return
        removeOpenCameraContinuousFocusReset()
        removeOpenCameraTouchFocusTimeout()
        removePendingTouchFocusStart()
        touchFocusStartSent = false
        val device = camera
        val currentSession = session
        val surface = previewSurface
        if (device != null && currentSession != null && surface != null) {
            try {
                val cancel = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    applyCameraControls(this)
                    if (dynamicExposureSettings.enabled) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                currentSession.capture(cancel.build(), debugCaptureCallback, cameraHandler)
            } catch (failure: CameraAccessException) {
                Log.w(LOG_TAG, "Open Camera AF cancel failed: ${cameraAccessReason(failure)}")
            }
        }
        openCameraTouchFocusActive = false
        openCameraTouchFocusCompleted = false
        touchFocusLockedSharp = false
        focusLockIndefinite = false
        focusLockDeadlineMs = 0L
        onFocusLock(false, false, 0L)
        updateRepeatingRequest(preserveRawZslBuffer = true)
        // UI-only notification: clear the AF/AE target circles when the explicit
        // touch-focus release completes. This does not alter Camera2 AF behavior.
        onMeteringReleased()
        onState("CONTINUOUS AF")
    }

    private fun clipPercent(fraction: Float): String =
        if (fraction.isFinite()) {
            String.format(java.util.Locale.US, "%.3f%%", fraction * 100f)
        } else "--"

    private fun publishDebugState(
        request: CaptureRequest,
        result: TotalCaptureResult,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDebugUpdateMs < DEBUG_UPDATE_INTERVAL_MS) return
        lastDebugUpdateMs = now
        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val afTrigger = request.get(CaptureRequest.CONTROL_AF_TRIGGER)
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val aeTrigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val oisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        onDebugState(
            "AF_MODE  ${afModeName(afMode)}\n" +
                "AF_TRIGGER ${afTriggerName(afTrigger)}\n" +
                "AF_STATE ${afStateName(afState)}\n" +
                "AF_POLICY TOUCH_HOLD/CONT_PICTURE  CAMERA_SEQ ${if (cameraCaptureSequenceActive) "BUSY" else "IDLE"}\n" +
                "SAVE_QUEUE ${pendingFrameSaveCount.get()}/${if (captureFormat.includesJpeg) MAX_IN_FLIGHT_JPEG_SAVES else MAX_IN_FLIGHT_DNG_SAVES}\n" +
                "AE_MODE  ${aeModeName(aeMode)}\n" +
                "AE_TRIGGER ${aeTriggerName(aeTrigger)}\n" +
                "AE_STATE ${aeStateName(aeState)}\n" +
                "OIS_MODE ${oisModeName(oisMode)}\n" +
                "ISO $iso  EXP ${formatExposure(exposure.coerceAtLeast(1L))}\n" +
                "P_AE ${if (dynamicExposureSettings.enabled) "ON" else "OFF"} " +
                "B01 ${String.format(java.util.Locale.US, "%.2f", programAeProfile.balance)} " +
                "METER ${programAeProfile.metering.name} " +
                "LOCK ${programAeProfile.lockMode.name} " +
                "EV ${String.format(java.util.Locale.US, "%+.1f", programAeProfile.evBias)} " +
                "ISO_CAP ${if (dynamicIsoLimited) "HIT" else "OK"} " +
                "S_CAP ${if (dynamicShutterLimited) "HIT" else "OK"}\n" +
                "P_TARGET " + (if (ettrCaptureActive()) "FROZEN>ETTR"
                else programTargetIso?.let { targetIso ->
                    programTargetShutterNanos?.let { targetShutter ->
                        val live = dynamicIso?.let { liveIso ->
                            dynamicShutterNanos?.let { liveShutter ->
                                "LIVE ISO $liveIso EXP ${formatExposure(liveShutter)}"
                            }
                        } ?: "LIVE --"
                        "TGT ISO $targetIso EXP ${formatExposure(targetShutter)} $live " +
                            (if (programConverged) "LOCKED" else "TRACKING") +
                            " BR ${if (programBrightness.isFinite()) String.format(java.util.Locale.US, "%.2f", programBrightness) else "--"}"
                    }
                } ?: "METERING") + "\n" +
                "ETTR " + when {
                    !ettrSettings.enabled -> "OFF"
                    !ettrModeAvailable() -> "OFF (AUTO ONLY)"
                    else -> "ON"
                } + " " +
                (ettrIso?.let { targetIso ->
                    ettrShutterNanos?.let { targetShutter ->
                        "ISO $targetIso EXP ${formatExposure(targetShutter)} " +
                            (if (ettrConverged) "LOCKED" else "TRACKING") +
                            " HOT ${if (ettrHottest.isFinite()) String.format(java.util.Locale.US, "%.2f", ettrHottest) else "--"}" +
                            " CLIP ${clipPercent(ettrClipHot)}/${clipPercent(ettrClipSecond)}"
                    }
                } ?: "METERING") + "\n" +
                "RAW_ZSL ${if (rawZslStreaming) "ON" else "OFF"} " +
                "BUF ${rawZslBuffer?.size ?: 0}/$rawZslCapacity " +
                String.format(java.util.Locale.US, "GYRO %.3f", motionTracker.currentMotion())
        )
        publishRawVfDebug(force = force)
    }

    private fun publishRawVfDebug(force: Boolean = false) {
        val vf = rawViewfinder ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRawVfDebugMs < RAW_VF_DEBUG_INTERVAL_MS) return
        lastRawVfDebugMs = now
        onRawVfDebug(formatRawVfDebug(vf.snapshot()))
    }

    internal fun formatRawVfDebug(stats: RawVfStats): String {
        val fpsText = if (stats.fps > 0f) {
            String.format(java.util.Locale.US, "%.1f FPS", stats.fps)
        } else "-- FPS"
        val msText = if (stats.frameMs > 0f) {
            String.format(java.util.Locale.US, "%.1f ms", stats.frameMs)
        } else "-- ms"
        val modeText = if (stats.jpeg) {
            "JPG " + String.format(java.util.Locale.US, "%+.1fEV", stats.exposureEv)
        } else "RAW"
        val vfText = if (stats.vfWidth > 0 && stats.vfHeight > 0) {
            "VF: ${stats.vfWidth}×${stats.vfHeight} ${if (stats.gpu) "GPU" else "CPU"} $modeText"
        } else "VF: --"
        val rawText = if (stats.rawWidth > 0 && stats.rawHeight > 0) {
            "RAW: ${stats.rawWidth}×${stats.rawHeight}"
        } else "RAW: --"
        val devBits = vfOverlayEffectBits(stats.jpeg, jpegOutputSettings)
        val glLabel = if (stats.glActive) "RAW" else "FALLBACK"
        val effects = if (!stats.glActive || devBits.isNotEmpty()) {
            "Yes ($glLabel${if (devBits.isNotEmpty()) " +" + devBits.joinToString("+") else ""})"
        } else "No"
        return "$fpsText $msText\n$vfText · $rawText\nEffects: $effects"
    }

    private fun oisModeName(value: Int?): String = when (value) {
        CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "UNAVAILABLE"
    }

    private fun afModeName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AF_MODE_OFF -> "OFF"
        CaptureResult.CONTROL_AF_MODE_AUTO -> "AUTO"
        CaptureResult.CONTROL_AF_MODE_MACRO -> "MACRO"
        CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
        CaptureResult.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($value)"
    }

    private fun afTriggerName(value: Int?): String = when (value) {
        CaptureRequest.CONTROL_AF_TRIGGER_IDLE, null -> "IDLE"
        CaptureRequest.CONTROL_AF_TRIGGER_START -> "START"
        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL -> "CANCEL"
        else -> "UNKNOWN($value)"
    }

    private fun afStateName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        else -> "UNKNOWN($value)"
    }

    private fun aeModeName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AE_MODE_OFF -> "OFF"
        CaptureResult.CONTROL_AE_MODE_ON -> "ON"
        CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH -> "AUTO_FLASH"
        CaptureResult.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ALWAYS_FLASH"
        CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_REDEYE"
        else -> "UNKNOWN($value)"
    }

    private fun aeTriggerName(value: Int?): String = when (value) {
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE, null -> "IDLE"
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START -> "START"
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL -> "CANCEL"
        else -> "UNKNOWN($value)"
    }

    private fun aeStateName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> "UNKNOWN($value)"
    }

    fun onDisplayRotationChanged() {
        val size = previewSize ?: return
        viewfinder.setAspectRatio(size.height, size.width)
        viewfinder.post { configurePreviewTransform(viewfinder.width, viewfinder.height) }
    }

    fun onDeviceOrientationChanged(degrees: Int) {
        deviceOrientationDegrees = degrees
    }

    private fun captureCallback(generation: Int, captureId: Int) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            val tag = request.tag as? CaptureTag
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: run {
                if (activeFramesRemaining.decrementAndGet() <= 0) {
                    finishCapture("CAPTURE ERROR: sensor timestamp missing")
                    resumeRawZslIfIdle()
                }
                return
            }
            Log.i(
                LOG_TAG,
                "RAW result id=$captureId frame=${tag?.frameNumber ?: "?"}/${tag?.frameCount ?: "?"} " +
                    "timestamp=$timestamp"
            )
            pendingResults[timestamp] = result
            schedulePairTimeout(timestamp, reportCaptureFailure = true)
            pairAvailableFrame(timestamp)
        }

        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            cameraCaptureSequenceActive = false
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            Log.e(
                LOG_TAG,
                "RAW capture failed id=$captureId reason=${failure.reason} frame=${failure.frameNumber}"
            )
            cameraCaptureSequenceActive = false
            if (topupActive) {
                Log.w(LOG_TAG, "Top-up capture failed; saving partial burst")
                finishTopupBurst()
                noteChainDeadAndMaybeRecover()
                return
            }
            finishCapture("CAPTURE FAILED: reason ${failure.reason}, frame ${failure.frameNumber}")
            resumeRawZslIfIdle()
            noteChainDeadAndMaybeRecover()
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            Log.e(LOG_TAG, "RAW capture aborted id=$captureId sequence=$sequenceId")
            cameraCaptureSequenceActive = false
            if (topupActive) {
                Log.w(LOG_TAG, "Top-up capture aborted; saving partial burst")
                finishTopupBurst()
                noteChainDeadAndMaybeRecover()
                return
            }
            finishCapture("CAPTURE ABORTED")
            resumeRawZslIfIdle()
            noteChainDeadAndMaybeRecover()
        }
    }

    private fun pairAvailableFrame(timestamp: Long) {
        if (pendingResults.containsKey(timestamp)) pairAndSave(timestamp)
        else if (pendingZslResults.containsKey(timestamp)) pairRawZslFrame(timestamp)
    }

    private fun pairRawZslFrame(timestamp: Long) {
        if (!running) return
        val pending = pendingZslResults[timestamp] ?: return
        val image = pendingImages.remove(timestamp) ?: return
        pendingZslResults.remove(timestamp)
        pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        if (!rawZslStreaming || rawZslDisabledForSession || rawZslStreamBroken || !rawZslRequested ||
            pending.requestEpoch != rawZslRequestEpoch
        ) {
            RawImageOwnership.release(image)
            return
        }
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "RAW ZSL paired exact timestamp=$timestamp")
        }
        admitToRing(image, pending.result, pending.motionRadiansPerSecond, timestamp, approximate = false)
    }

    /**
     * Shared ring admission: histogram sample, ownership transfer, stall-clock
     * tick, buffer-state publish. Returns false (caller closes) only when the
     * controller cannot retain the frame.
     */
    private fun admitToRing(
        image: Image,
        result: TotalCaptureResult,
        motionRadiansPerSecond: Float,
        timestampNanos: Long,
        approximate: Boolean
    ): Boolean {
        if (!running) {
            RawImageOwnership.release(image)
            return false
        }
        val c = characteristics ?: run {
            RawImageOwnership.release(image)
            return false
        }
        publishRawHistogramIfDue(image, c)
        val buffer = rawZslBuffer ?: run {
            RawImageOwnership.release(image)
            return false
        }
        buffer.add(image, result, motionRadiansPerSecond, timestampNanos, approximate)
        zslOverflowTracker.onPaired()
        fruitlessCooldowns = 0
        publishRawZslBufferState()
        return true
    }

    /**
     * PhotonCamera-style grace admission for a ZSL image whose capture result
     * never arrived before the pairing window expired. Admits it carrying the
     * latest preview result (flagged approximate) instead of dropping it, so a
     * capture-result stall starves neither the ring nor the reader budget. A
     * result that arrived but could not pair (blocked moment) still admits as
     * exact. Never admits while the stream is down, parked, or latched — those
     * frames belong to nobody and are closed by the caller.
     */
    private fun admitStaleZslFrame(
        timestamp: Long,
        image: Image,
        exactResult: TotalCaptureResult?
    ): Boolean {
        if (!running || !rawZslStreaming || !rawZslRequested ||
            rawZslDisabledForSession || rawZslStreamBroken || isRawZslBlocked()
        ) return false
        val result = exactResult ?: latestPreviewResult ?: return false
        val approximate = exactResult == null
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        val motion = motionTracker.motionForFrame(timestamp, exposure, skew, rawZslRealtimeTimestamps)
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(
                LOG_TAG,
                "RAW ZSL admitted ${if (approximate) "approximate" else "late exact"} " +
                    "timestamp=$timestamp"
            )
        }
        return admitToRing(image, result, motion, timestamp, approximate)
    }

    private fun publishRawZslBufferState() {
        val bufferedCount = rawZslBuffer?.size ?: 0
        if (!rawZslHasFrame && bufferedCount >= rawZslCapacity) {
            rawZslHasFrame = true
            cancelRawZslWatchdog()
            onState("ZSL ACTIVE")
        }
        if (bufferedCount != rawZslReportedSize) {
            rawZslReportedSize = bufferedCount
            publishRawZslStatus(
                RawZslState.ACTIVE,
                "$bufferedCount/$rawZslCapacity RAW frames buffered"
            )
        }
    }

    /**
     * Sampling a full-resolution Bayer buffer is CPU work.  A live histogram only needs a few
     * updates per second, so never perform it for hidden UI and never let it run at RAW-stream
     * frame rate on the camera callback thread.  A saved frame is sampled immediately.
     */
    private fun publishRawHistogramIfDue(
        image: Image,
        cameraCharacteristics: CameraCharacteristics,
        force: Boolean = false
    ) {
        if (!rawHistogramEnabled) return
        val now = SystemClock.elapsedRealtime()
        // The MIN_VALUE sentinel means "sample immediately"; see RawHistogramThrottle.
        if (!RawHistogramThrottle.shouldSample(now, lastRawHistogramSampleMs, RAW_HISTOGRAM_INTERVAL_MS, force)) return
        lastRawHistogramSampleMs = now
        RawHistogramSampler.sample(image, cameraCharacteristics)?.let(onRawHistogram)
    }

    private fun pairAndSave(timestamp: Long) {
        if (!running) return
        val result = pendingResults[timestamp] ?: return
        val image = pendingImages.remove(timestamp) ?: return
        pendingResults.remove(timestamp)
        pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        val remaining = activeFramesRemaining.decrementAndGet()
        Log.i(LOG_TAG, "RAW paired timestamp=$timestamp remaining=$remaining")
        // Any paired frame proves the HAL is producing: chains that pair then
        // die are partials, never session-wedge candidates (see
        // noteChainDeadAndMaybeRecover).
        chainPairedAny = true
        consecutiveDeadChains = 0
        fruitlessCooldowns = 0
        if (remaining <= 0) cancelCaptureTimeout()
        if (topupActive) accumulateTopupFrame(image, result)
        else if (activeHdrBracket) pendingHdrFrames += PendingHdrFrame(image, result)
        else saveRawFrame(image, result, null)
        // Serialized still chain: each pair completion submits the next prebuilt
        // still, keeping a single frame in flight for HALs whose gralloc pool
        // cannot survive a pipelined N-frame burst (log-proven on MediaTek).
        if (remaining > 0) {
            if (streamCapture == null) submitNextStill()
            return
        }
        // The RAW Image and its exact TotalCaptureResult are now owned by the bounded writer.
        // Reopen the shutter immediately; do not make capture latency depend on development.
        if (topupActive) finishTopupBurst()
        else {
            if (activeHdrBracket) {
                activeHdrBracket = false
                val saveEachBracket = activeHdrSaveEachBracket
                val saveDebugFrames = activeHdrSaveDebugFrames
                activeHdrSaveEachBracket = false
                activeHdrSaveDebugFrames = false
                activeHdrStops = 2
                saveHdrBracket(pendingHdrFrames.toList(), saveEachBracket, saveDebugFrames)
                pendingHdrFrames.clear()
            }
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    private fun saveHdrBracket(
        pending: List<PendingHdrFrame>,
        saveEachBracket: Boolean,
        saveDebugFrames: Boolean
    ) {
        val c = characteristics ?: return pending.forEach { RawImageOwnership.release(it.image) }
        if (pending.size < 2) return pending.forEach { RawImageOwnership.release(it.image) }
        val ownership = CloseOnceOwner(pending) { RawImageOwnership.release(it.image) }
        val orientation = activeOutputOrientation
        val cameraId = selectedCameraId ?: "unknown"
        val outputFormat = activeCaptureFormat
        val outputSettings = activeJpegOutputSettings
        val denoise = activeDenoiseSettings
        // One identifier for the whole set, independent of serialization/development latency.
        val captureId = System.currentTimeMillis()
        // One fix for the whole set: every bracket shares the same geotag.
        val captureGps = gpsLocation()
        pendingSaveCount.incrementAndGet()
        pendingFrameSaveCount.addAndGet(pending.size)
        if (!saveEachBracket && outputFormat.includesJpeg) beginJpegProcessing()
        val saveGeneration = lifecycleGeneration
        fun reportSaveState(message: String) {
            cameraHandler.post { if (isCurrent(saveGeneration)) onState(message) }
        }
        val releasedOwnership = AutoCloseable {
            try { ownership.close() } finally {
                postSaveCompletion(pending.size)
            }
        }
        val job = OwnedCaptureJob(releasedOwnership) {
            try {
                if (saveEachBracket || saveDebugFrames) {
                    val saver = DngSaver(context)
                    val backend = dngWriterBackend()
                    val overrides = dngMetadataOverrides(cameraId)
                    val names = pending.mapIndexed { index, frame ->
                        val metadata = RawFrameMetadataFactory.capture(
                            cameraId, frame.image, c, frame.result, orientation
                        )
                        // The same Image is unpacked for merging next. Do not let a writer
                        // backend's buffer cursor changes alter the RAW plane origin.
                        val buffer = frame.image.planes.single().buffer
                        val position = buffer.position()
                        val limit = buffer.limit()
                        try {
                            saver.save(
                                frame.image, c, frame.result, orientation, overrides, metadata, backend,
                                fileNameSuffix = hdrBracketSuffix(index),
                                captureId = captureId,
                                gps = captureGps
                            )
                        } finally {
                            buffer.limit(limit)
                            buffer.position(position)
                        }
                    }
                    Log.i(LOG_TAG, "HDR set=$captureId sources=${names.joinToString()} " +
                        "sensorTimestamps=${pending.map { it.image.timestamp }}")
                    reportSaveState("HDR ×${names.size} SAVED")
                    if (saveEachBracket) return@OwnedCaptureJob
                }
                val snapshots = pending.map { frame ->
                    val metadata = RawFrameMetadataFactory.capture(cameraId, frame.image, c, frame.result, orientation)
                    val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                        ?: throw UnsupportedOperationException("HDR RAW geometry is not supported")
                    val normalization = metadata.normalizationOrNull()
                        ?: throw UnsupportedOperationException("HDR normalization metadata is missing")
                    val plane = frame.image.planes.single()
                    val unpacked = RawSensorUnpacker.unpackNormalized(
                        plane.buffer,
                        RawPlaneLayout(metadata.imageWidth, metadata.imageHeight, plane.rowStride, plane.pixelStride,
                            geometry.sensorOriginX, geometry.sensorOriginY),
                        normalization, geometry.processingCrop, ByteOrder.nativeOrder())
                    // Darktable consumes rawprepare output: black subtraction/white normalization
                    // only. Lens gains before merging would corrupt the saturation envelope.
                    metadata.rawDevelopmentUnsupportedReason?.let { error(it) }
                    val prepared = unpacked
                    // Darktable merge falls back to f/22, 8mm when EXIF is missing.
                    val aperture = frame.result.get(CaptureResult.LENS_APERTURE)
                        ?.takeIf { it.isFinite() && it > 0f } ?: HdrRawMerge.FALLBACK_APERTURE
                    Triple(metadata, frame.result, HdrMergeFrame(prepared,
                        metadata.exposureTimeNanos ?: error("HDR exposure time missing"),
                        metadata.sensitivityIso ?: error("HDR ISO missing"), aperture,
                        focalLength = frame.result.get(CaptureResult.LENS_FOCAL_LENGTH)
                            ?.takeIf { it.isFinite() && it > 0f } ?: HdrRawMerge.FALLBACK_FOCAL_LENGTH,
                        noiseModel = CfaNoiseModel.from(metadata.noiseProfile)))
                }
                // Use the bracket's neutral/middle exposure as the geometric reference. Sorting
                // by actual exposure remains correct if the camera clamps one requested shutter.
                val referenceIndex = snapshots.indices.sortedBy {
                    snapshots[it].third.exposureTimeNanos.toDouble() * snapshots[it].third.sensitivityIso
                }[snapshots.size / 2]
                val aligner = HdrFlowNetAligner(context)
                val reference = snapshots[referenceIndex].third
                // Reference model input rendered once (raw); per-frame exposure
                // matching is a cheap linear pass inside the loop.
                val rawRef = aligner.renderRawInput(reference.cfa)
                val tAlign0 = SystemClock.elapsedRealtime()
                val aligned = snapshots.mapIndexed { index, item ->
                    if (index == referenceIndex) item.third
                    else {
                        // HDR+-style fast translation always runs (no native deps), so a
                        // bracket stays aligned even when FlowNet is unavailable/rejected.
                        // FlowNet is kept as the dense refinement on top when valid.
                        val shift = runCatching {
                            HdrBracketAligner.estimateShift(reference, item.third)
                        }.getOrNull()
                        val shiftTimings = HdrBracketAligner.lastTimings
                        Log.i(LOG_TAG, "HDR shift frame=$index " +
                            "proxy=${shiftTimings.proxyMs}ms " +
                            "coarse=${shiftTimings.coarseMs}ms " +
                            "fine=${shiftTimings.fineMs}ms shift=$shift")
                        // Coarse-to-fine with a single interpolating resample: the
                        // pre-shift is an even-integer pure reindex (lossless, no
                        // smoothing), FlowNet estimates the residual incl. the
                        // sub-pixel remainder, and the merge warps once.
                        val even = shift?.let { HdrBracketAligner.snapEven(it) }
                        val baseFlow = even?.asFlow()
                        val preShifted = if (even != null) item.third.copy(
                            cfa = HdrBracketAligner.warpShiftedEven(item.third.cfa, even)
                        ) else item.third
                        val base = aligner.scaleInput(
                            rawRef, aligner.exposureMatchScale(preShifted, reference))
                        val dense = aligner.alignWithBase(base, preShifted)
                        val flowTimings = aligner.lastTimings
                        Log.i(LOG_TAG, "HDR flow frame=$index " +
                            "render=${flowTimings.renderMs}ms " +
                            "infer=${flowTimings.inferMs}ms " +
                            "check=${flowTimings.checkMs}ms " +
                            "accepted=${dense != null}")
                        val flow = HdrBracketAligner.chain(baseFlow, dense)
                            // FlowNet rejected: fall back to the full-precision
                            // shift (one bilinear resample) over a ≤1px error.
                            ?: shift?.asFlow()
                        // No field at all; merge the frame unwarped (identity)
                        // rather than aborting the bracket.
                        if (flow != null) item.third.copy(flow = flow) else item.third
                    }
                }
                val tMerge0 = SystemClock.elapsedRealtime()
                val mergedRaw = HdrRawMerge.merge(aligned, referenceIndex)
                Log.i(LOG_TAG, "HDR timing align=${tMerge0 - tAlign0}ms " +
                    "merge=${SystemClock.elapsedRealtime() - tMerge0}ms " +
                    "frames=${aligned.size} " +
                    "${reference.cfa.width}x${reference.cfa.height}")
                // Bake reference lens correction once, after saturation weighting. Float DNG
                // carries these corrected pixels and must not carry a second gain-map opcode.
                val merged = requireNotNull(RawPreDemosaicPipeline.process(
                    mergedRaw, snapshots[referenceIndex].first, PreDemosaicSettings()).cfa)
                var referenceDng: String? = null
                val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also { rawDeveloper = it }
                // Merged CFA is lens-corrected normalized data: the exact AI
                // training domain. Denoise once here and share the result
                // between the merged-DNG write and the JPEG below.
                val effectiveMerged = if (denoise.aiEnabled) {
                    developer.denoiseCfa(merged, snapshots[referenceIndex].first) ?: merged
                } else merged
                if (outputFormat.includesDng || saveDebugFrames) {
                    referenceDng = DngSaver(context).saveMerged(
                        effectiveMerged, snapshots[referenceIndex].first, captureId, gps = captureGps
                    )
                }
                if (outputFormat.includesJpeg) {
                    val exposures = snapshots.map {
                        it.third.exposureTimeNanos.toDouble() * it.third.sensitivityIso
                    }.sorted()
                    val displayEv = kotlin.math.ln(exposures[exposures.size / 2] / exposures.first()) /
                        kotlin.math.ln(2.0)
                    val developed = developer.developMergedJpeg(effectiveMerged, snapshots[referenceIndex].first,
                        RawDevelopmentSettings(denoise = denoise, exposureEv = displayEv), outputSettings)
                    try {
                        val name = JpegSaver(context).save(developed, snapshots[referenceIndex].first,
                            snapshots[referenceIndex].second, captureTimeMillis = captureId,
                            typeSuffix = CaptureFileNames.TYPE_HDR, gps = captureGps)
                        reportSaveState(if (referenceDng != null) "HDR MERGED+DNG" else "HDR MERGED")
                    } finally {
                        if (developed.settings.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            recycleUltraHdrGainmapContents(developed.bitmap)
                        }
                        developed.bitmap.recycle()
                    }
                } else {
                    reportSaveState("HDR DNG SAVED")
                }
            } catch (failure: OutOfMemoryError) {
                // A full-resolution HDR bracket is close to the managed-heap ceiling on some
                // devices. Always release the owned Images and restore capture state instead of
                // allowing the writer thread's Error to terminate the whole application.
                Log.e(LOG_TAG, "HDR bracket output ran out of memory", failure)
                reportSaveState("HDR SAVE ERROR")
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "HDR bracket output failed", failure)
                reportSaveState("HDR SAVE ERROR")
            } finally {
                if (!saveEachBracket && outputFormat.includesJpeg) endJpegProcessing()
            }
        }
        try { writer.execute(job) } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun()
            if (!saveEachBracket && outputFormat.includesJpeg) endJpegProcessing()
            reportSaveState("QUEUE FULL")
        }
    }

    private fun saveRawFrame(image: Image, result: TotalCaptureResult, captureLabel: String?) {
        saveRawFrame(
            image, result, captureLabel,
            CloseOnceOwner(listOf(image), RawImageOwnership::release)
        )
    }

    private fun saveRawFrame(
        image: Image,
        result: TotalCaptureResult,
        captureLabel: String?,
        ownership: AutoCloseable = CloseOnceOwner(listOf(image), RawImageOwnership::release),
        // Burst grouping: shared stem + per-frame suffix + subfolder. All
        // default to legacy behavior (fresh millis stem, flat folder).
        captureTimeMillis: Long = System.currentTimeMillis(),
        fileNameSuffix: String? = null,
        subfolder: String? = null
    ) {
        val c = characteristics ?: run {
            ownership.close()
            finishCapture("CAPTURE ERROR")
            resumeRawZslIfIdle()
            return
        }
        val orientation = activeOutputOrientation
        publishRawHistogramIfDue(image, c, force = true)
        // Freeze the exact paired result before crossing to the writer thread. Future JPEG
        // development consumes this snapshot, never a later preview result or mutable HAL array.
        val frameMetadata = try {
            RawFrameMetadataFactory.capture(
                selectedCameraId ?: "unknown",
                image,
                c,
                result,
                orientation
            )
        } catch (failure: Exception) {
            ownership.close()
            Log.e(LOG_TAG, "Could not snapshot RAW save metadata", failure)
            finishCapture("SAVE ERROR")
            resumeRawZslIfIdle()
            return
        }
        val outputFormat = activeCaptureFormat
        val outputSettings = activeJpegOutputSettings
        val captureDenoiseSettings = activeDenoiseSettings
        val adaptiveExposureStrength = adaptivePreviewStrength(activeCaptureExposureMode, outputSettings)
        val sharedAdaptiveExposure = activeAdaptiveExposure
        // Burst callers pass one timestamp for every output of the capture
        // so DNGs, JPEGs, and sidecars share the IMG_YYYYMMDD_HHMMSS_mmm
        // stem and sort together; legacy callers mint per-frame time.
        val captureGps = gpsLocation()
        pendingSaveCount.incrementAndGet()
        pendingFrameSaveCount.incrementAndGet()
        if (outputFormat.includesJpeg) beginJpegProcessing()
        val saveGeneration = lifecycleGeneration
        fun reportSaveState(message: String) {
            cameraHandler.post { if (isCurrent(saveGeneration)) onState(message) }
        }
        val releasedOwnership = AutoCloseable {
            try { ownership.close() } finally {
                postSaveCompletion(1)
            }
        }
        val job = OwnedCaptureJob(releasedOwnership) {
                try {
                    var dngName: String? = null
                    var jpegName: String? = null
                    var dngFailure: Exception? = null
                    var jpegFailure: Exception? = null
                    val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also {
                        rawDeveloper = it
                    }
                    val devSettings = RawDevelopmentSettings(
                        denoise = captureDenoiseSettings,
                        adaptiveExposureStrength = adaptiveExposureStrength,
                        sharedAdaptiveExposure = sharedAdaptiveExposure
                    )
                    // AI denoise-once: one inference whose CFA is shared by the
                    // denoised-DNG write and the JPEG. Null when AI is off or
                    // unavailable -> legacy paths below run unchanged.
                    val aiCfa: UnpackedRawCfa? = if (captureDenoiseSettings.aiEnabled &&
                        (outputFormat.includesDng || outputFormat.includesJpeg)
                    ) {
                        val aiPlane = image.planes.singleOrNull()?.buffer
                        if (aiPlane != null) {
                            developer.denoiseCaptureCfa(aiPlane, frameMetadata, devSettings)
                        } else null
                    } else null
                    fun saveOriginalDng(): String? = try {
                        DngSaver(context).save(
                            image, c, result, orientation, dngMetadataOverrides(selectedCameraId),
                            frameMetadata, dngWriterBackend(), captureId = captureTimeMillis,
                            gps = captureGps, fileNameSuffix = fileNameSuffix,
                            subfolder = subfolder
                        ).also {
                            Log.i(
                                LOG_TAG,
                                "DNG saved name=$it pendingSaves=${pendingSaveCount.get()}"
                            )
                        }
                    } catch (failure: Exception) {
                        dngFailure = failure
                        Log.e(LOG_TAG, "DNG save failed", failure)
                        null
                    }
                    if (aiCfa != null) {
                        if (outputFormat.includesDng) {
                            try {
                                dngName = DngSaver(context).saveAiDenoised(
                                    aiCfa, frameMetadata, captureId = captureTimeMillis,
                                    fileNameSuffix = fileNameSuffix, gps = captureGps,
                                    subfolder = subfolder
                                )
                                Log.i(
                                    LOG_TAG,
                                    "AI DNG saved name=$dngName pendingSaves=${pendingSaveCount.get()}"
                                )
                            } catch (failure: Exception) {
                                dngFailure = failure
                                Log.e(LOG_TAG, "AI DNG save failed", failure)
                            }
                            if (captureDenoiseSettings.saveOriginalDng) {
                                val ogName = saveOriginalDng()
                                if (dngName == null) dngName = ogName
                            }
                        }
                    } else if (outputFormat.includesDng) {
                        dngName = saveOriginalDng()
                    }
                    if (outputFormat.includesJpeg) {
                        try {
                            Log.i(
                                LOG_TAG,
                                "RAW development input timestamp=${frameMetadata.timestampNanos} " +
                                    "frame=${frameMetadata.frameNumber} cfa=${frameMetadata.cfaPattern} " +
                                    "geometry=${frameMetadata.bufferGeometry}" +
                                    if (aiCfa != null) " aiDenoised" else ""
                            )
                            val developed = if (aiCfa != null) {
                                developer.developCfaJpeg(
                                    aiCfa, frameMetadata,
                                    settings = devSettings,
                                    outputSettings = outputSettings
                                )
                            } else {
                                val rawPlane = image.planes.singleOrNull()?.buffer
                                    ?: throw UnsupportedOperationException("RAW image must have one plane")
                                developer.developJpeg(
                                    rawPlane, frameMetadata,
                                    settings = devSettings,
                                    outputSettings = outputSettings
                                )
                            }
                            try {
                                jpegName = JpegSaver(context).save(
                                    developed, frameMetadata, result,
                                    captureTimeMillis = captureTimeMillis,
                                    typeSuffix = fileNameSuffix,
                                    gps = captureGps,
                                    subfolder = subfolder
                                )
                                Log.i(
                                    LOG_TAG,
                                    "Developed JPEG saved name=$jpegName " +
                                        "pendingSaves=${pendingSaveCount.get()}"
                                )
                            } finally {
                                if (developed.settings.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    recycleUltraHdrGainmapContents(developed.bitmap)
                                }
                                developed.bitmap.recycle()
                            }
                        } catch (failure: Exception) {
                            jpegFailure = failure
                            Log.e(LOG_TAG, "RAW JPEG development/save failed", failure)
                        }
                    }
                    val label = captureLabel?.let { " • $it" }.orEmpty() +
                        if (aiCfa != null) " • AI" else ""
                    val shadingNote = if (
                        frameMetadata.lensShadingMap == null &&
                        !frameMetadata.lensShadingAlreadyApplied
                    ) " • NO LENS MAP" else ""
                    reportSaveState(formatSaveOutcome(
                        jpegName, dngName, jpegFailure, dngFailure, label, shadingNote
                    ))
                } catch (failure: Exception) {
                    Log.e(LOG_TAG, "Capture artifact save failed", failure)
                    reportSaveState("SAVE ERROR: ${failure.message ?: failure.javaClass.simpleName}")
                } finally {
                    if (outputFormat.includesJpeg) endJpegProcessing()
                }
        }
        // Plain DNG-only saves (no JPEG develop, no AI inference) run on the
        // parallel DNG worker so a 30-frame burst drains ~3x faster and the next
        // burst waits out the refill, not the whole queue. Everything touching
        // the shared GPU developer stays serialized on [writer].
        val fastDngOnly = outputFormat.includesDng && !outputFormat.includesJpeg &&
            !captureDenoiseSettings.aiEnabled
        try {
            (if (fastDngOnly) dngWriter else writer).execute(job)
        } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun()
            if (outputFormat.includesJpeg) endJpegProcessing()
            reportSaveState("QUEUE FULL")
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    private fun beginJpegProcessing() {
        synchronized(jpegServiceLock) {
            if (pendingJpegCount.incrementAndGet() != 1) return
            // A foreground service keeps this process out of Android's background scheduling
            // group if the user leaves the Activity while the serialized render is still active.
            try {
                JpegProcessingService.start(context)
            } catch (failure: RuntimeException) {
                // Never leak the acquired RAW Image if a vendor rejects foreground-service
                // startup. Development can still finish, albeit at normal background priority.
                Log.e(LOG_TAG, "Could not elevate JPEG processing to foreground", failure)
            }
        }
    }

    private fun endJpegProcessing() {
        synchronized(jpegServiceLock) {
            check(pendingJpegCount.get() > 0) { "Unbalanced JPEG processing lifetime" }
            if (pendingJpegCount.decrementAndGet() == 0) {
                JpegProcessingService.stop(context)
            }
        }
    }

    private fun formatSaveOutcome(
        jpegName: String?,
        dngName: String?,
        jpegFailure: Exception?,
        dngFailure: Exception?,
        label: String,
        shadingNote: String
    ): String {
        // UI status shows the short type only; full file names stay in Log.
        val failed = jpegFailure != null || dngFailure != null
        val outcome = StatusText.saveOutcome(
            dngSaved = dngName != null,
            jpegSaved = jpegName != null,
            failed = failed
        )
        if (failed && jpegName == null && dngName == null) return outcome
        // Keep at most the first short label segment (e.g. "ZSL 1/3"); drop the rest.
        val shortLabel = label.split("•").getOrNull(1)?.trim()?.take(12)?.let { " • $it" }.orEmpty()
        val failedSuffix = when {
            jpegFailure != null && dngFailure != null -> " • JPG+DNG ERR"
            jpegFailure != null -> " • JPG ERR"
            dngFailure != null -> " • DNG ERR"
            else -> ""
        }
        return StatusText.compact(outcome + shortLabel + failedSuffix)
    }

    private fun scheduleCaptureTimeout(captureId: Int, exposureDurationMs: Long = 0L) {
        val timeout = Runnable {
            if (captureId == captureSequence.get() && captureInProgress.get()) {
                if (topupActive) {
                    Log.w(LOG_TAG, "Top-up capture timed out; saving partial burst")
                    finishTopupBurst()
                    noteChainDeadAndMaybeRecover()
                    return@Runnable
                }
                finishCapture("CAPTURE TIMEOUT")
                resumeRawZslIfIdle()
                noteChainDeadAndMaybeRecover()
            }
        }
        captureTimeout = timeout
        cameraHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS + exposureDurationMs)
    }

    private fun cancelCaptureTimeout() {
        captureTimeout?.let(cameraHandler::removeCallbacks)
        captureTimeout = null
    }

    private fun schedulePairTimeout(timestamp: Long, reportCaptureFailure: Boolean) {
        pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        val shot = captureSequence.get()
        val generation = lifecycleGeneration
        val timeout = Runnable {
            if (!isCurrent(generation)) return@Runnable
            pendingTimeouts.remove(timestamp)
            val image = pendingImages.remove(timestamp)
            val result = pendingResults.remove(timestamp)
            val zslPending = pendingZslResults.remove(timestamp)
            // Grace admission (see admitStaleZslFrame): only for ZSL expiries
            // carrying a live image. Forward-capture expiries keep exact
            // pairing and close below, untouched.
            val admitted = if (!reportCaptureFailure && image != null) {
                admitStaleZslFrame(timestamp, image, zslPending?.result)
            } else false
            if (!admitted) image?.let(RawImageOwnership::release)
            if (reportCaptureFailure && shot == captureSequence.get() && captureInProgress.get() && (image != null || result != null)) {
                // A top-up chain loses one frame here; save the partial burst
                // instead of dropping the holdout plus everything arrived.
                if (topupActive) finishTopupBurst()
                else {
                    finishCapture("CAPTURE TIMEOUT")
                    resumeRawZslIfIdle()
                }
            }
        }
        pendingTimeouts[timestamp] = timeout
        cameraHandler.postDelayed(
            timeout,
            if (reportCaptureFailure) PAIR_TIMEOUT_MS else ZSL_PAIR_TIMEOUT_MS
        )
    }

    private fun finishCapture(message: String? = null) {
        streamCapture = null
        cancelCaptureTimeout()
        // End of any serialized still chain (normal, timeout, or error): no
        // further chained submits may run after this point.
        pendingStillRequests.clear()
        activeStillCallback = null
        // Never leak top-up holdout frames: normal completion clears the state
        // inside finishTopupBurst first, so this is a no-op there and a release
        // everywhere else (errors, teardown).
        abortTopupFrames()
        if (activeHdrBracket) {
            activeHdrBracket = false
            activeHdrSaveEachBracket = false
            activeHdrSaveDebugFrames = false
            activeHdrStops = 2
            activeFramesRemaining.set(0)
            captureSequence.incrementAndGet()
            pendingHdrFrames.forEach { RawImageOwnership.release(it.image) }
            pendingHdrFrames.clear()
        }
        activeFramesRemaining.set(0)
        cameraCaptureSequenceActive = false
        captureSequence.incrementAndGet()
        closeAllPendingPairs()
        captureInProgress.getAndSet(false)
        resumeRawZslIfIdle()
        refreshCaptureAvailability()
        if (message != null) onState(message)
    }

    /** Camera-thread only. Pending count includes the running development task. */
    private fun hasProcessingCapacity(
        requiredSaveSlots: Int = 1,
        outputFormat: CaptureFormat = captureFormat
    ): Boolean {
        val limit = if (outputFormat.includesJpeg) {
            MAX_IN_FLIGHT_JPEG_SAVES
        } else {
            MAX_IN_FLIGHT_DNG_SAVES
        }
        return CaptureQueueCapacity.accepts(pendingFrameSaveCount.get(), requiredSaveSlots,
            limit, rawReaderMaxImages, RAW_PREVIEW_RESERVED_SLOTS)
    }

    private fun refreshCaptureAvailability() {
        // ZSL-aware shutter: a 30-frame burst needs 30 slots, so the 1-slot
        // default would leave the shutter enabled through the whole save drain
        // and invite futile presses (QUEUE FULL). Gate on the full burst.
        val hasCapacity = if (rawZslRequested) {
            hasProcessingCapacity(zslSelectedFrameCount(captureFormat), captureFormat)
        } else {
            hasProcessingCapacity()
        }
        onCaptureEnabled(
            running && !destroyed && session != null && !captureInProgress.get() && hasCapacity
        )
    }

    private fun resumeRawZslIfIdle() {
        if (!running || session == null || captureInProgress.get()) return
        // Refill while prior images save only when the complete ring and preview
        // headroom still fit. JPEG admission remains bounded independently at six.
        val wantZsl = rawZslRequested && !rawZslDisabledForSession && !rawZslStreamBroken &&
            rawZslCapacity > 0 &&
            zslRestartFitsReader() && SystemClock.elapsedRealtime() >= rawZslCooldownUntilMs
        // ZSL owns the RAW stream once saves have drained: preempt any
        // histogram/ETTR/PROGRAM metering stream started while saves were
        // pending. Without this, the first post-burst resume starts metering
        // (allowRawZsl=false) and every later call early-returns on
        // rawHistogramStreaming/ettrStreaming, wedging ZSL off until the next
        // session (first burst works, every later shutter is a single).
        if (wantZsl) {
            if (rawZslStreaming) return
            Log.i(
                LOG_TAG,
                "Resuming RAW ZSL ring (preempt metering " +
                    "hist=$rawHistogramStreaming ettr=$ettrStreaming prog=$programStreaming)"
            )
            updateRepeatingRequest(allowRawZsl = true)
            return
        }
        if (rawViewfinderStreaming || rawZslStreaming || rawHistogramStreaming || ettrStreaming || programStreaming) return
        // If the ring cannot fit, the viewfinder remains a sample-and-release stream.
        val wantHistogram = rawHistogramEnabled && histogramSourceRaw &&
            !rawHistogramDisabledForSession
        val wantEttr = ettrSettings.enabled && ettrModeAvailable()
        val wantProgram = programCustomActive()
        if (!wantHistogram && !wantEttr && !wantProgram && rawViewfinder == null) return
        updateRepeatingRequest(allowRawZsl = zslRestartFitsReader())
    }

    private fun calculateRawZslCapacity(rawSize: Size): Int {
        val estimatedFrameBytes = rawSize.width.toLong() * rawSize.height.toLong() * RAW_BYTES_PER_PIXEL
        // The ring always holds the full configured selection (up to MAX_ZSL_FRAMES):
        // shrinking it would silently drop requested ZSL coverage. The ImageReader
        // is instead sized with headroom for in-flight pairing on top of the ring
        // (see ZSL_PAIR_WINDOW_SLOTS), so a full 30-frame ring at 30 fps fits.
        return if (estimatedFrameBytes > 0L) rawZslFrameCount else 0
    }

    /**
     * Drive the repeating RAW ring at 30 fps when the HAL exposes such an AE range.
     * Camera2 only accepts ranges advertised by the device, so prefer a fixed fast range and then
     * the narrowest range containing 30. Do not cap this using getOutputMinFrameDuration(): some
     * vendor HALs report a conservative RAW duration while accepting and delivering their
     * advertised 30-FPS RAW repeating configuration. SENSOR_TIMESTAMP remains the source of
     * truth for the cadence actually received.
     */
    private fun chooseRawZslFpsRange(
        characteristics: CameraCharacteristics,
        rawSize: Size
    ): Range<Int>? {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty()
        // Camera2 only accepts advertised ranges, so map the fixed-rate match back
        // to the exact published Range object.
        val selected = selectRawZslFpsRange(
            ranges.map { it.lower..it.upper }, RAW_ZSL_TARGET_FPS
        )?.let { match ->
            ranges.firstOrNull { it.lower == match.first && it.upper == match.last }
        }

        Log.i(
            LOG_TAG,
            "RAW ZSL FPS target=$RAW_ZSL_TARGET_FPS requested=$RAW_ZSL_TARGET_FPS " +
                "range=$selected raw=${rawSize.width}x${rawSize.height}"
        )
        return selected
    }

    private fun scheduleRawZslWatchdog(generation: Int, requestEpoch: Long) {
        cancelRawZslWatchdog()
        val timeoutMs = maxOf(
            ZSL_STARTUP_TIMEOUT_MS,
            (lastExposureNanos / 1_000_000L).coerceAtMost(4_000L) * 2L + 1_500L,
            rawZslFrameCount * ZSL_FRAME_FILL_ALLOWANCE_MS
        )
        rawZslWatchdog = Runnable {
            rawZslWatchdog = null
            if (isCurrent(generation) && requestEpoch == rawZslRequestEpoch &&
                rawZslStreaming && !rawZslHasFrame
            ) {
                // Zero pairs in the whole startup window: same never-productive
                // signature as a stillborn storm — burst-mode, not blind retry.
                rawZslStreamBroken = true
                Log.w(LOG_TAG, "ZSL repeating stream never productive; burst-mode on")
                cooldownRawZslForRetry("No paired RAW frame arrived within ${timeoutMs}ms")
            }
        }.also { cameraHandler.postDelayed(it, timeoutMs) }
    }

    private fun cancelRawZslWatchdog() {
        rawZslWatchdog?.let(cameraHandler::removeCallbacks)
        rawZslWatchdog = null
    }

    /** True while the ZSL ring must not be attempted: latched session fallback,
     * burst-mode degradation, or a transient cooldown that has not expired yet. */
    private fun isRawZslBlocked(): Boolean =
        rawZslDisabledForSession || rawZslStreamBroken ||
            SystemClock.elapsedRealtime() < rawZslCooldownUntilMs

    /**
     * Parks the ZSL ring for [cooldownMs] after a transient outage (overflow
     * storm, watchdog miss, isolated repeating failure) instead of killing the
     * session like [disableRawZslForSession]. The repeating request is rebuilt
     * without the RAW target, the buffer is dropped, and the next press past
     * the deadline re-attempts ZSL from scratch. Second consecutive fruitless
     * park (zero pairs since) escalates to a session rebuild instead — request
     * rebuilds provably cannot fix that HAL state.
     */
    private fun cooldownRawZslForRetry(
        reason: String,
        cooldownMs: Long = ZSL_RETRY_COOLDOWN_MS
    ) {
        if (!rawZslRequested) return
        fruitlessCooldowns++
        if (fruitlessCooldowns >= FRUITLESS_COOLDOWN_REBUILDS && !captureInProgress.get()) {
            Log.w(LOG_TAG, "ZSL cooldown fruitless twice in a row; rebuilding camera session")
            onState("RECOVERING CAMERA")
            fruitlessCooldowns = 0
            restartCameraForConfigurationChange()
            return
        }
        Log.w(LOG_TAG, "ZSL transient outage ($reason); retrying in ${cooldownMs}ms, no session fallback")
        rawZslCooldownUntilMs = SystemClock.elapsedRealtime() + cooldownMs
        rawZslStreaming = false
        clearRawZslBuffer()
        updateRepeatingRequest(allowRawZsl = false)
        publishRawZslStatus(RawZslState.WARMING_UP, "Recovering: $reason — retrying ZSL")
        onState("ZSL RETRY")
    }

    fun setZslHybridTopup(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setZslHybridTopup(enabled) }
            return
        }
        zslHybridTopupEnabled = enabled
    }

    fun isZslHybridTopupEnabled(): Boolean = zslHybridTopupEnabled

    /**
     * Push the WYSIWYG render state to the VF. Both tonemaps share one RAW stream, so a
     * mode switch is a uniform flip on the next sampled frame — no session rebuild, no
     * buffer drop, no flicker. Must be called on the camera thread.
     */
    private fun pushVfRenderState() {
        val vf = rawViewfinder ?: return
        vf.setRenderJpeg(!vfPreviewMode.resolve(captureFormat))
        vf.setAgx(jpegOutputSettings)
        vf.setPreviewExposureStrength(adaptivePreviewStrength(captureExposureMode, jpegOutputSettings))
    }

    /** WYSIWYG VF mode + selectable resolution. Mode switches never rebuild the stream. */
    fun setVfPreviewMode(mode: VfPreviewMode) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setVfPreviewMode(mode) }
            return
        }
        if (vfPreviewMode == mode) return
        vfPreviewMode = mode
        pushVfRenderState()
    }

    fun setVfTargetLongEdge(longEdge: Int) {
        val validated = VfResolution.validated(longEdge)
        if (!isOnCameraThread()) {
            cameraHandler.post { setVfTargetLongEdge(validated) }
            return
        }
        vfTargetLongEdge = validated
        rawViewfinder?.setTargetLongEdge(validated)
    }

    /**
     * Durable session fallback, reserved for a rejected stream combination that
     * can never succeed. Everything transient (overflow storms, watchdog misses,
     * isolated repeating failures) goes through [cooldownRawZslForRetry] so
     * every later press re-attempts ZSL per-shot, PhotonCamera/GCam style.
     */
    private fun disableRawZslForSession(reason: String) {
        if (!rawZslRequested) return
        Log.w(LOG_TAG, "ZSL fallback: $reason")
        rawZslDisabledForSession = true
        rawZslFallbackDetail = reason
        rawZslStreaming = false
        clearRawZslBuffer()
        updateRepeatingRequest(allowRawZsl = false)
        publishRawZslStatus(RawZslState.FALLBACK, "$reason; using normal RAW capture")
        onState("ZSL FALLBACK")
    }

    /** A rejected histogram-only RAW stream must not wedge the viewfinder: fall back to the
     * processed-preview histogram and keep the session alive. Re-enabled on camera reopen or
     * when the user explicitly reselects the RAW source. */
    private fun disableRawHistogramForSession(reason: String) {
        rawHistogramDisabledForSession = true
        rawHistogramStreaming = false
        updateRepeatingRequest(preserveRawZslBuffer = true)
        onState("HISTO • YUV")
    }

    private fun clearRawZslBuffer() {
        rawZslBuffer?.clear()
        rawZslHasFrame = false
        rawZslReportedSize = 0
        val zslTimestamps = pendingZslResults.keys.toList()
        zslTimestamps.forEach { timestamp ->
            pendingZslResults.remove(timestamp)
            pendingImages.remove(timestamp)?.let(RawImageOwnership::release)
            pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        }
    }

    /** PhotonCamera uses the same drain on request transitions so stale RAW never crosses modes. */
    private fun drainQueuedRawImages(reader: ImageReader?) {
        if (reader == null) return
        try {
            while (true) {
                val image = reader.acquireNextImage() ?: break
                RawImageOwnership.release(image)
            }
        } catch (_: IllegalStateException) {
            // A concurrently closing reader has no buffers that remain safe to retain.
        }
    }

    /**
     * Frees one held gralloc slot when the ImageReader reports maxImages
     * outstanding. Prefers the oldest result-less image (it would time out
     * unpaired anyway) over evicting a paired ring frame.
     */
    private fun relieveZslReaderPressure(): Boolean {
        val unmatched = pendingImages.keys
            .filter { !pendingResults.containsKey(it) && !pendingZslResults.containsKey(it) }
            .minOrNull()
        if (unmatched != null) {
            pendingImages.remove(unmatched)?.let(RawImageOwnership::release)
            pendingTimeouts.remove(unmatched)?.let(cameraHandler::removeCallbacks)
            return true
        }
        if (rawZslBuffer?.evictOldest() == true) {
            rawZslReportedSize = rawZslBuffer?.size ?: 0
            publishRawZslStatus()
            return true
        }
        // Last resort: every held image has a result waiting, but the HAL still
        // needs a free slot. Drop the oldest pairing rather than wedging the
        // queue into persistent Mali/Adreno unlock errors.
        val oldest = pendingImages.keys.minOrNull()
        if (oldest != null) {
            pendingImages.remove(oldest)?.let(RawImageOwnership::release)
            pendingResults.remove(oldest)
            pendingZslResults.remove(oldest)
            pendingTimeouts.remove(oldest)?.let(cameraHandler::removeCallbacks)
            return true
        }
        return false
    }

    /** Proactive guard: keep at least one free ImageReader slot for the HAL. */
    private fun relieveZslReaderPressureIfNeeded() {
        val max = rawReaderMaxImages.takeIf { it > 0 } ?: return
        val held = rawReader?.let(RawImageOwnership::count) ?: 0
        if (held >= max - 1) relieveZslReaderPressure()
    }

    private fun stopRepeatingRawBeforeForwardCapture(
        currentSession: CameraCaptureSession,
        reader: ImageReader
    ) {
        // Invalidate callbacks first: frames completing while the HAL flushes belong to the
        // previous repeating RAW request and must not be mistaken for burst frames.
        rawViewfinderStreaming = false
        rawZslStreaming = false
        rawHistogramStreaming = false
        programStreaming = false
        ettrStreaming = false
        rawZslRequestEpoch++
        cancelRawZslWatchdog()
        motionTracker.stop()
        clearRawZslBuffer()
        closeUnmatchedRawImages()
        try {
            currentSession.stopRepeating()
            currentSession.abortCaptures()
        } catch (failure: CameraAccessException) {
            Log.w(LOG_TAG, "Could not fully flush repeating RAW before capture", failure)
        } catch (failure: IllegalStateException) {
            Log.w(LOG_TAG, "Session closed while flushing repeating RAW", failure)
        }
        drainQueuedRawImages(reader)
    }

    private fun closeAllPendingPairs() {
        pendingImages.values.forEach(RawImageOwnership::release)
        pendingImages.clear()
        pendingResults.clear()
        pendingZslResults.clear()
        previewRawTimestamps.clear()
        pendingTimeouts.values.forEach(cameraHandler::removeCallbacks)
        pendingTimeouts.clear()
        rawZslBuffer?.clear()
        rawZslHasFrame = false
        rawZslReportedSize = 0
    }

    private fun closeUnmatchedRawImages() {
        pendingImages.keys.toList().forEach { timestamp ->
            if (!pendingResults.containsKey(timestamp) && !pendingZslResults.containsKey(timestamp)) {
                pendingImages.remove(timestamp)?.let(RawImageOwnership::release)
                pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
            }
        }
    }

    private fun supportsAppOperatedZslTemplate(): Boolean =
        supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING) ||
            supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)

    private fun publishRawZslStatus(
        state: RawZslState? = null,
        detail: String? = null
    ) {
        val status = if (state != null && detail != null) {
            RawZslStatus(state, detail)
        } else when {
            !rawZslRequested -> RawZslStatus(RawZslState.OFF, "Disabled in settings")
            rawZslDisabledForSession -> RawZslStatus(
                RawZslState.FALLBACK,
                "${rawZslFallbackDetail ?: "Unavailable"}; using normal RAW capture"
            )
            rawZslCapacity == 0 -> RawZslStatus(
                RawZslState.FALLBACK,
                "Full-resolution RAW frames exceed the safe memory budget"
            )
            rawZslStreamBroken -> RawZslStatus(
                RawZslState.WARMING_UP,
                "ZSL burst mode: streaming unavailable on this HAL, captures fire instantly"
            )
            rawZslStreaming && rawZslHasFrame -> RawZslStatus(
                RawZslState.ACTIVE,
                "${rawZslBuffer?.size ?: 0}/${rawZslCapacity} RAW frames buffered"
            )
            else -> RawZslStatus(RawZslState.WARMING_UP, "Waiting for a paired RAW frame")
        }
        val enriched = status.copy(bufferedFrames = rawZslBuffer?.size ?: 0)
        if (enriched != lastRawZslStatus) {
            lastRawZslStatus = enriched
            onRawZslStatus(enriched)
        }
    }

    fun stop() {
        rawViewfinder?.invalidateSession()
        rawViewfinderStreaming = false
        synchronized(cameraStateLock) {
            if (!running && !opening && camera == null) return
            running = false
            opening = false
            lifecycleGeneration++
            openCameraTouchFocusActive = false
            openCameraTouchFocusCompleted = false
            touchFocusStartSent = false
            touchFocusLockedSharp = false
            focusLockIndefinite = false
            focusLockDeadlineMs = 0L
            removeOpenCameraTouchFocusTimeout()
            removeOpenCameraContinuousFocusReset()
            removePendingTouchFocusStart()
            cancelRawZslWatchdog()
            cancelProgramRampTick()
            motionTracker.stop()
            rawZslStreaming = false
            rawZslRequestEpoch++
            clearRawZslBuffer()
            captureSequence.incrementAndGet()
            cameraCaptureSequenceActive = false
            finishCapture()
            // Close outstanding Images before the ImageReader: closing the reader
            // with acquired gralloc buffers still held wedges Mali/Adreno queues
            // into "unlock() on a buffer locked with invalid write locks".
            pendingImages.values.forEach { runCatching { RawImageOwnership.release(it) } }
            pendingImages.clear()
            pendingResults.clear()
            pendingZslResults.clear()
            pendingTimeouts.values.forEach(cameraHandler::removeCallbacks)
            pendingTimeouts.clear()
            rawReader?.setOnImageAvailableListener(null, null)
            session?.close(); camera?.close(); previewSurface?.release()
            rawReader?.let(retiredRawReaders::add)
            closeRetiredRawReaders()
            session = null; camera = null; rawReader = null; previewSurface = null
            rawReaderMaxImages = 0
            activePhysicalCameraId = null
            characteristics = null
        }
        pendingImages.values.forEach(RawImageOwnership::release); pendingImages.clear(); pendingResults.clear()
        pendingZslResults.clear()
        pendingTimeouts.values.forEach(cameraHandler::removeCallbacks); pendingTimeouts.clear()
        rawZslBuffer = null
        rawZslCapacity = 0
        latestPreviewResult = null
        lastRawZslStatus = null
    }

    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        rawViewfinder?.onStarvation = null
        viewfinder.removeOnLayoutChangeListener(previewLayoutListener)
        viewfinder.surfaceTextureListener = null
        // Queue teardown behind any accepted saves. The executor is serial, so EGL resources are
        // destroyed on exactly the thread that compiled and used their programs.
        try {
            writer.execute {
                rawDeveloper?.close()
            }
        } catch (_: RejectedExecutionException) {
            // The process will reclaim this small program/context cache if a full queue prevents
            // graceful teardown during Activity destruction.
        }
        writer.shutdown()
        // Plain DNG jobs touch neither EGL nor the shared developer (AI/JPEG stay
        // on the serial writer), so no ordered teardown is needed here; queued
        // DNGs simply finish or are dropped with the process.
        dngWriter.shutdown()
        // Accepted samples own Images, including queued samples. Let them finish and
        // release those Images before retired readers close; stale results are ignored.
        meteringExecutor.shutdown()
    }

    private fun isCurrent(generation: Int): Boolean =
        running && !destroyed && generation == lifecycleGeneration

    private fun isOnCameraThread(): Boolean = Looper.myLooper() == cameraHandler.looper

    private fun clampControlsToCamera() {
        val c = characteristics ?: return
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
            selectedIso = selectedIso?.coerceIn(range.lower, range.upper)
        } ?: run { selectedIso = null }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
            selectedExposureNanos = selectedExposureNanos?.coerceIn(range.lower, range.upper)
        } ?: run { selectedExposureNanos = null }
        val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureCompensation = if (evRange == null) 0
        else exposureCompensation.coerceIn(evRange.lower, evRange.upper)
        if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) torchEnabled = false
    }

    private fun supportsCapability(capability: Int): Boolean =
        characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(capability) == true

    private fun publishControls() {
        val c = characteristics
        val step = c?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val evStops = if (step == null) 0f else exposureCompensation * step.toFloat()
        val lens = zoomLabel(selectedCameraId)
        val flashAvailable = c?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        onControls(
            selectedIso?.let { "ISO $it" }
                ?: if (hasPreviewMetadata) "ISO $lastIso" else "ISO A",
            selectedExposureNanos?.let(::formatExposure)
                ?: if (hasPreviewMetadata) formatExposure(lastExposureNanos) else "S A",
            selectedWbKelvin?.let { "${it}K" }
                ?: lastWbKelvin?.let { "${it}K" }
                ?: "WB A",
            selectedFocusDistanceDiopters?.let(::formatFocusDistance) ?: "AF",
            if (selectedIso != null || selectedExposureNanos != null) "EV --"
            else String.format(java.util.Locale.US, "EV %+.1f", evStops),
            lens,
            if (torchEnabled) "TORCH ON" else "TORCH OFF",
            flashAvailable
        )
    }

    private fun formatFocusDistance(diopters: Float): String {
        if (diopters <= 0.001f) return "∞"
        val meters = 1f / diopters
        return if (meters >= 10f) String.format(java.util.Locale.US, "%.0fm", meters)
        else String.format(java.util.Locale.US, "%.1fm", meters)
    }

    private fun exposureCompensationSteps(lower: Int, upper: Int): List<Int> {
        val step = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
            ?.takeIf { it > 0f } ?: return listOf(0)
        return listOf(-2f, -1f, 0f, 1f, 2f)
            .map { kotlin.math.round(it / step).toInt().coerceIn(lower, upper) }
            .distinct()
    }

    private fun <T> nextValue(current: T?, values: List<T>, includeAuto: Boolean = true): T? {
        if (values.isEmpty()) return null
        if (current == null) return values.first()
        val index = values.indexOf(current)
        if (index < 0) return values.first()
        if (index < values.lastIndex) return values[index + 1]
        return if (includeAuto) null else values.first()
    }

    private fun gainsForKelvin(kelvin: Int): RggbChannelVector {
        val t = ((kelvin.coerceIn(MIN_WB_KELVIN, MAX_WB_KELVIN) - MIN_WB_KELVIN) /
            (MAX_WB_KELVIN - MIN_WB_KELVIN).toFloat())
        val red = 1f + 1.2f * t
        val blue = 2.6f - 1.5f * t
        return RggbChannelVector(red, 1f, 1f, blue)
    }

    private fun estimateKelvin(gains: RggbChannelVector): Int {
        val fromBlue = (2.6f - gains.blue) / 1.5f
        val fromRed = (gains.red - 1f) / 1.2f
        val normalized = ((fromBlue + fromRed) * 0.5f).coerceIn(0f, 1f)
        return (MIN_WB_KELVIN + normalized * (MAX_WB_KELVIN - MIN_WB_KELVIN)).toInt()
    }

    private fun formatExposure(nanos: Long): String {
        if (nanos >= 1_000_000_000L) return String.format(
            java.util.Locale.US, "%.1fs", nanos / 1_000_000_000.0
        )
        return "1/${kotlin.math.round(1_000_000_000.0 / nanos).toInt()}"
    }

    private data class CameraRoute(
        val identity: String,
        val openCameraId: String,
        val physicalCameraId: String?,
        val characteristics: CameraCharacteristics
    )

    private fun directRearRawCharacteristics(id: String): CameraCharacteristics? = try {
        val c = cameraManager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawByCapability = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawByStream = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT &&
            (rawByCapability || rawByStream)) c else null
    } catch (_: Exception) {
        null
    }

    /**
     * Resolve a persisted lens identity into an actual Camera2 route.
     *
     * Composite IDs follow the vendor convention used by PhotonCamera: open the portion before
     * `/` or `-` as the logical CameraDevice and bind outputs to the portion after it. Hidden OEM
     * relationships frequently aren't present in physicalCameraIds, so successful characteristic
     * lookup is the compatibility check. Plain IDs continue to open directly.
     */
    private fun resolveCameraRoute(cameraId: String): CameraRoute? {
        val (logicalId, physicalId, logical) = cameraRouteParts(cameraId).firstNotNullOfOrNull { (logicalId, physicalId) ->
            val logical = try {
                cameraManager.getCameraCharacteristics(logicalId)
            } catch (_: Exception) {
                return@firstNotNullOfOrNull null
            }
            if (logical.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                return@firstNotNullOfOrNull null
            }
            Triple(logicalId, physicalId, logical)
        } ?: return directRearRawCharacteristics(cameraId)?.let { direct ->
            CameraRoute(cameraId, cameraId, null, direct)
        }

        // Prefer the full composite block as in the reference implementation, then fall back to
        // the physical suffix because some vendors only publish complete RAW tags there.
        val composite = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: Exception) {
            null
        }
        val physical = composite?.takeIf(::hasRawOutput) ?: try {
            cameraManager.getCameraCharacteristics(physicalId)
        } catch (_: Exception) {
            return null
        }
        if (!hasRawOutput(physical)) return null

        return CameraRoute(cameraId, logicalId, physicalId, physical)
    }

    private fun hasRawOutput(c: CameraCharacteristics): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ||
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.outputFormats?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true
    }

    /** All non-empty splits, since Camera2 IDs themselves are arbitrary strings. */
    private fun cameraRouteParts(cameraId: String): Sequence<Pair<String, String>> = sequence {
        cameraId.forEachIndexed { index, separator ->
            if (separator != '/' && separator != '-') return@forEachIndexed
            val logicalId = cameraId.substring(0, index)
            val physicalId = cameraId.substring(index + 1)
            if (logicalId.isNotBlank() && physicalId.isNotBlank()) yield(logicalId to physicalId)
        }
    }

    private fun resolveOpenCameraId(cameraId: String): String? =
        resolveCameraRoute(cameraId)?.openCameraId

    private fun opticalMetric(cameraId: String): Float = try {
        val c = resolveCameraRoute(cameraId)?.characteristics ?: return Float.MAX_VALUE
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensorWidth = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        if (focal == null || sensorWidth == null || sensorWidth <= 0f) Float.MAX_VALUE else focal / sensorWidth
    } catch (_: Exception) {
        Float.MAX_VALUE
    }

    private fun zoomLabel(cameraId: String?): String {
        if (cameraId == null) return "1×"
        val current = opticalMetric(cameraId)
        if (!current.isFinite() || rawCameraIds.isEmpty()) return "1×"
        // Pick the optical route closest to the conventional phone main-camera field of view
        // (~24 mm full-frame equivalent). This avoids assuming that camera ID "0" is the main
        // lens, while still producing intuitive 0.5× / 0.7× / 1× / tele labels across vendors.
        val targetMainMetric = 24f / 36f
        val mainId = rawCameraIds
            .filter { opticalMetric(it).isFinite() }
            .minByOrNull { kotlin.math.abs(opticalMetric(it) - targetMainMetric) }
            ?: cameraId
        val baseline = opticalMetric(mainId)
        if (!baseline.isFinite() || baseline <= 0f) return "1×"
        val zoom = current / baseline
        return if (kotlin.math.abs(zoom - kotlin.math.round(zoom)) < 0.08f) {
            "${kotlin.math.round(zoom).toInt()}×"
        } else {
            String.format(java.util.Locale.US, "%.1f×", zoom)
        }
    }

    private fun cameraAccessReason(failure: CameraAccessException): String = when (failure.reason) {
        CameraAccessException.CAMERA_DISABLED -> "disabled by device policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "camera disconnected"
        CameraAccessException.CAMERA_ERROR -> "camera service error"
        CameraAccessException.CAMERA_IN_USE -> "camera is in use"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras are open"
        else -> failure.message ?: "camera unavailable"
    }

    private fun largestRawSize(map: StreamConfigurationMap): Size? =
        map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }

    private fun choosePreviewSize(
        map: StreamConfigurationMap,
        rawSize: Size,
        requiredWidth: Int,
        requiredHeight: Int
    ): Size {
        val choices = map.getOutputSizes(SurfaceTexture::class.java)
        val targetWidth = requiredWidth.coerceIn(1, MAX_PREVIEW_WIDTH)
        val targetHeight = requiredHeight.coerceIn(1, MAX_PREVIEW_HEIGHT)

        // Preview and RAW should show the same framing. Allow a small tolerance because some
        // devices expose slightly cropped preview sizes rather than an exact sensor ratio.
        val matchingAspect = choices.filter { size ->
            val ratioError = kotlin.math.abs(
                size.width.toDouble() / size.height - rawSize.width.toDouble() / rawSize.height
            )
            ratioError <= ASPECT_RATIO_TOLERANCE
        }
        val suitable = matchingAspect.filter {
            it.width <= MAX_PREVIEW_WIDTH && it.height <= MAX_PREVIEW_HEIGHT
        }
        val pool = suitable.ifEmpty { matchingAspect }.ifEmpty { choices.asList() }

        // Use the least expensive stream that still covers the TextureView. If none does,
        // choose the largest bounded stream rather than requesting a full-sensor preview.
        return pool.filter { it.width >= targetWidth && it.height >= targetHeight }
            .minByOrNull { it.width.toLong() * it.height }
            ?: pool.maxByOrNull { it.width.toLong() * it.height }!!
    }

    /** Keeps the camera preview in the portrait-locked 4:3 viewbox. */
    private fun configurePreviewTransform(viewWidth: Int, viewHeight: Int) {
        if (previewSize == null || viewWidth <= 0 || viewHeight <= 0) return

        // The Activity/viewbox is portrait locked and SurfaceTexture/Camera2 already supplies the
        // camera buffer transform for this preview surface. Applying an additional sensor/display
        // rotation here double-rotates the image on devices such as Xiaomi/MediaTek (the previous
        // implementation produced a 90-degree sideways preview). Keep the TextureView transform
        // neutral; physical device orientation is used only for controls and DNG EXIF orientation.
        //
        // Reset explicitly because TextureView retains a previously assigned matrix across
        // relayouts and camera-session changes.
        viewfinder.setTransform(Matrix())
    }

    private fun dngOrientation(
        c: CameraCharacteristics?,
        deviceDegrees: Int = deviceOrientationDegrees
    ): Int {
        val sensorDegrees = c?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = c?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return CaptureOrientation.exif(sensorDegrees, deviceDegrees, frontFacing)
    }

    private data class CaptureTag(
        val captureId: Int,
        val frameNumber: Int,
        val frameCount: Int
    )

    companion object {
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val ASPECT_RATIO_TOLERANCE = 0.015
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val PAIR_TIMEOUT_MS = 5_000L
        /** Bounds result-waiting RAW images to ~5 at 30 fps so a lagging HAL
         * cannot fill the small ImageReader queue faster than pairing drains it.
         * At maxImages 8 the old 400 ms window held ~12 frames on top of the
         * ring and wedged Mali/Adreno gralloc queues into unlock errors. */
        private const val ZSL_PAIR_TIMEOUT_MS = 150L
        /** In-flight pairing headroom inside ImageReader maxImages: covers the
         * ZSL_PAIR_TIMEOUT_MS window (~5 frames at 30 fps) plus one spare slot,
         * so the ring itself can always fill to its configured capacity. */
        private const val ZSL_PAIR_WINDOW_SLOTS = 6
        /** Overlap/lag headroom inside ImageReader maxImages: absorbs result-lag
         * spikes while the ZSL ring streams at full rate (plus the pairing
         * window and transition slack), so transient HAL stalls degrade to
         * dropped ring frames instead of overflow storms. Small enough (+4
         * full-res buffers) to stay clear of gralloc/Mali pressure. The ring
         * itself is only ever restarted with zero saves held (see
         * resumeRawZslIfIdle), so these slots never cover save-held Images. */
        private const val ZSL_REARM_OVERLAP_SLOTS = 4
        private const val ZSL_SELECTION_WAIT_MS = 120L
        private const val ZSL_RECOVERY_WAIT_MS = 3_000L
        /**
         * Empty-take ticks (≈120ms each) before the wait loop escalates to a
         * pure forward burst with top-up ON. ~1s covers one full ring fill at
         * 30 fps, so only a truly non-filling ring escalates — never a merely
         * warming one.
         */
        private const val EMPTY_TOPUP_ESCALATION_TICKS = 8
        /** Consecutive fruitless ZSL cooldowns before a session rebuild. */
        private const val FRUITLESS_COOLDOWN_REBUILDS = 2
        /** Transient ZSL outage parking (overflow storm, watchdog miss, isolated
         * repeating failure): failures stay per-shot, PhotonCamera/GCam style.
         * Only a rejected stream combination latches the session fallback. */
        private const val ZSL_RETRY_COOLDOWN_MS = 2_000L
        private const val ZSL_STARTUP_TIMEOUT_MS = 4_000L
        private const val ZSL_FRAME_FILL_ALLOWANCE_MS = 1_000L
        private const val MAX_ZSL_FRAMES = 30
        private const val RAW_ZSL_TARGET_FPS = 30
        private fun hdrBracketSuffix(index: Int): String = "F%02d".format(index)
        /** RAW target on every repeating request (MotionCam RAW-viewfinder port): the
         * ZSL ring fills at the full requested 30 fps. Raise above 1 only to trade RAW
         * rate for preview smoothness on HALs whose full-resolution RAW readout is slow. */
        private const val RAW_ZSL_REQUEST_PERIOD = 1

        /**
         * Pure MotionCam-style fixed-rate selection: prefer the exact fixed
         * [requestedFps, requestedFps] range, then the narrowest range containing it,
         * then the fastest advertised range. Plain [IntRange] keeps this unit-testable
         * without the Android framework; the caller maps the match back to the
         * advertised Camera2 range.
         */
        /**
         * Shared gate for the live RAW streams (ZSL ring, histogram-only, ETTR).
         * Queued DNG/JPEG saves deliberately do not appear here: their buffers are
         * pre-allocated and bounded, so a burst development queue must not take the
         * RAW viewfinder down. Only an active forward capture stops the streams.
         */
        internal fun shouldRunRawStream(
            requested: Boolean,
            disabledForSession: Boolean,
            readerReady: Boolean,
            captureActive: Boolean
        ): Boolean = requested && !disabledForSession && readerReady && !captureActive

        internal fun selectRawZslFpsRange(
            ranges: List<IntRange>,
            requestedFps: Int
        ): IntRange? {
            if (ranges.isEmpty()) return null
            return ranges
                .filter { it.last >= requestedFps }
                .minWithOrNull(
                    compareBy<IntRange>(
                        { if (it.first >= requestedFps) 0 else 1 },
                        { it.last - requestedFps },
                        { it.last - it.first },
                        { -it.first }
                    )
                ) ?: ranges.maxWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })
        }

        /**
         * Stillborn-stream escalation: request-level RAW preview recovery already
         * had its chance ([RAW_PREVIEW_COMPAT_ESCALATION_FAILURES] attempts) and
         * zero repeating buffers arrived, so only a session rebuild can help.
         * Fires at most once: compat mode sticks once engaged.
         */
        internal fun shouldEscalateRawPreviewToCompatSession(
            failures: Int,
            deliveredImages: Int,
            compatMode: Boolean
        ): Boolean = !compatMode && deliveredImages == 0 &&
            failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES

        /** Request-level recoveries before a stillborn stream rebuilds the session. */
        private const val RAW_PREVIEW_COMPAT_ESCALATION_FAILURES = 1
        private const val RAW_BYTES_PER_PIXEL = 2L
        // JPEG development retains large intermediate CPU/GPU buffers, so keep one serialized
        // development worker while allowing five additional retained RAW inputs. DNG-only writes
        // need no such intermediates
        // and must accept the entire configured ZSL selection. The executor queue excludes its
        // running worker, hence MAX_ZSL_FRAMES - 1 queued slots.
        private const val MAX_IN_FLIGHT_JPEG_SAVES = 6
        private const val MAX_IN_FLIGHT_DNG_SAVES = MAX_ZSL_FRAMES
        private const val MAX_QUEUED_SAVES = MAX_IN_FLIGHT_DNG_SAVES - 1
        /** Parallel DNG-only save workers (see [dngWriter]): plain DNG writes are
         * independent (own DngCreator + MediaStore entry), so a 30-frame burst
         * drains ~2x faster than serially. Sized so workers + queue still cover
         * exactly one in-flight DNG budget and admission stays governed by
         * hasProcessingCapacity(). */
        private const val DNG_WRITER_THREADS = 2
        private const val DNG_WRITER_QUEUE_SLOTS = MAX_IN_FLIGHT_DNG_SAVES - DNG_WRITER_THREADS
        private const val BURST_FRAME_COUNT = 6
        private const val RAW_READER_TRANSITION_SLOTS = 2
        private const val RAW_PREVIEW_RESERVED_SLOTS = ZSL_PAIR_WINDOW_SLOTS + RAW_READER_TRANSITION_SLOTS + 2
        private const val MIN_ACQUIRED_RAW_IMAGES = BURST_FRAME_COUNT + 2
        private const val LOG_TAG = "RawLensCamera"
        private const val OPEN_CAMERA_AUTOFOCUS_TIMEOUT_MS = 2_500L
        /** Timed tap-lock hold before auto-return to continuous AF. */
        private const val TOUCH_FOCUS_LOCK_TIMEOUT_MS = 10_000L
        /** Settle between the AUTO repeating commit and the one-shot AF START. */
        private const val TOUCH_FOCUS_START_SETTLE_MS = 60L
        /** Retry delay when START is deferred because a still is executing. */
        private const val TOUCH_FOCUS_START_RETRY_MS = 400L
        /** Grace before INACTIVE-after-START counts as a failed lock. */
        private const val TOUCH_FOCUS_INACTIVE_GRACE_MS = 200L
        private const val DEBUG_UPDATE_INTERVAL_MS = 200L
        private const val RAW_VF_DEBUG_INTERVAL_MS = 500L
        private const val PREVIEW_METADATA_INTERVAL_MS = 125L
        private const val RAW_HISTOGRAM_INTERVAL_MS = 250L
        /** RAW ETTR converges in a few damped steps; ~2 updates/s tracks scene changes. */
        private const val ETTR_UPDATE_INTERVAL_MS = 500L
        /** Metering samples slower than this are logged so sick streams show in logcat. */
        private const val SICK_SAMPLE_LOG_MS = 150L
        /** PROGRAM custom AE tracks RAW mid-tone; cheap samples allow 4 Hz. */
        private const val PROGRAM_UPDATE_INTERVAL_MS = 250L
        /** Relaxed metering once the loop has converged; any correction snaps back. */
        private const val PROGRAM_CONVERGED_INTERVAL_MS = 1000L
        /** Relaxed metering once ETTR has converged; any correction snaps back. */
        private const val ETTR_CONVERGED_INTERVAL_MS = 1000L
        /** Glide tick cadence: fine gradient steps at ~1 stop/s catch-up. */
        private const val PROGRAM_RAMP_INTERVAL_MS = 100L
        /** Glide step is proportional to remaining distance (fast far, gentle near). */
        private const val PROGRAM_RAMP_PROPORTION = 0.4
        /** Maximum glide per tick: small enough to read as a gradient, not layers. */
        private const val PROGRAM_RAMP_MAX_STEP_EV = 1.0 / 8.0
        /** Remaining distance that snaps straight to target. */
        private const val PROGRAM_RAMP_SNAP_EV = 1.0 / 24.0
        /** EMA weight for sampled brightness; halves single-sample noise. */
        private const val PROGRAM_EMA_ALPHA = 0.5
        private const val DYNAMIC_EXPOSURE_PROBE_INTERVAL_MS = 750L
        private const val PROGRAM_SHUTTER_START_NANOS = 1_000_000_000L / 30
        private const val PROGRAM_SHUTTER_END_NANOS = 1_000_000_000L / 15
        private const val MIN_WB_KELVIN = 2000
        private const val MAX_WB_KELVIN = 10000
        private const val FOCUS_DISTANCE_SCALE = 1_000f
        private val ISO_STEPS = listOf(100, 200, 400, 800, 1600, 3200)
        private val SHUTTER_STEPS = listOf(
            1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
            1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30,
            1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4,
            1_000_000_000L / 2, 1_000_000_000L
        )
        private val WB_STEPS = listOf(3200, 4500, 5500, 6500)
        private val cameraThread = HandlerThread("RawCamera", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        private val cameraHandler = Handler(cameraThread.looper)
    }
}
