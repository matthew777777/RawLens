// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.Face
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
import java.util.concurrent.SynchronousQueue
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
    settings: JpegOutputSettings,
    wavelet: Boolean = false
): List<String> = buildList {
    if (settings.ultraHdr) add("UHDR")
    if (wavelet) add("WAVELET")
    if (jpegTonemap && (settings.agxContrast != 1f ||
        settings.agxSaturation != 1f ||
        settings.agxPurityBoost != 1f ||
        settings.highlightShoulder != 1f)
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

/**
 * Duplicate zoom pills (same optic reachable via several routes) keep their
 * short label when unique but gain an ID suffix when tied, so the viewfinder
 * switcher never shows two identical pills.
 */
fun disambiguatedLensLabels(options: List<LensOption>): List<LensOption> {
    val counts = options.groupingBy { it.label }.eachCount()
    return options.map { option ->
        if ((counts[option.label] ?: 0) > 1) {
            option.copy(label = "${option.label} · ${option.cameraId}")
        } else option
    }
}

enum class RawZslState { OFF, WARMING_UP, ACTIVE, FALLBACK }

data class RawZslStatus(val state: RawZslState, val detail: String,
                       val bufferedFrames: Int = 0,
                       val hdrPlusBusy: Boolean = false,
                       val mfsrBusy: Boolean = false)

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

// Future SR hook: reintroduce RawSuperResolutionFrame/Capture (burst frame
// ownership + planner decision) here when SR returns.

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
    initialScopeAgxApplied: Boolean,
    initialProgramAeProfile: ProgramAeProfile = ProgramAeProfile(),
    private val dngWriterBackend: () -> DngWriterBackend,
    private val dngCompression: () -> DngCompression = { DngCompression.UNCOMPRESSED },
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
    private val onRawStreamCompatMode: () -> Unit = { },
    initialHdrPlusSettings: HdrPlusSettings,
    initialMfsrSettings: MfsrSettings = MfsrSettings(),
    initialScopeMode: ScopeMode = ScopeMode.HISTOGRAM,
    private val onRawWaveform: (RgbWaveform) -> Unit = { },
    initialFocusPeakingEnabled: Boolean = true,
    private val onFocusPeaking: (FocusPeakingFrame) -> Unit = { },
    private val onFocusPeakingActive: (Boolean) -> Unit = { },
    private val bguViewfinder: VfEngine? = null,
    initialFacePriorityEnabled: Boolean = false,
    private val onFaces: (List<FaceOverlayBox>) -> Unit = { },
    private val vendorKeys: (cameraId: String?) -> VendorKeyProfile = { VendorKeyProfile() }
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
    // Future SR hook: single-flight SR merge worker + in-flight flag here.
    // Same single-flight contract for HDR+ (Vulkan merge on the writer): a
    // second press fails fast with HDR+ BUSY instead of queueing a second
    // full-burst merge.
    private val hdrPlusMergeInFlight = AtomicBoolean(false)
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
    // Future SR hook: SR-confined developer + merge processor + settings
    // (rawSuperResolutionSettings / activeRawSuperResolutionSettings /
    // rawSrCapability / rawSrProbeStarted) here.
    @Volatile private var hdrPlusSettings = initialHdrPlusSettings
    private var activeHdrPlusSettings = initialHdrPlusSettings
    @Volatile private var hdrPlusMerge: HdrPlusMerge? = null
    @Volatile private var mfsrSettings = initialMfsrSettings
    private var activeMfsrSettings = initialMfsrSettings
    private val mfsrMergeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
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
    private var activeDualRaw = false
    private val pendingDualRawFrames = ArrayList<PendingHdrFrame>(2)
    private var activeHdrSaveEachBracket = false
    private var activeHdrSaveDebugFrames = false
    private var activeHdrStops = 2
    /** Flat per-shutter EV offsets for the in-flight bracket (see [HdrBracketConfig]). */
    private var activeHdrFrameEvs: IntArray = intArrayOf(-2, 0, 2)
    private val pendingHdrFrames = ArrayList<PendingHdrFrame>(3)
    /**
     * Hybrid top-up (ZSL frames before the press + forward frames after it, as
     * in https://research.google/blog/hdr-with-bracketing-on-pixel-phones/): ring frames held aside while fresh
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
    // Route resolution hits CameraManager (binder + OEM overhead) per call while open(),
    // the lens switcher, and zoom labels re-resolve the same IDs on the main thread.
    // Characteristics are immutable per boot, so memoize per controller lifetime.
    private val cameraRouteCache = CameraIdCache { id: String -> resolveCameraRoute(id) }
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
    /** Continuous torch: preview-assist light only, never a still flash. */
    private var torchEnabled = false
    /** Still-capture flash: fires on shutter (SINGLE or AE-driven), never TORCH. */
    enum class FlashStillMode { OFF, ON, AUTO }
    private var flashStillMode = FlashStillMode.OFF
    /** Flash AE mode pushed to the repeating request while a precapture converges. */
    private var flashRepeatingAeModeOverride: Int? = null
    private var flashPrecaptureInFlight = false
    /** Latest repeating AE state; the flash precapture waiter polls this. */
    @Volatile private var lastAeState: Int? = null
    private var oisEnabled = initialOisEnabled
    @Volatile private var aeMeteringMode = initialAeMeteringMode
    @Volatile private var rawZslRequested = initialRawZslEnabled
    @Volatile private var rawZslFrameCount = initialRawZslFrameCount.coerceIn(1, MAX_ZSL_FRAMES)
    private var rawZslDisabledForSession = false
    private var rawZslFallbackDetail: String? = null
    /**
     * Transient ZSL outage (RawLens policy: failures are per-shot, never
     * session-fatal). Overflow storms, watchdog misses and isolated repeating-
     * request failures park the ring here instead of latching
     * [rawZslDisabledForSession]; every later press re-attempts ZSL once this
     * wall-clock deadline passes. Only a rejected stream combination (which can
     * never succeed) still latches the session fallback.
     */
    private var rawZslCooldownUntilMs = 0L
    /**
     * Repeating-RAW-stream health latch (degrade to forward bursts). Set when
     * a freshly started stream never becomes productive (overflow storm or
     * watchdog trip with zero paired frames): this HAL cannot sustain the
     * continuous RAW stream, so presses fire instant forward bursts under the
     * ZSL stem instead of waiting on a ring that will never fill. A stream that
     * filled and only later sickened is NOT marked — it keeps per-shot retry.
     * Cleared on session open and on ZSL (re-)entry.
     */
    private var rawZslStreamBroken = false
    /** Hybrid top-up (ZSL + post-press frames, see [topupHoldout]): complete a thin ring with fresh forward
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
    /** Ring admissions in the current watchdog window; the session-death latch
     * fires only on a window with zero of them. Camera thread only. */
    private var rawZslEpochPairs = 0
    /** Consecutive slow-but-productive watchdog windows on this epoch. */
    private var rawZslSlowWindows = 0
    private var rawZslReportedSize = 0
    private val zslOverflowTracker = ZslOverflowTracker()
    private var lastRawZslStatus: RawZslStatus? = null
    @Volatile private var rawHistogramEnabled = initialRawHistogramEnabled
    /** AgX-curved (JPEG preview) vs linear-RAW scope data; follows the capture format. */
    @Volatile private var scopeAgxApplied = initialScopeAgxApplied
    @Volatile private var scopeMode = initialScopeMode
    /** Live RAW scope-only repeating stream outside ZSL (AUTO/PROGRAM/MANUAL). Frames are
     * sampled for the histogram/waveform and closed; nothing is buffered and ZSL status
     * is unaffected. */
    private var rawHistogramStreaming = false
    private var rawHistogramDisabledForSession = false
    private var lastRawHistogramSampleMs = Long.MIN_VALUE
    /** Scope cost telemetry accumulators (see noteScopeSample); camera thread only. */
    private var scopeLutAccumNs = 0L
    private var scopeScanAccumNs = 0L
    private var scopeSamples = 0
    private var lastScopeLogMs = 0L
    /** Last sampler-failure log; camera thread + metering executor share it. */
    @Volatile private var lastSamplerErrorLogMs = Long.MIN_VALUE
    /** Focus-peaking master switch (default on); the auto-show policy gates delivery. */
    @Volatile private var focusPeakingEnabled = initialFocusPeakingEnabled
    private var lastFocusPeakingSampleMs = Long.MIN_VALUE
    private var lastPeakingActivePublished = false
    @Volatile private var lastPreviewSensorTimestamp = Long.MIN_VALUE
    private var latestPreviewResult: TotalCaptureResult? = null
    private val motionTracker = CameraMotionTracker(context)
    private var lastIso = 100
    private var lastExposureNanos = 10_000_000L
    private var lastWbKelvin: Int? = null
    /** Published DNG bit depth + sensor label, so per-frame dynamic white can move the chip when it disagrees with the static key. */
    private var lastWhiteLevelBits = 10
    private var lastSensorInfo = ""
    private var hasPreviewMetadata = false
    private var lastPreviewMetadataPublishMs = 0L
    private var afRegion: MeteringRectangle? = null
    private var aeRegion: MeteringRectangle? = null
    /**
     * Tap-to-meter point in VIEW-normalized coordinates (null = frame
     * center). Mirrors [aeRegion]: set wherever a tap installs AE metering,
     * cleared wherever metering returns to full-frame. Saves and the VF
     * meter map it into their own sampled region (full frame and preview
     * are view-proportional). Camera thread writes; volatile for saver reads.
     */
    @Volatile private var developFocusView: Pair<Float, Float>? = null
    // Face-priority AF/AE (HAL STATISTICS_FACES): regions applied only while no
    // tap target overrides them (see applyCameraControls). Camera thread only,
    // except maxFaceCount/faceDetectRejected which the UI reads for the N/A note.
    private var facePriorityEnabled: Boolean = initialFacePriorityEnabled
    @Volatile private var maxFaceCount: Int = 0
    @Volatile private var faceDetectRejected: Boolean = false
    private var faceAfRegion: MeteringRectangle? = null
    private var faceAeRegion: MeteringRectangle? = null
    private var trackedFace: HalFace? = null
    private var faceRegionRect: Rect? = null
    private var faceMissCount: Int = 0
    private var lastFaceRegionPushMs: Long = 0L
    private var lastFacePublishMs: Long = 0L

    // Tap / hold focus and metering (Open Camera continuous-picture AF port).
    private val touchFocus = TouchFocus(TouchFocusHost())
    /** Last idle continuous-AF state/mode logged (transition-only trace). */
    private var lastLoggedIdleAfState: Int? = null
    private var lastLoggedIdleAfMode: Int? = null

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
    @Volatile private var jxlOutputSettings = JxlOutputSettings()
    private var activeJxlOutputSettings = JxlOutputSettings()
    @Volatile private var developedFormat = DevelopedFormat.JPEG
    private var activeDevelopedFormat = DevelopedFormat.JPEG
    @Volatile private var denoiseSettings = DenoiseSettings()

    private var activeDenoiseSettings = DenoiseSettings()
    @Volatile private var galoshSettings = GaloshSettings()

    private var activeGaloshSettings = GaloshSettings()
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
        openRetryCount = 0
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
            val openId = resolveOpenCameraId(cameraId)
            openId != null && openId !in unopenableOpenIds
        }.sortedBy(::opticalMetric)
        val id = selectedCameraId?.takeIf(rawCameraIds::contains)
            ?: preferredCameraId()?.takeIf(rawCameraIds::contains)
            ?: rawCameraIds.firstOrNull()
        if (id == null) {
            opening = false
            if (candidates.isNotEmpty() &&
                retryOpenForTransientRoute("No RAW camera route (candidates=$candidates)")
            ) return
            Log.w(LOG_TAG, "No RAW camera route (candidates=$candidates)")
            onState("NO RAW CAMERA")
            return
        }
        selectedCameraId = id
        if (id != sessionLadderLensId) {
            sessionLadderLensId = id
            resetSessionLadder()
        }
        val route = cachedCameraRoute(id)
        if (route == null) {
            opening = false
            if (retryOpenForTransientRoute("RAW camera $id route unavailable")) return
            Log.w(LOG_TAG, "RAW camera $id route unavailable")
            onState("RAW CAMERA $id UNAVAILABLE")
            return
        }
        sessionPrimaryRoute = route
        sessionAltRoute = resolveDirectPhysicalFallback(route)
        val effectiveRoute = if (sessionRouteAltActive) sessionAltRoute ?: route else route
        val openCameraId = effectiveRoute.openCameraId
        activePhysicalCameraId = effectiveRoute.physicalCameraId
        characteristics = effectiveRoute.characteristics
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
        bguViewfinder?.invalidateSession()
        // A fresh session retries the full ladder from legacy (engines may
        // have failed only transiently).
        legacyDead = false
        bguDead = false
        notifyVfRoute()
        // A fresh session deserves a fresh verdict on the repeating stream.
        rawZslStreamBroken = false
        consecutiveDeadChains = 0
        chainPairedAny = false
        fruitlessCooldowns = 0
        rawZslHasFrame = false
        rawHistogramDisabledForSession = false
        rawHistogramStreaming = false
        maxFaceCount = characteristics?.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
        faceDetectRejected = false
        clearFaceState()
        clampControlsToCamera()
        
        val pixelArraySize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorInfo = String.format(java.util.Locale.US, "RAW\n%s", 
            if (pixelArraySize != null) "${pixelArraySize.width}x${pixelArraySize.height}" else "SENSOR")
        
        val maxDepth = characteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        lastWhiteLevelBits = whiteLevelBitDepth(maxDepth)
        lastSensorInfo = sensorInfo
        val dngInfo = String.format(java.util.Locale.US, "DNG\n%d-bit RAW", lastWhiteLevelBits)

        onInfo(dngInfo, sensorInfo)
        publishControls()
        onActiveCameraChanged(id)
        
        onState("OPENING RAW")
        val ladderRoute = if (sessionRouteAltActive) "direct-physical" else "primary"
        Log.i(LOG_TAG, "Opening camera $openCameraId (selected $id) [$ladderRoute combo $sessionComboIndex]")
        try {
            cameraManager.openCamera(openCameraId, deviceCallback(generation), cameraHandler)
        } catch (failure: IllegalArgumentException) {
            // Queryable but not openable: hidden/vendor IDs answer
            // getCameraCharacteristics yet the service reports them as unknown
            // devices on open (vivo V2562). Must not escape: this runs on the
            // camera thread, where it kills the process. The rejection is
            // deterministic per open ID, so ladder to the direct-physical
            // route first, then exclude the failed IDs and fall through to
            // the next lens instead of wedging on a black viewfinder.
            opening = false
            Log.w(LOG_TAG, "openCamera rejected $openCameraId (selected $id)", failure)
            if (!running) return
            if (advancePastFailedOpen(openCameraId)) return
            // An ID that opened before reporting unknown-device is a camera
            // service hiccup, not a bad lens: never denylist it, and stop the
            // cascade (the next lens would fail the same way). Retry instead.
            if (!shouldDenylistOnOpenRejected(openCameraId, openedOnceOpenIds) &&
                scheduleOpenRejectionRetry()
            ) {
                Log.w(LOG_TAG, "Lens $id rejected after opening before; treating as transient")
                return
            }
            unopenableOpenIds += openCameraId
            unopenableOpenIds += sessionFailedOpenIds
            try {
                // Record both keys: the open ID that refused and the route
                // identity that failed, so discovery matches exact identities
                // instead of inferring from prefixes.
                lensVerdicts.markDead(openCameraId)
                if (id != openCameraId) lensVerdicts.markDead(id)
            } catch (_: Exception) {
                // Best effort: a verdict write must never break the fallback path.
            }
            val fallback = nextLensAfterOpenRejected(candidates, unopenableOpenIds, ::resolveOpenCameraId)
            if (fallback == null) {
                Log.w(LOG_TAG, "No openable RAW lens remains (candidates=$candidates rejected=$unopenableOpenIds)")
                if (!scheduleOpenRejectionRetry()) onState("CAMERA UNAVAILABLE")
                return
            }
            Log.w(LOG_TAG, "Lens $id unopenable via $openCameraId; falling back to $fallback")
            selectedCameraId = null
            open()
            return
        }
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
                openRetryCount = 0
                openRejectionRetryCount = 0
                openedOnceOpenIds += device.id
                try {
                    lensVerdicts.markOpenable(device.id)
                    // Same exact-identity recording: under this generation
                    // guard selectedCameraId is the route being opened, so
                    // composites match exactly at discovery time.
                    val identity = selectedCameraId
                    if (identity != null && identity != device.id) {
                        lensVerdicts.markOpenable(identity)
                    }
                } catch (_: Exception) {
                    // Best effort: a verdict write must never break the open path.
                }
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
            // A device that fails before any session exists cannot be fixed by
            // degrading combos, but a vendor composite may still open through
            // its direct-physical route. Contention and policy errors are
            // terminal: retrying those would only spin.
            if (session == null &&
                error != CameraDevice.StateCallback.ERROR_CAMERA_IN_USE &&
                error != CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE &&
                error != CameraDevice.StateCallback.ERROR_CAMERA_DISABLED &&
                advancePastFailedOpen(device.id)
            ) return
            Log.w(LOG_TAG, "Camera device error on ${device.id}: $error")
            onState(if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) "CAMERA IN USE" else "CAMERA ERROR")
        }
        // P4: track clean teardown so a stale device reference can never feed
        // a later session or precapture waiter.
        override fun onClosed(device: CameraDevice) {
            synchronized(cameraStateLock) {
                if (camera === device) camera = null
            }
            if (!isCurrent(generation)) return
            Log.i(LOG_TAG, "Camera device closed: ${device.id}")
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
        // Stream format: auto resolves to RAW_SENSOR; an opted-in packed
        // format wins only when the map actually lists it, otherwise the
        // choice degrades to today's behavior with a log line.
        val sessionRawFormat = effectiveRawFormat(selectedCameraId, map)
        lastSessionRawFormat = sessionRawFormat
        val rawSizes = map.getOutputSizes(sessionRawFormat)?.toList()
        if (rawSizes.isNullOrEmpty()) {
            Log.e(LOG_TAG, "Session aborted: no RAW size for format $sessionRawFormat")
            onState("RAW UNAVAILABLE")
            return
        }
        // Packed-RAW probe (field diagnostics): how many packed sizes the
        // map lists, whether or not this session uses them.
        val packedRawSizes =
            (map.getOutputSizes(android.graphics.ImageFormat.RAW10)?.size ?: 0) +
                (map.getOutputSizes(android.graphics.ImageFormat.RAW12)?.size ?: 0) +
                (map.getOutputSizes(IMAGE_FORMAT_RAW14)?.size ?: 0)
        if (packedRawSizes > 0) {
            Log.i(LOG_TAG, "Packed RAW available ($packedRawSizes sizes); sessions stay RAW_SENSOR (no unpack path)")
        }
        // The activity is deliberately portrait-locked. Match Photon's preview geometry: camera
        // stream sizes stay in their native landscape order while the view uses the swapped
        // dimensions (3:4 for a 4:3 stream), irrespective of the phone's physical orientation.
        // Empty preview sizes are not terminal: the ladder's RAW-only tail
        // covers HALs that expose no preview stream at all.
        val previewChoices = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        // Fallback ladder: attempt 0 is raw-only (largest RAW, ring-sized
        // reader, no preview target). A HAL rejection advances the index and
        // rebuilds the session with a degraded combo instead of wedging on
        // SESSION ERROR.
        val mapRawSizes = rawSizes.map { SessionStreamSize(it.width, it.height) }
        val combos = orderCombosForPreferredSize(
            selectSessionStreamCombos(
                mapRawSizes,
                previewChoices.map { SessionStreamSize(it.width, it.height) },
                fullReaderMaxImages(),
                MIN_ACQUIRED_RAW_IMAGES
            ),
            preferredRawSize(selectedCameraId, mapRawSizes)
        )
        if (combos.isEmpty()) {
            Log.e(LOG_TAG, "Session aborted: no RAW_SENSOR size")
            onState("RAW UNAVAILABLE")
            return
        }
        sessionComboIndex = sessionComboIndex.coerceIn(0, combos.lastIndex)
        // ISP preview-request verdict: cameras proven to stall at ~20 fps
        // raw-only jump straight to the first preview combo; everyone else
        // starts raw-only (the probe below renders the verdict once).
        if (sessionComboIndex == 0 && ispPreviewRequestNeeded(selectedCameraId)) {
            firstPreviewComboIndex(combos).takeIf { it > 0 }?.let { sessionComboIndex = it }
        }
        sessionOffersPreview = combos.any { it.preview != null }
        val combo = combos[sessionComboIndex]
        if (sessionComboIndex > 0 && combo.preview == null && ispPreviewRequestNeeded(selectedCameraId)) {
            // The preview remedy failed on this camera: the stillborn ladder
            // fell through every preview step (some HALs crash with any
            // preview+RAW session), landing raw-only. Raw-only is the
            // terminal state even at reduced rate — flip the verdict so cold
            // starts stop retrying a preview session this HAL cannot run.
            persistIspPreviewVerdict(selectedCameraId, false)
            Log.w(LOG_TAG, "Preview remedy refused by HAL; raw-only verdict persisted")
        }
        val rawSize = Size(combo.raw.width, combo.raw.height)
        rawZslCapacity = clampZslCapacityForReader(calculateRawZslCapacity(rawSize), combo.readerMaxImages)
        if (rawZslRequested && rawZslCapacity == 0 && calculateRawZslCapacity(rawSize) > 0) {
            // Degraded combo: the reduced reader cannot host a ZSL ring, so this
            // session is viewfinder plus forward capture. Latched like any
            // rejected stream combination; a lens change resets the ladder.
            Log.w(LOG_TAG, "ZSL fallback: REDUCED BUFFERS")
            rawZslDisabledForSession = true
            rawZslFallbackDetail = "REDUCED BUFFERS"
        }
        rawZslRealtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        rawZslBuffer?.clear()
        rawZslBuffer = rawZslCapacity.takeIf { it > 0 }?.let(::RawZslBuffer)
        publishRawZslStatus()

        val previewSize = combo.preview?.let { Size(it.width, it.height) }
        this.previewSize = previewSize
        rawZslTargetFpsRange = chooseRawZslFpsRange(c, rawSize)
        // RAW-only sessions leave the (never-displayed) TextureView alone.
        previewSize?.let { ps ->
            viewfinder.post {
                viewfinder.setAspectRatio(ps.height, ps.width)
                configurePreviewTransform(viewfinder.width, viewfinder.height)
            }
        }

        // maxImages must fit the whole ring plus frames still waiting for their
        // capture result (~5 at 30 fps with ZSL_PAIR_TIMEOUT_MS) plus transition
        // slack, plus a small rearm overlap so the ring refills during the drain
        // tail of a burst (see resumeRawZslIfIdle). Without the pairing headroom
        // a full 30-frame ring plus normal result lag exceeds maxImages and wedges
        // the gralloc queue into overflow + Mali unlock errors. Degraded combos
        // carry a burst-minimum reader instead and run without a ZSL ring.
        val readerMaxImages = combo.readerMaxImages
        // GPU-sampled reader trial: CPU_READ keeps every plane consumer
        // (ZSL, histogram, peaking, NEON, upload tiers) working while
        // GPU_SAMPLED_IMAGE lets gralloc hand the sampled-image tier a
        // GPU-shareable allocation (drivers that need one reject CPU-only
        // buffers). Known-starving HALs (Xiaomi 25080RABDG / MT6878:
        // session configures, zero images arrive) disarm via the stillborn
        // ladder and persist the verdict; EGL import of CPU-only buffers
        // stays fenced behind runtime probing. See VfGpuImport.
        if (vfGpuReaderTrialArmed && !vfGpuReaderPreflightOk(rawSize.width, rawSize.height)) {
            // Allocator refuses the trial usage up front (MT6878-class
            // grallocs): skip the stillborn session entirely and go
            // straight CPU-only. Not persisted: the probe is free and
            // size-adaptive, unlike an observed starvation verdict.
            Log.w(LOG_TAG, "GPU-reader pre-flight refused ${rawSize.width}x${rawSize.height}; CPU-only reader")
            vfGpuReaderTrialArmed = false
        }
        // Packed buffers cannot import as RAW16: CPU-only reader while
        // packed is active, without touching the trial flag (it never
        // re-arms in-session, so disarming here would leak into later
        // RAW_SENSOR sessions).
        val packedSession = sessionRawFormat != android.graphics.ImageFormat.RAW_SENSOR
        if (packedSession) {
            Log.i(LOG_TAG, "Packed RAW session; CPU-only reader")
        }
        val reader = if (vfGpuReaderTrialArmed && !packedSession) {
            ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                sessionRawFormat,
                readerMaxImages,
                HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        } else {
            ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                sessionRawFormat,
                readerMaxImages
            )
        }.also {
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
                        lastRawImageReceivedMs = SystemClock.elapsedRealtime()
                        if (activeFramesRemaining.get() <= 0 && rawStreamImages++ == 0) {
                            Log.i(LOG_TAG, "First RAW stream image ${image.width}x${image.height} ts=${image.timestamp}")
                            // Frames flowing (not merely a configured session)
                            // is the verification event. Idempotent, so a
                            // stale frame attributing to the same identity is
                            // harmless; generation-gated above.
                            val verifiedIdentity = pendingVerifiedIdentity
                            pendingVerifiedIdentity = null
                            if (verifiedIdentity != null) {
                                try {
                                    lensVerdicts.markVerified(verifiedIdentity)
                                } catch (_: Exception) {
                                    // Best effort: verdicts never break streaming.
                                }
                            }
                        }
                        if (fpsProbeArmed) {
                            if (rawStreamImages == 1) {
                                fpsProbeFirstNs = image.timestamp
                            } else if (rawStreamImages == ISP_PROBE_FRAMES) {
                                fpsProbeArmed = false
                                evaluateIspFpsProbe(image.timestamp)
                            }
                        }
                        if (vfGpuTrialConvicted) {
                            // Control verdict: the trial delivered zero, the
                            // disarmed session delivers — GPU usage starves
                            // this HAL. Persist so cold starts skip the trial.
                            vfGpuTrialConvicted = false
                            persistVfGpuReaderVerdict()
                            Log.w(LOG_TAG, "GPU-reader trial convicted; CPU-only reader persisted")
                        }
                        RawImageOwnership.adopt(image, reader) {
                            cameraHandler.post { closeRetiredRawReaders() }
                        }
                        // Histogram delivery follows RAW arrival, independent of ZSL pairing
                        // and capture/save state. VF-only repeating frames are useful too.
                        characteristics?.let { publishRawScopeIfDue(image, it) }
                        // Focus peaking rides the same RAW VF stream at its own throttle,
                        // gated by the manual/tap-focus auto-show policy.
                        characteristics?.let { publishFocusPeakingIfDue(image, it) }
                        // The dual-RAW worker owns these allocations through Vulkan completion;
                        // don't concurrently hand the same AHB to the viewfinder GPU context.
                        if (!activeDualRaw) characteristics?.let { activeVf()?.offer(image, it, latestPreviewResult) }
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
                            if (rawHistogramStreaming) publishRawScopeIfDue(image, cameraCharacteristics)
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
                        // Wedge recovery: free ring slots too (closeAllPendingPairs
                        // preserves them; holdout transfers are unaffected).
                        clearRawZslBuffer()
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
        lastRawImageReceivedMs = 0L
        rawStreamResults = 0
        rawStreamFailures = 0
        // Arm the ISP frame-rate probe on the raw-only default step while
        // this camera has no persisted verdict. Any rebuild disarms via this
        // reset; degraded raw-only tail steps never arm (their preview steps
        // were already rejected — jumping back would loop the ladder).
        fpsProbeArmed = combo.label == RAW_ONLY_FULL_LABEL &&
            selectedCameraId != null && !ispPreviewVerdictKnown(selectedCameraId)
        fpsProbeCameraId = selectedCameraId
        fpsProbeFirstNs = 0L
        fpsProbeShot = captureSequence.get()
        logRawStreamCombo(map, rawSize, previewSize, combo.label, sessionComboIndex, combos.size)
        // RAW-only combos skip the preview surface entirely: the session runs
        // a single RAW stream and the RAW viewfinder is the only display.
        val surface: Surface? = combo.preview?.let { pv ->
            val texture = viewfinder.surfaceTexture ?: run {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                rawReaderMaxImages = 0
                return
            }
            texture.setDefaultBufferSize(pv.width, pv.height)
            Surface(texture)
        }
        synchronized(cameraStateLock) {
            if (!isCurrent(generation) || camera !== device) {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                rawReaderMaxImages = 0
                surface?.release()
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
                surface?.release()
                handleSessionSetupFailed(generation, "session creation failed: ${cameraAccessReason(failure)}")
            } catch (failure: IllegalArgumentException) {
                Log.w(LOG_TAG, "Session surfaces rejected by HAL", failure)
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface?.release()
                handleSessionSetupFailed(generation, "session surfaces rejected by HAL")
            } catch (failure: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Session creation unsupported by HAL", failure)
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface?.release()
                handleSessionSetupFailed(generation, "session creation unsupported by HAL")
            } catch (_: IllegalStateException) {
                rawReader = null
                rawReaderMaxImages = 0
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface?.release()
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
    private fun logRawStreamCombo(
        map: StreamConfigurationMap,
        rawSize: Size,
        previewSize: Size?,
        comboLabel: String,
        comboIndex: Int,
        comboCount: Int
    ) {
        val minFrameNs = runCatching {
            map.getOutputMinFrameDuration(android.graphics.ImageFormat.RAW_SENSOR, rawSize)
        }.getOrNull()
        val stallNs = runCatching {
            map.getOutputStallDuration(android.graphics.ImageFormat.RAW_SENSOR, rawSize)
        }.getOrNull()
        val previewLabel = previewSize?.let { "${it.width}x${it.height}" } ?: "none"
        Log.i(
            LOG_TAG,
            "RAW stream combo raw=${rawSize.width}x${rawSize.height} preview=$previewLabel " +
                "maxImages=$rawReaderMaxImages minFrameNs=${minFrameNs ?: "?"} stallNs=${stallNs ?: "?"} " +
                "compat=$rawStreamCompatMode gpuTrial=$vfGpuReaderTrialArmed combo=$comboLabel attempt=${comboIndex + 1}/$comboCount"
        )
    }

    @Suppress("DEPRECATION")
    private fun createPerformanceSession(
        device: CameraDevice,
        preview: Surface?,
        raw: Surface,
        generation: Int
    ) {
        val callback = sessionCallback(device, generation)
        val plan = chooseSessionPerformancePlan(device, preview, raw, callback)
        lastSessionPlanLabel = plan.label
        // Vendor stream operating mode (e.g. Xiaomi DCG opmode 37379): opens the
        // session through the hidden createCustomCaptureSession path like
        // PhotonVidCam. Any failure falls back to the standard session below.
        val opmode = try {
            vendorKeys(selectedCameraId).operatingMode
        } catch (_: Exception) {
            0
        }
        if (opmode != 0) {
            try {
                createCustomCaptureSession(device, plan.configuration, opmode, callback)
                lastSessionPlanLabel = "${plan.label} + opmode $opmode"
                Log.i(LOG_TAG, "Camera session configured: $lastSessionPlanLabel")
                return
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "Custom opmode session failed (opmode=$opmode); using standard session", failure)
            }
        }
        try {
            device.createCaptureSession(plan.configuration)
            Log.i(LOG_TAG, "Camera session configured: ${plan.label}")
        } catch (failure: UnsupportedOperationException) {
            // Legacy / vendor HALs (e.g. Snapdragon 685 on Redmi Note 13 4G) accept
            // SessionConfiguration creation but not execution, or reject the modern
            // entry point outright. The deprecated surface-list API is equivalent
            // when no stream-use-case / physical-routing hints are attached.
            Log.w(LOG_TAG, "Modern session API unsupported (${plan.label}); using legacy surfaces", failure)
            device.createCaptureSession(listOfNotNull(preview, raw), callback, cameraHandler)
            lastSessionPlanLabel = "LEGACY fallback for ${plan.label}"
            Log.i(LOG_TAG, "Camera session configured: $lastSessionPlanLabel")
        } catch (failure: IllegalArgumentException) {
            Log.w(LOG_TAG, "Modern session config rejected (${plan.label}); using legacy surfaces", failure)
            device.createCaptureSession(listOfNotNull(preview, raw), callback, cameraHandler)
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
        preview: Surface?,
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
            val outputs = mutableListOf<OutputConfiguration>()
            preview?.let {
                val previewOutput = OutputConfiguration(it)
                // Standard Camera2 logical -> physical routing: keep the logical CameraDevice open,
                // but bind both streams to the selected physical member. Standalone/vendor-direct
                // routes leave this null and behave exactly as before.
                activePhysicalCameraId?.let(previewOutput::setPhysicalCameraId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    previewUseCase?.let(previewOutput::setStreamUseCase)
                }
                outputs += previewOutput
            }
            val rawOutput = OutputConfiguration(raw)
            activePhysicalCameraId?.let(rawOutput::setPhysicalCameraId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                rawUseCase?.let(rawOutput::setStreamUseCase)
            }
            outputs += rawOutput
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor,
                callback
            )
            // Vendor SESSION keys (e.g. Vivo DCG forceSensorMode) must load
            // BEFORE session creation; per-request sets are silently ignored.
            attachVendorSessionParams(device, sessionConfig)
            return sessionConfig
        }

        if (preview == null) {
            // RAW-only last resort: a single stream with no use-case hints at
            // all. A lone RAW output tagged STILL_CAPTURE risks still-only
            // scheduling (the Samsung lesson); DEFAULT is the baseline.
            return SessionPerformancePlan(configuration(null, null), "RAW ONLY")
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

    /**
     * Step 2 vendor SESSION keys, attached before createCaptureSession.
     * No-ops when this lens enables no session keys. A HAL rejection of the
     * parameters only drops the vendor keys; the session itself still opens.
     */
    private fun attachVendorSessionParams(device: CameraDevice, config: SessionConfiguration) {
        val routeId = selectedCameraId ?: return
        val profile = try {
            vendorKeys(routeId)
        } catch (_: Exception) {
            return
        }
        if (profile.sessionEntries().isEmpty()) {
            lastVendorSessionReport = VendorKeyApplyReport(emptyList(), emptyList())
            vendorSessionFingerprint = VendorKeyApply.fingerprint(emptyList())
            vendorSessionOpmode = profile.operatingMode
            vendorSessionRoute = routeId
            return
        }
        val chars = characteristics ?: return
        val (params, report) = VendorKeyApply.buildSessionParameters(device, profile, chars) { message ->
            Log.i(LOG_TAG, message)
        }
        lastVendorSessionReport = report
        vendorSessionFingerprint = VendorKeyApply.fingerprint(profile.sessionEntries())
        vendorSessionOpmode = profile.operatingMode
        vendorSessionRoute = routeId
        if (params == null) return
        try {
            config.setSessionParameters(params)
            Log.i(LOG_TAG, "Vendor session parameters attached for $routeId")
        } catch (failure: IllegalArgumentException) {
            Log.w(LOG_TAG, "Vendor session parameters rejected for $routeId", failure)
        }
    }

    /**
     * Hidden `CameraDevice.createCustomCaptureSession` via plain reflection —
     * the same call PhotonVidCam makes through RestrictionBypass. Plain
     * reflection works where the platform permits hidden access; anything else
     * throws so the caller falls back to the standard session path.
     */
    private fun createCustomCaptureSession(
        device: CameraDevice,
        config: SessionConfiguration,
        opmode: Int,
        callback: CameraCaptureSession.StateCallback
    ) {
        val surfaces = config.outputConfigurations.map { it.surface }
        val method = device.javaClass.getDeclaredMethod(
            "createCustomCaptureSession",
            InputConfiguration::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java
        )
        method.isAccessible = true
        method.invoke(device, config.inputConfiguration, surfaces, opmode, callback, cameraHandler)
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
                    // Candidate for stream verification: confirmed only when
                    // this session's first RAW frame actually arrives below.
                    pendingVerifiedIdentity = sessionPrimaryRoute?.identity
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
            logAfCapabilities()
            touchFocus.nudgeIdleIfNeeded()
            onState(if (rawZslStreaming) "READY • ZSL WARMING" else "READY")
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (!isCurrent(generation)) return
            handleSessionSetupFailed(generation, "Camera session configuration rejected by HAL")
        }
    }

    /**
     * Session setup recovery: a HAL that rejects the session (sync exception
     * or async onConfigureFailed) advances the fallback ladder and rebuilds
     * instead of wedging on SESSION ERROR. First the direct-physical route
     * for vendor composites (a binding the HAL does not honor rejects every
     * size), then degraded combos on the primary route. Terminal only when
     * every attempt is exhausted.
     */
    private fun handleSessionSetupFailed(generation: Int, reason: String) {
        // Sync rejections can surface on the SurfaceTexture (main) thread when
        // createSession runs from the surface callback; the restart below is
        // camera-thread confined.
        if (!isOnCameraThread()) {
            cameraHandler.post { handleSessionSetupFailed(generation, reason) }
            return
        }
        if (!isCurrent(generation)) return
        if (advanceSessionLadder("Session setup failed ($reason)")) return
        if (downgradePackedFormatToAuto("Session setup failed ($reason)")) return
        Log.e(LOG_TAG, reason)
        onState("SESSION ERROR")
    }

    /**
     * Packed-format fallback rung below the combo ladder: when every packed
     * combo is exhausted, clear the opt-in back to auto and rebuild once as
     * `RAW_SENSOR`. Cannot loop: the rebuild runs unpacked, which never
     * re-enters this rung. Camera-thread only.
     */
    private fun downgradePackedFormatToAuto(reason: String): Boolean {
        check(isOnCameraThread())
        val id = selectedCameraId ?: return false
        if (lastSessionRawFormat == android.graphics.ImageFormat.RAW_SENSOR) return false
        rawFormatPreferenceStore.set(id, null)
        lastSessionRawFormat = android.graphics.ImageFormat.RAW_SENSOR
        sessionComboIndex = 0
        Log.w(LOG_TAG, "$reason; packed format refused, retrying $id as RAW_SENSOR")
        onState("RECOVERING PREVIEW")
        restartCameraForConfigurationChange()
        return true
    }

    /**
     * Session fallback ladder: first the direct-physical route for vendor
     * composites, then degraded combos on the primary route. Shared by
     * explicit HAL rejections ([handleSessionSetupFailed]) and silent ones:
     * a compat session that configures fine but delivers zero RAW buffers
     * (vivo X300 Ultra) is the same unsatisfiable combo without the error
     * callback. Returns true when a restart was issued. Camera-thread only.
     */
    private fun advanceSessionLadder(reason: String): Boolean {
        check(isOnCameraThread())
        val id = selectedCameraId
        val alt = sessionAltRoute
        if (!sessionRouteAltActive && alt != null && alt.openCameraId !in sessionFailedOpenIds) {
            sessionRouteAltActive = true
            sessionComboIndex = 0
            Log.w(LOG_TAG, "$reason; retrying $id via direct physical ${alt.openCameraId}")
            onState("RECOVERING PREVIEW")
            restartCameraForConfigurationChange()
            return true
        }
        val primary = sessionPrimaryRoute
        val primaryFailed = primary?.openCameraId?.let(sessionFailedOpenIds::contains) == true
        val comboCount = primary?.let { sessionComboCount(it.characteristics) } ?: 0
        if (!primaryFailed && sessionComboIndex + 1 < comboCount) {
            // A failed direct-physical full combo falls back to the primary
            // route's degraded combos; the alternate never degrades sizes.
            if (sessionRouteAltActive) sessionRouteAltActive = false
            sessionComboIndex++
            Log.w(LOG_TAG, "$reason; retrying $id with combo $sessionComboIndex/${comboCount - 1}")
            onState("RECOVERING PREVIEW")
            restartCameraForConfigurationChange()
            return true
        }
        return false
    }

    /**
     * Open-failure recovery: degrading combos cannot fix a device that fails
     * to open, but a vendor composite may still open through its
     * direct-physical route. Returns true when a restart was issued. Runs on
     * the camera handler (the openCamera callback thread).
     */
    private fun advancePastFailedOpen(failedOpenId: String): Boolean {
        sessionFailedOpenIds += failedOpenId
        val alt = sessionAltRoute
        if (!sessionRouteAltActive && alt != null && alt.openCameraId !in sessionFailedOpenIds) {
            sessionRouteAltActive = true
            sessionComboIndex = 0
            Log.w(LOG_TAG, "Camera open failed on $failedOpenId; retrying via direct physical ${alt.openCameraId}")
            onState("RECOVERING PREVIEW")
            restartCameraForConfigurationChange()
            return true
        }
        return false
    }

    /** Combo count for the given route, rebuilt cheaply to bound the ladder. */
    private fun sessionComboCount(characteristics: CameraCharacteristics): Int {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return 1
        val rawSizes = map.getOutputSizes(effectiveRawFormat(selectedCameraId, map))?.toList().orEmpty()
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (rawSizes.isEmpty()) return 1
        return selectSessionStreamCombos(
            rawSizes.map { SessionStreamSize(it.width, it.height) },
            previewSizes.map { SessionStreamSize(it.width, it.height) },
            fullReaderMaxImages(),
            MIN_ACQUIRED_RAW_IMAGES
        ).size.coerceAtLeast(1)
    }

    /**
     * Full-combo reader depth: the whole ZSL ring plus pairing headroom,
     * transition slack, and rearm overlap (see createSession), clamped to
     * the HAL queue hard cap so deep rings degrade to a smaller effective
     * ring instead of a rejected session or a dead process.
     */
    private fun fullReaderMaxImages(): Int = fullReaderMaxImagesFor(rawZslFrameCount)

    private var rawPreviewRetryUntilMs = 0L
    private var rawPreviewFailures = 0
    /**
     * Stillborn-stream detection: repeating-RAW health for the current session.
     * [rawStreamImages] counts repeating-path buffers only — an in-flight
     * forward capture proves nothing about the repeating stream the VF needs.
     * All three reset in [createSession]; all are camera-thread only.
     */
    private var lastRawImageReceivedMs = 0L
    private var rawStreamImages = 0
    private var rawStreamResults = 0
    private var rawStreamFailures = 0
    private var lastSessionPlanLabel = "?"
    /**
     * ISP frame-rate probe: armed while a raw-only default session streams
     * with no persisted verdict for this camera. Measures the sensor-
     * timestamp rate over [ISP_PROBE_FRAMES] frames; a ~20 fps stall
     * rebuilds with the smallest preview target and persists the verdict.
     * Camera-thread only; disarmed by any session rebuild.
     */
    private var fpsProbeArmed = false
    private var fpsProbeCameraId: String? = null
    private var fpsProbeFirstNs = 0L
    /** Still-capture sequence at probe arm: any capture inside the window aborts it. */
    private var fpsProbeShot = 0
    /** Whether the current ladder offers any preview step to jump to. */
    private var sessionOffersPreview = false
    /**
     * Session-level compat: DEFAULT plan without stream-use-case hints and a
     * HAL-default frame rate (no forced 30 fps). Engaged once a repeating RAW
     * stream proves stillborn; sticky afterwards and persisted by the host so
     * the next cold start skips the stillborn attempt entirely.
     */
    private var rawStreamCompatMode = initialRawStreamCompatMode
    /**
     * GPU-sampled reader trial: armed unless a persisted verdict convicted
     * GPU usage on this device. The trial reader allocates CPU_READ plus
     * GPU_SAMPLED_IMAGE, so gralloc hands the sampled-image tier a
     * GPU-shareable allocation (drivers that need one reject CPU-only
     * buffers); every plane consumer (ZSL, histogram, peaking, NEON,
     * upload tiers) keeps working through CPU_READ. A stillborn trial
     * disarms via [recoverRawPreview] and rebuilds with the default
     * CPU-only reader. Camera-thread only.
     */
    private var vfGpuReaderTrialArmed = !vfGpuReaderStarvedPersisted()
    /** A disarmed trial awaits its control verdict (first delivered image persists it). */
    private var vfGpuTrialConvicted = false
    /**
     * Session fallback ladder: HALs that reject the full RAW+preview combo
     * (Xiaomi 14 Ultra rejects it async on every route) retry with degraded
     * combos and, for vendor composites, a direct-physical route instead of
     * wedging on SESSION ERROR. Sticky per lens so a reopen goes straight to
     * the winning combo; reset only when the user changes lens.
     */
    private var sessionComboIndex = 0
    private var sessionRouteAltActive = false
    private var sessionLadderLensId: String? = null
    private val sessionFailedOpenIds = mutableSetOf<String>()
    /**
     * Open IDs the camera service rejected as unknown devices. Unlike
     * [sessionFailedOpenIds] (cleared on every lens change), this denylist
     * lives with the controller: rejection is deterministic per open ID, so a
     * lens that resolves characteristics yet refuses [android.hardware.camera2.CameraManager.openCamera]
     * stays excluded until the user re-runs lens discovery ([reloadLenses]).
     */
    private val unopenableOpenIds = mutableSetOf<String>()
    /** Open IDs that opened successfully at least once (controller lifetime). */
    private val openedOnceOpenIds = mutableSetOf<String>()
    /** Passive stream-probe record: real open outcomes, surfaced by lens discovery. */
    private val lensVerdicts = LensVerdictStore(context)
    /** Per-lens RAW size preference, honored first in the combo ladder. */
    private val rawSizePreferenceStore = RawSizePreferenceStore(context)
    /** Per-lens packed format preference; auto (`RAW_SENSOR`) unless opted in. */
    private val rawFormatPreferenceStore = RawFormatPreferenceStore(context)
    /** Stream format of the session being built; drives the packed fallback. */
    private var lastSessionRawFormat: Int = android.graphics.ImageFormat.RAW_SENSOR
    /**
     * Route identity captured at session-configure time (camera thread) and
     * consumed when its first RAW frame arrives (image thread): a session
     * that configures yet delivers zero buffers must never count as
     * verified. Volatile because the two sites run on different threads.
     */
    @Volatile private var pendingVerifiedIdentity: String? = null
    private var sessionPrimaryRoute: CameraRoute? = null
    private var sessionAltRoute: CameraRoute? = null
    /** Consumed reopen attempts for transient route resolution (see [retryOpenForTransientRoute]). */
    private var openRetryCount = 0
    /** Consumed reopen attempts after open rejections (see [scheduleOpenRejectionRetry]). */
    private var openRejectionRetryCount = 0

    private fun resetSessionLadder() {
        sessionComboIndex = 0
        sessionRouteAltActive = false
        sessionFailedOpenIds.clear()
        openRetryCount = 0
        openRejectionRetryCount = 0
    }

    /**
     * Bounded reopen after an open rejection that smells transient: an ID
     * that opened moments ago reporting unknown-device means the camera
     * service hiccuped (in the field log every lens failed within 2 ms),
     * not that each lens went bad. Mirrors [retryOpenForTransientRoute]'s
     * generation guard with a longer budget (~30 s) so a briefly wedged
     * service recovers without user intervention.
     */
    private fun scheduleOpenRejectionRetry(): Boolean {
        if (openRejectionRetryCount >= OPEN_REJECTION_RETRY_MAX) return false
        openRejectionRetryCount++
        Log.w(LOG_TAG, "Open rejected; retry $openRejectionRetryCount/$OPEN_REJECTION_RETRY_MAX")
        onState("OPENING RAW")
        val retryGeneration = lifecycleGeneration
        cameraHandler.postDelayed({
            if (isCurrent(retryGeneration) && camera == null && !opening) open()
        }, OPEN_REJECTION_RETRY_DELAY_MS)
        return true
    }

    /**
     * Bounded reopen for transient route resolution: Xiaomi answers early
     * characteristics queries with bogus values (rear lenses reporting as
     * front), so an unresolvable route right after launch is retried a few
     * times instead of wedging on a black screen until the user taps a lens.
     * Returns true when a retry was scheduled. Generation-guarded, so stop()
     * or a lens change cancels the pending attempt.
     */
    private fun retryOpenForTransientRoute(reason: String): Boolean {
        if (openRetryCount >= OPEN_RETRY_MAX) return false
        openRetryCount++
        Log.w(LOG_TAG, "$reason; retry $openRetryCount/$OPEN_RETRY_MAX")
        onState("OPENING RAW")
        val retryGeneration = lifecycleGeneration
        cameraHandler.postDelayed({
            if (isCurrent(retryGeneration) && camera == null && !opening) open()
        }, OPEN_RETRY_DELAY_MS)
        return true
    }

    /** Persisted GPU-usage verdict (false until a trial starves this device). */
    private fun vfGpuReaderStarvedPersisted(): Boolean = try {
        context.getSharedPreferences(VF_GPU_READER_PREFS, Context.MODE_PRIVATE)
            .getBoolean(VF_GPU_READER_STARVED_KEY, false)
    } catch (_: Exception) {
        false
    }

    /** Whether the ISP probe already rendered a verdict for this camera. */
    private fun ispPreviewVerdictKnown(cameraId: String?): Boolean = try {
        cameraId != null && context.getSharedPreferences(ISP_PREVIEW_REQUEST_PREFS, Context.MODE_PRIVATE)
            .contains(ISP_PREVIEW_REQUEST_KEY_PREFIX + cameraId)
    } catch (_: Exception) {
        // Best effort: without storage every cold start simply re-probes.
        true
    }

    /** Persisted ISP verdict for this camera (false = raw-only sustains rate, or no verdict yet). */
    private fun ispPreviewRequestNeeded(cameraId: String?): Boolean = try {
        cameraId != null && context.getSharedPreferences(ISP_PREVIEW_REQUEST_PREFS, Context.MODE_PRIVATE)
            .getBoolean(ISP_PREVIEW_REQUEST_KEY_PREFIX + cameraId, false)
    } catch (_: Exception) {
        false
    }

    /** Persist the ISP frame-rate probe verdict (per camera, never backed up). */
    private fun persistIspPreviewVerdict(cameraId: String?, needed: Boolean) {
        if (cameraId == null) return
        try {
            context.getSharedPreferences(ISP_PREVIEW_REQUEST_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ISP_PREVIEW_REQUEST_KEY_PREFIX + cameraId, needed).apply()
        } catch (_: Exception) {
            // Best effort: without persistence the probe simply re-arms next launch.
        }
    }

    /**
     * ISP frame-rate probe verdict, evaluated on the camera thread from
     * sensor timestamps over the probe window. A mid-window still capture
     * would depress the rate transiently, so it aborts the probe instead
     * of rendering a verdict (re-arms next session — the verdict stays
     * unknown). A stall persists the verdict and — when the ladder offers
     * any preview step — rebuilds: the plan-time check above reads the
     * just-persisted verdict and jumps to the first preview combo, so this
     * is always ladder progress, never a loop.
     */
    private fun evaluateIspFpsProbe(endNs: Long) {
        val cameraId = fpsProbeCameraId
        if (cameraId == null || cameraId != selectedCameraId) return
        if (captureInProgress.get() || captureSequence.get() != fpsProbeShot) {
            Log.i(LOG_TAG, "ISP probe aborted (capture inside window); re-arms next session")
            return
        }
        val fps = rawFpsForProbeWindow(ISP_PROBE_FRAMES - 1, endNs - fpsProbeFirstNs)
        val needed = previewRequestNeededForFps(fps)
        persistIspPreviewVerdict(cameraId, needed)
        Log.i(
            LOG_TAG,
            "ISP frame-rate probe: %.1f fps raw-only (%s)".format(
                fps,
                if (needed) "stalled, adding preview target" else "full rate, staying raw-only"
            )
        )
        if (!needed || !sessionOffersPreview) return
        onState("RECOVERING PREVIEW")
        restartCameraForConfigurationChange()
    }

    /**
     * Allocator pre-flight for the GPU-reader trial: asks gralloc whether
     * the trial usage can back this RAW size at all. False goes straight
     * CPU-only without spending a stillborn session + rebuild to learn it.
     * Any framework failure refuses (safe direction: CPU-only reader).
     */
    private fun vfGpuReaderPreflightOk(width: Int, height: Int): Boolean = try {
        HardwareBuffer.isSupported(width, height, HAL_PIXEL_FORMAT_RAW16, 1, gpuReaderProbeUsage())
    } catch (_: Exception) {
        false
    }

    /** Persist the GPU-usage starvation verdict (device-local, never backed up). */
    private fun persistVfGpuReaderVerdict() {
        try {
            context.getSharedPreferences(VF_GPU_READER_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(VF_GPU_READER_STARVED_KEY, true).apply()
        } catch (_: Exception) {
            // Best effort: without persistence the trial simply re-arms next launch.
        }
    }

    private fun recoverRawPreview(reason: String) {
        if (!running || captureInProgress.get()) return
        // A stillborn GPU-usage trial resolves first: the usage axis, not
        // the plan axis, starved this session. Disarm and rebuild with the
        // default reader; the first delivered image persists the verdict.
        if (shouldDisarmVfGpuReaderTrial(rawPreviewFailures, rawStreamImages, vfGpuReaderTrialArmed)) {
            vfGpuReaderTrialArmed = false
            vfGpuTrialConvicted = true
            // The usage axis changed: retry the full combo under it before
            // degrading sizes again (same as the compat escalation below).
            sessionComboIndex = 0
            Log.w(
                LOG_TAG,
                "RAW preview stillborn under GPU-reader trial (images=0 results=$rawStreamResults failures=$rawStreamFailures " +
                    "plan=$lastSessionPlanLabel); rebuilding without GPU usage"
            )
            onState("RECOVERING PREVIEW")
            restartCameraForConfigurationChange()
            return
        }
        // A repeating RAW stream that delivered zero buffers cannot recover at
        // the request level: the stall is the session/stream configuration
        // itself (Samsung: request retries 1..4 change nothing while zero RAW
        // buffers arrive). Escalate once to a compat session, then let
        // request-level recovery run its course there.
        if (shouldEscalateRawPreviewToCompatSession(rawPreviewFailures, rawStreamImages, rawStreamCompatMode)) {
            rawStreamCompatMode = true
            // The plan axis changed (hinted -> DEFAULT): retry the full combo
            // under it before degrading sizes again. Runs on the camera thread
            // (all recoverRawPreview callers are camera-confined).
            sessionComboIndex = 0
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
        // A compat session that still delivers zero RAW buffers is a silent
        // combo rejection: the HAL configured the session fine, then starved
        // it (vivo X300 Ultra accepts FULL but never produces a buffer).
        // Request toggles cannot fix the session itself, so advance the
        // combo ladder instead of wedging on a black viewfinder. Flowing
        // buffers stay on request-level recovery: that is a viewfinder or
        // rendering problem, not a session problem.
        val alt = sessionAltRoute
        val altAvailable = !sessionRouteAltActive && alt != null &&
            alt.openCameraId !in sessionFailedOpenIds
        val comboCount = sessionPrimaryRoute
            ?.let { sessionComboCount(it.characteristics) } ?: 0
        if (session != null && shouldAdvanceStillbornSessionLadder(
                rawPreviewFailures, rawStreamImages, rawStreamCompatMode,
                altAvailable, sessionComboIndex, comboCount
            ) && advanceSessionLadder(
                "RAW stream stillborn (images=0 results=$rawStreamResults " +
                    "failures=$rawStreamFailures plan=$lastSessionPlanLabel)"
            )
        ) return
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
        rawViewfinder?.onStarvation = {
            cameraHandler.post {
                // While stopped (a video take owns the camera) both
                // viewfinders idle by design: no route flip, no camera
                // recovery. Flipping here hides the legacy Surface, whose
                // teardown (resetNative -> destroyGrade) kills the shared
                // record pipelines mid-take and freezes the recording.
                if (!running) return@post
                // Legacy is the first AUTO rung: its GPU->NEON ladder is
                // exhausted when it starves, so BGU takes over for the
                // session. A blocked renderer is not a dead camera stream:
                // the camera recovery below still runs when the stream
                // itself is stale (restarting mid-merge would add a cut).
                if (vfGlobalMode == VfEngineMode.AUTO && !legacyDead) {
                    legacyDead = true
                    Log.w(LOG_TAG, "Legacy viewfinder failed; falling back to BGU")
                    notifyVfRoute()
                    publishRawVfDebug(force = true)
                }
                // Stale-stream recovery (was GpuScheduling.shouldRecoverCamera).
                if (lastRawImageReceivedMs == 0L ||
                    SystemClock.elapsedRealtime() - lastRawImageReceivedMs > 2000L) {
                    recoverRawPreview("No fresh RAW image or display frame")
                }
            }
        }
        bguViewfinder?.onStarvation = {
            cameraHandler.post {
                // Same stopped-camera rule as legacy above: never reroute
                // while a take owns the camera.
                if (!running) return@post
                // BGU is the last AUTO rung: any persistent failure marks
                // it dead and routing returns to legacy (last resort).
                if (!bguDead) {
                    bguDead = true
                    Log.w(LOG_TAG, "BGU viewfinder failed; falling back to legacy")
                    notifyVfRoute()
                    publishRawVfDebug(force = true)
                }
            }
        }
    }

    // ------------------------------------------------------- VF engine route
    //
    // The controller owns the global engine mode and routes every viewfinder
    // call to the active engine. AUTO walks the ladder legacy GPU, legacy
    // CPU (NEON), BGU GPU, BGU CPU; the overlay tap cycles AUTO -> GPU ->
    // CPU -> BGU -> BGU_CPU for manual override.

    private var vfGlobalMode: VfEngineMode = VfEngineMode.AUTO
    private var legacyDead = false
    private var bguDead = false
    private var lastVfRouteIsBgu = false

    /** Fired on the camera thread when the active engine flips (true = BGU). */
    var onVfRouteChanged: ((Boolean) -> Unit)? = null

    private fun activeVf(): VfEngine? =
        if (resolveVfRoute(
                vfGlobalMode, legacyDead, bguDead,
                bguViewfinder != null, rawViewfinder != null
            )
        ) {
            bguViewfinder
        } else {
            rawViewfinder
        }

    private fun notifyVfRoute() {
        val isBgu = resolveVfRoute(
            vfGlobalMode, legacyDead, bguDead,
            bguViewfinder != null, rawViewfinder != null
        )
        // The legacy engine keeps its internal tier override; map the global
        // mode onto it (BGU/AUTO both mean "legacy automatic").
        rawViewfinder?.setEngineMode(
            when (vfGlobalMode) {
                VfEngineMode.GPU -> VfEngineMode.GPU
                VfEngineMode.CPU -> VfEngineMode.CPU
                else -> VfEngineMode.AUTO
            }
        )
        // The BGU engine takes the global mode verbatim: BGU_CPU pins its
        // NEON guide path, every other mode releases the pin. Re-asserted on
        // every route change so taps flip mid-stream without a rebuild.
        bguViewfinder?.setEngineMode(vfGlobalMode)
        if (isBgu != lastVfRouteIsBgu) {
            lastVfRouteIsBgu = isBgu
            onVfRouteChanged?.invoke(isBgu)
        }
    }

    /** Latest stats from the active engine for the debug overlay. */
    fun vfSnapshot(): RawVfStats? = activeVf()?.snapshot()

    /** True when offers currently route to the BGU engine. */
    fun isBguActive(): Boolean {
        val active = activeVf()
        return active != null && active === bguViewfinder
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

    /**
     * Offline-merge BURST dump: single tap transfers the whole current ZSL
     * ring and writes it to [output] as a BURST container. Runs on the
     * caller's thread (queue on [writer] like other saves). Returns null
     * when the ring is empty; images always transfer exactly once.
     */
    internal fun dumpZslRingToBurstCrs(
        output: java.io.File
    ): BurstCrsStore.BurstDumpStats? {
        val frames = rawZslBuffer?.takeAll().orEmpty()
        if (frames.isEmpty()) {
            lastZslTakeSize = 0
            onState("BURST EMPTY")
            return null
        }
        lastZslTakeSize = frames.size
        val chars = characteristics
        if (chars == null) {
            frames.forEach { RawImageOwnership.release(it.image) }
            onState("BURST ERROR")
            return null
        }
        onState("BURST ×${frames.size} • SAVING")
        return try {
            BurstCrsStore.writeZslRingToCrs(
                frames, chars, rawZslRealtimeTimestamps, output
            ).also {
                onState("BURST ×${it.framesWritten} • SAVED")
                publishRawZslStatus()
            }
        } catch (failure: Exception) {
            android.util.Log.w(LOG_TAG, "BURST dump failed", failure)
            onState("BURST ERROR")
            null
        }
    }

    /** Test mode stays on the proven standalone route; never opens a second device. */
    fun supportsDualRaw(): Boolean = selectedCameraId == "0" && activePhysicalCameraId == null &&
        supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

    fun captureDualRaw() {
        val orientation = deviceOrientationDegrees
        postShutter {
            val c = characteristics
            val preview = latestPreviewResult
            val device = camera
            val currentSession = session
            val reader = rawReader
            if (!supportsDualRaw() || c == null || device == null || currentSession == null || reader == null) {
                onState("RAW HDR TEST NEEDS CAMERA 0"); return@postShutter
            }
            if (!hasPreviewMetadata || preview == null) { onState("RAW HDR TEST WAIT"); return@postShutter }
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
            val timeRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
            val analogMax = (c[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY] ?: isoRange.upper)
                .coerceIn(isoRange.lower,isoRange.upper)
            val program = captureExposureMode == CaptureExposureMode.PROGRAM && dynamicExposureSettings.enabled
            val meterIso = selectedIso ?: (if (ettrCaptureActive()) ettrIso else null)
                ?: (if (program) dynamicIso else null) ?: lastIso
            val meterTime = selectedExposureNanos ?: (if (ettrCaptureActive()) ettrShutterNanos else null)
                ?: (if (program) dynamicShutterNanos else null) ?: lastExposureNanos
            val plan = try { DualRawExposure.plan(meterIso,meterTime,isoRange.lower,analogMax,timeRange.lower,timeRange.upper) }
            catch (_: IllegalArgumentException) { onState("RAW HDR TEST EXPOSURE LIMIT"); return@postShutter }
            val focus = selectedFocusDistanceDiopters ?: preview[CaptureResult.LENS_FOCUS_DISTANCE]
                ?: if ((c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) == 0f) 0f else null
            if (focus == null || !focus.isFinite()) { onState("RAW HDR TEST FOCUS WAIT"); return@postShutter }
            val gains = preview[CaptureResult.COLOR_CORRECTION_GAINS]
            val transform = preview[CaptureResult.COLOR_CORRECTION_TRANSFORM]
            val manualWb = gains != null && transform != null &&
                supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val awbLock = c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true
            if (selectedWbKelvin == null && !manualWb && !awbLock) {
                onState("RAW HDR TEST NEEDS WB LOCK"); return@postShutter
            }
            if (!beginCapture(CaptureFormat.DNG_ONLY,requiredSaveSlots=2,orientationSnapshot=orientation)) return@postShutter
            activeDualRaw = true
            closeAllPendingPairs()
            stopRepeatingRawBeforeForwardCapture(currentSession,reader)
            activeFramesRemaining.set(2)
            val generation = lifecycleGeneration
            val id = captureSequence.incrementAndGet()
            try {
                val minFrame = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?.getOutputMinFrameDuration(android.graphics.ImageFormat.RAW_SENSOR,Size(reader.width,reader.height)) ?: 0L
                val requests = listOf(plan.high,plan.low).mapIndexed { index, exposure ->
                    device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
                        addTarget(reader.surface)
                        setTag(CaptureTag(id,index+1,2))
                        applyCameraControls(this,applyDynamicCurve=false)
                        set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY,exposure.iso)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure.nanos)
                        set(CaptureRequest.SENSOR_FRAME_DURATION,maxOf(minFrame,plan.low.nanos,plan.high.nanos))
                        set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE,focus)
                        if (selectedWbKelvin == null) {
                            if (manualWb) {
                                set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF)
                                set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                                set(CaptureRequest.COLOR_CORRECTION_GAINS,gains)
                                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,transform)
                            } else set(CaptureRequest.CONTROL_AWB_LOCK,true)
                        }
                        set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                        set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                        set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF)
                        requestLensShadingMap(this)
                    }.build()
                }
                cameraCaptureSequenceActive = true
                scheduleCaptureTimeout(id)
                onState("RAW HDR TEST ×2")
                Log.i("RawLensDualRaw","Burst id=$id high=${plan.high} low=${plan.low} focus=$focus wbFrozen=true")
                currentSession.captureBurst(requests,captureCallback(generation,id),cameraHandler)
            } catch (failure: Exception) {
                Log.e("RawLensDualRaw","Could not submit pair",failure)
                finishCapture("RAW HDR TEST CAPTURE ERROR")
            }
        }
    }

    /** Captures the default handheld bracket (-2, 0, +2 EV) around the latest metered pair. */
    fun captureHdrBracket(saveEachBracket: Boolean = false, bracketStops: Int = 2,
                          saveDebugFrames: Boolean = false) {
        captureBracket(HdrBracketConfig.classic(bracketStops), saveEachBracket, saveDebugFrames)
    }

    /**
     * Captures the sequential-DCG trio (0, -2, 0 at fixed ISO) around the
     * latest metered pair: two 0 EV frames for shadow averaging plus one
     * -2 EV frame for highlight headroom. Merges through [HdrSeqMerge]
     * (lightweight local alignment + noise-aware rejection), never the
     * FlowNet/deghost path.
     */
    fun captureSeqTrio(saveEachBracket: Boolean = false, saveDebugFrames: Boolean = false) {
        captureBracket(HdrBracketConfig.seqZeroMinusTwoZero(), saveEachBracket, saveDebugFrames)
    }

    /**
     * Captures an explicit-stop bracket (e.g. [-3,-2,-1,0,+1]) with one frame
     * per stop around the latest metered pair. See [HdrBracketConfig].
     */
    fun captureBracketStops(stops: IntArray, saveEachBracket: Boolean = false,
                            saveDebugFrames: Boolean = false) {
        captureBracket(HdrBracketConfig.plan(stops.copyOf()), saveEachBracket, saveDebugFrames)
    }

    /**
     * Captures a dense multi-frame-per-stop burst in the raymerge style (e.g.
     * stops [-3,-2,-1,0,+1] with 5/3/8/2/6 frames per stop): extra shutters on
     * the shadows where photon noise dominates. All frames merge through the
     * HDR path with per-frame alignment + deghost; the JPEG requires every EV
     * covered (see [HdrBracketConfig.coverageSummary]).
     */
    fun captureBracketedBurst(stops: IntArray, framesPerStop: IntArray,
                              saveEachBracket: Boolean = false,
                              saveDebugFrames: Boolean = false) {
        captureBracket(HdrBracketConfig.plan(stops.copyOf(), framesPerStop.copyOf()),
            saveEachBracket, saveDebugFrames)
    }

    /** Shared shutter for [captureHdrBracket], [captureBracketStops], and [captureBracketedBurst]. */
    fun captureBracket(plan: HdrBracketConfig.Plan, saveEachBracket: Boolean = false,
                       saveDebugFrames: Boolean = false) {
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
            activeHdrStops = plan.stops.maxOrNull()?.let { kotlin.math.max(it, -plan.stops.minOrNull()!!) } ?: 2
            activeHdrFrameEvs = IntArray(plan.totalFrames) { plan.frames[it].evStops }
            pendingHdrFrames.ensureCapacity(plan.totalFrames)
            // Warm-start FlowNet now (process-wide singleton, background init):
            // first-bracket merges were paying the ~10s model init inside the
            // align window while runInference blocked on waitReady.
            runCatching { FlowNetNcnnProcessor.start(context.applicationContext) }
            captureFrames(plan.totalFrames, outputFormat = outputFormat,
                orientationSnapshot = orientationSnapshot)
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

    /**
     * JPEG XL output choices are frozen with the shutter press, just like the
     * JPEG ones. No viewfinder push: the VF previews the JPEG look contract,
     * never the JXL container options.
     */
    fun setJxlOutputSettings(settings: JxlOutputSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setJxlOutputSettings(settings) }
            return true
        }
        jxlOutputSettings = settings.resolved()
        return true
    }

    /**
     * Developed-still container (JPEG vs JXL) frozen with the shutter press,
     * exactly like capture format. Selects the encoder at every developed
     * save site; the AgX look stays shared.
     */
    fun setDevelopedFormat(format: DevelopedFormat): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setDevelopedFormat(format) }
            return true
        }
        developedFormat = format
        return true
    }

    /**
     * Preview-only AgX live update while a settings slider drags: pushes the
     * candidate look to the VF without touching the saved output contract (which
     * still commits on release via [setJpegOutputSettings]). Deliberately NOT
     * gated on capture/save activity: VF volatiles never affect an in-flight
     * save, and the release path re-pushes the committed look when the commit
     * itself is refused — so the VF can never strand on an uncommitted look.
     */
    fun previewAgxLive(settings: JpegOutputSettings) {
        if (!isOnCameraThread()) {
            cameraHandler.post { previewAgxLive(settings) }
            return
        }
        val resolved = settings.resolvedForPlatform()
        val strength = adaptivePreviewStrength(captureExposureMode, resolved)
        rawViewfinder?.setAgx(resolved)
        rawViewfinder?.setPreviewExposureStrength(strength)
        bguViewfinder?.setAgx(resolved)
        bguViewfinder?.setPreviewExposureStrength(strength)
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

    /** GALOSH controls freeze exactly like the AI denoise controls. */
    fun setGaloshSettings(settings: GaloshSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setGaloshSettings(settings) }
            return true
        }
        galoshSettings = settings
        return true
    }

    // Future SR hook: rawSuperResolutionSettings() / setRawSuperResolutionSettings()
    // / applyRawSuperResolutionCameraState() here (SR-gated ZSL enable + status).

    fun hdrPlusSettings(): HdrPlusSettings = hdrPlusSettings

    fun setHdrPlusSettings(settings: HdrPlusSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) {
            onState("HDR+ LOCKED • SAVING")
            return false
        }
        hdrPlusSettings = settings
        if (settings.enabled) hdrPlusProcessor().prewarm()
        if (!isOnCameraThread()) {
            cameraHandler.post { applyHdrPlusCameraState(settings) }
            return true
        }
        applyHdrPlusCameraState(settings)
        return true
    }

    private fun applyHdrPlusCameraState(settings: HdrPlusSettings) {
        if (hdrPlusSettings != settings) return
        if (settings.enabled && !rawZslRequested) {
            rawZslRequested = true
            rawZslDisabledForSession = false
            rawZslFallbackDetail = null
            updateRepeatingRequest()
        }
        publishRawZslStatus()
    }

    private fun hdrPlusProcessor(): HdrPlusMerge =
        hdrPlusMerge ?: HdrPlusMerge(context).also { hdrPlusMerge = it }

    fun mfsrSettings(): MfsrSettings = mfsrSettings

    fun setMfsrSettings(settings: MfsrSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) {
            onState("MFSR LOCKED • SAVING")
            return false
        }
        mfsrSettings = settings
        if (!isOnCameraThread()) {
            cameraHandler.post { applyMfsrCameraState(settings) }
            return true
        }
        applyMfsrCameraState(settings)
        return true
    }

    private fun applyMfsrCameraState(settings: MfsrSettings) {
        if (mfsrSettings != settings) return
        if (settings.enabled && !rawZslRequested) {
            rawZslRequested = true
            rawZslDisabledForSession = false
            rawZslFallbackDetail = null
            updateRepeatingRequest()
        }
        publishRawZslStatus()
    }

    private fun beginSingleCapture(
        pressElapsedNanos: Long,
        sensorCutoffSnapshot: Long,
        outputFormat: CaptureFormat,
        orientationSnapshot: Int
    ) {
        // P0 flash: ring frames predate the flash, and merge queues would mix
        // flashed and unflashed frames, so a flash press always fires one
        // forward still (metered first under hardware AE) instead of ZSL.
        if (flashStillMode != FlashStillMode.OFF && isFlashAvailable()) {
            captureSingleWithFlash(outputFormat, orientationSnapshot)
            return
        }
        // ZSL purity: when ZSL is requested and usable, reserve the full burst
        // up front. A 30-frame burst needs 30 slots; checking only 1 would lock
        // the shutter, spin the 3s recovery wait with no capacity, then degrade
        // to a single that further delays the rearm. With the full reservation,
        // a busy queue fails fast with QUEUE FULL and the shutter stays free.
        if (rawZslRequested) {
            val need = zslSelectedFrameCount(outputFormat)
            // HDR+ merges to one artifact: reserve one slot, not the burst.
            // Future SR hook: include SR in the merge-to-one slot accounting.
            val needSlots = availabilitySlotCountFor(
                hdrPlusSettings,
                rawZslFrameCount, outputFormat, mfsrSettings
            )
            // Single-flight merge: a second press fails fast here, before
            // any take drains the ring — the preserved ring then takes
            // instantly on re-tap after the merge finishes.
            if (hdrPlusSettings.enabled && hdrPlusMergeInFlight.get()) {
                onState("HDR+ BUSY")
                refreshCaptureAvailability()
                return
            }
            if (!beginCapture(outputFormat, requiredSaveSlots = needSlots, orientationSnapshot = orientationSnapshot)) return
            // Future SR hook: SR branch here (full consistent ring, no top-up
            // mixing; "SELECTING RAW SR" / "SR QUEUED" + SR BUSY gate above).
            if (hdrPlusSettings.enabled) {
                // HDR+ needs the full consistent ring; no top-up mixing.
                if (rawZslStreaming) {
                    onState("SELECTING HDR+")
                    if (selectAndSaveRawZsl(pressElapsedNanos, sensorCutoffSnapshot)) return
                    val deadlineMs = SystemClock.elapsedRealtime() + ZSL_RECOVERY_WAIT_MS
                    retryZslSelectionUntil(deadlineMs)
                } else {
                    onState("HDR+ QUEUED")
                    val deadlineMs = SystemClock.elapsedRealtime() + ZSL_RECOVERY_WAIT_MS
                    retryZslSelectionUntil(deadlineMs)
                }
                return
            }
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
     * holdout). NOT READY only survives with the toggle
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
            // Per-shot recovery: the stream may be down
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

    private fun zslSelectedFrameCount(outputFormat: CaptureFormat): Int =
        zslSelectedFrameCountFor(
            hdrPlusSettings,
            rawZslFrameCount, outputFormat
        )

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
        activeJxlOutputSettings = jxlOutputSettings
        activeDevelopedFormat = developedFormat
        activeDenoiseSettings = denoiseSettings
        activeGaloshSettings = galoshSettings
        activeHdrPlusSettings = hdrPlusSettings
        activeMfsrSettings = mfsrSettings
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
        // ZSL sharp merge to one artifact: same full-ring discipline as
        // HDR+ below (sharpest-first native-resolve SR merge, linear DNG
        // + JPEG out). Mutual exclusivity with HDR+ is enforced at the
        // settings layer; this branch wins when both somehow engage.
        if (activeMfsrSettings.enabled) {
            return takeFullRingForMerge(cutoff, activeMfsrSettings.activeFrameCount(), "MFSR", ::saveMfsrMerge)
        }
        // HDR+ merges to one artifact: same full-ring discipline as SR.
        if (activeHdrPlusSettings.enabled) {
            return takeFullRingForMerge(cutoff, selectedFrameCount, "HDR+", ::saveHdrPlusMerge)
        }
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
        // An empty ring with forceTopup fires a pure forward burst: the wait loop escalates here after ~1s of empties so every
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
     * Multi-frame merge take (HDR+, MFSR): one output artifact, so one save
     * slot; the ring must hold the full [count] eligible frames (no partial
     * take, no top-up). Pauses the ring stream when the held frames plus a
     * refill would not fit the reader.
     */
    private fun takeFullRingForMerge(
        cutoff: Long,
        count: Int,
        label: String,
        save: (List<BufferedRawFrame>) -> Unit
    ): Boolean {
        if (!hasProcessingCapacity(1, activeCaptureFormat)) {
            onState("ZSL QUEUED")
            lastZslTakeSize = -1
            return false
        }
        val selected = rawZslBuffer?.takeBest(cutoff, rawZslRealtimeTimestamps, count).orEmpty()
        lastZslTakeSize = selected.size
        if (selected.isEmpty()) return false
        if (selected.size < count) return false
        if (!CaptureQueueCapacity.ringFits(pendingFrameSaveCount.get() + selected.size,
                rawZslCapacity, rawReaderMaxImages, RAW_PREVIEW_RESERVED_SLOTS)) {
            updateRepeatingRequest(allowRawZsl = false)
        }
        activeFramesRemaining.set(0)
        onState("$label ×${selected.size} • PREPARING")
        save(selected)
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

    // Future SR hook: full saveRawSuperResolutionMerge() here (planner
    // admission, GPU Bayer-direct linear merge + merged JPEG, CPU streaming
    // mosaic DNG, reference fallback). Callers: selectAndSaveRawZsl SR branch.
    private fun saveRawSuperResolutionMerge(selected: List<BufferedRawFrame>) {
        selected.forEach { RawImageOwnership.release(it.image) }
    }

    /**
     * HDR+ ZSL burst save: queues the Vulkan merge ([runHdrPlusJob]) on the
     * serial writer. Mirrors the HDR save tail.
     */
    private fun saveHdrPlusMerge(selected: List<BufferedRawFrame>) {
        val c = characteristics ?: run {
            selected.forEach { RawImageOwnership.release(it.image) }
            onState("HDR+ ERROR")
            return
        }
        val orientation = activeOutputOrientation
        val cameraId = selectedCameraId ?: "unknown"
        val settings = activeHdrPlusSettings
        val outputFormat = activeCaptureFormat
        val outputSettings = activeJpegOutputSettings
        val captureDenoiseSettings = activeDenoiseSettings
        val captureTimeMillis = System.currentTimeMillis()
        val captureGps = gpsLocation()
        pendingSaveCount.incrementAndGet()
        if (outputFormat.includesJpeg) beginJpegProcessing()
        val releasedOwnership = AutoCloseable {
            selected.forEach { RawImageOwnership.release(it.image) }
        }
        val job = OwnedCaptureJob(releasedOwnership) {
            try {
                runHdrPlusJob(
                    selected, settings, c, orientation, cameraId,
                    outputFormat, outputSettings, captureDenoiseSettings,
                    captureTimeMillis, captureGps = captureGps
                )
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "HDR+ save failed", failure)
                onState("HDR+ ERROR")
            } catch (oom: OutOfMemoryError) {
                Log.e(LOG_TAG, "HDR+ save out of memory", oom)
                onState("HDR+ ERROR")
            } finally {
                if (outputFormat.includesJpeg) endJpegProcessing()
                cameraHandler.post {
                    pendingSaveCount.decrementAndGet()
                    hdrPlusMergeInFlight.set(false)
                    refreshCaptureAvailability()
                    resumeRawZslIfIdle()
                    publishRawZslStatus()
                }
            }
        }
        try {
            writer.execute(job)
            hdrPlusMergeInFlight.set(true)
        } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun()
            if (outputFormat.includesJpeg) endJpegProcessing()
            pendingSaveCount.decrementAndGet()
            onState("QUEUE FULL")
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    private data class HdrPlusFrameInput(
        val frame: BufferedRawFrame,
        val metadata: RawFrameMetadata,
        val result: TotalCaptureResult,
        val layout: RawPlaneLayout,
        val normalization: RawNormalization,
        val crop: RawCrop
    )

    /**
     * HDR+ ZSL burst merge on Vulkan (upstream merge_hdrplus 1:1 port):
     * coarse-to-fine tile alignment per alternate, then the robust
     * spatial or frequency merge toward the middle reference. One merged
     * CFA DNG out (plus JPEG when the capture format includes it).
     * Mirrors the HDR save tail.
     */
    private fun runHdrPlusJob(
        selected: List<BufferedRawFrame>,
        settings: HdrPlusSettings,
        c: android.hardware.camera2.CameraCharacteristics,
        orientation: Int,
        cameraId: String,
        outputFormat: CaptureFormat,
        outputSettings: JpegOutputSettings,
        denoise: DenoiseSettings,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        developedFormat: DevelopedFormat = activeDevelopedFormat,
        jxlSettings: JxlOutputSettings = activeJxlOutputSettings
    ) {
        val saveGeneration = lifecycleGeneration
        fun reportSaveState(message: String) {
            cameraHandler.post { if (isCurrent(saveGeneration)) onState(message) }
        }
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = dngWriterBackend()
            val overrides = dngMetadataOverrides(cameraId)
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    saver.save(
                        frame.image, c, frame.result, orientation, overrides, metadata, backend,
                        fileNameSuffix = "F%02d".format(index),
                        captureId = captureTimeMillis,
                        gps = captureGps,
                        dngCompression = dngCompression()
                    )
                } finally {
                    buffer.limit(limit)
                    buffer.position(position)
                }
            }
        }
        val inputs = selected.map { frame ->
            val metadata = RawFrameMetadataFactory.capture(cameraId, frame.image, c, frame.result, orientation)
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: throw UnsupportedOperationException("HDR+ RAW geometry is not supported")
            val normalization = metadata.normalizationOrNull()
                ?: throw UnsupportedOperationException("HDR+ normalization metadata is missing")
            metadata.rawDevelopmentUnsupportedReason?.let { error(it) }
            val plane = frame.image.planes.single()
            HdrPlusFrameInput(
                frame = frame, metadata = metadata, result = frame.result,
                layout = RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                    plane.rowStride, plane.pixelStride,
                    geometry.sensorOriginX, geometry.sensorOriginY),
                normalization = normalization, crop = geometry.processingCrop
            )
        }
        val referenceIndex = inputs.size / 2
        reportSaveState("HDR+ MERGING")
        val tMerge0 = SystemClock.elapsedRealtime()
        val mergedRaw = try {
            hdrPlusProcessor().merge(
                inputs.map {
                    HdrPlusMerge.FrameInput(
                        it.frame.image, it.layout, it.crop, it.normalization,
                        timestampNanos = it.frame.timestampNanos,
                        gyroRadiansPerSecond = it.frame.motionRadiansPerSecond,
                        sensitivityIso = it.metadata.sensitivityIso ?: 0
                    )
                },
                referenceIndex, settings
            )
        } finally {
            inputs.forEach { RawImageOwnership.release(it.frame.image) }
        } ?: run {
            Log.e(LOG_TAG, "HDR+ merge returned null")
            reportSaveState("HDR+ ERROR")
            return
        }
        val mergeMs = SystemClock.elapsedRealtime() - tMerge0
        // The decision was computed inside our own synchronized merge call;
        // fall back to keep-all only if the volatile was never published.
        val decision = hdrPlusProcessor().lastDecision
            ?.takeIf {
                it.keepIndices.size >= HdrPlusSettings.MIN_MERGE_FRAMES &&
                    it.keepIndices.all { index -> index in inputs.indices } &&
                    it.refIndex in it.keepIndices.indices
            }
            ?: HdrPlusAutoDecision(
                strength = settings.strength, highQuality = settings.highQuality,
                keepIndices = inputs.indices.toList(),
                refIndex = referenceIndex.coerceIn(inputs.indices),
                droppedIndices = emptyList(), strengthAuto = false, pathAuto = false
            )
        val keptInputs = decision.keepIndices.map { inputs[it] }
        Log.i(LOG_TAG, "HDR+ timing merge=${mergeMs}ms " +
            "frames=${keptInputs.size}/${inputs.size} ${mergedRaw.width}x${mergedRaw.height}")
        val refInput = keptInputs[decision.refIndex]
        val refMetadata = refInput.metadata
        val refResult = refInput.result
        val merged = requireNotNull(RawPreDemosaicPipeline.process(
            mergedRaw, refMetadata, PreDemosaicSettings()).cfa)
        val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also { rawDeveloper = it }
        val effectiveMerged = if (denoise.aiEnabled) {
            developer.denoiseCfa(merged, refMetadata) ?: merged
        } else merged
        var referenceDng: String? = null
        if (outputFormat.includesDng) {
            val timings = hdrPlusProcessor().lastTimings
            val motion = hdrPlusProcessor().lastMotion
            // Same snapshot discipline as lastTimings: merge is synchronized
            // and this read follows our own merge call on the saver thread.
            val gyroReported = motionTracker.isRunning()
            // Per-frame strengths reach provenance only when they actually
            // vary (uniform reads as the burst strength line) — and never
            // when strength maps drove the merge (the maps supersede them).
            val frameStrengths = if (decision.strengthMaps != null) null
            else decision.frameStrengths?.takeIf { it.toSet().size > 1 }
            val provenance = HdrPlusProvenance(
                mergedFrames = keptInputs.size, selectedFrames = inputs.size,
                rejectedFrames = decision.droppedIndices.size,
                highQuality = decision.highQuality,
                strength = decision.strength,
                tileSize = HdrPlusSettings.TILE_SIZE,
                searchDistance = HdrPlusSettings.SEARCH_DISTANCE,
                referenceIndex = decision.refIndex,
                referenceTimestampNs = refMetadata.timestampNanos,
                referenceFrameNumber = refMetadata.frameNumber,
                sourceWidth = refMetadata.imageWidth, sourceHeight = refMetadata.imageHeight,
                sourceCameraId = refMetadata.cameraId,
                mergeMs = mergeMs,
                packMs = timings.packMs, gpuMs = timings.gpuMs, unpackMs = timings.unpackMs,
                mapsActive = decision.strengthMaps != null,
                frames = keptInputs.mapIndexed { keptPos, input ->
                    // Delta vs the immediate capture-order predecessor
                    // (which may itself have been dropped).
                    val origIndex = decision.keepIndices[keptPos]
                    val delta = motion?.pairDeltas?.getOrNull(origIndex - 1)
                        ?.takeIf { it.isFinite() }?.toFloat()
                    HdrPlusFrameInfo(
                        input.metadata.exposureTimeNanos, input.metadata.sensitivityIso,
                        deltaVsPrev = delta,
                        gyroRadiansPerSecond =
                            input.frame.motionRadiansPerSecond.takeIf { gyroReported },
                        mergeStrength = frameStrengths?.getOrNull(keptPos)
                    )
                }
            )
            referenceDng = DngSaver(context).saveMerged(
                effectiveMerged, refMetadata, captureTimeMillis, gps = captureGps,
                displayName = CaptureFileNames.hdrPlusDng(captureTimeMillis),
                sixteenBit = true, hdrPlusProvenance = provenance,
                dngCompression = dngCompression()
            )
        }
        if (outputFormat.includesJpeg) {
            // The HDR+ average lives in the single-frame normalized domain, so the
            // merged develop meters through the LDR adaptive path on the merged CFA
            // itself (same highlight guards as single-frame). The DNG above stays
            // linear and dark; only this developed still lifts. ZSL bursts never use
            // ETTR, so no residual headroom cap applies.
            if (developedFormat == DevelopedFormat.JXL) {
                val developed = developer.developMergedJxl(effectiveMerged, refMetadata,
                    RawDevelopmentSettings(
                        denoise = denoise,
                        exposureEv = 0.0,
                        adaptiveExposureStrength = adaptivePreviewStrength(
                            activeCaptureExposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, developFocusView
                        )
                    ), look = outputSettings, jxl = jxlSettings)
                JxlSaver(context).save(developed, refMetadata, refResult,
                    captureTimeMillis = captureTimeMillis,
                    typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps)
                publishDevelopedJxlScope(developed)
                reportSaveState(if (referenceDng != null) "HDR+ MERGED+DNG • JXL" else "HDR+ MERGED • JXL")
            } else {
                val developed = developer.developMergedJpeg(effectiveMerged, refMetadata,
                    RawDevelopmentSettings(
                        denoise = denoise,
                        exposureEv = 0.0,
                        adaptiveExposureStrength = adaptivePreviewStrength(
                            activeCaptureExposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, developFocusView
                        )
                    ), outputSettings)
                try {
                    JpegSaver(context).save(developed, refMetadata,
                        refResult, captureTimeMillis = captureTimeMillis,
                        typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps)
                    publishDevelopedScope(developed.bitmap)
                    reportSaveState(if (referenceDng != null) "HDR+ MERGED+DNG" else "HDR+ MERGED")
                } finally {
                    recycleUltraHdrGainmapContents(developed)
                    developed.bitmap.recycle()
                }
            }
        } else {
            reportSaveState("HDR+ DNG SAVED")
        }
    }

    private fun saveMfsrMerge(selected: List<BufferedRawFrame>) {
        val c = characteristics ?: run {
            selected.forEach { RawImageOwnership.release(it.image) }
            onState("MFSR ERROR")
            return
        }
        val orientation = activeOutputOrientation
        val cameraId = selectedCameraId ?: "unknown"
        val settings = activeMfsrSettings
        val outputFormat = activeCaptureFormat
        val outputSettings = activeJpegOutputSettings
        val captureDenoiseSettings = activeDenoiseSettings
        val captureTimeMillis = System.currentTimeMillis()
        val captureGps = gpsLocation()
        pendingSaveCount.incrementAndGet()
        if (outputFormat.includesJpeg) beginJpegProcessing()
        val releasedOwnership = AutoCloseable {
            selected.forEach { RawImageOwnership.release(it.image) }
        }
        val job = OwnedCaptureJob(releasedOwnership) {
            try {
                runMfsrJob(
                    selected, settings, c, orientation, cameraId,
                    outputFormat, outputSettings, captureDenoiseSettings,
                    captureTimeMillis, captureGps = captureGps
                )
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "MFSR save failed", failure)
                onState("MFSR ERROR")
            } catch (oom: OutOfMemoryError) {
                Log.e(LOG_TAG, "MFSR save out of memory", oom)
                onState("MFSR ERROR")
            } finally {
                if (outputFormat.includesJpeg) endJpegProcessing()
                cameraHandler.post {
                    pendingSaveCount.decrementAndGet()
                    mfsrMergeInFlight.set(false)
                    refreshCaptureAvailability()
                    resumeRawZslIfIdle()
                    publishRawZslStatus()
                }
            }
        }
        try {
            writer.execute(job)
            mfsrMergeInFlight.set(true)
        } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun()
            if (outputFormat.includesJpeg) endJpegProcessing()
            pendingSaveCount.decrementAndGet()
            onState("QUEUE FULL")
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    /**
     * ZSL sharp merge job: unpack + sharpest-first order, native-resolve
     * SR merge (scale 1, RGB out), linear DNG via [LinearRgbDngSaver]
     * plus developed JPEG (Amaze bypassed through the sharp RGB tail;
     * same rcd_final color contract and AGX/encode as the CFA path).
     */
    private fun runMfsrJob(
        selected: List<BufferedRawFrame>,
        settings: MfsrSettings,
        c: android.hardware.camera2.CameraCharacteristics,
        orientation: Int,
        cameraId: String,
        outputFormat: CaptureFormat,
        outputSettings: JpegOutputSettings,
        denoise: DenoiseSettings,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        developedFormat: DevelopedFormat = activeDevelopedFormat,
        jxlSettings: JxlOutputSettings = activeJxlOutputSettings
    ) {
        val saveGeneration = lifecycleGeneration
        fun reportSaveState(message: String) {
            cameraHandler.post { if (isCurrent(saveGeneration)) onState(message) }
        }
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = dngWriterBackend()
            val overrides = dngMetadataOverrides(cameraId)
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    saver.save(
                        frame.image, c, frame.result, orientation, overrides, metadata, backend,
                        fileNameSuffix = "F%02d".format(index),
                        captureId = captureTimeMillis,
                        gps = captureGps,
                        dngCompression = dngCompression()
                    )
                } finally {
                    buffer.limit(limit)
                    buffer.position(position)
                }
            }
        }
        reportSaveState("MFSR MERGING")
        val tMerge0 = android.os.SystemClock.elapsedRealtime()
        val ordered = try {
            MfsrMerge.prepare(selected) { frame ->
                RawFrameMetadataFactory.capture(cameraId, frame.image, c, frame.result, orientation)
            }
        } finally {
            // Images stay open for the just-in-time decode inside merge.
        }
        if (ordered.isNullOrEmpty()) {
            Log.e(LOG_TAG, "MFSR prepare produced no usable frames")
            reportSaveState("MFSR ERROR")
            selected.forEach { RawImageOwnership.release(it.image) }
            return
        }
        val merged = try {
            MfsrMerge.merge(context, ordered, settings)
        } finally {
            selected.forEach { RawImageOwnership.release(it.image) }
        } ?: run {
            Log.e(LOG_TAG, "MFSR merge returned null")
            reportSaveState("MFSR ERROR")
            return
        }
        val mergeMs = android.os.SystemClock.elapsedRealtime() - tMerge0
        Log.i(LOG_TAG, "MFSR timing merge=${mergeMs}ms frames=${merged.keptCount}/${selected.size} " +
            "${merged.width}x${merged.height}")
        var referenceDng: String? = null
        if (outputFormat.includesDng) {
            referenceDng = saveMfsrDng(merged, cameraId, captureTimeMillis, captureGps, selected)
            reportSaveState("MFSR DNG SAVED")
        }
        if (outputFormat.includesJpeg) {
            // The merge output is already RGB: no Amaze demosaic, fixed
            // reference exposure (adaptive metering has no RGB leg yet).
            // ZSL bursts never use ETTR, so no residual headroom cap.
            val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also { rawDeveloper = it }
            if (developedFormat == DevelopedFormat.JXL) {
                val developed = developer.developMfsrJxl(merged.rgb, merged.width, merged.height,
                    merged.refMetadata, RawDevelopmentSettings(denoise = denoise),
                    look = outputSettings, jxl = jxlSettings)
                JxlSaver(context).save(developed, merged.refMetadata, merged.refResult,
                    captureTimeMillis = captureTimeMillis,
                    typeSuffix = CaptureFileNames.TYPE_MFSR, gps = captureGps)
                publishDevelopedJxlScope(developed)
                reportSaveState(if (referenceDng != null) "MFSR MERGED+DNG • JXL" else "MFSR MERGED • JXL")
            } else {
                val developed = developer.developMfsrJpeg(merged.rgb, merged.width, merged.height,
                    merged.refMetadata, RawDevelopmentSettings(denoise = denoise), outputSettings)
                try {
                    JpegSaver(context).save(developed, merged.refMetadata,
                        merged.refResult, captureTimeMillis = captureTimeMillis,
                        typeSuffix = CaptureFileNames.TYPE_MFSR, gps = captureGps)
                    publishDevelopedScope(developed.bitmap)
                    reportSaveState(if (referenceDng != null) "MFSR MERGED+DNG" else "MFSR MERGED")
                } finally {
                    recycleUltraHdrGainmapContents(developed)
                    developed.bitmap.recycle()
                }
            }
        } else {
            reportSaveState("MFSR DNG SAVED")
        }
    }

    private fun saveMfsrDng(
        merged: MfsrMerge.MfsrResult,
        cameraId: String,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        selected: List<BufferedRawFrame>
    ): String {
        merged.rgb.rewind()
        val triplets = FloatArray(merged.width * merged.height * 3)
        merged.rgb.asFloatBuffer().get(triplets)
        val image = MergedLinearRgb(merged.width, merged.height, triplets)
        val provenance = MergeProvenance(
            algorithmVersion = "RawLens-Mfsr/scale1-sr-pipeline",
            selectedFrames = selected.size,
            acceptedFrames = merged.keptCount,
            rejectedFrames = selected.size - merged.keptCount,
            referenceTimestampNs = merged.refMetadata.timestampNanos,
            outputScale = 1,
            sourceCameraId = cameraId,
            lensShadingApplied = false,
            effectiveFrames = merged.keptCount.toDouble()
        )
        return LinearRgbDngSaver(context).saveLinearRgb(
            image, merged.refMetadata, provenance, captureId = captureTimeMillis, gps = captureGps
        )
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
     * Hybrid top-up (ZSL + post-press frames, https://research.google/blog/hdr-with-bracketing-on-pixel-phones/): the ring held [holdout] of [need]
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
     * ordered, saved under one stem. Partial on timeout/failure (a press always
     * ends in a picture, never a wedge); empty only when nothing arrived, which
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
                    // P0: resolve still flash after HDR (brackets force manual
                    // exposure) so the builder AE state is final. Never TORCH.
                    applyStillFlash(this)
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
        val stops = activeHdrFrameEvs.getOrElse(frameIndex) {
            intArrayOf(-activeHdrStops, 0, activeHdrStops).getOrElse(frameIndex) { 0 }
        }
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
        val time = HdrBracketConfig.shutterNanos(base, stops).let { value ->
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
        Log.i(LOG_TAG, "HDR bracket frame=${frameIndex + 1}/${activeHdrFrameEvs.size} ev=$stops iso=$iso shutterNs=$time")
    }

    /**
     * AF-only drag: moves the AF square, keeps the AE square where it was,
     * and restarts the AF scan. A single tap elsewhere while locked routes
     * through [setFocusAndMeteringPoint] instead and moves both together.
     */
    fun setAfPoint(viewX: Float, viewY: Float) {
        touchFocus.start(viewX, viewY, indefinite = false, updateAe = false)
    }

    /**
     * Coalesced tap-to-focus + tap-to-meter: installs the AF and AE regions
     * together, issues a single repeating update, then a single AF START.
     * Same visible behavior as the old split setAePoint()+setAfPoint() pair,
     * but 2-3 fewer HAL round-trips per tap. Every tap refocuses — including
     * a single tap while locked, which cancels the old scan/lock and starts
     * a new one at the new point; the sharp lock auto-releases after
     * [AutoFocusPolicy.LOCK_HOLD_MS].
     */
    fun setFocusAndMeteringPoint(viewX: Float, viewY: Float) {
        touchFocus.start(viewX, viewY, indefinite = false, updateAe = true)
    }

    /**
     * Press-and-hold variant: focuses at the point like a tap but the sharp
     * AF lock ignores the auto-release timer and holds until double-tap reset,
     * reset button, retap, manual focus, lens switch, or stop. Hardware AE
     * (CONTROL_AE_LOCK) freezes with it, so exposure holds too.
     */
    fun setFocusAndMeteringHold(viewX: Float, viewY: Float) {
        touchFocus.start(viewX, viewY, indefinite = true, updateAe = true)
    }

    /** Camera-side half of [TouchFocus]; every call runs on the camera thread. */
    private inner class TouchFocusHost : TouchFocus.Host {
        override val handler: Handler get() = cameraHandler
        override fun isOnCameraThread() = this@RawCameraController.isOnCameraThread()
        override fun canSendOneShot() =
            camera != null && session != null && singleShotTarget() != null
        override fun captureBusy() = captureInProgress.get() || cameraCaptureSequenceActive
        override fun maxAfRegions() =
            characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        override fun afAvailableModes() =
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        override fun isAeMeteringSupported() = this@RawCameraController.isAeMeteringSupported()
        override fun meteringRegion(viewX: Float, viewY: Float) =
            this@RawCameraController.meteringRegion(viewX, viewY)
        override fun syncDevelopFocus(viewX: Float, viewY: Float) =
            this@RawCameraController.syncDevelopFocus(viewX, viewY)
        override var afRegion: MeteringRectangle?
            get() = this@RawCameraController.afRegion
            set(value) { this@RawCameraController.afRegion = value }
        override var aeRegion: MeteringRectangle?
            get() = this@RawCameraController.aeRegion
            set(value) { this@RawCameraController.aeRegion = value }
        override var selectedFocusDistanceDiopters: Float?
            get() = this@RawCameraController.selectedFocusDistanceDiopters
            set(value) { this@RawCameraController.selectedFocusDistanceDiopters = value }
        override fun viewfinderMinSide() = minOf(viewfinder.width, viewfinder.height).toFloat()
        override fun updateRepeatingRequest() =
            this@RawCameraController.updateRepeatingRequest(preserveRawZslBuffer = true)
        override fun sendAfTrigger(trigger: Int, aeOnForDynamic: Boolean) {
            val device = camera ?: return
            val currentSession = session ?: return
            val surface = singleShotTarget() ?: return
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                if (aeOnForDynamic && dynamicExposureSettings.enabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            }
            currentSession.capture(request.build(), debugCaptureCallback, cameraHandler)
        }
        override fun cameraAccessReason(failure: CameraAccessException) =
            this@RawCameraController.cameraAccessReason(failure)
        override fun publishControls() = this@RawCameraController.publishControls()
        override fun publishPeakingActiveIfChanged() =
            this@RawCameraController.publishPeakingActiveIfChanged()
        override fun onState(state: String) = this@RawCameraController.onState(state)
        override fun onFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long) =
            this@RawCameraController.onFocusLock(locked, indefinite, deadlineMs)
        override fun onMeteringReleased() = this@RawCameraController.onMeteringReleased()
    }

    /**
     * One-shot capture target (AF triggers, metering probes): the invisible
     * preview surface normally, the RAW reader in RAW-only sessions. A stray
     * RAW frame from these triggers flows through normal handling (a bonus
     * viewfinder frame, then release or pair-timeout), so reusing the reader
     * is harmless.
     */
    private fun singleShotTarget(): Surface? = previewSurface ?: rawReader?.surface

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
        syncDevelopFocus(viewX, viewY)
        updateRepeatingRequest(preserveRawZslBuffer = true)
        onState("METERING EXPOSURE")
    }

    fun isAeMeteringSupported(): Boolean =
        (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0

    private fun continuousPictureAfMode(): Int =
        AutoFocusPolicy.defaultAfMode(
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        )

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
        if (touchFocus.active) {
            touchFocus.releaseToContinuous()
        } else {
            aeRegion = null
            updateDevelopFocus(null)
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
        // Manual release re-arms the tap gate: the next tap always refocuses.
        touchFocus.rearmTapGate()
        if (touchFocus.active) {
            touchFocus.releaseToContinuous()
            return
        }
        val hadTargets = afRegion != null || aeRegion != null || touchFocus.aeLockIndefinite
        afRegion = null
        aeRegion = null
        updateDevelopFocus(null)
        touchFocus.clearAeLock()
        if (hadTargets) updateRepeatingRequest(preserveRawZslBuffer = true)
        onMeteringReleased()
        onState("AE/AF RESET")
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

    /**
     * Mirrors an [aeRegion] write into the develop meter's tap-follow focus.
     * View-normalized like [meteringRegion]'s own first step (rotation and
     * mirroring only affect the sensor mapping, never view space). A null
     * [aeRegion] — metering back to full-frame — clears the focus so the
     * vote returns to the region center. Pushes to both VF engines; saves
     * read [developFocusView] when building their tuning.
     */
    private fun syncDevelopFocus(viewX: Float, viewY: Float) {
        val focus = if (aeRegion != null && viewfinder.width > 0 && viewfinder.height > 0) {
            (viewX / viewfinder.width).coerceIn(0f, 1f) to
                (viewY / viewfinder.height).coerceIn(0f, 1f)
        } else null
        updateDevelopFocus(focus)
    }

    private fun updateDevelopFocus(focus: Pair<Float, Float>?) {
        developFocusView = focus
        rawViewfinder?.setMeteringFocus(focus?.first, focus?.second)
        bguViewfinder?.setMeteringFocus(focus?.first, focus?.second)
    }

    /** Settings toggle for face-priority AF/AE. Drops the tracked face either way. */
    fun setFacePriorityEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setFacePriorityEnabled(enabled) }
            return
        }
        if (facePriorityEnabled == enabled) return
        facePriorityEnabled = enabled
        clearFaceState()
        updateRepeatingRequest(preserveRawZslBuffer = true)
    }

    /** False before the first open, on lenses without face stats, or after a HAL rejection. */
    fun isFaceDetectSupported(): Boolean = maxFaceCount > 0 && !faceDetectRejected

    private fun clearFaceState() {
        trackedFace = null
        faceRegionRect = null
        faceAfRegion = null
        faceAeRegion = null
        faceMissCount = 0
        onFaces(emptyList())
    }

    /** FULL scores every face for the tracker; OFF is set explicitly so a toggle-off sticks. */
    private fun applyFaceDetectMode(builder: CaptureRequest.Builder) {
        if (faceDetectRejected) return
        val mode = if (facePriorityEnabled && maxFaceCount > 0)
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
        else CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        try {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)
        } catch (failure: IllegalArgumentException) {
            // A HAL that advertises faces but rejects the key must not break the
            // repeating stream: latch off for this session and run faceless.
            faceDetectRejected = true
            Log.w(LOG_TAG, "Face-detect mode rejected; running without faces", failure)
        }
    }

    /**
     * Tracks one HAL face across preview results and steers the AF/AE regions
     * at it. Camera thread; throttles both the repeating pushes (jittery boxes
     * must not rebuild the stream every frame) and the overlay publication.
     */
    private fun handlePreviewFaces(result: TotalCaptureResult) {
        if (!facePriorityEnabled || maxFaceCount <= 0 || faceDetectRejected) return
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: active
        val faces = result.get(CaptureResult.STATISTICS_FACES)
            ?.mapNotNull { halFaceIn(it, active) } ?: emptyList()
        val selected = FacePriority.select(faces, trackedFace)
        if (selected == null) {
            // Debounce: a single faceless result is usually a blink, not a departure.
            if (++faceMissCount >= FACE_MISS_TOLERANCE && trackedFace != null) {
                trackedFace = null
                faceRegionRect = null
                faceAfRegion = null
                faceAeRegion = null
                updateRepeatingRequest(preserveRawZslBuffer = true, light = true)
            }
        } else {
            faceMissCount = 0
            trackedFace = selected
            val rect = Rect(selected.left, selected.top, selected.right, selected.bottom)
            // Metering rectangles must sit inside the crop, like tap targets.
            if (!rect.intersect(crop)) {
                if (faceAfRegion != null || faceAeRegion != null) {
                    faceRegionRect = null
                    faceAfRegion = null
                    faceAeRegion = null
                    updateRepeatingRequest(preserveRawZslBuffer = true, light = true)
                }
            } else {
                val tolerance = (active.width() * FACE_REGION_MOVE_FRACTION).toInt().coerceAtLeast(1)
                val previous = faceRegionRect
                val moved = previous == null ||
                    Math.abs(previous.left - rect.left) > tolerance ||
                    Math.abs(previous.top - rect.top) > tolerance ||
                    Math.abs(previous.right - rect.right) > tolerance ||
                    Math.abs(previous.bottom - rect.bottom) > tolerance
                val now = SystemClock.elapsedRealtime()
                if (moved && now - lastFaceRegionPushMs >= FACE_REGION_PUSH_MIN_INTERVAL_MS) {
                    lastFaceRegionPushMs = now
                    faceRegionRect = Rect(rect)
                    val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
                    faceAfRegion = region
                    faceAeRegion = region
                    updateRepeatingRequest(preserveRawZslBuffer = true, light = true)
                }
            }
        }
        publishFaces(faces, selected, active)
    }

    private fun halFaceIn(face: Face, active: Rect): HalFace? {
        val bounds = face.bounds ?: return null
        val left = bounds.left.coerceIn(active.left, active.right)
        val top = bounds.top.coerceIn(active.top, active.bottom)
        val right = bounds.right.coerceIn(active.left, active.right)
        val bottom = bounds.bottom.coerceIn(active.top, active.bottom)
        if (right <= left || bottom <= top) return null
        return HalFace(left, top, right, bottom, face.score)
    }

    private fun publishFaces(faces: List<HalFace>, tracked: HalFace?, active: Rect) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFacePublishMs < FACE_OVERLAY_INTERVAL_MS) return
        lastFacePublishMs = now
        val width = viewfinder.width
        val height = viewfinder.height
        if (width <= 0 || height <= 0) return
        // Same un-offset convention as taps: overlay pixels are fractions of the
        // viewfinder size, so boxes land where a tap at the same spot meters.
        val sensorActive = SensorActiveArray(active.left, active.top, active.right, active.bottom)
        val rotation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val mirrored = characteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        onFaces(faces.map { face ->
            val box = FacePriority.mapToView(face, sensorActive, rotation, mirrored)
            FaceOverlayBox(
                box.left * width, box.top * height,
                box.right * width, box.bottom * height,
                face == tracked
            )
        })
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

    /**
     * Top-bar flash button: cycles still-capture flash OFF → ON → AUTO.
     * True flash fires on shutter (SINGLE under manual exposure, AE-driven
     * with precapture under hardware AE) — never continuous TORCH.
     */
    fun toggleTorch() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::toggleTorch)
            return
        }
        cycleFlashStill()
    }

    fun cycleFlashStill() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleFlashStill)
            return
        }
        if (!isFlashAvailable()) {
            onState("FLASH N/A")
            return
        }
        flashStillMode = when (flashStillMode) {
            FlashStillMode.OFF -> FlashStillMode.ON
            FlashStillMode.ON -> FlashStillMode.AUTO
            FlashStillMode.AUTO -> FlashStillMode.OFF
        }
        publishControls()
        onState("FLASH ${flashStillMode.name}")
    }

    fun flashStillMode(): FlashStillMode = flashStillMode

    /** Preview-assist torch only; still captures always resolve flash separately. */
    fun setTorchEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setTorchEnabled(enabled) }
            return
        }
        if (enabled && !isFlashAvailable()) {
            onState("FLASH N/A")
            return
        }
        torchEnabled = enabled
        controlsChanged()
    }

    fun isFlashAvailable(): Boolean =
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

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

    /** AgX-curved (true, JPEG preview) vs linear-RAW (false, DNG) scope data. Follows the
     * capture format; both variants sample the same RAW stream, so no session rebuild. */
    fun setScopeAgxApplied(applied: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setScopeAgxApplied(applied) }
            return
        }
        scopeAgxApplied = applied
        lastRawHistogramSampleMs = Long.MIN_VALUE
    }

    /** Histogram vs waveform parade. Both sample the same RAW stream; no session rebuild. */
    fun setScopeMode(mode: ScopeMode) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setScopeMode(mode) }
            return
        }
        scopeMode = mode
        lastRawHistogramSampleMs = Long.MIN_VALUE
    }

    /** Focus-peaking master switch. Peaking samples the running RAW VF stream, so no session rebuild. */
    fun setFocusPeakingEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setFocusPeakingEnabled(enabled) }
            return
        }
        focusPeakingEnabled = enabled
        if (enabled) lastFocusPeakingSampleMs = Long.MIN_VALUE
        publishPeakingActiveIfChanged()
    }

    /** True while the user is focusing: manual distance set or a tap scan/lock in flight. */
    private fun focusPeakingActive(): Boolean =
        FocusPeakingPolicy.shouldShow(
            focusPeakingEnabled,
            selectedFocusDistanceDiopters != null,
            touchFocus.active
        )

    /**
     * Edge-triggered overlay visibility. Call on the camera thread after any
     * mutation of the policy inputs (master switch, manual focus, touch-focus
     * state) so the overlay never sticks on a stale verdict.
     */
    private fun publishPeakingActiveIfChanged() {
        val active = focusPeakingActive()
        if (active == lastPeakingActivePublished) return
        lastPeakingActivePublished = active
        if (active) lastFocusPeakingSampleMs = Long.MIN_VALUE
        Log.i(
            LOG_TAG,
            "Focus peaking ${if (active) "SHOWN" else "HIDDEN"} " +
                "(manual=${selectedFocusDistanceDiopters != null} " +
                "touch=${touchFocus.active} enabled=$focusPeakingEnabled)"
        )
        onFocusPeakingActive(active)
    }

    private fun publishFocusPeakingIfDue(image: Image, cameraCharacteristics: CameraCharacteristics) {
        if (!focusPeakingActive()) return
        val now = SystemClock.elapsedRealtime()
        // The MIN_VALUE sentinel means "sample immediately"; see RawHistogramThrottle.
        if (!RawHistogramThrottle.shouldSample(now, lastFocusPeakingSampleMs, FOCUS_PEAKING_INTERVAL_MS)) return
        lastFocusPeakingSampleMs = now
        sampleSafely("focus-peaking") {
            RawFocusPeakingSampler.sample(image, cameraCharacteristics)?.let(onFocusPeaking)
        }
    }

    /**
     * Live RAW samplers (scope, peaking, metering) are best-effort per-frame work
     * on camera/metering threads, where an uncaught throwable kills the app — a
     * NoSuchMethodError in the histogram sampler did exactly that on API 30.
     * Never let one bad frame take down the session; skip it and log (throttled).
     */
    private inline fun <T> sampleSafely(tag: String, block: () -> T?): T? {
        return try {
            block()
        } catch (t: Throwable) {
            val now = SystemClock.elapsedRealtime()
            if (lastSamplerErrorLogMs == Long.MIN_VALUE ||
                now - lastSamplerErrorLogMs >= SAMPLER_ERROR_LOG_INTERVAL_MS
            ) {
                lastSamplerErrorLogMs = now
                Log.e(LOG_TAG, "RAW $tag sample failed; frame skipped", t)
            }
            null
        }
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
                val sample = sampleSafely("metering") {
                    if (ettrDue) RawEttrSampler.sample(image, cameraCharacteristics)
                    else RawEttrSampler.sampleProgram(
                        image, cameraCharacteristics, programAeProfile.metering
                    )
                }
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
        if (touchFocus.scanning) {
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
        updateDevelopFocus(null)
        resetSessionLadder()
        restartCameraForConfigurationChange()
    }

    fun lensOptions(): List<LensOption> = disambiguatedLensLabels(rawCameraIds.map { id ->
        LensOption(id, zoomLabel(id), id == selectedCameraId)
    })

    fun activeCameraId(): String? = selectedCameraId

    /** Advertised vendor keys for the open lens, backing the per-lens key editor. */
    fun vendorKeyDiscovery(): List<DiscoveredVendorKey> {
        val chars = characteristics ?: return emptyList()
        return VendorKeyAndroid.discover(chars)
    }

    @Volatile private var lastVendorSessionReport: VendorKeyApplyReport? = null
    @Volatile private var lastVendorRequestReport: VendorKeyApplyReport? = null
    @Volatile private var vendorSessionFingerprint: String? = null
    @Volatile private var vendorSessionOpmode: Int? = null
    @Volatile private var vendorSessionRoute: String? = null

    /** Last session/request apply outcome for the editor readout. */
    fun vendorLastApply(): Pair<VendorKeyApplyReport?, VendorKeyApplyReport?> =
        lastVendorSessionReport to lastVendorRequestReport

    /** True when saved session keys or opmode differ from what the open session loaded. */
    fun vendorSessionNeedsReopen(): Boolean {
        val route = selectedCameraId ?: return false
        if (route != vendorSessionRoute) return false
        val profile = try {
            vendorKeys(route)
        } catch (_: Exception) {
            return false
        }
        if (VendorKeyApply.fingerprint(profile.sessionEntries()) != (vendorSessionFingerprint ?: "")) return true
        return profile.operatingMode != (vendorSessionOpmode ?: 0)
    }

    /** Throwaway-builder probe of one key against the live HAL. Result posts back on the camera thread. */
    fun probeVendorKey(entry: VendorKeyEntry, onResult: (VendorKeyProbeResult) -> Unit) {
        if (!isOnCameraThread()) {
            cameraHandler.post { probeVendorKey(entry, onResult) }
            return
        }
        val device = camera
        val chars = characteristics
        if (device == null || chars == null) {
            onResult(VendorKeyProbeResult(false, "Camera not open"))
            return
        }
        val builder = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        } catch (failure: Exception) {
            onResult(VendorKeyProbeResult(false, "No builder ($failure)"))
            return
        }
        onResult(VendorKeyApply.probe(builder, chars, entry, activePhysicalCameraId))
    }

    /**
     * Rebuild the repeating request so edited REQUEST keys take effect
     * without reopening the camera. SESSION keys still need [reopenCamera].
     */
    fun refreshVendorKeys() {
        if (!isOnCameraThread()) {
            cameraHandler.post { refreshVendorKeys() }
            return
        }
        updateRepeatingRequest()
    }

    /** Full stop/open cycle so edited SESSION keys load before session creation. */
    fun reopenCamera() {
        if (!isOnCameraThread()) {
            cameraHandler.post { reopenCamera() }
            return
        }
        restartCameraForConfigurationChange()
    }

    /** Enable-time GALOSH Vulkan preload, off the capture path. */
    fun prewarmGalosh() {
        val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also { rawDeveloper = it }
        developer.prewarmGalosh()
    }

    fun prewarmWavelet() {
        val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also { rawDeveloper = it }
        developer.prewarmWavelet()
    }

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

    /**
     * Latest preview lens-shading grid for [cameraId], or null when the
     * preview runs on another camera or carries no usable map. RAW video
     * calls this on the UI thread before taking over the camera: the HAL map
     * is session-static, so any recent preview result will do, and each
     * recorded frame resamples it to its own crop as the per-frame
     * `lensShadingMap` triplet (see [CrsLensShading]). A null return
     * records without the map.
     */
    fun snapshotPreviewLensShading(cameraId: String): LensShadingSnapshot? {
        if (selectedCameraId != cameraId) return null
        val map = try {
            latestPreviewResult?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (_: Exception) {
            null
        } ?: return null
        val rows = map.rowCount
        val cols = map.columnCount
        if (rows <= 0 || cols <= 0) return null
        return try {
            val gains = FloatArray(map.gainFactorCount)
            map.copyGainFactors(gains, 0)
            if (gains.size != rows * cols * 4 ||
                gains.any { !it.isFinite() || it < 1f }
            ) {
                return null
            }
            LensShadingSnapshot(rows, cols, ImmutableFloatValues(gains))
        } catch (_: Exception) {
            null
        }
    }

    fun selectLens(cameraId: String) {
        if (!isOnCameraThread()) {
            cameraHandler.post { selectLens(cameraId) }
            return
        }
        if (cameraId == selectedCameraId || cameraId !in rawCameraIds || captureInProgress.get()) return
        selectedCameraId = cameraId
        afRegion = null; aeRegion = null
        updateDevelopFocus(null)
        resetSessionLadder()
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
                // Manual focus takes over the held touch lock (see TouchFocus).
                if (value != null) touchFocus.dropForManualFocus()
            }
            ManualControl.EXPOSURE_COMPENSATION -> exposureCompensation = value?.toInt() ?: 0
        }
        if (control == ManualControl.FOCUS_DISTANCE) publishPeakingActiveIfChanged()
        clampControlsToCamera()
        controlsChanged()
    }

    fun exposureCompensationStops(index: Long): Float {
        val step = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: return 0f
        return index * step.toFloat()
    }

    /**
     * Video handoff: manual photo selections shared with RAW/Direct-Log takes.
     * Called on the UI thread before [stop] takes the camera; reads mirror
     * [manualControlRange] (stale by one frame at worst, same as the chips).
     */
    fun snapshotVideoManual(): VideoManualSnapshot = VideoManualSnapshot(
        iso = selectedIso,
        shutterNanos = selectedExposureNanos,
        wbKelvin = selectedWbKelvin,
        focusDiopters = selectedFocusDistanceDiopters,
        evIndex = exposureCompensation.takeIf { it != 0 },
    )

    /** Metered preview fallback frozen when a lock-on-record switch is on. */
    fun snapshotVideoMeteredIso(): Int = selectedIso ?: lastIso

    fun snapshotVideoMeteredShutter(): Long = selectedExposureNanos ?: lastExposureNanos

    fun snapshotVideoMeteredFocus(): Float? =
        selectedFocusDistanceDiopters
            ?: runCatching { latestPreviewResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) }.getOrNull()

    fun reloadLenses() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::reloadLenses)
            return
        }
        selectedCameraId = null
        resetSessionLadder()
        // Explicit re-discovery re-probes every saved lens, so a rejection
        // recorded earlier (possibly against a half-initialized camera
        // service) must not pin any lens out.
        unopenableOpenIds.clear()
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
        // Null in RAW-only sessions: the repeating request then carries the
        // RAW target alone and the RAW viewfinder is the only display.
        val surface = previewSurface
        val reader = rawReader
        val generation = lifecycleGeneration
        // Queued DNG/JPEG saves must not stop the live RAW streams: their buffers
        // are pre-allocated and bounded, and a 6-frame JPEG burst would otherwise
        // leave the viewfinder without RAW (stale scope graph) for the
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
        // Live RAW scope for every capture mode. Never competes with the ZSL ring:
        // scope-only frames are sampled and closed.
        val useRawHistogram = !useRawZsl && shouldRunRawStream(
            rawHistogramEnabled, rawHistogramDisabledForSession,
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
        val useRawViewfinder = (rawViewfinder != null || bguViewfinder != null) && reader != null && rawRetryReady
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
            // P1 RAW-only scope (intentional): TEMPLATE_ZERO_SHUTTER_LAG,
            // high-speed/offline/extension/concurrent/injection sessions and
            // InputConfiguration reprocessing stay unsupported — this app runs
            // one SESSION_REGULAR RAW+preview session per lens, no YUV/ISP
            // pipelines. The ZSL *intent* below only tells 3A scheduling that
            // these PREVIEW-template frames back a zero-shutter-lag pipeline.
            fun previewRequest(includeRaw: Boolean): CaptureRequest =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                surface?.let { addTarget(it) }
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
                }
                // VF preview applies the same lens-shading correction as saves (zero-copy
                // RGBA16F sidecar), so every repeating RAW request carries the map —
                // not only the ZSL path.
                if (includeRaw) requestLensShadingMap(this)
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
                        // RAW-only sessions have no preview target to interleave
                        // with: every request carries RAW (a targetless request
                        // is illegal).
                        add(previewRequest(includeRaw = surface == null))
                    }
                }
                if (requests.size == 1) {
                    currentSession.setRepeatingRequest(requests.single(), callback, cameraHandler)
                } else {
                    currentSession.setRepeatingBurst(requests, callback, cameraHandler)
                }
            } else {
                // A targetless request is illegal, so a RAW-only session with
                // no RAW consumer (transient backoff) leaves the existing
                // repeating untouched instead of stopping the stream the
                // backoff will resume.
                if (surface != null) {
                    currentSession.setRepeatingRequest(
                        previewRequest(includeRaw = false), callback, cameraHandler
                    )
                }
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
                    rawZslEpochPairs = 0
                    rawZslSlowWindows = 0
                    zslOverflowTracker.reset()
                    scheduleRawZslWatchdog(generation, requestEpoch)
                    publishRawZslStatus(RawZslState.WARMING_UP, "Buffering full-resolution RAW frames")
                } else {
                    // Kept ring, new epoch: the stale watchdog can never fire
                    // (epoch guard), so re-arm for the resumed stream unless
                    // this is a light control push on the live stream (there
                    // the original watchdog is still ticking). Fresh window,
                    // fresh counters; self-guards on rawZslHasFrame, so a
                    // preserved full ring needs no window.
                    if (!light) {
                        rawZslEpochPairs = 0
                        rawZslSlowWindows = 0
                        scheduleRawZslWatchdog(generation, requestEpoch)
                    }
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
                bguViewfinder?.expectFrameInterval(maxOf(shutter, result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L))
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { lastPreviewSensorTimestamp = it }
                if (iso > 0) lastIso = iso
                if (shutter > 0) lastExposureNanos = shutter
                // The VF normalizes against dynamic white first: when it
                // disagrees with the static key the chip was published from,
                // move the chip so both report the same effective depth.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { dynamicWhite ->
                        val bits = whiteLevelBitDepth(dynamicWhite)
                        if (bits != lastWhiteLevelBits) {
                            lastWhiteLevelBits = bits
                            Log.i(LOG_TAG, "Dynamic white level $dynamicWhite; DNG chip now $bits-bit")
                            onInfo(
                                String.format(java.util.Locale.US, "DNG\n%d-bit RAW", bits),
                                lastSensorInfo
                            )
                        }
                    }
                }
                latestPreviewResult = result
                // P0: feed the flash precapture waiter (polled on camera thread).
                result.get(CaptureResult.CONTROL_AE_STATE)?.let { lastAeState = it }
                handlePreviewFaces(result)
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
                touchFocus.onAfState(result.get(CaptureResult.CONTROL_AF_STATE))
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
        // P2/P3: never request an AF mode the HAL did not advertise.
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            coerceAfMode(
                when {
                    manualFocus != null -> CaptureRequest.CONTROL_AF_MODE_OFF
                    touchFocus.active -> CaptureRequest.CONTROL_AF_MODE_AUTO
                    else -> continuousPictureAfMode()
                }
            )
        )
        manualFocus?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )
        // Face-priority regions lose to tap targets: an explicit tap always wins,
        // and clearing the tap hands control back to the tracked face. Regions
        // are harmless where AF/AE run open-loop (manual/PROGRAM sensor drive):
        // the HAL ignores them, so no mode gating is needed here.
        (afRegion ?: faceAfRegion)?.takeIf {
            (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        }?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        val aeRegions = aeRegion?.let { arrayOf(it) }
            ?: faceAeRegion?.let { arrayOf(it) }
            ?: standardAeMeteringRegions()
        if (aeRegions != null && isAeMeteringSupported()) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions)
        }
        applyFaceDetectMode(builder)
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
            // P0: while a flash precapture converges the repeating request
            // meters under the flash AE mode; otherwise plain AE ON.
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                coerceAeMode(flashRepeatingAeModeOverride ?: CaptureRequest.CONTROL_AE_MODE_ON)
            )
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            // Touch-hold AE lock: freeze hardware AE at the held metering point until
            // double-tap / reset-button / retap / manual / lens-switch / stop.
            // CONTROL_AE_LOCK is only meaningful with AE ON; manual/PROGRAM exposure
            // already drives the sensor directly so the lock stays off there.
            builder.set(CaptureRequest.CONTROL_AE_LOCK, touchFocus.aeLockIndefinite)
        }
        val kelvin = selectedWbKelvin
        if (kelvin == null) {
            // P2/P3: AUTO only when advertised; the Kelvin path below owns OFF.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, coerceAwbMode(CaptureRequest.CONTROL_AWB_MODE_AUTO))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, coerceAwbMode(CaptureRequest.CONTROL_AWB_MODE_OFF))
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsForKelvin(kelvin))
        }
        // Repeating preview carries continuous torch assist only. Still
        // requests override this below in applyStillFlash (SINGLE or
        // AE-driven); TORCH never leaks into a still exposure.
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
        // Step 2 vendor REQUEST keys ride every builder (preview, still, HDR,
        // flash, AF) after the standard controls. Individually guarded: a bad
        // key skips itself and never fails capture.
        applyVendorRequestKeys(builder)
    }

    private fun applyVendorRequestKeys(builder: CaptureRequest.Builder) {
        val routeId = selectedCameraId ?: return
        val profile = try {
            vendorKeys(routeId)
        } catch (_: Exception) {
            return
        }
        if (profile.requestEntries().isEmpty()) return
        val chars = characteristics ?: return
        try {
            val report = VendorKeyApply.applyRequestKeys(
                builder, profile, chars, activePhysicalCameraId
            ) { message ->
                Log.i(LOG_TAG, message)
            }
            lastVendorRequestReport = report
            if (report.applied.isNotEmpty()) {
                Log.i(LOG_TAG, "Vendor request keys applied for $routeId: ${report.applied}")
            }
        } catch (_: Exception) {
            // Vendor keys must never break a capture request.
        }
    }

    /** P2/P3: clamp a requested AF mode to what the HAL advertised. */
    private fun coerceAfMode(mode: Int): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return mode
        if (modes.contains(mode)) return mode
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) return CaptureRequest.CONTROL_AF_MODE_OFF
        return modes.firstOrNull() ?: mode
    }

    /** P3: clamp a requested AE mode to what the HAL advertised, else [fallback]. */
    private fun coerceAeMode(mode: Int, fallback: Int = CaptureRequest.CONTROL_AE_MODE_ON): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return mode
        return if (modes.contains(mode)) mode else fallback
    }

    /** P2/P3: clamp a requested AWB mode to what the HAL advertised. */
    private fun coerceAwbMode(mode: Int): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return mode
        if (modes.contains(mode)) return mode
        if (modes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) return CaptureRequest.CONTROL_AWB_MODE_AUTO
        return modes.firstOrNull() ?: mode
    }

    private fun isHardwareLevelLegacy(): Boolean =
        characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    /**
     * Still-capture flash resolution (P0/P3). Called after [applyCameraControls]
     * (and after [applyHdrExposure], which forces manual exposure for brackets)
     * so the builder's AE state is final. Manual exposure fires an unmetered
     * SINGLE; hardware AE uses an advertised flash AE mode with FLASH OFF (the
     * AE unit drives the flash). TORCH is never set here. Also pins the still
     * intent explicitly.
     */
    private fun applyStillFlash(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        if (!isFlashAvailable() || flashStillMode == FlashStillMode.OFF) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            return
        }
        val manualExposure = builder.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF
        val aeModes = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            coerceAeMode(resolveStillAeMode(manualExposure, aeModes))
        )
        builder.set(
            CaptureRequest.FLASH_MODE,
            resolveStillFlashModeForRequest(manualExposure)
        )
    }

    private fun resolveStillAeMode(manualExposure: Boolean, aeModes: IntArray): Int {
        if (manualExposure) return CaptureRequest.CONTROL_AE_MODE_OFF
        return resolveStillFlashAeMode(
            flashStillMode.ordinal,
            aeModes,
            isFlashAvailable()
        )
    }

    private fun resolveStillFlashModeForRequest(manualExposure: Boolean): Int =
        resolveStillFlashMode(flashStillMode.ordinal, manualExposure, isFlashAvailable())

    /**
     * Flash still entry (P0). Flash bypasses the ZSL ring and merge queues:
     * ring frames predate the flash, so a flash press always fires one forward
     * still (metered first under hardware AE, unmetered SINGLE under manual
     * exposure).
     */
    private fun captureSingleWithFlash(
        outputFormat: CaptureFormat,
        orientationSnapshot: Int
    ) {
        if (!beginCapture(outputFormat, requiredSaveSlots = 1, orientationSnapshot = orientationSnapshot)) return
        val manualExposure = selectedIso != null || selectedExposureNanos != null
        val aeModes = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
        if (needsFlashPrecapture(
                manualExposure,
                flashStillMode.ordinal,
                aeModes,
                isFlashAvailable(),
                isHardwareLevelLegacy()
            )
        ) {
            runFlashPrecaptureThenCapture()
        } else {
            captureFrames(1, captureAlreadyStarted = true)
        }
    }

    /**
     * Official precapture flow for AE-driven flash: push the flash AE mode to
     * the repeating request, fire one AE_PRECAPTURE_TRIGGER START, wait for
     * convergence (or timeout), then fire the forward still. Best-effort: any
     * failure degrades to an immediate still instead of wedging the shutter.
     */
    private fun runFlashPrecaptureThenCapture() {
        val device = camera
        val currentSession = session
        val surface = previewSurface
        if (device == null || currentSession == null || surface == null) {
            captureFrames(1, captureAlreadyStarted = true)
            return
        }
        val aeModes = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
        val wantAe = if (flashStillMode == FlashStillMode.ON)
            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        else CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        if (!aeModes.contains(wantAe)) {
            captureFrames(1, captureAlreadyStarted = true)
            return
        }
        val shot = captureSequence.get()
        val generation = lifecycleGeneration
        flashRepeatingAeModeOverride = wantAe
        flashPrecaptureInFlight = true
        updateRepeatingRequest(allowRawZsl = false)
        try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                set(CaptureRequest.CONTROL_AE_MODE, coerceAeMode(wantAe))
                set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
            }.let { currentSession.capture(it.build(), debugCaptureCallback, cameraHandler) }
        } catch (_: Exception) {
            flashRepeatingAeModeOverride = null
            flashPrecaptureInFlight = false
            captureFrames(1, captureAlreadyStarted = true)
            return
        }
        onState("FLASH METERING")
        val deadlineMs = SystemClock.elapsedRealtime() + FLASH_PRECAPTURE_TIMEOUT_MS
        val waiter = object : Runnable {
            override fun run() {
                if (!isCurrent(generation) || shot != captureSequence.get() || !captureInProgress.get()) {
                    flashRepeatingAeModeOverride = null
                    flashPrecaptureInFlight = false
                    return
                }
                val converged = lastAeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    lastAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    lastAeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                if (converged || SystemClock.elapsedRealtime() >= deadlineMs) {
                    flashRepeatingAeModeOverride = null
                    flashPrecaptureInFlight = false
                    if (!converged) Log.w(LOG_TAG, "Flash precapture timed out; firing best-effort still")
                    captureFrames(1, captureAlreadyStarted = true)
                } else {
                    cameraHandler.postDelayed(this, FLASH_PRECAPTURE_POLL_MS)
                }
            }
        }
        cameraHandler.postDelayed(waiter, FLASH_PRECAPTURE_POLL_MS)
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
            touchFocus.onAfState(result.get(CaptureResult.CONTROL_AF_STATE))
        }
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
        // Idle continuous-AF trace (transitions only): without this, a
        // "blurry VF" report with no taps is undecidable — the tap trace
        // covers scans, but idle PASSIVE_* states were previously invisible.
        if (!touchFocus.active &&
            (afState != lastLoggedIdleAfState || afMode != lastLoggedIdleAfMode)
        ) {
            lastLoggedIdleAfState = afState
            lastLoggedIdleAfMode = afMode
            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            Log.i(
                LOG_TAG,
                "Idle AF ${Camera2Names.afStateName(afState)} mode=${Camera2Names.afModeName(afMode)}" +
                    (focusDistance?.let {
                        " fd=${String.format(java.util.Locale.US, "%.2f", it)}D"
                    } ?: "")
            )
        }
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val aeTrigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val oisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        onDebugState(
            "AF_MODE  ${Camera2Names.afModeName(afMode)}\n" +
                "AF_TRIGGER ${Camera2Names.afTriggerName(afTrigger)}\n" +
                "AF_STATE ${Camera2Names.afStateName(afState)}\n" +
                "AF_POLICY TOUCH_HOLD/CONT_PICTURE  CAMERA_SEQ ${if (cameraCaptureSequenceActive) "BUSY" else "IDLE"}\n" +
                "SAVE_QUEUE ${pendingFrameSaveCount.get()}/${if (captureFormat.includesJpeg) MAX_IN_FLIGHT_JPEG_SAVES else MAX_IN_FLIGHT_DNG_SAVES}\n" +
                "AE_MODE  ${Camera2Names.aeModeName(aeMode)}\n" +
                "AE_TRIGGER ${Camera2Names.aeTriggerName(aeTrigger)}\n" +
                "AE_STATE ${Camera2Names.aeStateName(aeState)}\n" +
                "OIS_MODE ${Camera2Names.oisModeName(oisMode)}\n" +
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
        val vf = activeVf() ?: return
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
            val path = if (stats.gpu) "GPU" else "NEON"
            "VF: ${stats.vfWidth}×${stats.vfHeight} $path ${stats.engine.shortLabel()} ⇄ $modeText"
        } else "VF: --"
        val rawText = if (stats.rawWidth > 0 && stats.rawHeight > 0) {
            "RAW: ${stats.rawWidth}×${stats.rawHeight}"
        } else "RAW: --"
        val devBits = vfOverlayEffectBits(stats.jpeg, jpegOutputSettings, denoiseSettings.wavelet.enabled)
        val glLabel = if (stats.glActive) "RAW" else "FALLBACK"
        val effects = if (!stats.glActive || devBits.isNotEmpty()) {
            "Yes ($glLabel${if (devBits.isNotEmpty()) " +" + devBits.joinToString("+") else ""})"
        } else "No"
        // A dead forced engine shows as starved (VfEngineMode contract), never
        // as a silently black VF.
        val starveText = if (stats.starved) " STARVED" else ""
        return "$fpsText$starveText $msText\n$vfText · $rawText\nEffects: $effects"
    }

    /** One line per session so logcat shows the idle AF contract for diagnosis. */
    private fun logAfCapabilities() {
        val c = characteristics ?: return
        val modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        Log.i(
            LOG_TAG,
            "AF caps modes=[${modes.joinToString(",") { Camera2Names.afModeName(it) }}] " +
                "maxAfRegions=$maxAf idle=${Camera2Names.afModeName(continuousPictureAfMode())}"
        )
    }

    fun onDeviceOrientationChanged(degrees: Int) {
        deviceOrientationDegrees = degrees
    }

    /**
     * Re-emits the last stills info labels ([onInfo]): bit depth from the
     * current white level plus the cached sensor line. MainActivity calls
     * this when leaving video mode — video overwrites the info chips and
     * nothing else republishes them (open and dynamic-white paths only fire
     * on their own events, and any publication while still in video mode is
     * routed to the video labels). No-op before the first camera open.
     */
    fun republishInfo() {
        if (lastSensorInfo.isEmpty()) return
        onInfo(
            String.format(java.util.Locale.US, "DNG\n%d-bit RAW", lastWhiteLevelBits),
            lastSensorInfo
        )
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
        publishRawScopeIfDue(image, c)
        val buffer = rawZslBuffer ?: run {
            RawImageOwnership.release(image)
            return false
        }
        buffer.add(image, result, motionRadiansPerSecond, timestampNanos, approximate)
        rawZslEpochPairs++
        zslOverflowTracker.onPaired()
        fruitlessCooldowns = 0
        publishRawZslBufferState()
        return true
    }

    /**
     * Grace admission for a ZSL image whose capture result
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
     * Sampling a full-resolution Bayer buffer is CPU work.  A live scope only needs a few
     * updates per second, so never perform it for hidden UI and never let it run at RAW-stream
     * frame rate on the camera callback thread.  A saved frame is sampled immediately.
     * The AgX curve LUT tracks the live JPEG sliders; a 1024-entry rebuild per sample is
     * negligible next to the full-resolution Bayer scan.
     */
    private fun publishRawScopeIfDue(
        image: Image,
        cameraCharacteristics: CameraCharacteristics,
        force: Boolean = false
    ) {
        if (!rawHistogramEnabled) return
        val now = SystemClock.elapsedRealtime()
        // The MIN_VALUE sentinel means "sample immediately"; see RawHistogramThrottle.
        if (!RawHistogramThrottle.shouldSample(now, lastRawHistogramSampleMs, RAW_HISTOGRAM_INTERVAL_MS, force)) return
        lastRawHistogramSampleMs = now
        val lutStartNs = System.nanoTime()
        val lut = if (scopeAgxApplied) AgxDisplayTransform.buildScopeLut(jpegOutputSettings) else null
        // JPEG scope predicts the saved file: the same adaptive EV develop
        // applies pre-tonemap. DNG scopes stay unexposed linear (lut null
        // ignores the gain), matching the linear DNG.
        val scopeEv = if (scopeAgxApplied) liveScopeExposureEv() else 0.0
        val scanStartNs = System.nanoTime()
        sampleSafely("scope") {
            if (scopeMode == ScopeMode.WAVEFORM) {
                RawWaveformSampler.sample(image, cameraCharacteristics, lut, scopeEv)?.let(onRawWaveform)
            } else {
                RawHistogramSampler.sample(image, cameraCharacteristics, lut, scopeEv)?.let(onRawHistogram)
            }
        }
        noteScopeSample(now, scanStartNs - lutStartNs, System.nanoTime() - scanStartNs)
    }

    /**
     * Scope cost telemetry, same 5 s average discipline as the NEON sampler
     * line: LUT build vs Bayer scan split per scope mode, so a slow scope
     * (waveform vs histogram, AgX LUT on/off) is attributable in field logs.
     * Camera thread only.
     */
    private fun noteScopeSample(nowMs: Long, lutNs: Long, scanNs: Long) {
        scopeLutAccumNs += lutNs
        scopeScanAccumNs += scanNs
        scopeSamples++
        if (nowMs - lastScopeLogMs < SCOPE_LOG_INTERVAL_MS || scopeSamples == 0) return
        lastScopeLogMs = nowMs
        val lutMs = scopeLutAccumNs / 1_000_000f / scopeSamples
        val scanMs = scopeScanAccumNs / 1_000_000f / scopeSamples
        Log.i(LOG_TAG, String.format(java.util.Locale.US,
            "Scope sample: avg lut=%.1fms scan=%.1fms over %d frames (%s)",
            lutMs, scanMs, scopeSamples, scopeMode.name.lowercase()))
        scopeLutAccumNs = 0L
        scopeScanAccumNs = 0L
        scopeSamples = 0
    }

    /**
     * Live adaptive EV the VF is currently rendering with — the same
     * scene-referred correction the save's own dense meter converges to (VF EV
     * == save EV by construction), so the JPEG scope predicts the saved file
     * instead of metering at 0 EV. Zero when the VF isn't rendering JPEG
     * (user-forced RAW VF mode): the scope keeps the unexposed AgX
     * approximation until the saved-frame snapshot lands exact. Snapshot is
     * a volatile-field read, safe from the camera thread at 4 Hz.
     */
    private fun liveScopeExposureEv(): Double {
        val stats = try {
            activeVf()?.snapshot()
        } catch (_: Exception) {
            null
        } ?: return 0.0
        if (!stats.jpeg) return 0.0
        val ev = stats.exposureEv
        if (!ev.isFinite()) return 0.0
        return ev.toDouble().coerceIn(-8.0, 8.0)
    }

    /**
     * Saved-frame JPEG scope: samples the developed bitmap (the exact pixels
     * the JPEG is encoded from), so the scope matches the saved JPEG the way
     * the linear RAW scope matches the saved DNG. Runs on the saver thread
     * after a successful encode, before the bitmap is recycled; delivery
     * reuses the live scope callbacks (UI-thread downstream).
     */
    private fun publishDevelopedScope(bitmap: Bitmap) {
        if (!rawHistogramEnabled) return
        sampleSafely("developed-scope") {
            val scope = DevelopedScopeSampler.sampleBitmap(bitmap) ?: return@sampleSafely
            onRawHistogram(scope.histogram)
            onRawWaveform(scope.waveform)
        }
    }

    /**
     * Saved-frame JXL scope: the half-float developed frame downsampled to
     * 8-bit for the overlay only (the saved JXL keeps full precision), so
     * the scope matches the saved still the way the bitmap scope matches
     * the saved JPEG. Skipped entirely unless the scope overlay is enabled,
     * mirroring [publishDevelopedScope].
     */
    private fun publishDevelopedJxlScope(developed: DevelopedJxl) {
        if (!rawHistogramEnabled) return
        sampleSafely("developed-jxl-scope") {
            val rgba8 = JxlPixelPack.halfRgbaToScopeRgba8(
                developed.halfRgba, developed.width, developed.height
            )
            val bitmap = Bitmap.createBitmap(
                developed.width, developed.height, Bitmap.Config.ARGB_8888
            )
            try {
                bitmap.copyPixelsFromBuffer(rgba8)
                val scope = DevelopedScopeSampler.sampleBitmap(bitmap) ?: return@sampleSafely
                onRawHistogram(scope.histogram)
                onRawWaveform(scope.waveform)
            } finally {
                bitmap.recycle()
            }
        }
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
        if (activeDualRaw) pendingDualRawFrames += PendingHdrFrame(image,result)
        else if (topupActive) accumulateTopupFrame(image, result)
        else if (activeHdrBracket) pendingHdrFrames += PendingHdrFrame(image, result)
        else saveRawFrame(image, result, null)
        // Serialized still chain: each pair completion submits the next prebuilt
        // still, keeping a single frame in flight for HALs whose gralloc pool
        // cannot survive a pipelined N-frame burst (log-proven on MediaTek).
        if (remaining > 0) {
            if (!activeDualRaw && streamCapture == null) submitNextStill()
            return
        }
        // The RAW Image and its exact TotalCaptureResult are now owned by the bounded writer.
        // Reopen the shutter immediately; do not make capture latency depend on development.
        if (topupActive) finishTopupBurst()
        else {
            if (activeDualRaw) {
                activeDualRaw = false
                val pair = pendingDualRawFrames.toList()
                pendingDualRawFrames.clear()
                saveDualRaw(pair)
            }
            if (activeHdrBracket) {
                activeHdrBracket = false
                val saveEachBracket = activeHdrSaveEachBracket
                val saveDebugFrames = activeHdrSaveDebugFrames
                activeHdrSaveEachBracket = false
                activeHdrSaveDebugFrames = false
                activeHdrStops = 2
                activeHdrFrameEvs = intArrayOf(-2, 0, 2)
                saveHdrBracket(pendingHdrFrames.toList(), saveEachBracket, saveDebugFrames)
                pendingHdrFrames.clear()
            }
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    private fun saveDualRaw(frames: List<PendingHdrFrame>) {
        val c = characteristics
        if (c == null || frames.size != 2) {
            frames.forEach { RawImageOwnership.release(it.image) }
            onState("RAW HDR TEST PAIR MISSING")
            return
        }
        val orientation = activeOutputOrientation
        val captureTime = System.currentTimeMillis()
        val gps = gpsLocation()
        val generation = lifecycleGeneration
        val ordered = frames.sortedBy { (it.result.request.tag as? CaptureTag)?.frameNumber ?: Int.MAX_VALUE }
        pendingSaveCount.incrementAndGet()
        pendingFrameSaveCount.addAndGet(2)
        val owner = CloseOnceOwner(frames) { RawImageOwnership.release(it.image) }
        val released = AutoCloseable { try { owner.close() } finally { postSaveCompletion(2) } }
        val job = OwnedCaptureJob(released) {
            try {
                DualRawSaver.save(context,c,ordered[0].image,ordered[0].result,
                    ordered[1].image,ordered[1].result,orientation,captureTime,gps,
                    selectedCameraId ?: "unknown")
                cameraHandler.post { if (isCurrent(generation)) onState("RAW HDR TEST DNG SAVED") }
            } catch (failure: Exception) {
                Log.e("RawLensDualRaw","Merge/save failed",failure)
                cameraHandler.post { if (isCurrent(generation)) onState("RAW HDR TEST SAVE ERROR") }
            } catch (failure: OutOfMemoryError) {
                Log.e("RawLensDualRaw","Merge ran out of memory",failure)
                cameraHandler.post { if (isCurrent(generation)) onState("RAW HDR TEST MEMORY LIMIT") }
            }
        }
        try { writer.execute(job) } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun(); onState("QUEUE FULL")
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
                                gps = captureGps,
                                dngCompression = dngCompression()
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
                    val unpacked = RawPackedUnpacker.unpackFrameNormalized(
                        plane.buffer, metadata.imageWidth, metadata.imageHeight,
                        plane.rowStride, plane.pixelStride, frame.image.format, normalization,
                        geometry.sensorOriginX, geometry.sensorOriginY,
                        geometry.processingCrop, ByteOrder.nativeOrder())
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
                val exposures = snapshots.map {
                    it.third.exposureTimeNanos.toDouble() * it.third.sensitivityIso
                }
                // Sequential-DCG trio (0, -2, 0 at fixed ISO) takes the
                // lightweight local-alignment path; every other bracket keeps
                // the legacy translation + FlowNet + Wiener route below.
                val seqFrames = snapshots.map { it.third }
                val seqTrio = HdrSeqMerge.isTrio(seqFrames)
                var referenceIndex = HdrBracketConfig.referenceIndex(exposures)
                var evRelative = HdrBracketConfig.evRelativeToReference(
                    exposures.map { it as Double? }, referenceIndex)
                val mergedRaw: UnpackedRawCfa
                val dropped = ArrayList<Pair<Int, Double>>()
                if (seqTrio) {
                    val seq = HdrSeqMerge.mergeTrio(seqFrames)
                    referenceIndex = seq.refIndex
                    evRelative = HdrBracketConfig.evRelativeToReference(
                        exposures.map { it as Double? }, referenceIndex)
                    mergedRaw = seq.cfa
                    for (i in seqFrames.indices) {
                        val ev = evRelative.getOrNull(i)
                        val evTag = if (ev == null) "?" else "%+.1f".format(ev)
                        val verdict = if (i == referenceIndex) "Reference(sharpest 0EV)" else "Accepted"
                        Log.i(LOG_TAG, "SEQ " + HdrBracketConfig.frameLogLine(
                            i, ev?.let { kotlin.math.round(it).toInt() } ?: 0, verdict,
                            "ev=$evTag", isReference = i == referenceIndex))
                    }
                    Log.i(LOG_TAG, "SEQ timing align=${seq.timings.alignMs}ms " +
                        "merge=${seq.timings.mergeMs}ms rescue=${seq.timings.rescueMs}ms " +
                        "kept=${"%.1f".format(seq.keptFraction * 100)}% " +
                        "rescued=${"%.1f".format(seq.rescuedFraction * 100)}% " +
                        "${mergedRaw.width}x${mergedRaw.height}")
                } else {
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
                        // A non-null coarse shift must not hide a rejected
                        // dense refinement: retain the measured subpixel part.
                        val flow = HdrBracketAligner.refinedFlow(shift, dense)
                        // No field at all; merge the frame unwarped (identity)
                        // rather than aborting the bracket.
                        if (flow != null) item.third.copy(flow = flow) else item.third
                    }
                }
                val tMerge0 = SystemClock.elapsedRealtime()
                // The legacy frequency-domain merge is the production path.
                val legacyOptions = HdrRawMerge.Options(alternateDropDcRejectFrac = 0.9)
                val onDropped: (Int, HdrTileDeghost.TileStats) -> Unit =
                    { index, stats -> dropped.add(index to stats.dcRejectFrac) }
                mergedRaw = HdrRawMerge.merge(aligned, referenceIndex, legacyOptions, onDropped)
                // Raymerge-style F-list: one line per frame with its EV,
                // reference anchor, and merge verdict (dropped = fully ghosted
                // alternate collapsed to the reference during deghost).
                for (i in aligned.indices) {
                    val ev = evRelative.getOrNull(i)
                    val evTag = if (ev == null) "?" else "%+.1f".format(ev)
                    val verdict = if (i == referenceIndex) "Reference"
                    else dropped.find { it.first == i }?.let {
                        "DROPPED (ghost reject=${"%.0f".format(it.second * 100)}%)"
                    } ?: "Accepted"
                    Log.i(LOG_TAG, "HDR " + HdrBracketConfig.frameLogLine(
                        i, ev?.let { kotlin.math.round(it).toInt() } ?: 0, verdict,
                        "ev=$evTag", isReference = i == referenceIndex))
                }
                Log.i(LOG_TAG, "HDR timing align=${tMerge0 - tAlign0}ms " +
                    "merge=${SystemClock.elapsedRealtime() - tMerge0}ms " +
                    "frames=${aligned.size} dropped=${dropped.size} " +
                    "${reference.cfa.width}x${reference.cfa.height}")
                }
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
                // Shortest-frame normalization compensation, shared by the JPEG
                // develop and the DNG BaselineExposure tag below.
                val bracketExposures = snapshots.map {
                    it.third.exposureTimeNanos.toDouble() * it.third.sensitivityIso
                }.sorted()
                val displayEv = kotlin.math.ln(
                    bracketExposures[bracketExposures.size / 2] / bracketExposures.first()
                ) / kotlin.math.ln(2.0)
                if (outputFormat.includesDng || saveDebugFrames) {
                    val evList = evRelative.joinToString(",") {
                        if (it == null) "?" else "%+.1f".format(it)
                    }
                    val dropList = if (dropped.isEmpty()) "none"
                    else dropped.joinToString(",") { (index, frac) ->
                        "F$index(reject=${"%.0f".format(frac * 100)}%)"
                    }
                    referenceDng = DngSaver(context).saveMerged(
                        effectiveMerged, snapshots[referenceIndex].first, captureId, gps = captureGps,
                        baselineExposureEv = displayEv, dngCompression = dngCompression()
                    )
                }
                if (outputFormat.includesJpeg) {
                    // The HDR develop caps the shoulder (see developMergedJpeg):
                    // the meter must use the capped value so it sees what AgX sees.
                    if (activeDevelopedFormat == DevelopedFormat.JXL) {
                        val developed = developer.developMergedJxl(effectiveMerged, snapshots[referenceIndex].first,
                            RawDevelopmentSettings(denoise = denoise, exposureEv = displayEv,
                                hdrAdaptiveTuning = HdrAdaptiveTuning(
                                    highlightShoulder = minOf(
                                        outputSettings.highlightShoulder,
                                        RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER
                                    ),
                                    focusX = developFocusView?.first,
                                    focusY = developFocusView?.second
                                )), look = outputSettings, jxl = activeJxlOutputSettings)
                        val name = JxlSaver(context).save(developed,
                            snapshots[referenceIndex].first, snapshots[referenceIndex].second,
                            captureTimeMillis = captureId,
                            typeSuffix = CaptureFileNames.TYPE_HDR, gps = captureGps)
                        publishDevelopedJxlScope(developed)
                        reportSaveState(if (referenceDng != null) "HDR MERGED+DNG • JXL" else "HDR MERGED • JXL")
                    } else {
                        val developed = developer.developMergedJpeg(effectiveMerged, snapshots[referenceIndex].first,
                            RawDevelopmentSettings(denoise = denoise, exposureEv = displayEv,
                                hdrAdaptiveTuning = HdrAdaptiveTuning(
                                    highlightShoulder = minOf(
                                        outputSettings.highlightShoulder,
                                        RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER
                                    ),
                                    focusX = developFocusView?.first,
                                    focusY = developFocusView?.second
                                )), outputSettings)
                        try {
                            val name = JpegSaver(context).save(developed, snapshots[referenceIndex].first,
                                snapshots[referenceIndex].second, captureTimeMillis = captureId,
                                typeSuffix = CaptureFileNames.TYPE_HDR, gps = captureGps)
                            publishDevelopedScope(developed.bitmap)
                            reportSaveState(if (referenceDng != null) "HDR MERGED+DNG" else "HDR MERGED")
                        } finally {
                            recycleUltraHdrGainmapContents(developed)
                            developed.bitmap.recycle()
                        }
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
        publishRawScopeIfDue(image, c, force = true)
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
        val captureGaloshSettings = activeGaloshSettings
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
                        galosh = captureGaloshSettings,
                        adaptiveExposureStrength = adaptiveExposureStrength,
                        sharedAdaptiveExposure = sharedAdaptiveExposure,
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, developFocusView
                        ),
                        // ETTR frames are already optimally bright: develop caps the
                        // adaptive lift at the residual to the ETTR target instead of
                        // pushing bright skies past white.
                        ettrHeadroomEv = if (ettrCaptureActive()) ettrSettings.headroomEv else null
                    )
                    // Denoise-once: at most one stage runs per capture. Galosh
                    // wins when enabled and AI is skipped for that capture;
                    // otherwise AI runs as before. The winning CFA is shared
                    // by the denoised-DNG write and the JPEG; null means the
                    // legacy paths below run unchanged.
                    val galoshCfa: UnpackedRawCfa? = if (captureGaloshSettings.enabled &&
                        (outputFormat.includesDng || outputFormat.includesJpeg)
                    ) {
                        val galoshPlane = image.planes.singleOrNull()?.buffer
                        if (galoshPlane != null) {
                            developer.galoshCaptureCfa(galoshPlane, frameMetadata, devSettings)
                        } else null
                    } else null
                    val aiCfa: UnpackedRawCfa? = if (!captureGaloshSettings.enabled &&
                        captureDenoiseSettings.aiEnabled &&
                        (outputFormat.includesDng || outputFormat.includesJpeg)
                    ) {
                        val aiPlane = image.planes.singleOrNull()?.buffer
                        if (aiPlane != null) {
                            developer.denoiseCaptureCfa(aiPlane, frameMetadata, devSettings)
                        } else null
                    } else null
                    val denCfa: UnpackedRawCfa? = galoshCfa ?: aiCfa
                    fun saveOriginalDng(): String? = try {
                        DngSaver(context).save(
                            image, c, result, orientation, dngMetadataOverrides(selectedCameraId),
                            frameMetadata, dngWriterBackend(), captureId = captureTimeMillis,
                            gps = captureGps, fileNameSuffix = fileNameSuffix,
                            subfolder = subfolder, dngCompression = dngCompression()
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
                    if (galoshCfa != null) {
                        if (outputFormat.includesDng) {
                            try {
                                dngName = DngSaver(context).saveGaloshDenoised(
                                    galoshCfa, frameMetadata, captureId = captureTimeMillis,
                                    fileNameSuffix = fileNameSuffix, gps = captureGps,
                                    subfolder = subfolder
                                )
                                Log.i(
                                    LOG_TAG,
                                    "GALOSH DNG saved name=$dngName pendingSaves=${pendingSaveCount.get()}"
                                )
                            } catch (failure: Exception) {
                                dngFailure = failure
                                Log.e(LOG_TAG, "GALOSH DNG save failed", failure)
                            }
                            if (captureGaloshSettings.saveOriginalDng) {
                                val ogName = saveOriginalDng()
                                if (dngName == null) dngName = ogName
                            }
                        }
                    } else if (aiCfa != null) {
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
                                    if (galoshCfa != null) " galoshDenoised"
                                    else if (aiCfa != null) " aiDenoised" else ""
                            )
                            if (activeDevelopedFormat == DevelopedFormat.JXL) {
                                val developed = if (denCfa != null) {
                                    developer.developCfaJxl(
                                        denCfa, frameMetadata,
                                        settings = devSettings,
                                        look = outputSettings,
                                        jxl = activeJxlOutputSettings
                                    )
                                } else {
                                    val rawPlane = image.planes.singleOrNull()?.buffer
                                        ?: throw UnsupportedOperationException("RAW image must have one plane")
                                    developer.developJxl(
                                        rawPlane, frameMetadata,
                                        settings = devSettings,
                                        look = outputSettings,
                                        jxl = activeJxlOutputSettings
                                    )
                                }
                                jpegName = JxlSaver(context).save(
                                    developed, frameMetadata, result,
                                    captureTimeMillis = captureTimeMillis,
                                    typeSuffix = fileNameSuffix,
                                    gps = captureGps,
                                    subfolder = subfolder
                                )
                                publishDevelopedJxlScope(developed)
                                Log.i(
                                    LOG_TAG,
                                    "Developed JXL saved name=$jpegName " +
                                        "pendingSaves=${pendingSaveCount.get()}"
                                )
                            } else {
                                val developed = if (denCfa != null) {
                                    developer.developCfaJpeg(
                                        denCfa, frameMetadata,
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
                                    publishDevelopedScope(developed.bitmap)
                                    Log.i(
                                        LOG_TAG,
                                        "Developed JPEG saved name=$jpegName " +
                                            "pendingSaves=${pendingSaveCount.get()}"
                                    )
                                } finally {
                                    recycleUltraHdrGainmapContents(developed)
                                    developed.bitmap.recycle()
                                }
                            }
                        } catch (failure: Exception) {
                            jpegFailure = failure
                            Log.e(LOG_TAG, "RAW developed still development/save failed", failure)
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
                try {
                    JpegProcessingService.stop(context)
                } catch (failure: RuntimeException) {
                    Log.e(LOG_TAG, "Could not stop JPEG processing service", failure)
                }
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
        val developedToken = activeDevelopedFormat.token
        val outcome = StatusText.saveOutcome(
            dngSaved = dngName != null,
            jpegSaved = jpegName != null,
            failed = failed,
            developed = developedToken
        )
        if (failed && jpegName == null && dngName == null) return outcome
        // Keep at most the first short label segment (e.g. "ZSL 1/3"); drop the rest.
        val shortLabel = label.split("•").getOrNull(1)?.trim()?.take(12)?.let { " • $it" }.orEmpty()
        val failedSuffix = when {
            jpegFailure != null && dngFailure != null -> " • $developedToken+DNG ERR"
            jpegFailure != null -> " • $developedToken ERR"
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
        activeDualRaw = false
        pendingDualRawFrames.forEach { RawImageOwnership.release(it.image) }
        pendingDualRawFrames.clear()
        if (activeHdrBracket) {
            activeHdrBracket = false
            activeHdrSaveEachBracket = false
            activeHdrSaveDebugFrames = false
            activeHdrStops = 2
            activeHdrFrameEvs = intArrayOf(-2, 0, 2)
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
        // and invite futile presses (QUEUE FULL). Gate on the full burst —
        // except merges, which save one artifact (one slot, like admission).
        val hasCapacity = if (rawZslRequested) {
            hasProcessingCapacity(
                availabilitySlotCountFor(
                    hdrPlusSettings,
                    rawZslFrameCount, captureFormat
                ),
                captureFormat
            )
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
            // Preserve surviving pre-roll (partial takes, forward singles):
            // the stream refills around it instead of from zero. Empty ring
            // takes the normal clearing path with its watchdog window.
            updateRepeatingRequest(
                allowRawZsl = true,
                preserveRawZslBuffer = (rawZslBuffer?.size ?: 0) > 0
            )
            return
        }
        if (rawViewfinderStreaming || rawZslStreaming || rawHistogramStreaming || ettrStreaming || programStreaming) return
        // If the ring cannot fit, the viewfinder remains a sample-and-release stream.
        val wantHistogram = rawHistogramEnabled &&
            !rawHistogramDisabledForSession
        val wantEttr = ettrSettings.enabled && ettrModeAvailable()
        val wantProgram = programCustomActive()
        if (!wantHistogram && !wantEttr && !wantProgram && rawViewfinder == null && bguViewfinder == null) return
        updateRepeatingRequest(allowRawZsl = zslRestartFitsReader())
    }

    /**
     * Ring depth for a RAW size: the full configured selection (up to
     * [MAX_ZSL_FRAMES]) for any non-empty size, never a memory-derived
     * subset — shrinking it would silently drop requested ZSL coverage.
     * The ImageReader is sized with pairing headroom on top of the ring
     * (see [ZSL_PAIR_WINDOW_SLOTS]); [clampZslCapacityForReader] applies
     * the reader limit.
     */
    private fun calculateRawZslCapacity(rawSize: Size): Int =
        if (rawSize.width.toLong() * rawSize.height.toLong() > 0L) rawZslFrameCount else 0

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
            "RAW ZSL FPS target=$RAW_ZSL_TARGET_FPS " +
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
            if (!isCurrent(generation) || requestEpoch != rawZslRequestEpoch ||
                !rawZslStreaming || rawZslHasFrame
            ) return@Runnable
            if (rawZslEpochPairs <= 0) {
                // Zero pairs in the whole startup window: same never-productive
                // signature as a stillborn storm — burst-mode, not blind retry.
                rawZslStreamBroken = true
                Log.w(LOG_TAG, "ZSL repeating stream never productive; burst-mode on")
                cooldownRawZslForRetry("No paired RAW frame arrived within ${timeoutMs}ms")
                return@Runnable
            }
            if (rawZslSlowWindows < ZSL_SLOW_WINDOW_EXTENSIONS) {
                // Productive but too slow to fill (reader pressure, save drain):
                // keep the stream and its partial ring and re-arm for another
                // window. The counter resets, so a wedged stream (zero NEW
                // pairs) still latches on the next window.
                rawZslSlowWindows++
                Log.w(
                    LOG_TAG,
                    "ZSL refill slow (${rawZslEpochPairs} pairs in ${timeoutMs}ms); " +
                        "extending window ${rawZslSlowWindows}/$ZSL_SLOW_WINDOW_EXTENSIONS"
                )
                rawZslEpochPairs = 0
                scheduleRawZslWatchdog(generation, requestEpoch)
                return@Runnable
            }
            // Repeatedly slow: transient cooldown (retryable, per-shot restart
            // re-attempts), never the session-death latch.
            rawZslSlowWindows = 0
            rawZslEpochPairs = 0
            cooldownRawZslForRetry(
                "ZSL refill too slow (${timeoutMs}ms x ${ZSL_SLOW_WINDOW_EXTENSIONS + 1} windows)"
            )
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

    /**
     * Push the WYSIWYG render state to the VF. Both tonemaps share one RAW stream, so a
     * mode switch is a uniform flip on the next sampled frame — no session rebuild, no
     * buffer drop, no flicker. Must be called on the camera thread.
     */
    private fun pushVfRenderState() {
        val jpeg = !vfPreviewMode.resolve(captureFormat)
        val strength = adaptivePreviewStrength(captureExposureMode, jpegOutputSettings)
        rawViewfinder?.setRenderJpeg(jpeg)
        rawViewfinder?.setAgx(jpegOutputSettings)
        rawViewfinder?.setPreviewExposureStrength(strength)
        bguViewfinder?.setRenderJpeg(jpeg)
        bguViewfinder?.setAgx(jpegOutputSettings)
        bguViewfinder?.setPreviewExposureStrength(strength)
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
        if (validated != vfTargetLongEdge) {
            // Blur diagnosis: who/when the VF resolution moved (user button,
            // persisted pref at startup, or any future governor).
            Log.i(LOG_TAG, "VF target $vfTargetLongEdge -> ${validated}px")
        }
        vfTargetLongEdge = validated
        rawViewfinder?.setTargetLongEdge(validated)
        bguViewfinder?.setTargetLongEdge(validated)
    }

    /**
     * RAW VF engine override (overlay tap). A volatile flip on the viewfinder,
     * so no stream rebuild; the debug line refreshes immediately. Safe from any
     * thread.
     */
    fun setVfEngineMode(mode: VfEngineMode) {
        vfGlobalMode = mode
        notifyVfRoute()
        cameraHandler.post { publishRawVfDebug(force = true) }
    }

    /** Overlay-tap cycle AUTO -> GPU -> CPU -> BGU -> BGU_CPU; returns the new mode for persistence. */
    fun cycleVfEngineMode(): VfEngineMode {
        vfGlobalMode = vfGlobalMode.next()
        notifyVfRoute()
        cameraHandler.post { publishRawVfDebug(force = true) }
        return vfGlobalMode
    }

    fun vfEngineMode(): VfEngineMode = vfGlobalMode

    /**
     * Durable session fallback, reserved for a rejected stream combination that
     * can never succeed. Everything transient (overflow storms, watchdog misses,
     * isolated repeating failures) goes through [cooldownRawZslForRetry] so
     * every later press re-attempts ZSL per-shot.
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

    /** A rejected scope-only RAW stream must not wedge the viewfinder. Hold the last
     * scope frame and keep the session alive. Re-enabled on camera reopen. */
    private fun disableRawHistogramForSession(reason: String) {
        rawHistogramDisabledForSession = true
        rawHistogramStreaming = false
        updateRepeatingRequest(preserveRawZslBuffer = true)
        Log.w(LOG_TAG, "RAW histogram stream unavailable: $reason")
        onState("HISTO RAW WAIT")
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
        // No ring clear: pre-forward history stays eligible (age-gated) so
        // the next ZSL press reuses it. In-flight pairing self-cleans via
        // the epoch bump (mismatched pairs release, stale timeouts no-op
        // once rawZslStreaming is false).
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
        // The ring survives capture end: remaining frames (partial takes,
        // untouched pre-roll around forward singles) stay eligible for the
        // next press instead of forcing a refill from zero. Only teardown,
        // session rebuild, ZSL-off and outage paths clear it explicitly.
        val buffered = rawZslBuffer?.size ?: 0
        rawZslHasFrame = rawZslCapacity > 0 && buffered >= rawZslCapacity
        rawZslReportedSize = buffered
    }

    private fun closeUnmatchedRawImages() {
        pendingImages.keys.toList().forEach { timestamp ->
            if (!pendingResults.containsKey(timestamp) && !pendingZslResults.containsKey(timestamp)) {
                pendingImages.remove(timestamp)?.let(RawImageOwnership::release)
                pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
            }
        }
    }

    private fun publishRawZslStatus(
        state: RawZslState? = null,
        detail: String? = null
    ) {
        // Future SR hook: SR Vulkan capability probe here (VulkanHub.open +
        // rawsr shader listing -> rawSrCapability; re-add srAvailable/srBusy).
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
        val enriched = status.copy(bufferedFrames = rawZslBuffer?.size ?: 0,
            hdrPlusBusy = activeHdrPlusSettings.enabled && pendingSaveCount.get() > 0,
            mfsrBusy = activeMfsrSettings.enabled && pendingSaveCount.get() > 0)
        if (enriched != lastRawZslStatus) {
            lastRawZslStatus = enriched
            onRawZslStatus(enriched)
        }
    }

    /** True while the camera session is up; read by background citizenship audits. */
    fun isRunning(): Boolean = running

    fun stop() {
        rawViewfinder?.invalidateSession()
        bguViewfinder?.invalidateSession()
        rawViewfinderStreaming = false
        synchronized(cameraStateLock) {
            if (!running && !opening && camera == null) return
            running = false
            opening = false
            lifecycleGeneration++
            touchFocus.resetForStop()
            publishPeakingActiveIfChanged()
            touchFocus.cancelTimers()
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
        clearFaceState()
    }

    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        cameraRouteCache.clear()
        rawViewfinder?.onStarvation = null
        bguViewfinder?.onStarvation = null
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
        // Future SR hook: close SR-owned resources (SR-confined developer +
        // merge processor) on the SR thread here.
        writer.shutdown()
        // Plain DNG jobs touch neither EGL nor the shared developer (AI/JPEG stay
        // on the serial writer), so no ordered teardown is needed here; queued
        // DNGs simply finish or are dropped with the process.
        dngWriter.shutdown()
        // A running merge finishes or dies with the process; nothing queues
        // behind it by construction (single-flight).
        // Future SR hook: srWriter.shutdown() here.
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
        if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            torchEnabled = false
            flashStillMode = FlashStillMode.OFF
        }
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
            when {
                torchEnabled -> "TORCH ON"
                flashStillMode == FlashStillMode.ON -> "FLASH ON"
                flashStillMode == FlashStillMode.AUTO -> "FLASH AUTO"
                else -> "FLASH OFF"
            },
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

        // Physical-first statics: downstream metadata (black levels, CCMs, active array,
        // lens-shading support) reads from these characteristics, so they must describe the
        // producing sensor, not the logical camera. Per-frame dynamics route the same way
        // at save time (see RawFrameMetadataFactory physical-first routing).
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

    private fun cachedCameraRoute(cameraId: String): CameraRoute? =
        cameraRouteCache.get(cameraId)

    private fun resolveOpenCameraId(cameraId: String): String? =
        cachedCameraRoute(cameraId)?.openCameraId

    /**
     * Direct-physical fallback for vendor composites (e.g. `9/2`): when the
     * physical member is not advertised in the logical camera's
     * `physicalCameraIds`, binding outputs to it may be rejected by the HAL
     * even though the physical camera opens fine on its own (Xiaomi aux
     * routing). Returns null for standalone routes and for advertised
     * memberships, where a direct open would be refused as a subordinate.
     */
    private fun resolveDirectPhysicalFallback(route: CameraRoute): CameraRoute? {
        val physicalId = route.physicalCameraId ?: return null
        val advertised = try {
            cachedCameraRoute(route.openCameraId)?.characteristics?.physicalCameraIds
        } catch (_: Exception) {
            null
        }
        if (advertised?.contains(physicalId) == true) return null
        val direct = cachedCameraRoute(physicalId) ?: return null
        if (direct.openCameraId != physicalId || direct.physicalCameraId != null) return null
        return CameraRoute(route.identity, physicalId, null, direct.characteristics)
    }

    /**
     * Validated size preference for this route: parsed, then checked
     * against the live stream map so a stale choice degrades to auto
     * instead of wedging the session.
     */
    /**
     * Session stream format for this route: the opted-in packed format when
     * the map lists it, else `RAW_SENSOR`. Stale choices log once and behave
     * exactly like auto.
     */
    private fun effectiveRawFormat(
        cameraId: String?,
        map: android.hardware.camera2.params.StreamConfigurationMap
    ): Int {
        val available = listOf(
            android.graphics.ImageFormat.RAW_SENSOR,
            android.graphics.ImageFormat.RAW10,
            android.graphics.ImageFormat.RAW12,
            IMAGE_FORMAT_RAW14
        ).filter { format ->
            runCatching { map.getOutputSizes(format)?.isNotEmpty() == true }.getOrDefault(false)
        }.toSet()
        val resolved = resolveRawFormatPreference(cameraId?.let(rawFormatPreferenceStore::get), available)
        if (resolved != android.graphics.ImageFormat.RAW_SENSOR) {
            Log.i(LOG_TAG, "Packed RAW session format $resolved for $cameraId")
        }
        return resolved
    }

    private fun preferredRawSize(
        cameraId: String?,
        mapSizes: List<SessionStreamSize>
    ): SessionStreamSize? {
        val parsed = parseRawSizePreference(cameraId?.let(rawSizePreferenceStore::get))
            ?: return null
        if (mapSizes.none { it == parsed }) {
            Log.i(LOG_TAG, "Ignoring stale RAW size ${parsed.width}x${parsed.height} for $cameraId")
            return null
        }
        return parsed
    }

    private fun opticalMetric(cameraId: String): Float = try {
        val c = cachedCameraRoute(cameraId)?.characteristics ?: return Float.MAX_VALUE
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
        private const val ASPECT_RATIO_TOLERANCE = 0.015
        /** Consecutive faceless results before the tracked face (and its regions) drops. */
        private const val FACE_MISS_TOLERANCE = 5
        /** Minimum gap between face-region repeating pushes; HAL boxes jitter every frame. */
        private const val FACE_REGION_PUSH_MIN_INTERVAL_MS = 150L
        /** Face-box overlay cadence; full preview rate would just churn invalidates. */
        private const val FACE_OVERLAY_INTERVAL_MS = 80L
        /** Region rebuild threshold as a fraction of the active-array width. */
        private const val FACE_REGION_MOVE_FRACTION = 0.02f
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val PAIR_TIMEOUT_MS = 5_000L
        /** P0 flash precapture: convergence wait ceiling and poll cadence. */
        private const val FLASH_PRECAPTURE_TIMEOUT_MS = 2_000L
        private const val FLASH_PRECAPTURE_POLL_MS = 100L
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
         * repeating failure): failures stay per-shot.
         * Only a rejected stream combination latches the session fallback. */
        private const val ZSL_RETRY_COOLDOWN_MS = 2_000L
        private const val ZSL_STARTUP_TIMEOUT_MS = 4_000L
        private const val ZSL_FRAME_FILL_ALLOWANCE_MS = 1_000L
        /** Slow-but-productive watchdog windows tolerated per epoch before a
         * transient (retryable, never latching) cooldown. Zero-pair windows
         * still latch burst-mode immediately. */
        private const val ZSL_SLOW_WINDOW_EXTENSIONS = 2
        // Configured ZSL ring ceiling. 59 is the deepest full-resolution RAW
        // ring verified on the reference HAL (Xiaomi 25080RABDG/MT6878 at
        // 30 fps, zero failures); the reader hard cap plus
        // clampZslCapacityForReader shrink the effective ring to whatever the
        // HAL queue fits, so requesting 59 never over-allocates elsewhere.
        private const val MAX_ZSL_FRAMES = 59
        // Hard ceiling for the RAW ImageReader queue. The reference HAL
        // configures a session with at most 61 RAW buffers and aborts the
        // process when ImageReader exceeds 62, so the computed depth
        // (ring + pairing + transition + rearm headroom) clamps here.
        private const val READER_MAXIMAGES_HARD_CAP = 61
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

        /**
         * GPU-reader trial disarm: the RAW reader trial with GPU-sampled
         * usage delivered zero buffers after request-level recovery had its
         * chance, so the allocation itself starves this HAL (MT6878: the
         * session configures, zero images arrive). Disarm once and rebuild
         * with the default CPU-only reader; the first delivered image of
         * the disarmed session persists the verdict, so later cold starts
         * skip the stillborn attempt entirely. Fires before the compat
         * escalation: the usage axis resolves before the plan axis.
         */
        internal fun shouldDisarmVfGpuReaderTrial(
            failures: Int,
            deliveredImages: Int,
            trialArmed: Boolean
        ): Boolean = trialArmed && deliveredImages == 0 &&
            failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES

        /**
         * Still-flash AE resolution (P0/P3, pure for unit tests). Manual
         * exposure keeps AE OFF (the flash fires via FLASH SINGLE, not the AE
         * unit). Hardware AE uses the advertised flash AE mode matching the
         * still-flash setting, falling back to plain AE ON when the HAL does
         * not advertise it. Never returns TORCH: TORCH is preview-assist only.
         *
         * @param flashModeOrdinal [FlashStillMode] ordinal (OFF/ON/AUTO) so
         * tests stay off the Android framework; only primitive args in/out.
         */
        internal fun resolveStillFlashAeMode(
            flashModeOrdinal: Int,
            availableAeModes: IntArray,
            flashAvailable: Boolean
        ): Int {
            if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) {
                return android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
            }
            val want = if (flashModeOrdinal == FlashStillMode.ON.ordinal) {
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            } else {
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            }
            return if (availableAeModes.contains(want)) {
                want
            } else {
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
            }
        }

        /**
         * Precapture gate (P0/P3, pure for unit tests). Metering precapture is
         * only meaningful for AE-driven flash on a full-capability HAL: manual
         * exposure fires an unmetered SINGLE, LEGACY HALs meter best-effort
         * without a precapture round-trip, and an unadvertised flash AE mode
         * means the HAL cannot meter flash at all.
         */
        /**
         * Still FLASH_MODE resolution (P0, pure for unit tests): SINGLE only
         * for manual exposure (AE OFF, unmetered fire); OFF everywhere else —
         * under hardware AE the flash AE mode drives the unit. TORCH is never
         * returned: TORCH is preview-assist only and would smear a still.
         */
        internal fun resolveStillFlashMode(
            flashModeOrdinal: Int,
            manualExposure: Boolean,
            flashAvailable: Boolean
        ): Int {
            if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) {
                return android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF
            }
            if (manualExposure) return android.hardware.camera2.CaptureRequest.FLASH_MODE_SINGLE
            return android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF
        }

        internal fun needsFlashPrecapture(
            manualExposure: Boolean,
            flashModeOrdinal: Int,
            availableAeModes: IntArray,
            flashAvailable: Boolean,
            hardwareLevelLegacy: Boolean
        ): Boolean {
            if (!flashAvailable || flashModeOrdinal == FlashStillMode.OFF.ordinal) return false
            if (manualExposure || hardwareLevelLegacy) return false
            val want = if (flashModeOrdinal == FlashStillMode.ON.ordinal) {
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            } else {
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            }
            return availableAeModes.contains(want)
        }

        /**
         * Allocator probe usage for the GPU-reader trial: exactly the trial
         * reader usage plus the camera-write producer bit, so a refusal
         * means the trial allocation itself is unbackable (a looser
         * superset would risk false refusals that wrongly disarm a working
         * trial). No GPU_DATA_BUFFER: unproven need, extra refusal surface.
         */
        internal fun gpuReaderProbeUsage(): Long =
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                GRALLOC_USAGE_HW_CAMERA_WRITE

        /**
         * Stillborn-in-compat escalation: the compat rebuild already proved
         * request toggles cannot revive this session, so a RAW stream that
         * still delivers zero buffers advances the session fallback ladder
         * (direct-physical route, then degraded combos) instead of parking
         * on a black viewfinder. Fires only after the same cheap
         * request-level retry the compat escalation allows, and never while
         * buffers flow (a rendering problem) or past the last combo.
         */
        internal fun shouldAdvanceStillbornSessionLadder(
            failures: Int,
            deliveredImages: Int,
            compatMode: Boolean,
            altRouteAvailable: Boolean,
            comboIndex: Int,
            comboCount: Int
        ): Boolean = compatMode && deliveredImages == 0 &&
            failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES &&
            (altRouteAvailable || comboIndex + 1 < comboCount)

        /**
         * Bit depth implied by a white level (1023 -> 10, 4095 -> 12,
         * 16383 -> 14, 65535 -> 16); 10 when the key is missing. Shared by
         * the DNG chip's static publish and its dynamic-white updates so
         * both report the same depth for the same level.
         */
        internal fun whiteLevelBitDepth(whiteLevel: Int?): Int =
            whiteLevel?.let { 32 - Integer.numberOfLeadingZeros(it) } ?: 10

        /**
         * Next-lens fallback after [android.hardware.camera2.CameraManager.openCamera]
         * rejects an open ID as an unknown device. Rejection is deterministic
         * per open ID, so every candidate whose route opens through a rejected
         * ID is skipped together (composite siblings sharing one logical
         * camera die with it); unresolvable candidates are skipped as well.
         * Null means no fallback remains and the open must surface a terminal
         * state instead of retrying.
         */
        internal fun nextLensAfterOpenRejected(
            candidates: List<String>,
            rejectedOpenIds: Set<String>,
            openIdOf: (String) -> String?
        ): String? = candidates.firstOrNull { id ->
            val openId = openIdOf(id)
            openId != null && openId !in rejectedOpenIds
        }

        /**
         * A rejection is only denylist-worthy when the ID never opened: an ID
         * that opened before and now reports unknown-device means the camera
         * service hiccuped (in the field log every lens failed within 2 ms),
         * so the caller retries instead of excluding a healthy lens.
         */
        internal fun shouldDenylistOnOpenRejected(
            openId: String,
            openedOnceOpenIds: Set<String>
        ): Boolean = openId !in openedOnceOpenIds

        /**
         * Plain width/height pair for session-combo selection. `android.util.Size`
         * is a framework stub under JVM unit tests, so the ladder below runs on
         * this type and the camera thread maps to/from `Size` at the boundary.
         */
        internal data class SessionStreamSize(val width: Int, val height: Int) {
            val area: Long get() = width.toLong() * height
        }

        /**
         * One session attempt: RAW size, preview size, and reader queue depth.
         * A null preview is a RAW-only session (no preview stream at all) for
         * HALs that do not support RAW+preview in any combination.
         */
        internal data class SessionStreamCombo(
            val raw: SessionStreamSize,
            val preview: SessionStreamSize?,
            val readerMaxImages: Int,
            val label: String
        )

        /**
         * Session ladder, RAW-only first: taps, metering, and all overlays
         * are wired to the RAW viewfinder and the fixed view rect (the
         * system preview is an alpha-0 stream target only), so the default
         * attempt carries no preview target at all — full RAW with a
         * ZSL-capable reader. Some HALs (MediaTek) hold the RAW stream at
         * ~20 fps until any preview target exists; the ISP frame-rate probe
         * detects that stall and rebuilds at FULL, persisting the verdict
         * per camera. HALs that reject the raw-only session outright fall
         * through the same ladder via the stillborn path. Each later step
         * degrades exactly one axis: the smallest preview retry, a larger
         * preview retry, minimal buffers (viewfinder-only, the reader
         * cannot host a ZSL ring), a half-area RAW size, then RAW-only
         * sessions (largest RAW first for capture quality, smallest last for
         * minimum bandwidth). Steps that would retry an identical session are
         * deduped away. An empty preview list yields only the RAW-only steps.
         */
        internal fun selectSessionStreamCombos(
            rawSizes: List<SessionStreamSize>,
            previewSizes: List<SessionStreamSize>,
            fullReaderMaxImages: Int,
            reducedReaderMaxImages: Int
        ): List<SessionStreamCombo> {
            val fullRaw = rawSizes.maxByOrNull { it.area } ?: return emptyList()
            val combos = mutableListOf<SessionStreamCombo>()
            if (fullReaderMaxImages != reducedReaderMaxImages) {
                combos += SessionStreamCombo(fullRaw, null, fullReaderMaxImages, RAW_ONLY_FULL_LABEL)
            }
            if (previewSizes.isNotEmpty()) {
                val fullPreview = selectFullPreviewSize(previewSizes)
                val fallbackPreview = selectFallbackPreviewSize(previewSizes, fullRaw)
                combos += SessionStreamCombo(fullRaw, fullPreview, fullReaderMaxImages, "FULL")
                combos += SessionStreamCombo(fullRaw, fallbackPreview, fullReaderMaxImages, "MIN PREVIEW")
                combos += SessionStreamCombo(fullRaw, fallbackPreview, reducedReaderMaxImages, "MIN BUFFERS")
                selectSmallerRawSize(rawSizes)?.let { smaller ->
                    combos += SessionStreamCombo(smaller, fallbackPreview, reducedReaderMaxImages, "SMALL RAW")
                }
            }
            combos += SessionStreamCombo(fullRaw, null, reducedReaderMaxImages, "RAW ONLY")
            rawSizes.minByOrNull { it.area }?.let { smallest ->
                combos += SessionStreamCombo(smallest, null, reducedReaderMaxImages, "RAW ONLY MIN")
            }
            val deduped = mutableListOf<SessionStreamCombo>()
            for (combo in combos) {
                val prev = deduped.lastOrNull()
                if (prev == null || prev.raw != combo.raw || prev.preview != combo.preview ||
                    prev.readerMaxImages != combo.readerMaxImages
                ) {
                    deduped += combo
                }
            }
            return deduped
        }

        /**
         * Invisible-stream choice: the smallest preview available, requested
         * only so HALs that need a preview target sustain the 30 fps RAW
         * stream. No aspect, coverage, or viewfinder requirement applies —
         * the TextureView is alpha-0 and `fillViewport` ignores aspect.
         */
        internal fun selectFullPreviewSize(
            previewSizes: List<SessionStreamSize>
        ): SessionStreamSize = previewSizes.minByOrNull { it.area }!!

        /**
         * Fallback preview: the smallest matching-aspect stream that stays
         * usable as a viewfinder, else the smallest matching-aspect stream,
         * else the smallest stream overall.
         */
        internal fun selectFallbackPreviewSize(
            previewSizes: List<SessionStreamSize>,
            rawSize: SessionStreamSize
        ): SessionStreamSize {
            val matchingAspect = previewSizes.filter { size ->
                kotlin.math.abs(
                    size.width.toDouble() / size.height - rawSize.width.toDouble() / rawSize.height
                ) <= ASPECT_RATIO_TOLERANCE
            }
            return matchingAspect
                .filter { it.width >= FALLBACK_PREVIEW_MIN_WIDTH && it.height >= FALLBACK_PREVIEW_MIN_HEIGHT }
                .minByOrNull { it.area }
                ?: matchingAspect.minByOrNull { it.area }
                ?: previewSizes.minByOrNull { it.area }!!
        }

        /**
         * Largest RAW size at most half the sensor area: one decisive bandwidth
         * step instead of a slow walk through near-identical remosaic sizes.
         * Null when no such step exists (single RAW size).
         */
        internal fun selectSmallerRawSize(rawSizes: List<SessionStreamSize>): SessionStreamSize? {
            val largest = rawSizes.maxByOrNull { it.area } ?: return null
            return rawSizes.filter { it.area * 2 <= largest.area }.maxByOrNull { it.area }
        }

        /**
         * User size preference as a stable ladder reorder: combos carrying
         * the preferred RAW size move first with their relative order kept,
         * everything else follows untouched, so HAL rejection still degrades
         * exactly as before. Null (auto) or a size with no combo returns the
         * ladder unchanged.
         */
        internal fun orderCombosForPreferredSize(
            combos: List<SessionStreamCombo>,
            preferred: SessionStreamSize?
        ): List<SessionStreamCombo> {
            if (preferred == null || combos.none { it.raw == preferred }) return combos
            val (matching, rest) = combos.partition { it.raw == preferred }
            return matching + rest
        }

        /** Pure + host-tested: stored "WxH" into a combo size, else null. */
        internal fun parseRawSizePreference(text: String?): SessionStreamSize? {
            if (text == null || !RawSizePreferenceStore.isPlausibleSize(text)) return null
            val (width, height) = text.trim().split('x').map(String::toInt)
            return SessionStreamSize(width, height)
        }

        /**
         * Pure + host-tested: stored format name into a session format int.
         * Anything but an available packed format resolves to `RAW_SENSOR`,
         * so packed capture stays strictly opt-in and stale choices degrade
         * to today's behavior instead of wedging the session.
         */
        internal fun resolveRawFormatPreference(
            wanted: String?,
            availableFormats: Set<Int>
        ): Int {
            if (wanted == null) return android.graphics.ImageFormat.RAW_SENSOR
            val packed = runCatching { PackedRawFormat.valueOf(wanted) }.getOrNull()
                ?: return android.graphics.ImageFormat.RAW_SENSOR
            val format = packed.toImageFormat()
            return if (format in availableFormats) format else android.graphics.ImageFormat.RAW_SENSOR
        }

        /**
         * Measured RAW rate over the probe window, from sensor timestamps
         * (monotonic on any timestamp source — only differences matter).
         */
        internal fun rawFpsForProbeWindow(frames: Int, elapsedNs: Long): Double =
            if (elapsedNs <= 0L) Double.POSITIVE_INFINITY else frames * 1e9 / elapsedNs

        /** Below-threshold rate means the ISP holds the pipe: add the preview target. */
        internal fun previewRequestNeededForFps(fps: Double): Boolean = fps < ISP_STALL_FPS

        /**
         * First ladder step carrying any preview target (the 20 fps remedy),
         * or -1 when the ladder is raw-only throughout. The probe jumps here
         * — never backwards past already-rejected preview steps, so a
         * stillborn-degraded tail cannot loop back onto a refused combo.
         */
        internal fun firstPreviewComboIndex(combos: List<SessionStreamCombo>): Int =
            combos.indexOfFirst { it.preview != null }

        /**
         * Inverse of the reader sizing in [createSession]: the forward formula
         * keeps `max(capacity, MAX_IN_FLIGHT_JPEG_SAVES)` plus pairing,
         * transition, and rearm headroom within the reader. A full-size reader
         * clamps back to the requested count; a reader below that floor yields
         * zero (viewfinder-only, no ZSL ring).
         */
        /**
         * Pure reader sizing behind [fullReaderMaxImages]: ring plus pairing,
         * transition, and rearm headroom, clamped to the HAL queue hard cap.
         * Unit-testable without the Android framework.
         */
        internal fun fullReaderMaxImagesFor(frameCount: Int): Int = minOf(
            maxOf(
                MIN_ACQUIRED_RAW_IMAGES,
                maxOf(frameCount, MAX_IN_FLIGHT_JPEG_SAVES) + RAW_PREVIEW_RESERVED_SLOTS +
                    ZSL_REARM_OVERLAP_SLOTS
            ),
            READER_MAXIMAGES_HARD_CAP
        )

        internal fun clampZslCapacityForReader(requestedFrameCount: Int, readerMaxImages: Int): Int {
            val roomForRing = readerMaxImages - RAW_PREVIEW_RESERVED_SLOTS - ZSL_REARM_OVERLAP_SLOTS
            if (roomForRing < MAX_IN_FLIGHT_JPEG_SAVES) return 0
            return minOf(requestedFrameCount, roomForRing)
        }

        /**
         * Pure ZSL selection size behind [RawCameraController.zslSelectedFrameCount].
         * Merge modes save one artifact (one save slot), so each uses its
         * own 2..30 merge bounds and bypasses the per-frame JPEG queue cap;
         * plain bursts keep the cap in JPEG modes (JPEG development holds
         * large intermediates per frame). Unit-testable without the Android
         * framework.
         */
        internal fun zslSelectedFrameCountFor(
            hdrPlus: HdrPlusSettings,
            configuredCount: Int,
            outputFormat: CaptureFormat
        ): Int {
            // Future SR hook: `if (sr.enabled) return sr.activeFrameCount(...)` first.
            if (hdrPlus.enabled) return hdrPlus.activeFrameCount(configuredCount)
            return if (outputFormat.includesJpeg) {
                minOf(configuredCount, MAX_IN_FLIGHT_JPEG_SAVES)
            } else {
                configuredCount
            }
        }

        /**
         * Pure save-slot reservation behind [RawCameraController.refreshCaptureAvailability]
         * and the admission check in beginSingleCapture: merge modes save one
         * artifact, so the shutter gates on one slot — otherwise a 12-frame
         * HDR+ selection in a JPEG mode would grey out the shutter against
         * the 6-slot per-frame JPEG budget. Plain bursts gate on the full
         * selection. Unit-testable without the Android framework.
         */
        internal fun availabilitySlotCountFor(
            hdrPlus: HdrPlusSettings,
            configuredCount: Int,
            outputFormat: CaptureFormat,
            mfsr: MfsrSettings = MfsrSettings()
        ): Int =
            if (hdrPlus.enabled || mfsr.enabled) 1
            else zslSelectedFrameCountFor(hdrPlus, configuredCount, outputFormat)

        /** Request-level recoveries before a stillborn stream rebuilds the session. */
        private const val RAW_PREVIEW_COMPAT_ESCALATION_FAILURES = 1
        /**
         * Private GPU-reader verdict store (deliberately outside
         * [SettingsBackup.KNOWN_STORES]): GPU-usage starvation is a property
         * of this device's HAL/gralloc, so the verdict must never travel to
         * another device via settings backup.
         */
        private const val VF_GPU_READER_PREFS = "vf_gpu_reader"
        private const val VF_GPU_READER_STARVED_KEY = "starved"
        /**
         * Private ISP preview-request verdict store (deliberately outside
         * [SettingsBackup.KNOWN_STORES]): the 20 fps stall is a property of
         * this device's HAL/ISP, so the verdict must never travel to another
         * device via settings backup.
         */
        private const val ISP_PREVIEW_REQUEST_PREFS = "raw_isp_preview_request"
        private const val ISP_PREVIEW_REQUEST_KEY_PREFIX = "needs_preview_request_"
        /**
         * RAW frames counted before the ISP frame-rate probe renders its
         * verdict (~1.5 s at full rate, ~2.25 s on a stalled 20 fps stream).
         */
        internal const val ISP_PROBE_FRAMES = 45
        /**
         * Below this measured RAW rate the ISP is considered stalled: the
         * session rebuilds with the smallest preview target, which unlocks
         * full rate on HALs that need any preview request (MediaTek).
         */
        internal const val ISP_STALL_FPS = 25.0
        /** Label of the default raw-only step (full RAW, ZSL-capable reader). */
        internal const val RAW_ONLY_FULL_LABEL = "RAW ONLY FULL"
        /** HAL pixel format for RAW16 sessions (gralloc allocator queries). */
        internal const val HAL_PIXEL_FORMAT_RAW16 = 0x20
        /** Gralloc producer bit (GRALLOC_USAGE_HW_CAMERA_WRITE) the HAL adds at allocation. */
        internal const val GRALLOC_USAGE_HW_CAMERA_WRITE = 0x20000L
        /** Fallback preview stays usable as a viewfinder at or above this size. */
        private const val FALLBACK_PREVIEW_MIN_WIDTH = 640
        private const val FALLBACK_PREVIEW_MIN_HEIGHT = 480
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
        /** Transient route-resolution reopen budget (Xiaomi characteristics settle late). */
        private const val OPEN_RETRY_MAX = 3
        private const val OPEN_RETRY_DELAY_MS = 1500L
        /** Longer budget for open rejections: a wedged service needs seconds. */
        private const val OPEN_REJECTION_RETRY_MAX = 15
        private const val OPEN_REJECTION_RETRY_DELAY_MS = 2000L
        private const val LOG_TAG = "RawLensCamera"
        private const val DEBUG_UPDATE_INTERVAL_MS = 200L
        private const val RAW_VF_DEBUG_INTERVAL_MS = 500L
        private const val PREVIEW_METADATA_INTERVAL_MS = 125L
        private const val RAW_HISTOGRAM_INTERVAL_MS = 250L
        /** Scope cost telemetry cadence: one LUT/scan average line per 5 s. */
        private const val SCOPE_LOG_INTERVAL_MS = 5000L
        /** Sampler-failure log cadence: a sick sampler must not flood logcat. */
        private const val SAMPLER_ERROR_LOG_INTERVAL_MS = 5000L
        /** Live peaking verdicts ride the same ~4 Hz cadence as the scope. */
        private const val FOCUS_PEAKING_INTERVAL_MS = 250L
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
