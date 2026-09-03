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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

enum class ManualControl { ISO, SHUTTER, WHITE_BALANCE, FOCUS_DISTANCE, EXPOSURE_COMPENSATION }

/** DSLR-style capture intent exposed by the viewfinder mode switcher. */
enum class CaptureExposureMode { AUTO, PROGRAM, ZSL, MANUAL }

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

data class RawZslStatus(val state: RawZslState, val detail: String)

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

internal data class RawSuperResolutionFrame(
    val image: Image,
    val result: TotalCaptureResult,
    val metadata: RawFrameMetadata,
    val timestampNanos: Long,
    val motionRadiansPerSecond: Float
)

internal class RawSuperResolutionCapture(
    frames: Collection<RawSuperResolutionFrame>,
    val settings: RawSuperResolutionSettings,
    val captureFormat: CaptureFormat,
    val jpegSettings: JpegOutputSettings,
    val denoiseSettings: DenoiseSettings,
    val selectedCameraId: String,
    val outputOrientation: Int
) : AutoCloseable {
    private val imageOwner = CloseOnceOwner(frames) { it.image.close() }
    /** Defensive snapshot: callers cannot mutate burst membership after ownership transfer. */
    val frames: List<RawSuperResolutionFrame> = imageOwner.items

    init {
        require(frames.size in RawSuperResolutionSettings.MIN_MERGE_FRAMES..
            RawSuperResolutionSettings.MAX_MERGE_FRAMES)
    }

    /** The temporal middle is a stable diagnostic base until sharpness scoring lands in Phase B. */
    val reference: RawSuperResolutionFrame get() = frames[frames.size / 2]

    override fun close() = imageOwner.close()
}

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
    initialRawSuperResolutionSettings: RawSuperResolutionSettings,
    initialDynamicExposureSettings: DynamicExposureSettings,
    initialAeMeteringMode: AeMeteringMode,
    initialRawHistogramEnabled: Boolean,
    private val dngMetadataOverrides: (cameraId: String?) -> DngMetadataOverrides,
    private val onRawZslStatus: (RawZslStatus) -> Unit,
    private val onDebugState: (String) -> Unit,
    private val onMeteringReleased: () -> Unit,
    private val onRawHistogram: (RgbHistogram) -> Unit
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
    // Keep exactly one worker so DNG writes remain ordered and JPEG development never overlaps
    // two full-resolution CPU/GPU allocation peaks. The physical queue accepts a complete ZSL
    // DNG selection; hasProcessingCapacity() applies the smaller JPEG-development safety bound.
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_QUEUED_SAVES)
    )
    @Volatile private var rawDeveloper: RawDevelopmentCoordinator? = null
    private val pendingImages = ConcurrentHashMap<Long, Image>()
    private val pendingResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pendingZslResults = ConcurrentHashMap<Long, PendingZslResult>()
    private val pendingTimeouts = ConcurrentHashMap<Long, Runnable>()
    private val captureInProgress = AtomicBoolean(false)
    private val captureSequence = AtomicInteger(0)
    private val activeFramesRemaining = AtomicInteger(0)
    private val pendingSaveCount = AtomicInteger(0)
    private val pendingJpegCount = AtomicInteger(0)
    private val jpegServiceLock = Any()
    @Volatile private var captureTimeout: Runnable? = null
    private var characteristics: CameraCharacteristics? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var selectedCameraId: String? = null
    private var rawCameraIds: List<String> = emptyList()
    private var selectedIso: Int? = null
    private var selectedExposureNanos: Long? = null
    private var selectedWbKelvin: Int? = null
    private var selectedFocusDistanceDiopters: Float? = null
    private var exposureCompensation = 0
    @Volatile private var dynamicExposureSettings = initialDynamicExposureSettings
    private var dynamicIso: Int? = null
    private var dynamicShutterNanos: Long? = null
    private var dynamicIsoLimited = false
    private var dynamicShutterLimited = false
    private var dynamicExposureProbe: Runnable? = null
    private var torchEnabled = false
    private var oisEnabled = initialOisEnabled
    @Volatile private var aeMeteringMode = initialAeMeteringMode
    @Volatile private var rawZslRequested = initialRawZslEnabled
    @Volatile private var rawZslFrameCount = initialRawZslFrameCount.coerceIn(1, MAX_ZSL_FRAMES)
    @Volatile private var rawSuperResolutionSettings = initialRawSuperResolutionSettings
    private var activeRawSuperResolutionSettings = initialRawSuperResolutionSettings
    private var activeRawSrReferenceFallback = false
    private var rawZslDisabledForSession = false
    private var rawZslFallbackDetail: String? = null
    private var rawZslStreaming = false
    private var rawZslRequestEpoch = 0L
    private var rawZslCapacity = 0
    private var rawZslTargetFpsRange: Range<Int>? = null
    private var rawZslStreamFpsCeiling: Int = 0
    private var rawZslRealtimeTimestamps = false
    private var rawZslBuffer: RawZslBuffer? = null
    private var rawZslWatchdog: Runnable? = null
    private var rawZslHasFrame = false
    private var rawZslReportedSize = 0
    private var lastRawZslStatus: RawZslStatus? = null
    @Volatile private var rawHistogramEnabled = initialRawHistogramEnabled
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
    @Volatile private var touchFocusActive = false
    private var touchFocusResultReported = false
    private var touchFocusTimeout: Runnable? = null
    private var lastDebugUpdateMs = 0L
    @Volatile private var deviceOrientationDegrees = 0
    @Volatile private var captureFormat = CaptureFormat.DNG_ONLY
    private var activeCaptureFormat = CaptureFormat.DNG_ONLY
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
        if (viewfinder.isAvailable) open()
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
            try {
                val c = cameraManager.getCameraCharacteristics(cameraId)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT &&
                    caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            } catch (_: Exception) {
                false
            }
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
        characteristics = cameraManager.getCameraCharacteristics(id)
        hasPreviewMetadata = false
        lastPreviewMetadataPublishMs = 0L
        lastWbKelvin = null
        lastPreviewSensorTimestamp = Long.MIN_VALUE
        latestPreviewResult = null
        rawZslDisabledForSession = false
        rawZslFallbackDetail = null
        rawZslHasFrame = false
        clampControlsToCamera()
        
        val pixelArraySize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorInfo = String.format(java.util.Locale.US, "RAW\n%s", 
            if (pixelArraySize != null) "${pixelArraySize.width}x${pixelArraySize.height}" else "SENSOR")
        
        val maxDepth = characteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val dngInfo = String.format(java.util.Locale.US, "DNG\n%d-bit RAW", 
            if (maxDepth != null) 32 - Integer.numberOfLeadingZeros(maxDepth) else 10)
        
        onInfo(dngInfo, sensorInfo)
        publishControls()
        
        onState("OPENING RAW")
        cameraManager.openCamera(id, deviceCallback(generation), cameraHandler)
        } catch (_: CameraAccessException) {
            opening = false
            if (running) onState("CAMERA UNAVAILABLE")
        } catch (_: SecurityException) {
            opening = false
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
            createSession(device, generation)
        }
        override fun onDisconnected(device: CameraDevice) {
            synchronized(cameraStateLock) {
                device.close()
                if (camera === device) camera = null
            }
            if (!isCurrent(generation)) return
            opening = false
            onState("CAMERA DISCONNECTED")
        }
        override fun onError(device: CameraDevice, error: Int) {
            synchronized(cameraStateLock) {
                device.close()
                if (camera === device) camera = null
            }
            if (!isCurrent(generation)) return
            opening = false
            onState(if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) "CAMERA IN USE" else "CAMERA ERROR")
        }
    }

    private fun createSession(device: CameraDevice, generation: Int) {
        if (!isCurrent(generation) || camera !== device || session != null) return
        // Camera open and TextureView availability are independent asynchronous events. On a
        // cold launch the device can open first; wait for the surface and let its listener call
        // us again instead of leaving an open camera with no session until the next Activity.
        if (!viewfinder.isAvailable || viewfinder.surfaceTexture == null) {
            onState("WAITING FOR VIEWFINDER")
            return
        }
        if (!viewfinder.isLaidOut || viewfinder.width <= 0 || viewfinder.height <= 0) {
            viewfinder.post { createSession(device, generation) }
            return
        }
        val c = characteristics ?: return
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val rawSize = largestRawSize(map) ?: run { onState("RAW UNAVAILABLE"); return }
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
        rawZslTargetFpsRange = chooseRawZslFpsRange(c, map, rawSize, previewSize)
        viewfinder.post {
            viewfinder.setAspectRatio(previewSize.height, previewSize.width)
            configurePreviewTransform(viewfinder.width, viewfinder.height)
        }

        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            android.graphics.ImageFormat.RAW_SENSOR,
            maxOf(MIN_ACQUIRED_RAW_IMAGES, rawZslCapacity + RAW_READER_TRANSITION_SLOTS)
        ).also {
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
                    do {
                        val image = reader.acquireNextImage() ?: break
                        if (rawZslStreaming && activeFramesRemaining.get() <= 0) {
                            val timestamp = image.timestamp
                            pendingImages.put(timestamp, image)?.close()
                            if (pendingZslResults.containsKey(timestamp)) {
                                pairAvailableFrame(timestamp)
                            } else {
                                schedulePairTimeout(timestamp, reportCaptureFailure = false)
                            }
                            continue
                        }
                        if (activeFramesRemaining.get() <= 0) {
                            image.close()
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
                        pendingImages.put(timestamp, image)?.close()
                        schedulePairTimeout(timestamp, reportCaptureFailure = false)
                        pairAvailableFrame(timestamp)
                    } while (rawZslStreaming && activeFramesRemaining.get() <= 0)
                } catch (_: IllegalStateException) {
                    if (rawZslStreaming) disableRawZslForSession("RAW buffer limit reached")
                    else {
                        Log.e(LOG_TAG, "RAW ImageReader reached maxImages during forward capture")
                        activeFramesRemaining.set(0)
                        captureSequence.incrementAndGet()
                        try {
                            session?.abortCaptures()
                        } catch (_: CameraAccessException) {
                            // The normal capture error path below restores preview/ZSL if possible.
                        }
                        closeAllPendingPairs()
                        finishCapture("CAPTURE ERROR: RAW buffer limit reached")
                        resumeRawZslIfIdle()
                    }
                }
            }, cameraHandler)
        }
        val texture = viewfinder.surfaceTexture ?: run {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            return
        }
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        synchronized(cameraStateLock) {
            if (!isCurrent(generation) || camera !== device) {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                return
            }
            rawReader = reader
            previewSurface = surface
            try {
                createPerformanceSession(device, surface, reader.surface, generation)
            } catch (failure: CameraAccessException) {
                rawReader = null
                previewSurface = null
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                surface.release()
                if (isCurrent(generation)) onState("SESSION ERROR: ${cameraAccessReason(failure)}")
            } catch (_: IllegalStateException) {
                rawReader = null
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
    private fun createPerformanceSession(
        device: CameraDevice,
        preview: Surface,
        raw: Surface,
        generation: Int
    ) {
        val callback = sessionCallback(device, generation)
        val plan = chooseSessionPerformancePlan(device, preview, raw, callback)
        device.createCaptureSession(plan.configuration)
        Log.i(LOG_TAG, "Camera session configured: ${plan.label}")
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
                    updateRepeatingRequest()
                    // stop() disables the shutter while the Activity is backgrounded. A new
                    // camera session must explicitly restore it when the Activity resumes.
                    refreshCaptureAvailability()
                } catch (failure: CameraAccessException) {
                    configured.close()
                    if (session === configured) session = null
                    if (isCurrent(generation)) onState("SESSION ERROR: ${cameraAccessReason(failure)}")
                    return
                } catch (_: IllegalStateException) {
                    configured.close()
                    if (session === configured) session = null
                    if (isCurrent(generation)) onState("CAMERA CLOSED")
                    return
                }
            }
            onState(if (rawZslStreaming) "READY • ZSL WARMING" else "READY")
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (!isCurrent(generation)) return
            onState("SESSION ERROR")
        }
    }

    fun capture() {
        val pressElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val sensorCutoffSnapshot = lastPreviewSensorTimestamp
        val outputFormat = captureFormat
        cameraHandler.post { beginSingleCapture(pressElapsedNanos, sensorCutoffSnapshot, outputFormat) }
    }

    fun captureBurst() {
        val outputFormat = captureFormat
        Log.i(LOG_TAG, "Burst requested frames=$BURST_FRAME_COUNT")
        cameraHandler.post { captureFrames(BURST_FRAME_COUNT, outputFormat = outputFormat) }
    }

    /** Format changes apply only between captures so every frame has one output contract. */
    fun setCaptureFormat(format: CaptureFormat): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) return false
        if (!isOnCameraThread()) {
            cameraHandler.post { setCaptureFormat(format) }
            return true
        }
        captureFormat = format
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
        outputFormat: CaptureFormat
    ) {
        if (!beginCapture(outputFormat, requiredSaveSlots = 1)) return
        if (rawZslStreaming) {
            onState("SELECTING RAW ZSL")
            if (selectAndSaveRawZsl(pressElapsedNanos, sensorCutoffSnapshot)) return
            cameraHandler.postDelayed({
                if (!captureInProgress.get()) return@postDelayed
                if (!selectAndSaveRawZsl(pressElapsedNanos, sensorCutoffSnapshot)) {
                    if (activeRawSuperResolutionSettings.enabled) {
                        activeRawSrReferenceFallback = true
                        onState("RAW SR FALLBACK • CURRENT FRAME")
                    }
                    captureFrames(1, captureAlreadyStarted = true)
                }
            }, ZSL_SELECTION_WAIT_MS)
        } else {
            if (activeRawSuperResolutionSettings.enabled) {
                activeRawSrReferenceFallback = true
                onState("RAW SR FALLBACK • ZSL NOT READY")
            }
            captureFrames(1, captureAlreadyStarted = true)
        }
    }

    private fun beginCapture(outputFormat: CaptureFormat, requiredSaveSlots: Int): Boolean {
        if (!hasProcessingCapacity(requiredSaveSlots, outputFormat)) {
            onState("PROCESSING QUEUE FULL • WAIT FOR SAVES")
            onCaptureEnabled(false)
            return false
        }
        if (!running || !captureInProgress.compareAndSet(false, true)) {
            if (captureInProgress.get()) onState("CAPTURE IN PROGRESS")
            return false
        }
        onCaptureEnabled(false)
        activeCaptureFormat = outputFormat
        activeCaptureExposureMode = captureExposureMode
        activeRawSuperResolutionSettings = rawSuperResolutionSettings
        activeRawSrReferenceFallback = false
        activeAdaptiveExposure = SharedAdaptiveExposure()
        activeJpegOutputSettings = jpegOutputSettings
        activeDenoiseSettings = denoiseSettings
        return true
    }

    private fun selectAndSaveRawZsl(pressElapsedNanos: Long, sensorCutoffSnapshot: Long): Boolean {
        val cutoff = if (rawZslRealtimeTimestamps) pressElapsedNanos else sensorCutoffSnapshot
        if (cutoff == Long.MIN_VALUE) return false
        val requestedFrameCount = activeRawSuperResolutionSettings.activeFrameCount(rawZslFrameCount)
        val selectedFrameCount = if (
            activeCaptureFormat.includesJpeg && !activeRawSuperResolutionSettings.enabled
        ) {
            minOf(requestedFrameCount, MAX_IN_FLIGHT_JPEG_SAVES)
        } else {
            requestedFrameCount
        }
        // A ZSL request saves the configured selection as one logical capture. Do not remove
        // frames from the ring unless all of them fit in the bounded development queue.
        val requiredSaveSlots = if (activeRawSuperResolutionSettings.enabled) 1 else selectedFrameCount
        if (!hasProcessingCapacity(requiredSaveSlots, activeCaptureFormat)) {
            onState("ZSL QUEUED • CAPTURING CURRENT RAW")
            return false
        }
        val selected = rawZslBuffer?.takeBest(
            cutoff,
            rawZslRealtimeTimestamps,
            selectedFrameCount
        ).orEmpty()
        if (selected.isEmpty()) return false
        updateRepeatingRequest(allowRawZsl = false)
        activeFramesRemaining.set(0)
        restartTouchFocusReleaseTimer()
        if (activeRawSuperResolutionSettings.enabled) {
            onState("RAW SR ×${selected.size} • PREPARING")
            saveRawSuperResolutionDiagnostic(selected)
        } else {
            onState("SAVING ZSL ×${selected.size}")
            selected.forEachIndexed { index, frame ->
                saveRawFrame(frame.image, frame.result, "ZSL ${index + 1}/${selected.size}")
            }
        }
        finishCapture()
        return true
    }

    private fun captureFrames(
        frameCount: Int,
        captureAlreadyStarted: Boolean = false,
        outputFormat: CaptureFormat = activeCaptureFormat
    ) {
        if (!captureAlreadyStarted && !beginCapture(outputFormat, requiredSaveSlots = frameCount)) return
        val currentSession = session
        val device = camera
        val reader = rawReader
        if (currentSession == null || device == null || reader == null) {
            finishCapture("CAPTURE ERROR: camera not ready")
            return
        }
        stopRepeatingRawBeforeForwardCapture(currentSession, reader)
        updateRepeatingRequest(allowRawZsl = false)
        val generation = lifecycleGeneration
        val captureId = captureSequence.incrementAndGet()
        activeFramesRemaining.set(frameCount)
        Log.i(LOG_TAG, "Submitting RAW capture id=$captureId frames=$frameCount")
        try {
            val requests = List(frameCount) { frameIndex ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    setTag(CaptureTag(captureId, frameIndex + 1, frameCount))
                    // Same split as PhotonCamera Photo mode: preview meters under hardware AE;
                    // the shutter-first pair is applied only to the submitted still request.
                    applyCameraControls(this, applyDynamicCurve = true)
                    requestLensShadingMap(this)
                }.build()
            }
            onState(if (frameCount == 1) "CAPTURING" else "BURST ×$frameCount")
            scheduleCaptureTimeout(captureId)
            val callback = captureCallback(generation, captureId)
            if (frameCount == 1) currentSession.capture(requests.single(), callback, cameraHandler)
            else currentSession.captureBurst(requests, callback, cameraHandler)
            Log.i(LOG_TAG, "RAW capture submitted id=$captureId frames=$frameCount")
            restartTouchFocusReleaseTimer()
        } catch (failure: CameraAccessException) {
            finishCapture("CAPTURE ERROR: ${cameraAccessReason(failure)}")
            resumeRawZslIfIdle()
        } catch (failure: IllegalArgumentException) {
            finishCapture("CAPTURE NOT SUPPORTED: ${failure.message ?: "invalid request"}")
            resumeRawZslIfIdle()
        } catch (_: IllegalStateException) {
            finishCapture("CAPTURE ERROR: camera closed")
        }
    }

    fun setAfPoint(viewX: Float, viewY: Float) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setAfPoint(viewX, viewY) }
            return
        }
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        val maxRegions = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxRegions == 0) {
            onState("FOCUS AREA NOT SUPPORTED")
            return
        }
        if (!supportsTriggeredAutoFocus()) {
            onState("AUTOFOCUS NOT SUPPORTED")
            return
        }
        // A tap is an explicit request to return from the manual-focus lens position to AF.
        selectedFocusDistanceDiopters = null
        publishControls()
        afRegion = meteringRegion(viewX, viewY) ?: return
        try {
            touchFocusTimeout?.let(cameraHandler::removeCallbacks)
            touchFocusActive = true
            touchFocusResultReported = false
            // Android requires START/CANCEL to be individual requests.  Do not issue an AE
            // precapture trigger for an ordinary tap: it can delay the AF sweep and is intended
            // for still-capture/flash preparation, not normal continuous-preview metering.
            val cancel = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                if (dynamicExposureSettings.enabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }
            val start = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyCameraControls(this)
                if (dynamicExposureSettings.enabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            currentSession.capture(cancel.build(), debugCaptureCallback, cameraHandler)
            currentSession.capture(start.build(), debugCaptureCallback, cameraHandler)
            updateRepeatingRequest(preserveRawZslBuffer = true)
            onState("FOCUSING")
            restartTouchFocusReleaseTimer()
        } catch (failure: CameraAccessException) {
            onState("FOCUS ERROR: ${cameraAccessReason(failure)}")
        }
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

    /**
     * AF modes are optional and vary more than their names suggest across camera HALs.  Pick the
     * still-photo continuous mode first, then degrade to the best available mode instead of
     * submitting an unsupported request value.
     */
    private fun preferredContinuousAfMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    /** A tap-to-focus AF trigger needs a movable lens and an AUTO or MACRO trigger mode. */
    private fun supportsTriggeredAutoFocus(): Boolean {
        val minimumFocusDistance = characteristics
            ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minimumFocusDistance <= 0f) return false
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        return modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ||
            modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)
    }

    fun getAeMeteringMode(): AeMeteringMode = aeMeteringMode

    fun setAeMeteringMode(mode: AeMeteringMode): Boolean {
        if (!isOnCameraThread()) {
            if (mode != AeMeteringMode.AUTO && !isAeMeteringSupported()) {
                onState("AE METERING MODES NOT SUPPORTED")
                return false
            }
            cameraHandler.post { setAeMeteringMode(mode) }
            return true
        }
        if (mode != AeMeteringMode.AUTO && !isAeMeteringSupported()) {
            onState("AE METERING MODES NOT SUPPORTED")
            return false
        }
        aeMeteringMode = mode
        if (touchFocusActive) {
            finishTouchFocus("CONTINUOUS AF")
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
        if (touchFocusActive) {
            finishTouchFocus("CONTINUOUS AF")
            return
        }
        val hadTargets = afRegion != null || aeRegion != null
        touchFocusTimeout?.let(cameraHandler::removeCallbacks)
        touchFocusTimeout = null
        touchFocusResultReported = false
        afRegion = null
        aeRegion = null
        if (hadTargets) updateRepeatingRequest(preserveRawZslBuffer = true)
        onMeteringReleased()
    }

    private fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle? {
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (viewfinder.width == 0 || viewfinder.height == 0) return null
        val x = (viewX / viewfinder.width).coerceIn(0f, 1f)
        val y = (viewY / viewfinder.height).coerceIn(0f, 1f)
        val relativeRotation = ((characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) -
            deviceOrientationDegrees + 360) % 360
        val sensorPoint = when (relativeRotation) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        val centerX = active.left + (sensorPoint.first * active.width()).toInt()
        val centerY = active.top + (sensorPoint.second * active.height()).toInt()
        val halfSize = (minOf(active.width(), active.height()) * 0.06f).toInt()
        val rect = Rect(
            (centerX - halfSize).coerceIn(active.left, active.right - 1),
            (centerY - halfSize).coerceIn(active.top, active.bottom - 1),
            (centerX + halfSize).coerceIn(active.left + 1, active.right),
            (centerY + halfSize).coerceIn(active.top + 1, active.bottom)
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    fun cycleIso() {
        if (!isOnCameraThread()) {
            cameraHandler.post(::cycleIso)
            return
        }
        if (!supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            onState("MANUAL ISO NOT SUPPORTED")
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
            onState("MANUAL SHUTTER NOT SUPPORTED")
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
            onState("MANUAL WB NOT SUPPORTED")
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
            onState("EV REQUIRES AUTO EXPOSURE")
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
            onState("FLASH NOT AVAILABLE")
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
            onState("OIS NOT AVAILABLE")
            return
        }
        oisEnabled = !oisEnabled
        controlsChanged()
        onState(if (oisEnabled) "OIS ON" else "OIS OFF")
    }

    fun isRawZslEnabled(): Boolean = rawZslRequested

    fun rawSuperResolutionSettings(): RawSuperResolutionSettings = rawSuperResolutionSettings

    fun setRawSuperResolutionSettings(settings: RawSuperResolutionSettings): Boolean {
        if (captureInProgress.get() || pendingSaveCount.get() > 0) {
            onState("RAW SR LOCKED WHILE SAVING")
            return false
        }
        rawSuperResolutionSettings = settings
        if (!isOnCameraThread()) {
            cameraHandler.post { applyRawSuperResolutionCameraState(settings) }
            return true
        }
        applyRawSuperResolutionCameraState(settings)
        return true
    }

    private fun applyRawSuperResolutionCameraState(settings: RawSuperResolutionSettings) {
        // Ignore stale posts if the UI changed the setting again before this camera-thread turn.
        if (rawSuperResolutionSettings != settings) return
        if (settings.enabled && !rawZslRequested) {
            rawZslRequested = true
            rawZslDisabledForSession = false
            rawZslFallbackDetail = null
            updateRepeatingRequest()
        }
        publishRawZslStatus()
    }

    /** Avoid full-resolution RAW histogram work while the histogram UI is hidden. */
    fun setRawHistogramEnabled(enabled: Boolean) {
        if (!isOnCameraThread()) {
            cameraHandler.post { setRawHistogramEnabled(enabled) }
            return
        }
        rawHistogramEnabled = enabled
        if (enabled) lastRawHistogramSampleMs = Long.MIN_VALUE
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
            if (rawZslCapacity == 0) {
                publishRawZslStatus(
                    RawZslState.FALLBACK,
                    "Full-resolution RAW frames exceed the safe memory budget"
                )
                onState("RAW ZSL UNAVAILABLE • NORMAL RAW")
            } else {
                updateRepeatingRequest()
                onState(if (rawZslStreaming) "RAW ZSL WARMING" else "RAW ZSL WAITING")
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
        rawZslRequested = mode == CaptureExposureMode.ZSL || rawSuperResolutionSettings.enabled
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
        cancelDynamicExposureProbe()
        when (mode) {
            CaptureExposureMode.MANUAL -> {
                // Entering M freezes the currently metered pair, like a DSLR's manual mode.
                selectedIso = selectedIso ?: lastIso
                selectedExposureNanos = selectedExposureNanos ?: lastExposureNanos
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
            updateRepeatingRequest()
        }
        publishControls()
        publishRawZslStatus()
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
        preserveRawZslBuffer: Boolean = false
    ) {
        if (!isOnCameraThread()) {
            cameraHandler.post { updateRepeatingRequest(allowRawZsl, preserveRawZslBuffer) }
            return
        }
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = previewSurface ?: return
        val reader = rawReader
        val generation = lifecycleGeneration
        val useRawZsl = allowRawZsl && rawZslRequested && !rawZslDisabledForSession &&
            rawZslCapacity > 0 && reader != null &&
            !captureInProgress.get() && pendingSaveCount.get() == 0
        try {
            val preserveBuffer = preserveRawZslBuffer && rawZslStreaming && useRawZsl
            // Keep existing paired candidates through benign control changes (tap focus and
            // dynamic AE). Only transition frames are discarded; the ring stays ready.
            rawZslStreaming = false
            val requestEpoch = ++rawZslRequestEpoch
            if (!preserveBuffer) {
                clearRawZslBuffer()
                if (activeFramesRemaining.get() <= 0) closeUnmatchedRawImages()
                drainQueuedRawImages(reader)
            }
            // Keep the SurfaceTexture on a real PREVIEW request even while RAW ZSL is active.
            // Several Camera2 HALs treat TEMPLATE_ZERO_SHUTTER_LAG as a reprocessing/still path
            // and stop advancing the preview target once a full-resolution RAW target is added.
            // RAW ZSL here is app-operated (our ImageReader + ring buffer), so PREVIEW is the
            // correct repeating template and both targets continue to receive every frame.
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (useRawZsl) addTarget(reader!!.surface)
                applyCameraControls(this)
                if (useRawZsl) {
                    // App-operated RAW ZSL still uses TEMPLATE_PREVIEW so the TextureView never
                    // freezes, but tell 3A/HAL scheduling that this repeating request is serving
                    // a zero-shutter-lag capture pipeline. Request the best advertised 30 fps
                    // range whenever the configured RAW+preview streams can physically sustain
                    // it; slower RAW sensors fall back to their measured stream ceiling.
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG)
                    rawZslTargetFpsRange?.let { range ->
                        val streamCeiling = rawZslStreamFpsCeiling
                        if (streamCeiling <= 0 || range.lower <= streamCeiling || range.upper <= streamCeiling) {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        }
                    }
                    requestLensShadingMap(this)
                }
            }
            currentSession.setRepeatingRequest(
                request.build(),
                previewCaptureCallback(generation, useRawZsl, requestEpoch),
                cameraHandler
            )
            rawZslStreaming = useRawZsl
            if (useRawZsl) {
                if (!preserveBuffer) rawZslHasFrame = false
                motionTracker.start(cameraHandler)
                if (!preserveBuffer) {
                    scheduleRawZslWatchdog(generation, requestEpoch)
                    publishRawZslStatus(RawZslState.WARMING_UP, "Buffering full-resolution RAW frames")
                } else {
                    publishRawZslStatus()
                }
            } else {
                cancelRawZslWatchdog()
                motionTracker.stop()
                publishRawZslStatus()
            }
        } catch (failure: CameraAccessException) {
            if (useRawZsl) disableRawZslForSession(cameraAccessReason(failure))
            else if (running) onState("CONTROL ERROR: ${cameraAccessReason(failure)}")
        } catch (failure: IllegalArgumentException) {
            if (useRawZsl) disableRawZslForSession(failure.message ?: "stream combination rejected")
            else onState("CONTROL NOT SUPPORTED: ${failure.message ?: "invalid value"}")
        }
    }

    private fun previewCaptureCallback(generation: Int, includesRaw: Boolean, requestEpoch: Long) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isCurrent(generation)) return
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { lastPreviewSensorTimestamp = it }
                if (iso > 0) lastIso = iso
                if (shutter > 0) lastExposureNanos = shutter
                latestPreviewResult = result
                if (includesRaw && rawZslStreaming && requestEpoch == rawZslRequestEpoch) {
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
                val displayIso = dynamicExposureDisplayActive().let { showDynamic ->
                    if (showDynamic) dynamicIso ?: iso else iso
                }
                val displayShutter = dynamicExposureDisplayActive().let { showDynamic ->
                    if (showDynamic) dynamicShutterNanos ?: shutter else shutter
                }
                publishPreviewMetadata(displayIso, displayShutter, wb)
                publishDebugState(request, result)
                handleTouchFocusState(result.get(CaptureResult.CONTROL_AF_STATE))
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                if (isCurrent(generation) && includesRaw && rawZslStreaming &&
                    requestEpoch == rawZslRequestEpoch
                ) {
                    disableRawZslForSession("repeating RAW capture failed (${failure.reason})")
                }
            }
        }

    /**
     * Preview and true RAW ZSL keep Android's AE running. This matches PhotonCamera Photo/ZSL:
     * its live preview supplies the meter; IsoExpoSelector applies the shutter-first pair to the
     * submitted still capture, never by freezing the repeating request.
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
                touchFocusActive -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> preferredContinuousAfMode()
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
        val dynamicExposure = applyDynamicCurve && !manualExposure && dynamicExposureSettings.enabled &&
            !rawZslRequested &&
            dynamicIso != null && dynamicShutterNanos != null
        if ((manualExposure || dynamicExposure) &&
            supportsCapability(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ) {
            val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val targetIso = selectedIso ?: dynamicIso ?: lastIso
            val targetExposure = selectedExposureNanos ?: dynamicShutterNanos ?: lastExposureNanos
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
        val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        val shutterRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return
        val result = AutoExposureBalance.apply(
            meteredIso,
            meteredShutter,
            settings.balance,
            ExposureBalanceLimits(
                isoRange.lower,
                settings.isoLimit.takeIf { it > 0 }?.coerceIn(isoRange.lower, isoRange.upper) ?: isoRange.upper,
                shutterRange.lower,
                resolveDynamicShutterLimit(settings, shutterRange.upper),
                minOf(PROGRAM_SHUTTER_START_NANOS, resolveDynamicShutterLimit(settings, shutterRange.upper))
            )
        )
        dynamicIso = result.iso
        dynamicShutterNanos = result.shutterNanos
        dynamicIsoLimited = result.isoLimited
        dynamicShutterLimited = result.shutterLimited
        // Repeating preview remains AE_ON, continuously supplying the next Photon-style pair.
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
            handleTouchFocusState(result.get(CaptureResult.CONTROL_AF_STATE))
        }
    }

    private fun handleTouchFocusState(state: Int?) {
        if (!touchFocusActive || touchFocusResultReported) return
        when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                touchFocusResultReported = true
                onState("FOCUS LOCKED")
                restartTouchFocusReleaseTimer()
            }
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                touchFocusResultReported = true
                onState("FOCUS NOT LOCKED")
                restartTouchFocusReleaseTimer()
            }
        }
    }

    private fun restartTouchFocusReleaseTimer() {
        if (!touchFocusActive) return
        touchFocusTimeout?.let(cameraHandler::removeCallbacks)
        touchFocusTimeout = Runnable {
            if (touchFocusActive) finishTouchFocus("CONTINUOUS AF")
        }.also { cameraHandler.postDelayed(it, TOUCH_FOCUS_HOLD_MS) }
    }

    private fun finishTouchFocus(message: String) {
        if (!touchFocusActive) return
        touchFocusTimeout?.let(cameraHandler::removeCallbacks)
        touchFocusTimeout = null
        touchFocusResultReported = false
        val device = camera
        val currentSession = session
        val surface = previewSurface
        if (device != null && currentSession != null && surface != null) {
            try {
                // Cancel while the touch AUTO mode and regions are still applied. Restoring the
                // defaults before this one-shot request can leave some camera HALs stuck in AUTO.
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
                onState("FOCUS RELEASE ERROR: ${cameraAccessReason(failure)}")
            }
        }
        touchFocusActive = false
        afRegion = null
        aeRegion = null
        updateRepeatingRequest(preserveRawZslBuffer = true)
        onMeteringReleased()
        onState(message)
    }

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
                "AE_MODE  ${aeModeName(aeMode)}\n" +
                "AE_TRIGGER ${aeTriggerName(aeTrigger)}\n" +
                "AE_STATE ${aeStateName(aeState)}\n" +
                "OIS_MODE ${oisModeName(oisMode)}\n" +
                "ISO $iso  EXP ${formatExposure(exposure.coerceAtLeast(1L))}\n" +
                "DYN_AE ${if (dynamicExposureSettings.enabled) "ON" else "OFF"} " +
                "B ${String.format(java.util.Locale.US, "%.2f", dynamicExposureSettings.balance)} " +
                "ISO_CAP ${if (dynamicIsoLimited) "HIT" else "OK"} " +
                "S_CAP ${if (dynamicShutterLimited) "HIT" else "OK"}\n" +
                "DYN_TARGET " + (dynamicIso?.let { targetIso ->
                    dynamicShutterNanos?.let { targetShutter ->
                        "ISO $targetIso  EXP ${formatExposure(targetShutter)}"
                    }
                } ?: "METERING") + "\n" +
                "RAW_ZSL ${if (rawZslStreaming) "ON" else "OFF"} " +
                "BUF ${rawZslBuffer?.size ?: 0}/$rawZslCapacity " +
                String.format(java.util.Locale.US, "GYRO %.3f", motionTracker.currentMotion())
        )
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
        CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
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
            if (touchFocusActive) finishTouchFocus("CONTINUOUS AF")
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            Log.e(
                LOG_TAG,
                "RAW capture failed id=$captureId reason=${failure.reason} frame=${failure.frameNumber}"
            )
            if (touchFocusActive) finishTouchFocus("CONTINUOUS AF")
            finishCapture("CAPTURE FAILED: reason ${failure.reason}, frame ${failure.frameNumber}")
            resumeRawZslIfIdle()
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (!isCurrent(generation) || captureId != captureSequence.get()) return
            Log.e(LOG_TAG, "RAW capture aborted id=$captureId sequence=$sequenceId")
            if (touchFocusActive) finishTouchFocus("CONTINUOUS AF")
            finishCapture("CAPTURE ABORTED")
            resumeRawZslIfIdle()
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
        if (!rawZslStreaming || rawZslDisabledForSession || !rawZslRequested ||
            pending.requestEpoch != rawZslRequestEpoch
        ) {
            image.close()
            return
        }
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "RAW ZSL paired exact timestamp=$timestamp")
        }
        publishRawHistogramIfDue(image, characteristics ?: return image.close())
        rawZslBuffer?.add(image, pending.result, pending.motionRadiansPerSecond) ?: image.close()
        publishRawZslBufferState()
    }

    private fun publishRawZslBufferState() {
        val bufferedCount = rawZslBuffer?.size ?: 0
        if (!rawZslHasFrame && bufferedCount >= rawZslCapacity) {
            rawZslHasFrame = true
            cancelRawZslWatchdog()
            onState("RAW ZSL ACTIVE")
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
        if (!force && now - lastRawHistogramSampleMs < RAW_HISTOGRAM_INTERVAL_MS) return
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
        if (remaining <= 0) cancelCaptureTimeout()
        saveRawFrame(
            image,
            result,
            if (activeRawSrReferenceFallback) "RAW SR • REFERENCE FALLBACK" else null
        )
        // The RAW Image and its exact TotalCaptureResult are now owned by the bounded writer.
        // Reopen the shutter immediately; do not make capture latency depend on development.
        if (remaining <= 0) {
            finishCapture()
            resumeRawZslIfIdle()
        }
    }

    private fun saveRawFrame(image: Image, result: TotalCaptureResult, captureLabel: String?) {
        saveRawFrame(
            image, result, captureLabel,
            CloseOnceOwner(listOf(image)) { owned -> owned.close() }, null
        )
    }

    /**
     * Phase A diagnostic: one queue item owns the complete selected burst and saves/develops its
     * temporal-middle reference. Phase B replaces the reference input with the real merged result.
     */
    private fun saveRawSuperResolutionDiagnostic(selected: List<BufferedRawFrame>) {
        val c = characteristics ?: run {
            selected.forEach { it.image.close() }
            onState("RAW SR ERROR • camera metadata unavailable")
            return
        }
        val orientation = dngOrientation(c)
        val cameraId = selectedCameraId ?: "unknown"
        val frames = try {
            selected.map { frame ->
                RawSuperResolutionFrame(
                    image = frame.image,
                    result = frame.result,
                    metadata = RawFrameMetadataFactory.capture(
                        cameraId, frame.image, c, frame.result, orientation
                    ),
                    timestampNanos = frame.timestampNanos,
                    motionRadiansPerSecond = frame.motionRadiansPerSecond
                )
            }
        } catch (failure: Exception) {
            selected.forEach { it.image.close() }
            Log.e(LOG_TAG, "Could not snapshot RAW SR burst", failure)
            onState("RAW SR ERROR • ${failure.message ?: "invalid metadata"}")
            return
        }
        val capture = RawSuperResolutionCapture(
            frames = frames,
            settings = activeRawSuperResolutionSettings,
            captureFormat = activeCaptureFormat,
            jpegSettings = activeJpegOutputSettings,
            denoiseSettings = activeDenoiseSettings,
            selectedCameraId = cameraId,
            outputOrientation = orientation
        )
        val reference = capture.reference
        saveRawFrame(
            reference.image,
            reference.result,
            "RAW SR ×${frames.size} • REFERENCE FALLBACK",
            capture,
            capture
        )
    }

    private fun saveRawFrame(
        image: Image,
        result: TotalCaptureResult,
        captureLabel: String?,
        ownership: AutoCloseable,
        rawSrCapture: RawSuperResolutionCapture?
    ) {
        val c = characteristics ?: run {
            ownership.close()
            finishCapture("CAPTURE ERROR: camera metadata unavailable")
            resumeRawZslIfIdle()
            return
        }
        val orientation = dngOrientation(c)
        publishRawHistogramIfDue(image, c, force = true)
        // Freeze the exact paired result before crossing to the writer thread. Future JPEG
        // development consumes this snapshot, never a later preview result or mutable HAL array.
        val frameMetadata = rawSrCapture?.reference?.metadata ?: try {
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
            finishCapture("SAVE ERROR: ${failure.message ?: "invalid RAW metadata"}")
            resumeRawZslIfIdle()
            return
        }
        val outputFormat = activeCaptureFormat
        val outputSettings = activeJpegOutputSettings
        val captureDenoiseSettings = activeDenoiseSettings
        val adaptiveExposureStrength = when (activeCaptureExposureMode) {
            CaptureExposureMode.AUTO, CaptureExposureMode.ZSL ->
                if (outputSettings.adaptiveExposureAuto) 1f else 0f
            CaptureExposureMode.PROGRAM -> outputSettings.adaptiveExposureProgramStrength
            CaptureExposureMode.MANUAL -> 0f
        }
        val sharedAdaptiveExposure = activeAdaptiveExposure
        pendingSaveCount.incrementAndGet()
        if (outputFormat.includesJpeg) beginJpegProcessing()
        val job = OwnedCaptureJob(ownership) {
                try {
                    rawSrCapture?.let {
                        Log.i(
                            LOG_TAG,
                            "RAW SR diagnostic job frames=${it.frames.size} " +
                                "mode=${it.settings.dngMode} reference=${it.reference.timestampNanos}"
                        )
                    }
                    var dngName: String? = null
                    var jpegName: String? = null
                    var dngFailure: Exception? = null
                    var jpegFailure: Exception? = null
                    if (outputFormat.includesDng) {
                        try {
                            dngName = DngSaver(context).save(
                                image, c, result, orientation, dngMetadataOverrides(selectedCameraId),
                                frameMetadata
                            )
                            Log.i(
                                LOG_TAG,
                                "DNG saved name=$dngName pendingSaves=${pendingSaveCount.get()}"
                            )
                        } catch (failure: Exception) {
                            dngFailure = failure
                            Log.e(LOG_TAG, "DNG save failed", failure)
                        }
                    }
                    if (outputFormat.includesJpeg) {
                        try {
                            Log.i(
                                LOG_TAG,
                                "RAW development input timestamp=${frameMetadata.timestampNanos} " +
                                    "frame=${frameMetadata.frameNumber} cfa=${frameMetadata.cfaPattern} " +
                                    "geometry=${frameMetadata.bufferGeometry}"
                            )
                            val rawPlane = image.planes.singleOrNull()?.buffer
                                ?: throw UnsupportedOperationException("RAW image must have one plane")
                            val developer = rawDeveloper ?: RawDevelopmentCoordinator(context).also {
                                rawDeveloper = it
                            }
                            val developed = developer.developJpeg(
                                rawPlane, frameMetadata,
                                settings = RawDevelopmentSettings(
                                    denoise = captureDenoiseSettings,
                                    adaptiveExposureStrength = adaptiveExposureStrength,
                                    sharedAdaptiveExposure = sharedAdaptiveExposure
                                ),
                                outputSettings = outputSettings
                            )
                            try {
                                jpegName = JpegSaver(context).save(developed, frameMetadata, result)
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
                    val label = captureLabel?.let { " • $it" }.orEmpty()
                    val shadingNote = if (
                        frameMetadata.lensShadingMap == null &&
                        !frameMetadata.lensShadingAlreadyApplied
                    ) " • NO LENS MAP" else ""
                    onState(formatSaveOutcome(
                        jpegName, dngName, jpegFailure, dngFailure, label, shadingNote
                    ))
                } catch (failure: Exception) {
                    Log.e(LOG_TAG, "Capture artifact save failed", failure)
                    onState("SAVE ERROR: ${failure.message ?: failure.javaClass.simpleName}")
                } finally {
                    if (outputFormat.includesJpeg) endJpegProcessing()
                    cameraHandler.post {
                        pendingSaveCount.decrementAndGet()
                        refreshCaptureAvailability()
                        resumeRawZslIfIdle()
                    }
                }
        }
        try {
            writer.execute(job)
        } catch (_: RejectedExecutionException) {
            job.cancelBeforeRun()
            if (outputFormat.includesJpeg) endJpegProcessing()
            pendingSaveCount.decrementAndGet()
            onState("SAVE QUEUE FULL: wait for storage")
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
        val saved = listOfNotNull(jpegName, dngName)
        val failures = buildList {
            jpegFailure?.let { add("JPEG ERROR: ${it.message ?: it.javaClass.simpleName}") }
            dngFailure?.let { add("DNG ERROR: ${it.message ?: it.javaClass.simpleName}") }
        }
        val prefix = when {
            saved.isNotEmpty() && failures.isNotEmpty() -> "PARTIAL: SAVED ${saved.joinToString(" + ")}"
            saved.isNotEmpty() -> "SAVED ${saved.joinToString(" + ")}"
            else -> "SAVE ERROR"
        }
        return buildString {
            append(prefix)
            append(label)
            append(shadingNote)
            failures.forEach { append(" • ").append(it) }
        }
    }

    private fun scheduleCaptureTimeout(captureId: Int) {
        val timeout = Runnable {
            if (captureId == captureSequence.get() && captureInProgress.get()) {
                finishCapture("CAPTURE TIMEOUT: camera did not respond")
                resumeRawZslIfIdle()
            }
        }
        captureTimeout = timeout
        cameraHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelCaptureTimeout() {
        captureTimeout?.let(cameraHandler::removeCallbacks)
        captureTimeout = null
    }

    private fun schedulePairTimeout(timestamp: Long, reportCaptureFailure: Boolean) {
        pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        val timeout = Runnable {
            pendingTimeouts.remove(timestamp)
            val image = pendingImages.remove(timestamp)
            val result = pendingResults.remove(timestamp)
            pendingZslResults.remove(timestamp)
            image?.close()
            if (reportCaptureFailure && (image != null || result != null)) {
                finishCapture(
                    if (image == null) "CAPTURE TIMEOUT: RAW image missing"
                    else "CAPTURE TIMEOUT: capture metadata missing"
                )
                resumeRawZslIfIdle()
            }
        }
        pendingTimeouts[timestamp] = timeout
        cameraHandler.postDelayed(
            timeout,
            if (reportCaptureFailure) PAIR_TIMEOUT_MS else ZSL_PAIR_TIMEOUT_MS
        )
    }

    private fun finishCapture(message: String? = null) {
        cancelCaptureTimeout()
        captureInProgress.getAndSet(false)
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
        return requiredSaveSlots > 0 && pendingSaveCount.get() + requiredSaveSlots <= limit
    }

    private fun refreshCaptureAvailability() {
        onCaptureEnabled(
            running && !destroyed && !captureInProgress.get() && hasProcessingCapacity()
        )
    }

    private fun resumeRawZslIfIdle() {
        if (running && session != null && rawZslRequested && !rawZslDisabledForSession &&
            !captureInProgress.get() && pendingSaveCount.get() == 0 && !rawZslStreaming
        ) {
            updateRepeatingRequest()
        }
    }

    private fun calculateRawZslCapacity(rawSize: Size): Int {
        val estimatedFrameBytes = rawSize.width.toLong() * rawSize.height.toLong() * RAW_BYTES_PER_PIXEL
        return if (estimatedFrameBytes > 0L) rawZslFrameCount else 0
    }

    /**
     * Drive the repeating RAW ring at 30 fps when the HAL exposes such an AE range.
     * Camera2 only accepts ranges advertised by the device, so prefer a fixed fast range and then
     * the narrowest range containing 30. Exposure time and configured stream durations can still
     * lower the delivered cadence; SENSOR_TIMESTAMP remains the source of truth.
     */
    private fun chooseRawZslFpsRange(
        characteristics: CameraCharacteristics,
        map: StreamConfigurationMap,
        rawSize: Size,
        previewSize: Size
    ): Range<Int>? {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty()
        if (ranges.isEmpty()) return null

        val rawDuration = map.getOutputMinFrameDuration(
            android.graphics.ImageFormat.RAW_SENSOR,
            rawSize
        )
        val previewDuration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, previewSize)
        val configuredDuration = maxOf(rawDuration, previewDuration)
        val streamCeiling = if (configuredDuration > 0L) {
            (1_000_000_000L / configuredDuration).coerceAtLeast(1L).toInt()
        } else {
            RAW_ZSL_TARGET_FPS
        }
        rawZslStreamFpsCeiling = streamCeiling
        val requestedFps = minOf(RAW_ZSL_TARGET_FPS, streamCeiling)
        val selected = ranges
            .filter { it.upper >= requestedFps }
            .minWithOrNull(
                compareBy<Range<Int>>(
                    { if (it.lower >= requestedFps) 0 else 1 },
                    { it.upper - requestedFps },
                    { it.upper - it.lower },
                    { -it.lower }
                )
            ) ?: ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

        Log.i(
            LOG_TAG,
            "RAW ZSL FPS target=$RAW_ZSL_TARGET_FPS requested=$requestedFps " +
                "range=$selected streamCeiling=$streamCeiling raw=${rawSize.width}x${rawSize.height}"
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
                disableRawZslForSession("No paired RAW frame arrived within ${timeoutMs}ms")
            }
        }.also { cameraHandler.postDelayed(it, timeoutMs) }
    }

    private fun cancelRawZslWatchdog() {
        rawZslWatchdog?.let(cameraHandler::removeCallbacks)
        rawZslWatchdog = null
    }

    private fun disableRawZslForSession(reason: String) {
        if (!rawZslRequested) return
        rawZslDisabledForSession = true
        rawZslFallbackDetail = reason
        rawZslStreaming = false
        clearRawZslBuffer()
        updateRepeatingRequest(allowRawZsl = false)
        publishRawZslStatus(RawZslState.FALLBACK, "$reason; using normal RAW capture")
        onState("ZSL FALLBACK • NORMAL RAW")
    }

    private fun clearRawZslBuffer() {
        rawZslBuffer?.clear()
        rawZslHasFrame = false
        rawZslReportedSize = 0
        val zslTimestamps = pendingZslResults.keys.toList()
        zslTimestamps.forEach { timestamp ->
            pendingZslResults.remove(timestamp)
            pendingImages.remove(timestamp)?.close()
            pendingTimeouts.remove(timestamp)?.let(cameraHandler::removeCallbacks)
        }
    }

    /** PhotonCamera uses the same drain on request transitions so stale RAW never crosses modes. */
    private fun drainQueuedRawImages(reader: ImageReader?) {
        if (reader == null) return
        try {
            while (true) {
                val image = reader.acquireNextImage() ?: break
                image.close()
            }
        } catch (_: IllegalStateException) {
            // A concurrently closing reader has no buffers that remain safe to retain.
        }
    }

    private fun stopRepeatingRawBeforeForwardCapture(
        currentSession: CameraCaptureSession,
        reader: ImageReader
    ) {
        // Invalidate callbacks first: frames completing while the HAL flushes belong to the
        // previous repeating RAW request and must not be mistaken for burst frames.
        rawZslStreaming = false
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
        pendingImages.values.forEach(Image::close)
        pendingImages.clear()
        pendingResults.clear()
        pendingZslResults.clear()
        pendingTimeouts.values.forEach(cameraHandler::removeCallbacks)
        pendingTimeouts.clear()
        rawZslBuffer?.clear()
        rawZslHasFrame = false
        rawZslReportedSize = 0
    }

    private fun closeUnmatchedRawImages() {
        pendingImages.keys.toList().forEach { timestamp ->
            if (!pendingResults.containsKey(timestamp) && !pendingZslResults.containsKey(timestamp)) {
                pendingImages.remove(timestamp)?.close()
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
            rawZslStreaming && rawZslHasFrame -> RawZslStatus(
                RawZslState.ACTIVE,
                "${rawZslBuffer?.size ?: 0}/${rawZslCapacity} RAW frames buffered"
            )
            else -> RawZslStatus(RawZslState.WARMING_UP, "Waiting for a paired RAW frame")
        }
        if (status != lastRawZslStatus) {
            lastRawZslStatus = status
            onRawZslStatus(status)
        }
    }

    fun stop() {
        synchronized(cameraStateLock) {
            if (!running && !opening && camera == null) return
            running = false
            opening = false
            lifecycleGeneration++
            touchFocusActive = false
            touchFocusResultReported = false
            touchFocusTimeout?.let(cameraHandler::removeCallbacks)
            touchFocusTimeout = null
            cancelRawZslWatchdog()
            motionTracker.stop()
            rawZslStreaming = false
            rawZslRequestEpoch++
            clearRawZslBuffer()
            captureSequence.incrementAndGet()
            finishCapture()
            rawReader?.setOnImageAvailableListener(null, null)
            session?.close(); camera?.close(); rawReader?.close(); previewSurface?.release()
            session = null; camera = null; rawReader = null; previewSurface = null
        }
        pendingImages.values.forEach { it.close() }; pendingImages.clear(); pendingResults.clear()
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
        viewfinder.removeOnLayoutChangeListener(previewLayoutListener)
        viewfinder.surfaceTextureListener = null
        // Queue teardown behind any accepted saves. The executor is serial, so EGL resources are
        // destroyed on exactly the thread that compiled and used their programs.
        try {
            writer.execute { rawDeveloper?.close() }
        } catch (_: RejectedExecutionException) {
            // The process will reclaim this small program/context cache if a full queue prevents
            // graceful teardown during Activity destruction.
        }
        writer.shutdown()
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

    private fun opticalMetric(cameraId: String): Float = try {
        val c = cameraManager.getCameraCharacteristics(cameraId)
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
        val mainId = rawCameraIds.firstOrNull { it == "0" }
            ?: rawCameraIds.getOrNull(rawCameraIds.size / 2)
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

    private fun dngOrientation(c: CameraCharacteristics): Int = when (
        ((c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) -
            deviceOrientationDegrees + 360) % 360
    ) {
        90 -> 6  // EXIF rotate 90° clockwise
        180 -> 3
        270 -> 8 // EXIF rotate 270° clockwise
        else -> 1
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
        private const val ZSL_PAIR_TIMEOUT_MS = 1_500L
        private const val ZSL_SELECTION_WAIT_MS = 120L
        private const val ZSL_STARTUP_TIMEOUT_MS = 4_000L
        private const val ZSL_FRAME_FILL_ALLOWANCE_MS = 1_000L
        private const val MAX_ZSL_FRAMES = 30
        private const val RAW_ZSL_TARGET_FPS = 30
        private const val RAW_BYTES_PER_PIXEL = 2L
        // JPEG development retains large intermediate CPU/GPU buffers, so keep one serialized
        // development worker while allowing five additional retained RAW inputs. DNG-only writes
        // need no such intermediates
        // and must accept the entire configured ZSL selection. The executor queue excludes its
        // running worker, hence MAX_ZSL_FRAMES - 1 queued slots.
        private const val MAX_IN_FLIGHT_JPEG_SAVES = 6
        private const val MAX_IN_FLIGHT_DNG_SAVES = MAX_ZSL_FRAMES
        private const val MAX_QUEUED_SAVES = MAX_IN_FLIGHT_DNG_SAVES - 1
        private const val BURST_FRAME_COUNT = 6
        private const val RAW_READER_TRANSITION_SLOTS = 2
        private const val MIN_ACQUIRED_RAW_IMAGES = BURST_FRAME_COUNT + 2
        private const val LOG_TAG = "RawLensCamera"
        private const val TOUCH_FOCUS_HOLD_MS = 5_000L
        private const val DEBUG_UPDATE_INTERVAL_MS = 200L
        private const val PREVIEW_METADATA_INTERVAL_MS = 125L
        private const val RAW_HISTOGRAM_INTERVAL_MS = 250L
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
        private val cameraThread = HandlerThread("RawCamera").apply { start() }
        private val cameraHandler = Handler(cameraThread.looper)
    }
}
