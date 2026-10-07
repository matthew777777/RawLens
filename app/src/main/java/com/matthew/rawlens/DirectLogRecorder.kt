// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct-video demosaic backend. MHC (fused MHC+grade, one dispatch)
 * is the record path; RCD runs the 4-mode split path (RCD + grade-YUV)
 * and cannot hold 24/30fps at 4K (~233ms/frame) — it drops to a few
 * fps effective and exists for A/B comparison, not realtime takes.
 */
enum class VideoDemosaic(val label: String) {
    MHC("MHC"),
    RCD("RCD");

    fun next(): VideoDemosaic = if (this == MHC) RCD else MHC

    companion object {
        fun fromPreference(value: String?): VideoDemosaic =
            entries.firstOrNull { it.name == value } ?: MHC
    }
}

/**
 * Direct-Log recorder: sensor RAW -> fused Vulkan MHC demosaic + log
 * grade in one dispatch (per-frame WB/CCM from the capture result;
 * superpixel fallback when fused is unavailable) -> HW HEVC Main10 ->
 * MP4. No synthetic buffers, no CPU pixel copies, no `glFinish` stalls.
 *
 * Ownership per frame mirrors the viewfinder contract: adopt into
 * [RawImageOwnership], borrow a GPU lease, release the camera ref up front
 * (native close deferred to lease close), run the GPU chain synchronously
 * on the camera thread, then close lease + HardwareBuffer. The take
 * monitor shows the staged encode pixels (one tiny P010->RGBA8 dispatch
 * per frame, same-queue ordered behind the encode) instead of a
 * separately-demosaiced viewfinder: no second full-res dispatch contends
 * with the record submits, and the monitor IS the encode (LOG EV and
 * grade moves show live). Preview is best-effort and never gates the
 * encode: every skip degrades to an older monitor frame.
 *
 * The encoder runs its input Surface + a drain thread feeding [MediaMuxer].
 * Video + audio are recorded; full-take gyro is captured when [stabEnabled]
 * for the post-record Vulkan warp pass ([StabSidecar]).
 */
class DirectLogRecorder(
    private val cameraManager: CameraManager,
    private val cameraId: String,
    private val appContext: Context,
) {
    data class Stats(
        val sensorSize: String,
        val cropRect: String,
        val exportSize: String,        val framesAcquired: Int,
        val framesGraded: Int,
        val framesDropped: Int,
        val resultMiss: Int,
        val staleResults: Int,
        /** Frames fully wait-free (submit + fd handoff, zero CPU stall). */
        val waitFreeFrames: Int,
        /** Frames that fell back to a blocking submit (should be ~0). */
        val fallbackWaits: Int,
        /** Frames via the fused MHC+grade path (vs superpixel fallback). */
        val fusedFrames: Int,
        /** Frames via the RCD split path (A/B lever; 0 on normal takes). */
        val rcdFrames: Int,
        /** Graded frames with the HAL lens-shading (vignetting) map applied. */
        val shadedFrames: Int,
        /**
         * CPU cost per frame (submits + blit setup). The GPU executes
         * overlapped and is NOT in this number; small here proves the CPU
         * never stalls on it (blocking era: ~9ms).
         */
        val cpuChainMsAvg: Float,
        /** Worst single-frame CPU chain this take (spike hunter for drops). */
        val cpuChainMsMax: Float,
        /** Frames whose CPU chain exceeded the 33.4ms frame (each one drops the next camera image). */
        val overBudgetFrames: Int,
        /** Worst full camera-thread occupancy per frame (adopt + chain): the true drop predictor. */
        val cycleMsMax: Float,
        /** Frames whose full occupancy exceeded the 33.4ms frame. */
        val cycleOverBudget: Int,
        /** Head frames dropped before the first capture result (expected warm-up, not mid-take loss). */
        val headDroppedFrames: Int,
        /** Frames with a monitor preview submitted (best-effort; skips never affect the encode). */
        val previewFrames: Int,
        /** Graded frames whose monitor preview was skipped (busy worker/surface/pipeline). */
        val previewSkipped: Int,
        /** Deepest record backlog seen (sheds only beyond QUEUE_DEPTH). */
        val maxBacklogDepth: Int,
        /** Dumb-copy cost per frame (the only CPU pixel work); MotionCam burns CPU on convert. */
        val copyMsAvg: Float,
        /** Worst single-frame copy this take (DRAM contention hunter). */
        val copyMsMax: Float,
        /** GPU queue time still outstanding at worker arrival (avg). */
        val fenceWaitMsAvg: Float,
        /** Worst single-frame fence wait (GPU-bound proof when high). */
        val fenceWaitMsMax: Float,
        /** True when P010 direct input is active (true 10-bit content). */
        val true10Bit: Boolean,
        /** Frames dropped for lack of a free codec input buffer. */
        val inputStarved: Int,
        /** Frames queued with stale/zero pixels after a submit failure. */
        val degradedFrames: Int,
        /** Unexpected frame-path exceptions (must stay 0; see onFrame). */
        val frameErrors: Int,
        val audioChunks: Int,
        val audioFrames: Long,
        val audioDropped: Int,
        val hasAudio: Boolean,
        /** AAC bytes emitted (0 = AAC wedged, check its drain). */
        val aacBytes: Long,
        /** Audio samples muxed (0 with aacBytes>0 = muxer path broken). */
        val audioOutBufs: Int,
        val outputBuffers: Int,
        val outputBytes: Long,
        val fileBytes: Long,
        /** PowerManager thermal status sampled at start (0=none). */
        val thermalStatus: Int,
        /** True when heat forced a cheaper rung/step/path. */
        val heatDegraded: Boolean,
        /** Output profile name for this take (BT709/SLOG3/HLG). */
        val profile: String,
        /** True when a gyro sidecar was captured + written for the warp pass. */
        val hasStabGyro: Boolean,
        /** Gyro samples in the sidecar (0 when [hasStabGyro] is false). */
        val stabGyroSamples: Long,
    )

    var bitrate: Int = LogVideoProbe.BITRATE
    var fps: Int = LogVideoProbe.FPS
    var width: Int = LogVideoProbe.WIDTH
    var height: Int = LogVideoProbe.HEIGHT
    /**
     * Grade lift in EV (default 0: the developed chain renders metered
     * exposure correctly — 18% gray lands at 46% — so no lift is needed;
     * the slider in the video HUD adjusts live). Volatile: the HUD
     * writes on the UI thread, the camera thread reads per frame.
     */
    @Volatile var exposureEv: Float = 0f
    /**
     * Output profile for the take (BT709/SLOG3/HLG). Decided at take
     * start (the encoder format is fixed then); the HUD toggle applies
     * to the next take. Read into [sessionProfile] in [start].
     */
    @Volatile var logProfile: DirectLogProfile = DirectLogProfile.BT709
    /** Effective profile for this take (snapshot of [logProfile]). */
    private var sessionProfile: DirectLogProfile = DirectLogProfile.BT709
    /**
     * Take monitor: the camera thread submits one tiny P010->RGBA8
     * preview dispatch per graded frame (same-queue ordered behind the
     * encode) and hands the buffer + completion fd to this view, which
     * presents it on its own GL worker. Best-effort and never gating:
     * null (or a busy worker/surface) only skips monitor frames, the
     * encode is unaffected. No RAW-viewfinder fan-out runs during a
     * Direct-Log take, so no second full-res dispatch contends with the
     * record submits.
     */
    @Volatile private var previewView: DirectLogPreviewView? = null

    fun attachPreviewDisplay(view: DirectLogPreviewView?) {
        previewView = view
    }
    /** Resolution ladder rung (sensor pixels; encoder output stays 4K). */
    var rung: LogVideoLadder.Rung = LogVideoLadder.Rung.FULL
    /**
     * Superpixel stride (2 = every quad, 4 = every 4th quad): compute-side
     * scaling with identical FOV, for sensors with no binned RAW sizes.
     * Superpixel-fallback path only; MHC always demosaics the full crop.
     */
    var computeStep: Int = 2
    /**
     * Demosaic preference (default fused MHC+grade). False forces the
     * superpixel fallback: A/B lever and field escape hatch. Session
     * setup still falls back automatically when fused is unavailable.
     */
    var preferMhc: Boolean = true
    /**
     * Demosaic backend for the take (default [VideoDemosaic.MHC]).
     * Decided at take start like [logProfile]; the Video tab applies
     * to the next take. RCD init failure falls back to fused MHC.
     */
    @Volatile var videoDemosaic: VideoDemosaic = VideoDemosaic.MHC
    /**
     * Lens-shading (vignetting) correction (default on): the per-frame
     * HAL gain map multiplies demosaiced RGB pre-WB, like the
     * viewfinder. False records raw sensor falloff (A/B lever; also
     * skipped automatically when the HAL pre-applies shading).
     */
    var correctShading: Boolean = true
    /**
     * Heat escape hatch (default on): at SEVERE+ the take degrades one
     * rung/step instead of wedging. False pins the requested config
     * (tests use this for determinism under heat).
     */
    var allowThermalDegrade: Boolean = true

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rawSize: Size? = null
    private var exportSize: Size? = null
    private var cropRect: LogVideoLadder.Crop? = null
    private var chars: CameraCharacteristics? = null
    /** Static calibration CCM baked once per start(); null when unavailable. */
    private var staticCcm: FloatArray? = null
    private var channels: IntArray = intArrayOf(0, 1, 2, 3)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack: Int = -1
    private var audioTrack: Int = -1
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    private var muxerStarted = false
    /** True when this take carries an audio track (decided at start). */
    @Volatile private var audioRequired = false
    private var drainThread: Thread? = null
    private val draining = AtomicBoolean(false)
    private var eosSeen = CountDownLatch(1)
    /** Set false before [start] to record silent video without asking for the mic. */
    var audioEnabled: Boolean = true
    /**
     * Set false before [start] to skip gyro capture (OG-only takes).
     * When true, full-take gyro + the queued-frame timestamp table are
     * captured and [stop] writes the warp sidecar next to the MP4.
     */
    var stabEnabled: Boolean = true
    private var pcm: AudioPcmRecorder? = null
    private var aac: AacAudioEncoder? = null
    private val muxAudioQueue =
        ArrayBlockingQueue<AacAudioEncoder.Encoded>(256)
    private val audioChunks = AtomicInteger(0)
    private val audioFrames = AtomicLong(0)
    private val audioDropped = AtomicInteger(0)
    private val audioOutBufs = AtomicInteger(0)
    @Volatile private var hasAudioStream = false
    @Volatile private var aacBytesOut = 0L
    // Stab gyro capture (post-record warp pass): full-take MotionRecorder
    // (same class the CinemaRAW path uses) + the queued-frame sensor-ts
    // table. The table appends in queueReady — the ONLY point that sees
    // exactly the queued frames in order (the gate advances past starved
    // copies, so submit order would misalign the warp by every starve).
    private var stabMotion: MotionRecorder? = null
    @Volatile private var stabTableArmed = false
    private val stabFrameTs = ArrayList<Long>()
    private var stabFocalMm = 0f
    private var stabSensorWMm = 0f
    private var stabSensorHMm = 0f
    private var stabOrientation = 0
    private var stabFront = false
    private var stabGyroSamples = 0L

    private data class Slot(
        var export: HardwareBufferRef? = null,
        var p010: HardwareBufferRef? = null,
        var preview: HardwareBufferRef? = null,
        var fd: Int = -1,
        var ptsUs: Long = 0L,
        // Copy-worker handoff: the camera thread awaits the previous
        // job, then replaces this with a fresh latch before enqueueing.
        var jobDone: CountDownLatch = CountDownLatch(0),
        // Monitor-present handoff: the camera thread only submits a
        // preview when the previous present released this (else it
        // skips — the encode never waits on the monitor), then
        // replaces it with a fresh latch the view counts down after
        // its sampling completes (glFinish), before slot reuse.
        var previewDone: CountDownLatch = CountDownLatch(0),
    )
    private val slots = arrayOf(Slot(), Slot(), Slot())
    // OUR P010 staging layout (allocator-described, submit-validated).
    private var ourYStrideB: Int = 0
    private var ourUvStrideB: Int = 0
    // Codec input layout (plane metadata, CPU-visible; no AHB needed).
    private var yStrideB: Int = 0
    private var uvStrideB: Int = 0
    private var vStrideB: Int = 0
    private var yPxStrideB: Int = 0
    private var uvPxStrideB: Int = 0
    private var vPxStrideB: Int = 0
    private var p010Size: Int = 0
    private var frameCounter: Long = 0L
    private var lastQueuedPts: Long = Long.MIN_VALUE
    private var queues: Int = 0

    private val resultLock = java.lang.Object()
    private val captureResults = java.util.TreeMap<Long, TotalCaptureResult>()
    private var lastResult: TotalCaptureResult? = null

    @Volatile private var accepting: Boolean = false
    @Volatile private var timeOriginNs: Long = Long.MIN_VALUE
    private val acquired = AtomicInteger(0)
    private val graded = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val resultMiss = AtomicInteger(0)
    private val staleResults = AtomicInteger(0)
    private val waitFree = AtomicInteger(0)
    private val fallbackWaits = AtomicInteger(0)
    private val fusedCount = AtomicInteger(0)
    private val rcdCount = AtomicInteger(0)
    /** Session demosaic path (decided in start; fused unless unavailable). */
    private var useFused = false
    /** RCD split path (decided in start; needs RCD init + [VideoDemosaic.RCD]). */
    private var useRcd = false
    /**
     * RCD split-path working buffers (crop-sized RGBA_FP16, single shared
     * pair): GPU-only with same-queue FIFO submit order, so no per-slot
     * copies are needed (unlike P010, which the copy worker reads back).
     */
    private var rcdRgb: HardwareBufferRef? = null
    private var rcdScratch: HardwareBufferRef? = null
    @Volatile private var heatThermalStatus: Int = 0
    @Volatile private var heatDegraded: Boolean = false
    /** Effective compute step for this take (heat may raise it above [computeStep]). */
    private var sessionStep: Int = 2
    private val inputStarved = AtomicInteger(0)
    private val degradedFrames = AtomicInteger(0)
    private val frameErrors = AtomicInteger(0)
    private val shadedCount = AtomicInteger(0)
    /** Cached HAL lens-shading map (content-keyed; the HAL map is session-static). */
    private var shadeKey = 0L
    private var shadeDims: IntArray? = null
    private var shadeGains: ShortArray? = null
    private var shadeHalApplied = false
    private var shadeLogged = false
    @Volatile private var cpuChainMsAvg = 0f
    @Volatile private var cpuChainMsMax = 0f
    private val overBudget = AtomicInteger(0)
    @Volatile private var cycleMsMax = 0f
    private val cycleOverBudget = AtomicInteger(0)
    /** Head warm-up drops (pre-result gate); excluded from [dropped] mid-take health. */
    private val headDropped = AtomicInteger(0)
    /** Monitor previews submitted / skipped (best-effort; never gates the encode). */
    private val previewCount = AtomicInteger(0)
    private val previewSkipped = AtomicInteger(0)
    /** Session preview state (decided in start; disabled gracefully on any failure). */
    private var previewEnabled = false
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewBox = VfRecordPreview.PREVIEW_BOX
    private var previewRotation = 0
    private var previewMirrored = false
    private var previewErrorLogged = false
    @Volatile private var maxBacklogDepth = 0
    @Volatile private var copyMsAvg = 0f
    @Volatile private var copyMsMax = 0f
    @Volatile private var fenceWaitMsAvg = 0f
    @Volatile private var fenceWaitMsMax = 0f
    /** Encoder latency EMA (queueInputBuffer -> drained output with the same PTS). */
    @Volatile private var encLatencyMsAvg = 0f
    /** Muxer writeSampleData cost EMA (storage-write busy fraction input). */
    @Volatile private var muxWriteMsAvg = 0f
    /**
     * Queue timestamps by PTS for encode-latency (gate thread writes,
     * drain thread reads+removes; capped: full takes must not grow it).
     */
    private val queuedPtsNs = LinkedHashMap<Long, Long>()
    private val queuedPtsLock = java.lang.Object()
    @Volatile private var true10Bit = false
    private val outBufs = AtomicInteger(0)
    private val outBytes = AtomicLong(0)
    /**
     * First muxed video sample time (elapsedRealtimeNanos): the output-fps
     * window starts here, not at take start — camera/encoder bring-up (~1.5s
     * of dead time before the first output) would otherwise read as a
     * permanent ~20% fps deficit on the overlay.
     */
    @Volatile private var firstOutNs = 0L
    private var outputFile: File? = null
    private var startRealtimeMs: Long = 0L

    fun start(output: File) {
        check(device == null) { "Already started" }
        require(VfEglImport.available) { "rawLensVfEgl native library unavailable" }
        require(VfVulkan.available) { "Vulkan bridge unavailable" }
        outputFile = output
        if (output.exists()) output.delete()
        startRealtimeMs = SystemClock.elapsedRealtime()
        acquired.set(0); graded.set(0); dropped.set(0); resultMiss.set(0)
        staleResults.set(0); waitFree.set(0); fallbackWaits.set(0)
        inputStarved.set(0); degradedFrames.set(0); frameErrors.set(0)
        shadedCount.set(0)
        shadeKey = 0L; shadeDims = null; shadeGains = null; shadeLogged = false
        true10Bit = false
        yStrideB = 0; uvStrideB = 0; vStrideB = 0
        yPxStrideB = 0; uvPxStrideB = 0; vPxStrideB = 0
        ourYStrideB = 0; ourUvStrideB = 0
        p010Size = 0; frameCounter = 0L
        lastQueuedPts = Long.MIN_VALUE; queues = 0
        copyMsAvg = 0f
        copyMsMax = 0f
        encLatencyMsAvg = 0f; muxWriteMsAvg = 0f
        synchronized(queuedPtsLock) { queuedPtsNs.clear() }
        fenceWaitMsAvg = 0f
        fenceWaitMsMax = 0f
        cpuChainMsMax = 0f; overBudget.set(0)
        cycleMsMax = 0f; cycleOverBudget.set(0)
        headDropped.set(0); previewCount.set(0); previewSkipped.set(0)
        previewEnabled = false; previewErrorLogged = false
        maxBacklogDepth = 0
        for (s in slots) {
            s.fd = -1; s.ptsUs = 0L; s.jobDone = CountDownLatch(0)
            s.previewDone = CountDownLatch(0)
        }
        startCopyWorker()
        outBufs.set(0); outBytes.set(0); firstOutNs = 0L
        audioChunks.set(0); audioFrames.set(0); audioDropped.set(0); audioOutBufs.set(0)
        audioRequired = false; hasAudioStream = false; aacBytesOut = 0L
        audioTrack = -1
        videoFormat = null; audioFormat = null
        muxAudioQueue.clear()
        timeOriginNs = Long.MIN_VALUE
        videoTrack = -1; muxerStarted = false
        heatThermalStatus = 0; heatDegraded = false
        sessionProfile = logProfile
        synchronized(resultLock) { captureResults.clear() }
        lastResult = null
        eosSeen = CountDownLatch(1)

        val c = cameraManager.getCameraCharacteristics(cameraId)
        chars = c
        // Static CCM floor: per-frame COLOR_CORRECTION results usually miss
        // on the record path, and the old identity fallback rendered as a
        // cyan/green overcast. Bake once from the calibration matrices.
        staticCcm = DirectLogColor.readForwardPair(c)?.let { pair ->
            DirectLogColor.staticCcm(pair.fm1, pair.fm2, pair.illuminant1, pair.illuminant2)
        }
        Log.i(
            TAG, "static CCM: " + (staticCcm?.let {
                "baked @${DirectLogColor.STATIC_CCT_KELVIN.toInt()}K " +
                    "[${it.joinToString(", ") { v -> "%.3f".format(v) }}]"
            } ?: "unavailable (no ForwardMatrix pair) -> identity")
        )
        channels = RawPreviewGeometry.channels(
            c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        )
        previewRotation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        previewMirrored =
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        shadeHalApplied =
            c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) == true
        Log.i(TAG, "lens shading: " + when {
            !correctShading -> "disabled by flag (raw falloff)"
            shadeHalApplied -> "HAL pre-applied (no map needed)"
            else -> "live HAL map (first frame arms it)"
        })
        startStabMotion(c)
        val rawSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList()
            ?: error("No RAW_SENSOR sizes")
        Log.i(TAG, "sensor RAW sizes: ${rawSizes.sortedByDescending { it.width * it.height }
            .joinToString { "${it.width}x${it.height}" }}")
        heatThermalStatus = try {
            appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        } catch (_: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
        var effRung = rung
        var effStep = computeStep
        var effPreferMhc = preferMhc
        if (allowThermalDegrade && heatThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            val decision = LogVideoLadder.heatDegrade(
                rawSizes.map { LogVideoLadder.RawSize(it.width, it.height) },
                effRung, effStep, effPreferMhc
            )
            effRung = decision.rung
            effStep = decision.step
            effPreferMhc = decision.preferMhc
            heatDegraded = true
            Log.w(TAG, "heat degrade: thermal=$heatThermalStatus rung=${rung.label}->${effRung.label} " +
                "step=$computeStep->$effStep mhc=$preferMhc->$effPreferMhc")
        }
        val size = LogVideoLadder.resolve(rawSizes, effRung)
        Log.i(TAG, "ladder rung=${effRung.label} selected=${size.width}x${size.height} " +
            "(${"%.2f".format(LogVideoLadder.megapixels(size))} MP)")
        require(size.width % 2 == 0 && size.height % 2 == 0) { "Odd sensor size ${size.width}x${size.height}" }
        require(effStep >= 2 && effStep % 2 == 0) { "computeStep must be even and >= 2" }
        rawSize = size
        val crop = LogVideoLadder.centerCropCapped16x9(size.width, size.height)
        cropRect = crop
        // 16:9 compute region (not full 4:3 frame): the blit stretches
        // texture->surface, so correct aspect comes from the region.
        val export = LogVideoLadder.exportDims(
            LogVideoLadder.RawSize(crop.width, crop.height), effStep
        )
        exportSize = Size(export.width, export.height)
        sessionStep = effStep
        Log.i(TAG, "compute step=$effStep crop=${crop.width}x${crop.height}@(${crop.left},${crop.top}) " +
            "export=${export.width}x${export.height}")

        // Vulkan pipelines (idempotent; shared device with the viewfinder).
        val spv = appContext.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val f16spv = appContext.assets.open("shaders/vf/vf_superpixel_f16.spv").use { it.readBytes() }
        val yuvspv = appContext.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val gradespv = appContext.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfVulkan.initF16Native(f16spv) == VfVulkan.OK) { "f16 init failed" }
        // Grade init first: twin command buffers + isolated sets live with
        // it and the yuv submit reuses them.
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "grade-yuv init failed" }
        // Fused MHC+grade record stage (after grade: its twins live
        // with grade init). Failure is never fatal: the take falls back
        // to the superpixel path below.
        useFused = false
        useRcd = false
        fusedCount.set(0)
        rcdCount.set(0)
        fun tryInitFused(): Boolean {
            return try {
                val fusedspv = appContext.assets.open("shaders/vf/vf_mhcyuv.spv").use { it.readBytes() }
                if (VfLogGrade.initFusedYuvNative(fusedspv) == VfVulkan.OK) {
                    useFused = true
                    true
                } else {
                    Log.w(TAG, "fused init failed; superpixel fallback")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "fused unavailable (${e.message}); superpixel fallback")
                false
            }
        }
        if (!effPreferMhc) {
            Log.i(TAG, "superpixel forced (preferMhc=$preferMhc heatDegraded=$heatDegraded)")
        } else if (videoDemosaic == VideoDemosaic.RCD) {
            // RCD split path (4 RCD modes + grade-YUV): A/B lever only,
            // never realtime at 4K. Init failure falls back to fused MHC.
            try {
                val rcdspv = appContext.assets.open("shaders/vf/vf_rcd.spv").use { it.readBytes() }
                if (VfRcd.initRcdNative(rcdspv) != VfVulkan.OK) {
                    Log.w(TAG, "rcd init failed; fused fallback")
                    tryInitFused()
                } else {
                    try {
                        rcdRgb = HardwareBufferRef.create(crop.width, crop.height)
                        rcdScratch = HardwareBufferRef.create(crop.width, crop.height)
                        useRcd = true
                    } catch (e: Exception) {
                        Log.w(TAG, "rcd buffers failed (${e.message}); fused fallback")
                        try { rcdRgb?.close() } catch (_: Exception) {}
                        try { rcdScratch?.close() } catch (_: Exception) {}
                        rcdRgb = null
                        rcdScratch = null
                        tryInitFused()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "rcd unavailable (${e.message}); fused fallback")
                tryInitFused()
            }
        } else {
            tryInitFused()
        }
        Log.i(
            TAG, "demosaic path=" + when {
                useRcd -> "rcd-split"
                useFused -> "fused-mhcyuv"
                else -> "superpixel-fallback"
            }
        )
        // Upload one neutral warp LUT BEFORE the camera streams (creation
        // + upload ≈ 17ms). The record shaders ignore the fetch (direct
        // encode, no look) but the native gate requires the upload; the
        // binding is reserved for upcoming LUT support. Best-effort.
        try {
            val urc = VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST))
            if (urc != VfVulkan.OK) Log.w(TAG, "lut pre-warm failed ($urc)")
        } catch (e: Exception) {
            Log.w(TAG, "lut pre-warm failed (${e.message})")
        }
        // Record-preview stage (take monitor): P010 staging -> small RGBA8.
        // Best-effort like the monitor itself — any failure here only
        // disables the monitor, the encode is unaffected.
        try {
            val dims = VfRecordPreview.previewDims(width, height)
            previewWidth = dims[0]
            previewHeight = dims[1]
            previewBox = VfRecordPreview.previewBox(width, height, previewWidth, previewHeight)
            val pvspv = appContext.assets.open("shaders/vf/vf_p010preview.spv").use { it.readBytes() }
            if (VfRecordPreview.initPreviewNative(pvspv) == VfVulkan.OK) {
                previewEnabled = true
                Log.i(TAG, "preview ${width}x$height -> ${previewWidth}x$previewHeight box=$previewBox")
            } else {
                Log.w(TAG, "preview init failed; monitor off")
            }
        } catch (e: Exception) {
            Log.w(TAG, "preview unavailable (${e.message}); monitor off")
            previewEnabled = false
        }

        // True-10-bit encoder: P010 ByteBuffer input (no EGL surface — the
        // recordable configs top out at 8-bit). Grade writes the codec's
        // own input images; queueInputBuffer carries completion.
        val codecName = LogVideoProbe.preferredHwEncoder()
            ?: error("no HW HEVC 4K30 encoder")
        val target = sessionProfile.targetColors()
            .copy(width = width, height = height, fps = fps, bitrate = bitrate)
        Log.i(TAG, "output profile=${sessionProfile.name} " +
            "cs=${target.colorStandard} xfer=${target.colorTransfer} range=${target.colorRange}")
        val mc = MediaCodec.createByCodecName(codecName)
        codec = mc
        try {
            mc.configure(
                LogVideoProbe.toMediaFormat(
                    target, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                ),
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        } catch (e: Exception) {
            codec = null
            throw e
        }
        mc.start()
        true10Bit = true
        // App-owned P010 staging (GPU-written, CPU-readable): 3 ping-pong
        // buffers at encode res. Strides come from the allocator and are
        // re-validated natively per submit (loud -20 on mismatch).
        for (s in slots) {
            val p010 = VfEglImport.createP010(width, height)
                ?: error("P010 staging allocate failed")
            val info = VfEglImport.describeP010(p010)
                ?: error("P010 describe failed")
            if (ourYStrideB == 0) {
                ourYStrideB = info[0]
                ourUvStrideB = info[1]
                Log.i(TAG, "ours P010 yStride=$ourYStrideB uvStride=$ourUvStrideB")
            } else {
                check(info[0] == ourYStrideB && info[1] == ourUvStrideB) {
                    "P010 staging stride drift"
                }
            }
            s.p010 = HardwareBufferRef(p010)
        }

        // Ping-pong demosaic working buffers. Fused needs none (CFA
        // straight to P010 staging); the superpixel fallback allocates
        // quarter-res quad exports here. RCD owns one shared rgb/scratch
        // pair instead (allocated with its init above).
        val expW = export.width
        val expH = export.height
        if (!useFused && !useRcd) {
            for (s in slots) {
                s.export = HardwareBufferRef.create(expW, expH)
            }
        }

        // Monitor preview buffers (RGBA8, quarter res per axis). Best
        // effort: allocation failure only disables the monitor.
        if (previewEnabled) {
            try {
                for (s in slots) {
                    s.preview = HardwareBufferRef.createRgba8(previewWidth, previewHeight)
                }
            } catch (e: Exception) {
                Log.w(TAG, "preview buffers unavailable (${e.message}); monitor off")
                for (s in slots) {
                    try {
                        s.preview?.close()
                    } catch (_: Exception) {
                    }
                    s.preview = null
                }
                previewEnabled = false
            }
        }

        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        draining.set(true)
        drainThread = Thread(::drainLoop, "DirectLogDrain").apply { start() }
        startAudio()

        val thread = HandlerThread("DirectLogCam", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        camThread = thread
        val handler = Handler(thread.looper)
        camHandler = handler
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 8)
        reader = r
        accepting = true
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        try {
            device = openCamera(handler)
            session = createSession(handler)
            val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(fps, fps))
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // Without this the results carry no lens-shading map and the
                // take records raw sensor falloff (shaded=0). Mirrors the
                // stills path (RawCameraController.requestLensShadingMap).
                val shadingModes = c.get(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES
                ) ?: intArrayOf()
                if (shadingModes.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                    try {
                        set(
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                        )
                    } catch (_: IllegalArgumentException) {
                        Log.w(TAG, "lens-shading map request rejected; take runs unshaded")
                    }
                }
            }.build()
            session!!.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    synchronized(resultLock) {
                        captureResults[ts] = result
                        while (captureResults.size > 64) captureResults.pollFirstEntry()
                        resultLock.notifyAll()
                    }
                    lastResult = result
                }
            }, handler)
        } catch (e: Exception) {
            teardown()
            throw e
        }
        // Take is rolling: arm the monitor (size first, then attach, so
        // no offer can present before the viewport math is valid).
        previewView?.let { view ->
            try {
                view.setPreviewSize(previewWidth, previewHeight)
                view.setPreviewAttached(true)
            } catch (e: Exception) {
                Log.w(TAG, "preview attach failed (${e.message}); monitor off")
            }
        }
    }

    /** Live feed for the video HUD + watchdog (mirrors RawVideoRecorder.VideoStats, lite). */
    data class VideoStats(
        val recording: Boolean,
        val elapsedMs: Long,
        val framesAcquired: Int,
        val framesGraded: Int,
        val framesDropped: Int,
        val fileBytes: Long,
        val waitFreeFrames: Int,
        val fallbackWaits: Int,
        val cpuChainMsAvg: Float,
        val previewFrames: Int,
        /** Output profile name for this take (BT709/SLOG3/HLG). */
        val profile: String,
        /** Session demosaic path label (MHC/RCD/SUPER). */
        val demosaic: String,
        /** Encoded output rate (muxed samples since the first muxed sample; 0 until 2+). */
        val outputFps: Float,
        /** IO busy %: P010 copy + muxer write vs the frame budget. */
        val ioBusyPct: Int,
        /** Encoder busy %: queue-to-drain latency vs the frame budget. */
        val encBusyPct: Int,
        /** System RAM used % (whole device, not just the app). */
        val memPct: Int,
    )

    fun snapshot(): VideoStats {
        val elapsed = if (startRealtimeMs == 0L) 0L
        else SystemClock.elapsedRealtime() - startRealtimeMs
        val intervalMs = if (fps > 0) 1000f / fps else 0f
        fun busyPct(ms: Float): Int =
            if (intervalMs > 0f) (ms / intervalMs * 100f).toInt().coerceIn(0, 999) else 0
        val memPct = try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem > 0) ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt() else 0
        } catch (_: Exception) {
            0
        }
        return VideoStats(
            recording = device != null,
            elapsedMs = elapsed,
            framesAcquired = acquired.get(),
            framesGraded = graded.get(),
            framesDropped = dropped.get(),
            fileBytes = try { outputFile?.length() ?: 0L } catch (_: Exception) { 0L },
            waitFreeFrames = waitFree.get(),
            fallbackWaits = fallbackWaits.get(),
            cpuChainMsAvg = cpuChainMsAvg,
            previewFrames = previewCount.get(),
            profile = sessionProfile.name,
            demosaic = when {
                useRcd -> "RCD"
                useFused -> "MHC"
                else -> "SUPER"
            },
            outputFps = muxedFps(
                outBufs.get(), firstOutNs, android.os.SystemClock.elapsedRealtimeNanos()
            ),
            ioBusyPct = busyPct(copyMsAvg + muxWriteMsAvg),
            encBusyPct = busyPct(encLatencyMsAvg),
            memPct = memPct.coerceIn(0, 100),
        )
    }

    /** Latest mic peak 0..1 for HUD meters (0 when silent); safe any thread. */
    fun audioLevel(): Float = pcm?.lastPeak ?: 0f

    private fun startAudio() {
        audioRequired = false
        hasAudioStream = false
        if (!audioEnabled) return
        val rec = AudioPcmRecorder()
        if (!rec.start(::onPcmChunk)) return
        val enc = AacAudioEncoder(AudioPcmRecorder.SAMPLE_RATE, rec.channels)
        val ok = enc.start(
            onFormat = { fmt -> audioFormat = fmt },
            onOutput = { encoded ->
                // Single muxer writer lives on the drain thread; frames
                // outrank audio under pressure (gap counters, never stalls).
                if (!muxAudioQueue.offer(encoded)) audioDropped.incrementAndGet()
            }
        )
        if (!ok) {
            try { rec.stop() } catch (_: Exception) {}
            return
        }
        pcm = rec
        aac = enc
        audioRequired = true
        hasAudioStream = true
        Log.i(TAG, "audio AAC ${rec.channels}ch")
    }

    private fun onPcmChunk(chunk: AudioPcmRecorder.Chunk) {
        // Recording-relative like video; pre-roll without video is dropped
        // so both timelines start together.
        val origin = timeOriginNs
        if (origin == Long.MIN_VALUE) {
            audioDropped.incrementAndGet()
            return
        }
        val relUs = (chunk.timestampNs - origin) / 1000L
        if (relUs < 0) {
            audioDropped.incrementAndGet()
            return
        }
        val enc = aac ?: run { audioDropped.incrementAndGet(); return }
        if (enc.queue(AacAudioEncoder.Input(chunk.data, relUs))) {
            audioChunks.incrementAndGet()
            audioFrames.addAndGet(chunk.frames.toLong())
        } else {
            audioDropped.incrementAndGet()
        }
    }

    private fun stopAudio() {
        try { pcm?.stop() } catch (_: Exception) {}
        pcm = null
        // Drains queued PCM through AAC + EOS, then joins (bounded ~seconds).
        try {
            aacBytesOut = aac?.stop()?.bytes ?: 0L
        } catch (_: Exception) {}
        aac = null
    }

    /**
     * Stab gyro capture start (fail-soft: any failure leaves motion off
     * and the take records a plain OG MP4). Stashes the sidecar
     * calibration alongside. Mirrors [RawVideoRecorder]'s motion setup.
     */
    private fun startStabMotion(c: CameraCharacteristics) {
        stabMotion = null
        stabTableArmed = false
        stabFrameTs.clear()
        stabGyroSamples = 0L
        stabOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        stabFront =
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        stabFocalMm =
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        stabSensorWMm = physical?.width ?: 0f
        stabSensorHMm = physical?.height ?: 0f
        if (!stabEnabled) return
        val realtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        try {
            val rec = MotionRecorder(appContext, stabOrientation, stabFront, realtime)
            if (rec.start()) {
                stabMotion = rec
                stabTableArmed = true
                Log.i(TAG, "stab gyro on (f=${stabFocalMm}mm sensor=${stabSensorWMm}x${stabSensorHMm}mm)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stab gyro failed (${e.message}); OG-only take")
            stabMotion = null
            stabTableArmed = false
        }
    }

    /** Idempotent motion stop (stop path + teardown safety net). */
    private fun stopStabMotion() {
        try {
            stabMotion?.stop()
        } catch (_: Exception) {
        }
        stabMotion = null
        stabTableArmed = false
    }

    /**
     * Drains the take gyro + frame table into the warp sidecar next to
     * [output], returning the gyro sample count (0 when the take cannot
     * stabilize: motion off, no queued frames, thin gyro, write failure).
     * Runs on the stop path AFTER the copy workers joined, so [stabFrameTs]
     * is complete.
     */
    private fun writeStabSidecar(output: File): Long {
        val rec = stabMotion
        val frames = stabFrameTs.toLongArray()
        stopStabMotion()
        stabGyroSamples = 0L
        if (rec == null || frames.isEmpty()) return 0L
        val gyro = ArrayList<GyroSample>(4096)
        try {
            while (true) {
                val chunk = rec.drainGyro(2048) ?: break
                for (i in 0 until chunk.count) {
                    gyro.add(
                        GyroSample(
                            chunk.timestampsNs[i],
                            chunk.axes[i * 3], chunk.axes[i * 3 + 1], chunk.axes[i * 3 + 2]
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stab gyro drain failed (${e.message})")
            return 0L
        }
        if (gyro.isEmpty()) {
            Log.w(TAG, "stab gyro empty; OG-only take")
            return 0L
        }
        val raw = rawSize
        val crop = cropRect
        if (raw == null || crop == null) return 0L
        val take = StabSidecar.Take(
            header = StabSidecar.Header(
                fps = fps, encodeW = width, encodeH = height,
                timeOriginNs = timeOriginNs,
                sensorOrientationDeg = stabOrientation, frontFacing = stabFront,
                focalMm = stabFocalMm, sensorWMm = stabSensorWMm, sensorHMm = stabSensorHMm,
                sensorW = raw.width, sensorH = raw.height,
                cropLeft = crop.left, cropTop = crop.top,
                cropW = crop.width, cropH = crop.height,
                mapperVersion = GyroCameraFrameMapper.MAPPER_VERSION
            ),
            frameTimestampsNs = frames,
            gyro = gyro
        )
        val sidecar = File(output.parent, output.nameWithoutExtension + StabSidecar.FILE_SUFFIX)
        val tmp = File(output.parent, sidecar.name + ".tmp")
        try {
            tmp.writeText(StabSidecar.render(take))
            if (!tmp.renameTo(sidecar)) {
                try { tmp.delete() } catch (_: Exception) {}
                Log.w(TAG, "stab sidecar rename failed")
                return 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "stab sidecar write failed (${e.message})")
            try { tmp.delete() } catch (_: Exception) {}
            return 0L
        }
        stabGyroSamples = gyro.size.toLong()
        Log.i(TAG, "stab sidecar ${sidecar.name} frames=${frames.size} gyro=${gyro.size}")
        return stabGyroSamples
    }

    fun stop(): Stats {        accepting = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        // Audio flushes first so its tail lands before the video EOS.
        stopAudio()
        // Copy worker drains the tail (every graded frame already has a
        // job queued) before EOS. Its fds closed by value: mark the slot
        // fields so teardown below must not double-close them.
        stopCopyWorker()
        for (s in slots) s.fd = -1
        val mcEOS = codec
        if (mcEOS != null) {
            try {
                val eosIdx = mcEOS.dequeueInputBuffer(5000)
                if (eosIdx >= 0) {
                    mcEOS.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (_: Exception) {}
        }
        try { eosSeen.await(12, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        draining.set(false)
        // Covers the drain tail (8s) + final audio flush: mux.stop() below
        // must not run while the drain thread still writes (lost tail).
        try { drainThread?.join(10_000) } catch (_: InterruptedException) {}
        val mux = muxer
        muxer = null
        try {
            if (muxerStarted) mux?.stop()
        } catch (_: Exception) {}
        try { mux?.release() } catch (_: Exception) {}
        muxerStarted = false
        // Copy workers joined inside stopCopyWorker above: the stab frame
        // table is complete. Finalize the warp sidecar BEFORE teardown
        // (teardown nulls the motion recorder; 0 samples = OG-only take).
        val stabSamples = try {
            outputFile?.let { writeStabSidecar(it) } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "stab sidecar failed (${e.message})")
            0L
        }
        teardown()
        return Stats(
            sensorSize = "${rawSize?.width}x${rawSize?.height}",
            cropRect = cropRect?.let { "${it.width}x${it.height}@(${it.left},${it.top})" } ?: "?",
            exportSize = "${exportSize?.width}x${exportSize?.height}",
            framesAcquired = acquired.get(),
            framesGraded = graded.get(),
            framesDropped = dropped.get(),
            resultMiss = resultMiss.get(),
            staleResults = staleResults.get(),
            waitFreeFrames = waitFree.get(),
            fallbackWaits = fallbackWaits.get(),
            fusedFrames = fusedCount.get(),
            rcdFrames = rcdCount.get(),
            shadedFrames = shadedCount.get(),
            cpuChainMsAvg = cpuChainMsAvg,
            cpuChainMsMax = cpuChainMsMax,
            overBudgetFrames = overBudget.get(),
            cycleMsMax = cycleMsMax,
            cycleOverBudget = cycleOverBudget.get(),
            headDroppedFrames = headDropped.get(),
            previewFrames = previewCount.get(),
            previewSkipped = previewSkipped.get(),
            maxBacklogDepth = maxBacklogDepth,
            true10Bit = true10Bit,
            inputStarved = inputStarved.get(),
            degradedFrames = degradedFrames.get(),
            frameErrors = frameErrors.get(),
            copyMsAvg = copyMsAvg,
            copyMsMax = copyMsMax,
            fenceWaitMsAvg = fenceWaitMsAvg,
            fenceWaitMsMax = fenceWaitMsMax,
            audioChunks = audioChunks.get(),
            audioFrames = audioFrames.get(),
            audioDropped = audioDropped.get(),
            hasAudio = hasAudioStream,
            aacBytes = aacBytesOut,
            audioOutBufs = audioOutBufs.get(),
            outputBuffers = outBufs.get(),
            outputBytes = outBytes.get(),
            fileBytes = outputFile?.length() ?: 0L,
            thermalStatus = heatThermalStatus,
            heatDegraded = heatDegraded,
            profile = sessionProfile.name,
            hasStabGyro = stabSamples > 0,
            stabGyroSamples = stabSamples,
        )
    }

    // ---- frame path (camera thread) ----

    private fun onFrame(rd: ImageReader) {
        if (!accepting) {
            // Drain without work so stop() isn't wedged behind stale frames.
            while (true) {
                val stale = try { rd.acquireNextImage() } catch (_: Exception) { break } ?: break
                try { stale.close() } catch (_: Exception) {}
            }
            return
        }
        // Bounded queue (NOT latest-only): the camera thread stalls
        // stochastically (RT preemption, copy spikes, GPU backlog —
        // measured 8-35ms with no heavy op in the path), and shedding
        // every backlog frame turns each stall into lost realtime (motion
        // jumps + A/V drift against the constant grid) = visible stutter.
        // Instead, collect what's waiting (the reader holds 8), shed
        // oldest-first only beyond QUEUE_DEPTH, and record the rest in
        // order — each frame takes the next constant-rate grid PTS, so the
        // file stays gapless while live latency breathes by <= depth frames.
        val first = try { rd.acquireNextImage() } catch (_: Exception) { null } ?: return
        val second = try { rd.acquireNextImage() } catch (_: Exception) { null }
        if (second == null) {
            processOne(rd, first)
            return
        }
        val backlog = ArrayList<Image>(8)
        backlog.add(first)
        backlog.add(second)
        while (backlog.size < 8) {
            backlog.add(try { rd.acquireNextImage() } catch (_: Exception) { null } ?: break)
        }
        if (backlog.size > maxBacklogDepth) maxBacklogDepth = backlog.size
        var i = 0
        while (backlog.size - i > QUEUE_DEPTH) {
            try { backlog[i].close() } catch (_: Exception) {}
            i++
            dropped.incrementAndGet()
        }
        while (i < backlog.size) {
            if (!accepting) {
                while (i < backlog.size) {
                    try { backlog[i].close() } catch (_: Exception) {}
                    i++
                }
                return
            }
            processOne(rd, backlog[i])
            i++
        }
    }

    /** One recorded frame (camera thread): adopt, grade, submit. */
    private fun processOne(rd: ImageReader, image: Image) {
        // Full camera-thread occupancy: adopt + chain. This is the true
        // drop predictor (the split chain counter misses adopt/lease
        // overruns that silently drop the next camera image).
        val cycleStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        acquired.incrementAndGet()
        RawImageOwnership.adopt(image, rd)
        val hb = try {
            image.hardwareBuffer
        } catch (_: Exception) {
            null
        }
        if (hb == null) {
            RawImageOwnership.release(image)
            dropped.incrementAndGet()
            return
        }
        val lease = RawImageOwnership.borrow(image)
        if (lease == null) {
            try { hb.close() } catch (_: Exception) {}
            RawImageOwnership.release(image)
            dropped.incrementAndGet()
            return
        }
        // Camera ref released up front; native close waits out the GPU lease.
        RawImageOwnership.release(image)
        try {
            processFrame(image, hb)
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
            frameErrors.incrementAndGet()
            dropped.incrementAndGet()
        } finally {
            try { lease.close() } catch (_: Exception) {}
            try { hb.close() } catch (_: Exception) {}
        }
        val cycleMs = (android.os.SystemClock.elapsedRealtimeNanos() - cycleStartNs) / 1e6f
        if (cycleMs > cycleMsMax) cycleMsMax = cycleMs
        if (cycleMs > 1000f / 30f) cycleOverBudget.incrementAndGet()
    }

    private fun processFrame(image: Image, hb: android.hardware.HardwareBuffer) {
        val c = chars ?: return
        val plane = image.planes[0]
        if (plane.pixelStride != 2) {
            dropped.incrementAndGet()
            return
        }
        val ts = image.timestamp
        val result = awaitResult(ts)
        if (shouldDropPreresult(result != null, graded.get(), acquired.get())) {
            // Stream warming: no capture result yet, so this frame would
            // bake probe WB + static CCM (+ no lens shading) while every
            // later frame uses live values — a first-frame color pop.
            // Drop; PTS stays contiguous (frameCounter only advances on
            // graded frames) and the A/V origin arms below on the first
            // recorded frame. Counted apart from mid-take drops: warm-up
            // is expected (and timing-dependent), not a health signal.
            headDropped.incrementAndGet()
            return
        }
        if (timeOriginNs == Long.MIN_VALUE) timeOriginNs = ts
        // Constant frame rate: PTS comes from the submit-order frame index
        // on the ideal 1/fps grid — never from the sensor timestamp. Sensor
        // jitter and AE exposure wander would otherwise bake uneven PTS
        // into the file (variable frame rate); the grid keeps every sample
        // duration identical. Audio stays sensor-relative against the same
        // origin, so both timelines still start at 0 together.
        val cfrUs = constantPtsUs(frameCounter, fps)
        val (levels, white) = blackWhite(c, result)
        // Same result feeds WB/CCM and shading, so the three never disagree.
        val (shadeD, shadeG) = snapshotShading(result)
        val pitch = plane.rowStride / plane.pixelStride
        val exp = exportSize ?: return
        val crop = cropRect ?: return
        val expW = exp.width
        val expH = exp.height
        // Slot rotation advances ONLY on submit-success (see bottom): a
        // failed frame must retry the SAME slot next frame, not desync
        // (advancing anyway queues f1,f2 before f0 — non-monotonic pts
        // that the MTK encoder silently discards, wedging the take).
        val slotIndex = (frameCounter % 3).toInt()
        val s = slots[slotIndex]
        val chainStartNs = android.os.SystemClock.elapsedRealtimeNanos()

        // 0. Join this slot's copy job: the worker owns the P010 until
        // its copy+queue lands (~19ms of ~100ms slot slack, so this
        // never waits in practice). A timeout means the worker is
        // wedged — drop loudly rather than corrupt the slot.
        val joined = try {
            s.jobDone.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!joined) {
            Log.w(TAG, "slot $slotIndex copy stuck; dropping frame")
            frameErrors.incrementAndGet()
            dropped.incrementAndGet()
            return
        }

        // 1. Wait-free submit(s) straight into OUR P010 staging. The
        // exported fd gates the copy ~2 frames later (see reclaim).
        // Fused (one dispatch: CFA -> demosaic -> grade -> P010) is the
        // record path; superpixel + grade-YUV (two dispatches, same-queue
        // ordered) is the session fallback.
        val mc = codec ?: return
        val strides = intArrayOf(ourYStrideB, ourUvStrideB, width, height)
        val p010Buf = s.p010!!.buffer
        val fd: Int
        if (useFused) {
            val (miparams, mfparams) = VfMhc.packMhc(
                crop.width, crop.height, crop.left, crop.top, channels, pitch, levels, white
            )
            val (gidims, gfparams) = VfLogGrade.packGrade(
                intArrayOf(width, height), wbGains(result), ccmMatrix(result, staticCcm), exposureEv, false,
                profile = sessionProfile
            )
            fd = VfLogGrade.fusedYuvSubmitNative(
                hb, p010Buf, miparams, mfparams, gidims, gfparams, strides, slotIndex,
                shadeD, shadeG
            )
            if (fd < 0) {
                if (fd == -20) error("P010 staging layout mismatch (see logcat); aborting take")
                Log.w(TAG, "fused submit failed ($fd); dropping frame")
                dropped.incrementAndGet()
                return
            }
            fusedCount.incrementAndGet()
        } else if (useRcd) {
            // RCD split path (A/B only): 4 RCD modes (same-queue FIFO
            // ordered, so no CPU wait between modes) then grade-YUV
            // fullRgb=1 over the crop-sized RGB. Intermediate sync fds
            // close immediately; only the grade fd gates the copy.
            // ~233ms/frame at 4K: the take drops to a few fps effective.
            val rgbBuf = rcdRgb?.buffer
            val scratchBuf = rcdScratch?.buffer
            if (rgbBuf == null || scratchBuf == null) {
                Log.w(TAG, "rcd buffers missing; dropping frame")
                dropped.incrementAndGet()
                return
            }
            var rcdOk = true
            for (mode in 0..3) {
                val (riparams, rfparams) = VfRcd.packRcd(
                    crop.width, crop.height, crop.left, crop.top,
                    channels, pitch, mode, levels, white
                )
                val rfd = VfRcd.rcdSubmitNative(hb, rgbBuf, scratchBuf, riparams, rfparams, slotIndex)
                if (rfd < 0) {
                    Log.w(TAG, "rcd mode $mode submit failed ($rfd); dropping frame")
                    dropped.incrementAndGet()
                    rcdOk = false
                    break
                }
                try { VfEglImport.closeSyncFd(rfd) } catch (_: Exception) {}
            }
            if (!rcdOk) return
            val (gidims, gfparams) = VfLogGrade.packGrade(
                intArrayOf(width, height), wbGains(result), ccmMatrix(result, staticCcm), exposureEv, false,
                profile = sessionProfile
            )
            fd = VfLogGrade.gradeYuvSubmitNative(
                rgbBuf, p010Buf, gidims, intArrayOf(crop.width, crop.height), gfparams, strides, slotIndex, 1
            )
            if (fd == -20) {
                error("P010 staging layout mismatch (see logcat); aborting take")
            }
            if (fd < 0) {
                Log.w(TAG, "rcd grade-yuv submit failed ($fd); dropping frame")
                dropped.incrementAndGet()
                return
            }
            rcdCount.incrementAndGet()
        } else {
            val expBuf = s.export?.buffer
            if (expBuf == null) {
                Log.w(TAG, "export slot missing; dropping frame")
                dropped.incrementAndGet()
                return
            }
            val (iparams, fparams) = VfVulkan.packParams(
                channels, crop.left, crop.top, expW, expH, sessionStep, pitch, levels, white
            )
            val computeRc = VfVulkan.computeSubmitNative(hb, expBuf, iparams, fparams, slotIndex, shadeD, shadeG)
            if (computeRc != VfVulkan.OK) {
                Log.w(TAG, "compute submit failed (rc=$computeRc); dropping frame")
                dropped.incrementAndGet()
                return
            }
            val (gidims, gfparams) = VfLogGrade.packGrade(
                intArrayOf(width, height), wbGains(result), ccmMatrix(result, staticCcm), exposureEv, false,
                profile = sessionProfile
            )
            fd = VfLogGrade.gradeYuvSubmitNative(
                expBuf, p010Buf, gidims, intArrayOf(expW, expH), gfparams, strides, slotIndex, 0
            )
            if (fd == -20) {
                error("P010 staging layout mismatch (see logcat); aborting take")
            }
            if (fd < 0) {
                Log.w(TAG, "grade-yuv submit failed ($fd); dropping frame")
                dropped.incrementAndGet()
                return
            }
        }
        if (fd == -20) {
            error("P010 staging layout mismatch (see logcat); aborting take")
        }
        if (fd < 0) {
            Log.w(TAG, "grade-yuv submit failed ($fd); dropping frame")
            dropped.incrementAndGet()
            return
        }
        s.fd = fd
        s.ptsUs = cfrUs
        frameCounter++
        graded.incrementAndGet()
        if (shadeD != null) shadedCount.incrementAndGet()
        // Monitor preview for this graded frame (best-effort; never
        // throws, never gates: any skip keeps an older monitor frame).
        submitPreview(s, slotIndex)
        // Hand the P010 to the copy worker (fd travels by value; the
        // worker closes it). The camera thread never touches pixels.
        val latch = CountDownLatch(1)
        s.jobDone = latch
        // frameCounter already incremented above: jobs are 0-based contiguous.
        val seq = frameCounter - 1
        copyQueue.put(CopyJob(s.p010!!, fd, cfrUs, latch, seq, ts))
        // Submit-only pair + fd handoff: zero CPU stall by construction
        // (no blocking twin exists on the P010 path, so fallbackWaits
        // stays 0 — any reclaim-valve stall would be a GPU wedge).
        waitFree.incrementAndGet()
        val chainMs = (android.os.SystemClock.elapsedRealtimeNanos() - chainStartNs) / 1e6f
        cpuChainMsAvg = if (cpuChainMsAvg == 0f) chainMs else cpuChainMsAvg * 0.9f + chainMs * 0.1f
        if (chainMs > cpuChainMsMax) cpuChainMsMax = chainMs
        if (chainMs > 1000f / 30f) overBudget.incrementAndGet()
    }

    /**
     * Monitor preview for one graded frame (camera thread): a tiny
     * P010->RGBA8 dispatch, same-queue ordered behind the encode
     * submit above (FIFO carries the P010 write->read dependency, no
     * extra sync), then hand the buffer + completion fd to the monitor
     * view. Best-effort on every axis: detached/busy worker, busy
     * slot, or submit failure only skips the monitor frame (counted),
     * the encode already queued is unaffected. Never throws.
     */
    private fun submitPreview(s: Slot, slotIndex: Int) {
        if (!previewEnabled) return
        // This frame's latch once replaced (counted down here if the
        // offer never takes it; countDown is idempotent, so a racing
        // view release double-counts harmlessly).
        var mine: CountDownLatch? = null
        try {
            val view = previewView
            val previewBuf = s.preview?.buffer
            if (view == null || previewBuf == null) {
                previewSkipped.incrementAndGet()
                return
            }
            // Idle worker + released slot, else skip: the monitor is
            // latest-only, catching up is pointless.
            if (!view.isPreviewActive || !view.isIdleForOffer()) {
                previewSkipped.incrementAndGet()
                return
            }
            if (s.previewDone.count > 0L) {
                previewSkipped.incrementAndGet()
                return
            }
            val latch = CountDownLatch(1)
            s.previewDone = latch
            mine = latch
            val pfd = VfRecordPreview.previewSubmitNative(
                s.p010!!.buffer, previewBuf,
                intArrayOf(previewWidth, previewHeight),
                intArrayOf(width, height),
                intArrayOf(ourYStrideB, ourUvStrideB, width, height),
                previewBox, slotIndex
            )
            if (pfd < 0) {
                latch.countDown()
                previewSkipped.incrementAndGet()
                if (!previewErrorLogged) {
                    previewErrorLogged = true
                    Log.w(TAG, "preview submit failed ($pfd); monitor skipping (encode unaffected)")
                }
                return
            }
            view.offerPreview(previewBuf, pfd, previewRotation, previewMirrored) {
                latch.countDown()
            }
            previewCount.incrementAndGet()
        } catch (e: Exception) {
            // Paranoia only (offerPreview is contractually non-throwing):
            // never break the encode for the monitor. No fd cleanup here
            // by design: a double-close could hit a recycled fd number,
            // while a leaked fd on an impossible path is harmless.
            try {
                mine?.countDown()
            } catch (_: Exception) {
            }
            Log.w(TAG, "preview failed (${e.message}); monitor skipping")
            previewSkipped.incrementAndGet()
        }
    }

    /**
     * Codec input layout (plane metadata, CPU-visible) for the copy
     * destination size. Strides are session-stable; read once.
     */
    private fun discoverCodecStrides(image: Image) {
        val planes = image.planes
        require(planes.size >= 3) { "codec input is not P010" }
        yStrideB = planes[0].rowStride
        uvStrideB = planes[1].rowStride
        vStrideB = planes[2].rowStride
        yPxStrideB = planes[0].pixelStride
        uvPxStrideB = planes[1].pixelStride
        vPxStrideB = planes[2].pixelStride
        require(yStrideB > 0 && uvStrideB > 0 && vStrideB > 0) { "bad codec strides" }
        // Flexible-YUV U/V are comps of ONE interleaved plane (pxStride 4,
        // V base = U base + 2 — visible as remaining differing by 2), not
        // two planes: Y+U+V double-counts chroma. Queue size comes from
        // the input capacity (see processCopyJob), never from strides.
        val uRem = planes[1].buffer.remaining()
        val vRem = planes[2].buffer.remaining()
        Log.i(TAG, "codec P010 yStride=$yStrideB(${yPxStrideB}Bpx) " +
            "uStride=$uvStrideB(${uvPxStrideB}Bpx) vStride=$vStrideB(${vPxStrideB}Bpx) " +
            "nplanes=${planes.size} uRem=$uRem vRem=$vRem")
    }

    /**
     * Copy worker: fence-wait + P010 copy + codec queue on a dedicated
     * thread, ~100ms behind the camera thread (3 slots). The camera
     * thread only joins (normally instant: the worker finished ~80ms
     * ago) + submits (~5ms), so copy spikes (DRAM contention, measured
     * to 41ms) and fence waits absorb into slot slack instead of blowing
     * the 33ms frame. Jobs are FIFO: pts stay monotonic by construction.
     * Ownership: the fd travels BY VALUE in the job and closes exactly
     * once in the job finally; slot fields stay camera-owned.
     */
    private data class CopyJob(
        val p010: HardwareBufferRef,
        val fd: Int,
        val ptsUs: Long,
        val done: CountDownLatch,
        /** Submit order (0-based contiguous): the queue gate keys on this. */
        val seq: Long,
        /** Sensor timestamp (boot-time ns) for the stab frame table. */
        val sensorTsNs: Long,
    )

    /** A filled codec input awaiting its ordered turn at queueInputBuffer. */
    private class ReadyQueue(
        val inIdx: Int,
        val image: android.media.Image?,
        val size: Int,
    )

    private val copyQueue = LinkedBlockingQueue<CopyJob>()
    @Volatile private var copyPoisoned = false
    private var copyThread: Thread? = null
    private var copyThread2: Thread? = null
    /** Worker-side: first input dequeued (codec pool primed). */
    @Volatile private var codecPrimed = false
    /**
     * Ordered queue gate for the two copy workers: copies run in
     * parallel, but queueInputBuffer retires strictly in submit order
     * (C2 silently discards out-of-order works). Guarded by [queueGate].
     */
    private val queueGate = java.lang.Object()
    private var nextQueueSeq = 0L

    private fun startCopyWorker() {
        if (copyThread?.isAlive == true) return
        copyQueue.clear()
        copyPoisoned = false
        codecPrimed = false
        nextQueueSeq = frameCounter
        val handler = Thread.UncaughtExceptionHandler { _, e ->
            Log.w(TAG, "copy worker died: ${e.message}")
        }
        // Two workers: each copy is ~25ms of DRAM moves (dest-write
        // bound), so one worker barely covers the 33ms period; two share
        // the queue and retire in order through the gate below.
        copyThread = Thread({ copyLoop() }, "DirectLogCopyA").apply {
            uncaughtExceptionHandler = handler
            start()
        }
        copyThread2 = Thread({ copyLoop() }, "DirectLogCopyB").apply {
            uncaughtExceptionHandler = handler
            start()
        }
    }

    /**
     * Drain the tail (every graded frame already has a job queued) and
     * stop the worker. Must run before EOS: the muxer must not close
     * while jobs still queue.
     */
    private fun stopCopyWorker() {
        // Both workers drain the pending jobs, then see the flag on
        // their next idle poll and exit (queues are null-hostile: no
        // poison). The queue gate keeps the drain in submit order.
        copyPoisoned = true
        try {
            copyThread?.join(10_000)
        } catch (_: InterruptedException) {}
        try {
            copyThread2?.join(10_000)
        } catch (_: InterruptedException) {}
        if (copyThread?.isAlive == true || copyThread2?.isAlive == true) {
            Log.w(TAG, "copy worker stuck; tail frames lost")
        }
        copyThread = null
        copyThread2 = null
    }

    private fun copyLoop() {
        while (true) {
            val job = try {
                copyQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            }
            if (job == null) {
                if (copyPoisoned && copyQueue.isEmpty()) return
                continue
            }
            // Copy phase (parallel across workers) then the ordered queue
            // gate (every job passes exactly once, in submit order: C2
            // silently discards out-of-order works). The slot releases as
            // soon as its copy lands — the queue may lag behind without
            // pinning the slot (it only touches the codec buffer).
            val ready = try {
                processCopyJob(job)
            } catch (e: Exception) {
                Log.w(TAG, "copy job failed", e)
                frameErrors.incrementAndGet()
                null
            } finally {
                try { VfEglImport.closeSyncFd(job.fd) } catch (_: Exception) {}
                job.done.countDown()
            }
            synchronized(queueGate) {
                while (job.seq != nextQueueSeq) queueGate.wait()
                try {
                    if (ready != null) queueReady(job, ready)
                } finally {
                    nextQueueSeq++
                    queueGate.notifyAll()
                }
            }
        }
    }

    /**
     * Copy phase of one job (parallel across workers): wait the submit
     * fence, copy P010 staging into a fresh codec input, hand back a
     * [ReadyQueue] for the ordered gate (null = nothing to queue; the
     * gate still advances). Stalls here cost slot slack, not frames.
     * Starved pool drops the JOB's frame (already graded — counted in
     * framesDropped like any other loss).
     */
    private fun processCopyJob(job: CopyJob): ReadyQueue? {
        val mc = codec
        if (mc == null) {
            dropped.incrementAndGet()
            return null
        }
        // Fence wait = GPU queue time still outstanding when the worker
        // arrives (submit-to-signal minus travel): sustained high here
        // means the GPU, not the CPU, is the bottleneck.
        val fenceStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        if (!VfEglImport.pollSyncFd(job.fd)) {
            // Not ready long after submit: pathological. Blocking valve
            // (rare) instead of corrupting the slot.
            val deadline = System.currentTimeMillis() + 100
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                if (VfEglImport.pollSyncFd(job.fd)) {
                    ready = true
                    break
                }
                Thread.sleep(2)
            }
            if (!ready) {
                Log.w(TAG, "copy job: GPU wedge")
                frameErrors.incrementAndGet()
                dropped.incrementAndGet()
                return null
            }
        }
        val fenceWaitMs = (android.os.SystemClock.elapsedRealtimeNanos() - fenceStartNs) / 1e6f
        fenceWaitMsAvg = if (fenceWaitMsAvg == 0f) fenceWaitMs else fenceWaitMsAvg * 0.9f + fenceWaitMs * 0.1f
        if (fenceWaitMs > fenceWaitMsMax) fenceWaitMsMax = fenceWaitMs
        // Cold-start priming: C2 answers TRY_AGAIN while its input pool
        // comes up (~ms at take start), so the first jobs may wait; once
        // primed, dequeue is instant again. A 50ms grace past priming
        // absorbs transient pool pressure: during backlog bursts both
        // workers hold an input each (copy ~25ms + ordered-gate wait) while
        // the codec holds several more, briefly exceeding MTK's small pool
        // (measured starves on cool takes with a healthy drain). The wait
        // sits on the worker, off the camera thread, inside slot slack
        // (~100ms). A dry pool past the grace still means a wedged drain:
        // starve loudly (with the seq for correlation), don't paper over it.
        val inIdx = try {
            mc.dequeueInputBuffer(if (codecPrimed) 50_000L else 5_000L)
        } catch (_: Exception) {
            -1
        }
        if (inIdx >= 0) codecPrimed = true
        if (inIdx < 0) {
            Log.w(TAG, "codec input dry past grace (seq=${job.seq}); starving frame")
            inputStarved.incrementAndGet()
            dropped.incrementAndGet()
            return null
        }
        if (yStrideB == 0) {
            // Capacity from the raw buffer BEFORE wrapping an image:
            // getInputBuffer() after getInputImage() orphans the MediaImage
            // on C2 (the next planes access throws "already closed",
            // killing the discovery frame + leaking its index). Order is
            // load-bearing; the stride probe below then owns the image.
            val cap = try { mc.getInputBuffer(inIdx)?.capacity() ?: 0 } catch (_: Exception) { 0 }
            if (cap <= 0) {
                degradedFrames.incrementAndGet()
                return ReadyQueue(inIdx, null, 0)
            }
            p010Size = cap
        }
        val inImage = try {
            mc.getInputImage(inIdx)
        } catch (_: Exception) {
            null
        }
        if (inImage == null) {
            degradedFrames.incrementAndGet()
            return ReadyQueue(inIdx, null, 0)
        }
        if (yStrideB == 0) {
            try {
                discoverCodecStrides(inImage)
                Log.i(TAG, "codec input queue size=$p010Size (capacity, not Y+U+V)")
            } catch (e: Exception) {
                degradedFrames.incrementAndGet()
                return ReadyQueue(inIdx, inImage, 0)
            }
        }
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        val yPlane = inImage.planes[0].buffer
        val uPlane = inImage.planes[1].buffer
        val vPlane = inImage.planes[2].buffer
        val rc = VfEglImport.copyP010ToCodec(
            job.p010.buffer,
            yPlane, yStrideB, uPlane, uvStrideB, uvPxStrideB, vPlane, vStrideB, vPxStrideB,
            width, height, ourYStrideB, ourUvStrideB
        )
        val copyMs = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        copyMsAvg = if (copyMsAvg == 0f) copyMs else copyMsAvg * 0.9f + copyMs * 0.1f
        if (copyMs > copyMsMax) copyMsMax = copyMs
        if (rc != 0) {
            Log.w(TAG, "P010 copy rc=$rc; recycling empty input")
            degradedFrames.incrementAndGet()
            return ReadyQueue(inIdx, inImage, 0)
        }
        return ReadyQueue(inIdx, inImage, p010Size)
    }

    /**
     * Ordered retire of one filled input (inside the queue gate: strictly
     * in submit order). Owns the monotonic guard, the queue call and the
     * image close. Gate thread only (either worker, serialized).
     */
    private fun queueReady(job: CopyJob, ready: ReadyQueue) {
        val mc = codec
        if (mc == null) {
            degradedFrames.incrementAndGet()
            try { ready.image?.close() } catch (_: Exception) {}
            return
        }
        // Monotonicity guard: C2 silently discards late works, so a
        // backward step here wedges the take — never let one pass quiet.
        if (job.ptsUs <= lastQueuedPts && queues > 0) {
            Log.w(TAG, "queued pts non-monotonic n=$queues pts=${job.ptsUs} last=$lastQueuedPts")
        }
        lastQueuedPts = job.ptsUs
        queues++
        var queuedOk = false
        try {
            mc.queueInputBuffer(ready.inIdx, 0, ready.size, job.ptsUs, 0)
            queuedOk = true
        } catch (e: Exception) {
            Log.w(TAG, "input queue failed: ${e.message}")
            // A failed queue does NOT recycle the index: re-queue empty so
            // the buffer returns to the pool instead of dying here (a dry
            // pool wedges the whole take with inputStarved).
            try {
                mc.queueInputBuffer(ready.inIdx, 0, 0, job.ptsUs, 0)
                queuedOk = true
            } catch (_: Exception) {}
            degradedFrames.incrementAndGet()
        } finally {
            try { ready.image?.close() } catch (_: Exception) {}
        }
        // Stab frame table: exactly the queued frames, in mux order (the
        // gate serializes this, so the plain list append is race-free).
        if (queuedOk && stabTableArmed) stabFrameTs.add(job.sensorTsNs)
        // Encode-latency arm: the drain matches outputs by PTS.
        if (queuedOk) {
            synchronized(queuedPtsLock) {
                queuedPtsNs[job.ptsUs] = android.os.SystemClock.elapsedRealtimeNanos()
                while (queuedPtsNs.size > 128) {
                    val eldest = queuedPtsNs.keys.iterator()
                    if (!eldest.hasNext()) break
                    eldest.next()
                    eldest.remove()
                }
            }
        }
    }

    /**
     * Per-frame lens-shading snapshot: the HAL gain map from this frame's
     * result (exact or stale — same object that feeds WB/CCM), validated
     * once and cached by content key (the map is session-static, so the
     * steady state is a pointer return). A missing/invalid map keeps the
     * previous take state (usually null = unshaded frame 1, then armed).
     * Camera thread only.
     */
    private fun snapshotShading(result: TotalCaptureResult?): Pair<IntArray?, ShortArray?> {
        if (!correctShading || shadeHalApplied) return null to null
        val c = chars ?: return shadeDims to shadeGains
        val map = try {
            result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (_: Exception) {
            null
        } ?: return shadeDims to shadeGains
        val rows = map.rowCount
        val cols = map.columnCount
        if (rows <= 0 || cols <= 0) return shadeDims to shadeGains
        val gains = FloatArray(map.gainFactorCount)
        map.copyGainFactors(gains, 0)
        val key = VfGpuImport.lensContentKey(cols, rows, gains)
        if (key == shadeKey && shadeDims != null && shadeGains != null) {
            return shadeDims to shadeGains
        }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val size = rawSize
        val packed = packShading(
            rows, cols,
            active?.left ?: 0, active?.top ?: 0,
            active?.right ?: (size?.width ?: 0), active?.bottom ?: (size?.height ?: 0),
            gains
        )
        if (packed == null) {
            if (!shadeLogged) {
                shadeLogged = true
                Log.w(TAG, "lens-shading map ${cols}x$rows rejected (shape/content); take runs unshaded")
            }
            return shadeDims to shadeGains
        }
        shadeKey = key
        shadeDims = packed.first
        shadeGains = packed.second
        // The HAL may adapt the map per frame (re-pack is correct); log
        // the arm once per take, not per change.
        if (!shadeLogged) {
            shadeLogged = true
            Log.i(TAG, "lens-shading live ${cols}x$rows active=${packed.first[2]},${packed.first[3]}..${packed.first[4]},${packed.first[5]}")
        }
        return shadeDims to shadeGains
    }

    /**
     * Non-blocking result lookup. WB/CCM drift slowly frame-to-frame, so a
     * one-frame-old result is harmless for log — but blocking the camera
     * thread on the exact timestamp caps throughput at ~7fps (measured).
     * Exact hit preferred, previous frame's result as stale fallback, miss
     * only when nothing exists yet (recording start).
     */
    private fun awaitResult(ts: Long): TotalCaptureResult? {
        val hit = synchronized(resultLock) {
            // Entries older than this frame can never match (timestamps are
            // capture-ordered on one thread); purge so the map stays tiny.
            val it = captureResults.entries.iterator()
            while (it.hasNext()) {
                if (it.next().key < ts) it.remove()
            }
            captureResults.remove(ts)
        }
        if (hit != null) {
            lastResult = hit
            return hit
        }
        // Opportunistic drain of already-arrived results keeps the map small
        // and makes the next exact hit more likely; never waits.
        val observer = lastResult
        if (observer == null) resultMiss.incrementAndGet()
        else staleResults.incrementAndGet()
        return observer
    }

    // ---- drain thread -> muxer ----

    /** Starts the muxer once every required track format is known. */
    private fun maybeStartMuxer() {
        if (muxerStarted) return
        val vf = videoFormat ?: return
        if (audioRequired && audioFormat == null) return
        val mux = muxer ?: return
        try {
            videoTrack = mux.addTrack(vf)
            if (audioRequired) {
                audioTrack = mux.addTrack(audioFormat!!)
            }
            mux.start()
            muxerStarted = true
            Log.i(TAG, "muxer started: ${vf.getString(MediaFormat.KEY_MIME)} " +
                "${vf.getInteger(MediaFormat.KEY_WIDTH)}x${vf.getInteger(MediaFormat.KEY_HEIGHT)}" +
                (if (audioRequired) " +aac fmt=${audioFormat}" else ""))
        } catch (e: Exception) {
            Log.w(TAG, "muxer start failed: ${e.message}")
        }
    }

    private fun writeAudioQueue() {
        if (!muxerStarted || audioTrack < 0) return
        var chunk = muxAudioQueue.poll()
        while (chunk != null) {
            try {
                // Codec-config carries the csd (already in the track format).
                if (chunk.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && chunk.data.remaining() > 0) {
                    val info = MediaCodec.BufferInfo()
                    info.set(0, chunk.data.remaining(), chunk.ptsUs, chunk.flags)
                    muxer?.writeSampleData(audioTrack, chunk.data, info)
                    audioOutBufs.incrementAndGet()
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio write failed: ${e.message}")
                break
            }
            chunk = muxAudioQueue.poll()
        }
    }

    private fun drainLoop() {
        val mc = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (draining.get()) {
            val idx = try {
                mc.dequeueOutputBuffer(info, 2000)
            } catch (e: Exception) {
                Log.w(TAG, "drain dequeue failed: ${e.message}")
                break
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        videoFormat = mc.outputFormat
                        maybeStartMuxer()
                    } catch (e: Exception) {
                        Log.w(TAG, "video format failed: ${e.message}")
                        break
                    }
                }
                idx >= 0 -> {
                    try {
                        if (info.size > 0 && muxerStarted && videoTrack >= 0) {
                            val w0 = android.os.SystemClock.elapsedRealtimeNanos()
                            muxer?.writeSampleData(videoTrack, mc.getOutputBuffer(idx)!!, info)
                            val w1 = android.os.SystemClock.elapsedRealtimeNanos()
                            if (firstOutNs == 0L) firstOutNs = w1
                            val writeMs = (w1 - w0) / 1e6f
                            muxWriteMsAvg = if (muxWriteMsAvg == 0f) writeMs
                            else muxWriteMsAvg * 0.9f + writeMs * 0.1f
                            outBufs.incrementAndGet()
                            outBytes.addAndGet(info.size.toLong())
                        }
                        // Encode latency: queue -> drained output by PTS.
                        val queuedAt = synchronized(queuedPtsLock) { queuedPtsNs.remove(info.presentationTimeUs) }
                        if (queuedAt != null) {
                            val latMs = (android.os.SystemClock.elapsedRealtimeNanos() - queuedAt) / 1e6f
                            encLatencyMsAvg = if (encLatencyMsAvg == 0f) latMs
                            else encLatencyMsAvg * 0.9f + latMs * 0.1f
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            mc.releaseOutputBuffer(idx, false)
                            eosSeen.countDown()
                            break
                        }
                        mc.releaseOutputBuffer(idx, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "drain failed: ${e.message}")
                        try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    }
                }
            }
            writeAudioQueue()
        }
        // Tail: keep draining briefly after stop() signals EOS.
        try {
            var eos = false
            val end = System.currentTimeMillis() + 8_000
            while (!eos && System.currentTimeMillis() < end) {
                val idx = try {
                    mc.dequeueOutputBuffer(info, 1000)
                } catch (_: Exception) {
                    break
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            videoFormat = mc.outputFormat
                            maybeStartMuxer()
                        } catch (_: Exception) {}
                    }
                    idx >= 0 -> {
                        try {
                            if (info.size > 0 && muxerStarted && videoTrack >= 0) {
                                muxer?.writeSampleData(videoTrack, mc.getOutputBuffer(idx)!!, info)
                                if (firstOutNs == 0L) firstOutNs = android.os.SystemClock.elapsedRealtimeNanos()
                                outBufs.incrementAndGet()
                                outBytes.addAndGet(info.size.toLong())
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                            mc.releaseOutputBuffer(idx, false)
                        } catch (_: Exception) {
                            try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                        }
                    }
                }
            }
            writeAudioQueue()
        } finally {
            eosSeen.countDown()
        }
    }

    // ---- camera plumbing (mirrors RawVideoRecorder session shape) ----

    private fun openCamera(handler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var error: String? = null
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                opened = d
                latch.countDown()
            }

            override fun onDisconnected(d: CameraDevice) {
                error = "disconnected"
                latch.countDown()
            }

            override fun onError(d: CameraDevice, code: Int) {
                error = "error=$code"
                latch.countDown()
            }
        }, handler)
        check(latch.await(10, TimeUnit.SECONDS)) { "Camera open timed out" }
        if (opened == null) throw IllegalStateException("Camera open failed: $error")
        return opened!!
    }

    private fun createSession(handler: Handler): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null
        var error: String? = null
        val r = reader!!
        val config = if (Build.VERSION.SDK_INT >= 28) {
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(r.surface)),
                java.util.concurrent.Executor { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured = s
                        latch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        error = "configureFailed"
                        latch.countDown()
                    }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            device!!.createCaptureSession(
                listOf(r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured = s
                        latch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        error = "configureFailed"
                        latch.countDown()
                    }
                },
                handler
            )
            null
        }
        if (config != null) device!!.createCaptureSession(config)
        check(latch.await(10, TimeUnit.SECONDS)) { "Session configure timed out" }
        if (configured == null) throw IllegalStateException("Session failed: $error")
        return configured!!
    }

    private fun teardown() {
        accepting = false
        // Motion listener off (start-failure path lands here with the
        // recorder mid-take; the stop path already stopped it — idempotent).
        stopStabMotion()
        try { pcm?.stop() } catch (_: Exception) {}
        pcm = null
        try { aac?.stop() } catch (_: Exception) {}
        aac = null
        // Drain the camera thread first: quitSafely + join waits out any
        // in-flight onFrame so no GPU work races the teardown below.
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { camThread?.quitSafely() } catch (_: Exception) {}
        try { camThread?.join(5_000) } catch (_: InterruptedException) {}
        camThread = null
        camHandler = null
        // Monitor off first: detaching flushes any queued preview (its
        // latch releases), then join in-flight presents (bounded) before
        // the preview buffers below are closed — the view samples them
        // until its glFinish, which precedes its latch countdown.
        try {
            previewView?.setPreviewAttached(false)
        } catch (_: Exception) {
        }
        previewView = null
        for (s in slots) {
            try {
                if (!s.previewDone.await(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "preview present stuck; closing anyway")
                }
            } catch (_: InterruptedException) {
            }
        }
        // Release any still-held fds (stop-flush failures).
        for (s in slots) {
            if (s.fd != -1) {
                try { VfEglImport.closeSyncFd(s.fd) } catch (_: Exception) {}
                s.fd = -1
            }
            try { s.export?.close() } catch (_: Exception) {}
            s.export = null
            try { s.p010?.close() } catch (_: Exception) {}
            s.p010 = null
            try { s.preview?.close() } catch (_: Exception) {}
            s.preview = null
        }
        try { rcdRgb?.close() } catch (_: Exception) {}
        rcdRgb = null
        try { rcdScratch?.close() } catch (_: Exception) {}
        rcdScratch = null
        try { VfVulkan.resetNative() } catch (_: Exception) {}
        val mc = codec
        codec = null
        try { mc?.stop() } catch (_: Exception) {}
        try { mc?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "DirectLog"

        /**
         * Recorded backlog bound (frames): the callback collects what's
         * waiting and records it in order, shedding oldest-first only
         * beyond this. 6 absorbs ~200ms camera stalls (RT preemption,
         * copy/GPU spikes — measured to 132ms) while the reader's 8
         * slots leave headroom for stalls mid-catch-up.
         */
        private const val QUEUE_DEPTH = 6

        /**
         * Pre-result drop budget (frames): result-less frames are dropped
         * only before the first graded frame and only up to this many —
         * the first capture callback lands within a frame or two of
         * stream start, so this is generous; past it a HAL that never
         * delivers results still records (with fallback colors) instead
         * of wedging the take behind the no-frames watchdog.
         */
        internal const val MAX_PRERESULT_DROPS = 10

        /**
         * Pre-result gate: true while a result-less frame must be dropped
         * instead of recorded with probe-WB fallback colors. [resultPresent]
         * is false only before the first capture callback; [graded] is the
         * take's graded count; [acquired] (1-based, current frame included)
         * bounds the wait.
         */
        internal fun shouldDropPreresult(resultPresent: Boolean, graded: Int, acquired: Int): Boolean =
            !resultPresent && graded == 0 && acquired <= MAX_PRERESULT_DROPS

        /** Recording-relative presentation time in microseconds. */
        fun relativeUs(sensorTsNs: Long, originNs: Long): Long = (sensorTsNs - originNs) / 1000L

        /**
         * Muxed output rate in fps: [samples] video samples muxed between
         * [firstOutNs] and [nowNs] (both elapsedRealtimeNanos). Anchored at
         * the first muxed sample so camera/encoder bring-up (~1.5s of dead
         * time before the first output) never reads as a deficit; needs 2+
         * samples (a single sample spans no interval yet).
         */
        fun muxedFps(samples: Int, firstOutNs: Long, nowNs: Long): Float =
            if (samples > 1 && firstOutNs > 0L && nowNs > firstOutNs)
                samples * 1e9f / (nowNs - firstOutNs)
            else 0f

        /**
         * Constant-frame-rate PTS in microseconds: frame [frameIndex]
         * (0-based submit order) on the ideal 1/[fps] grid. Multiply-first
         * keeps the grid drift-free (divide-first truncates ~10ns/s at 30fps);
         * consecutive gaps land on 33333/33334us at 30fps, 41666/41667us at 24fps.
         */
        fun constantPtsUs(frameIndex: Long, fps: Int): Long {
            require(fps > 0) { "fps must be positive" }
            require(frameIndex >= 0) { "frameIndex must be non-negative" }
            return frameIndex * 1_000_000L / fps
        }

        /**
         * Grade WB gains from the capture result (green channels averaged
         * for the demosaiced-by-average green). Falls back to the probe
         * gains when the result carries none.
         */
        fun wbGains(result: TotalCaptureResult?): FloatArray {
            val g = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (g == null) return VfLogGrade.PROBE_GAINS.copyOf()
            return floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue, 1f)
        }

        /** Grade CCM row-major from the capture result, else identity. */
        /**
         * Per-frame CCM: the live framework transform when the result
         * carries one, else the calibrated static bake, else identity.
         * Android applies WB gains first and the transform second, so a
         * constant matrix stays consistent with the dynamic gains.
         */
        fun ccmMatrix(result: TotalCaptureResult?, staticFallback: FloatArray?): FloatArray {
            val live = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            if (live != null) return ccmFromTransform(live)
            return staticFallback?.copyOf() ?: ccmFromTransform(null)
        }

        /**
         * Row-major CCM from a [ColorSpaceTransform] (getElement takes
         * [column, row]!), else identity. Split for JVM tests: the row
         * index must be i/3 (i/2 reads out-of-bounds rows past element
         * 5).
         */
        fun ccmFromTransform(t: ColorSpaceTransform?): FloatArray {
            if (t == null) return VfLogGrade.IDENTITY_CCM.copyOf()
            return FloatArray(9) { i ->
                t.getElement(i % 3, i / 3).toFloat()
            }
        }

        /**
         * Validate + pack a HAL lens-shading map for the native record
         * stages: dims [rows, cols, l, t, r, b] + the Camera2-order gains
         * as fp16 bits (the GPU samples a small RGBA16F texture with
         * hardware bilinear — 16 manual loads + weight ALU per texel cost
         * ~7ms on the fused kernel (measured), a texture fetch ~0.5ms).
         * Null when the map is unusable (the take continues unshaded).
         * Content rules (finite, >= 1) come from [LensShadingModel]; the
         * 64-cell cap mirrors the native image guard.
         */
        fun packShading(
            rows: Int, cols: Int,
            left: Int, top: Int, right: Int, bottom: Int,
            gains: FloatArray
        ): Pair<IntArray, ShortArray>? {
            if (rows <= 0 || cols <= 0 || rows > 64 || cols > 64) return null
            if (gains.size != rows * cols * 4) return null
            try {
                LensShadingModel(rows, cols, gains, IntRectSnapshot(left, top, right, bottom), false)
            } catch (_: Exception) {
                return null
            }
            return intArrayOf(rows, cols, left, top, right, bottom) to
                ShortArray(gains.size) { i -> floatToHalfBits(gains[i]) }
        }

        /**
         * IEEE-754 fp32 -> fp16 bits (round-to-nearest-even; overflow ->
         * infinity, NaN stays NaN). Pure Kotlin (no android.util.Half, so
         * JVM tests pin exact bits). Gains are finite and >= 1 by the
         * time they reach here; the edges exist for safety, not for HALs.
         */
        fun floatToHalfBits(f: Float): Short {
            val bits = f.toRawBits()
            val sign = (bits ushr 16) and 0x8000
            var exp = ((bits ushr 23) and 0xff) - 112
            var mant = bits and 0x7fffff
            if (exp <= 0) {
                // Subnormal-or-zero in fp16: gains never land here (>= 1),
                // but flush correctly anyway.
                if (exp < -10) return sign.toShort()
                mant = mant or 0x800000
                val shift = 14 - exp
                var half = mant shr shift
                // Round to nearest even on the shifted-out bits.
                val mask = (1 shl shift) - 1
                val rest = mant and mask
                val halfway = 1 shl (shift - 1)
                if (rest > halfway || (rest == halfway && (half and 1) != 0)) half++
                return (sign or half).toShort()
            }
            if (exp >= 31) {
                // Overflow -> Inf; NaN (mant != 0) stays quiet NaN.
                return (sign or 0x7c00 or (if (mant == 0) 0 else (mant shr 13) or 1)).toShort()
            }
            // Normalized: round mantissa 23 -> 10 bits, RNE.
            var half = mant shr 13
            val rest = mant and 0x1fff
            if (rest > 0x1000 || (rest == 0x1000 && (half and 1) != 0)) {
                half++
                if (half == 0x400) {
                    half = 0
                    exp++
                    if (exp >= 31) return (sign or 0x7c00).toShort()
                }
            }
            return (sign or (exp shl 10) or half).toShort()
        }

        /** Black levels (dynamic ?: static pattern) + white (dynamic ?: static). */
        fun blackWhite(c: CameraCharacteristics, result: TotalCaptureResult?): Pair<FloatArray, Float> {
            val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val dynamicBlack = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            val white = (result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val levels = FloatArray(4) { i ->
                dynamicBlack?.get(i) ?: black?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 0f
            }
            return levels to white
        }
    }
}

/** Small RAII holder so Slot fields stay null-safe across teardown. */
private class HardwareBufferRef(val buffer: android.hardware.HardwareBuffer) {
    fun close() {
        try { buffer.close() } catch (_: Exception) {}
    }

    companion object {
        /** RGBA16F export: >8-bit precision end to end for the 10-bit encode. */
        fun create(w: Int, h: Int): HardwareBufferRef = HardwareBufferRef(
            android.hardware.HardwareBuffer.create(
                w, h, android.hardware.HardwareBuffer.RGBA_FP16, 1,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    android.hardware.HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
        )

        /**
         * RGBA8 record-preview: Vulkan STORAGE_IMAGE target, EGL-sampled
         * by the monitor view. Same usage set as the RAW viewfinder's
         * Vulkan export (proven EGL-importable + storage-writable).
         */
        fun createRgba8(w: Int, h: Int): HardwareBufferRef = HardwareBufferRef(
            android.hardware.HardwareBuffer.create(
                w, h, android.hardware.HardwareBuffer.RGBA_8888, 1,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    android.hardware.HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
        )
    }
}
