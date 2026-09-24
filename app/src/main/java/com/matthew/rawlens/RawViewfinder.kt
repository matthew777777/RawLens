// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20.*
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Quad-sampling geometry for one viewfinder offer: even-aligned origin, even step. */
internal data class VfQuadGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val step: Int
)

/** Geometry is expressed in the portrait-locked preview's coordinates, not handset orientation. */
internal object RawPreviewGeometry {
    fun sensorPoint(x: Float, y: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        val u = if (mirrored) 1f - x else x
        return when (rotation) {
            90 -> y to 1f - u
            180 -> 1f - u to 1f - y
            270 -> 1f - y to u
            else -> u to y
        }
    }

    fun channels(cfa: Int): IntArray = when (cfa) {
        0 -> intArrayOf(0, 1, 2, 3) // RGGB: R, Gr, Gb, B
        1 -> intArrayOf(1, 0, 3, 2) // GRBG
        2 -> intArrayOf(2, 3, 0, 1) // GBRG
        3 -> intArrayOf(3, 2, 1, 0) // BGGR
        else -> throw IllegalArgumentException("Unsupported Bayer layout $cfa")
    }

    /**
     * Quad grid covering [crop] (or the full frame) at roughly [longEdge] px:
     * the step is the smallest even integer holding the budget (2 minimum),
     * so output extents stay at or under the budget on every sensor size.
     */
    fun quadGeometry(imageWidth: Int, imageHeight: Int, crop: Rect?, longEdge: Int): VfQuadGeometry {
        val left = ((crop?.left ?: 0).coerceIn(0, imageWidth - 2) / 2) * 2
        val top = ((crop?.top ?: 0).coerceIn(0, imageHeight - 2) / 2) * 2
        val right = (crop?.right ?: imageWidth).coerceIn(left + 2, imageWidth)
        val bottom = (crop?.bottom ?: imageHeight).coerceIn(top + 2, imageHeight)
        val step = max(2, ((max(right - left, bottom - top) + longEdge - 1) / longEdge + 1) / 2 * 2)
        return VfQuadGeometry(left, top, (right - left) / step, (bottom - top) / step, step)
    }
}

/** Snapshot for the RAW viewfinder debug overlay. All sizes are in pixels. */
data class RawVfStats(
    val fps: Float,
    val frameMs: Float,
    val vfWidth: Int,
    val vfHeight: Int,
    val rawWidth: Int,
    val rawHeight: Int,
    val glActive: Boolean,
    val starved: Boolean,
    /** True when the last rendered frame took the zero-copy GPU path (false = NEON CPU). */
    val gpu: Boolean = false,
    /** Tonemap actually rendered: true = JPEG/AgX-lite, false = RAW/Reinhard. */
    val jpeg: Boolean = false,
    /** Adaptive preview EV applied by the JPEG tonemap (0 in RAW mode). */
    val exposureEv: Float = 0f,
    /** Engine override selected by tapping the RAW VF debug overlay. */
    val engine: VfEngineMode = VfEngineMode.AUTO
)

/**
 * Three reusable sampled-Bayer buffers; one latest pending frame. Never owns a camera Image.
 *
 * A [SurfaceView] (not TextureView) so the GPU phase owns the present path: the EGL window
 * surface talks directly to SurfaceFlinger, eglSwapBuffers never round-trips through a
 * SurfaceTexture, and an EGL fence guards the previous frame so the CPU never waits on the
 * GPU. Overlay geometry is unchanged: MainActivity sizes this view from the system preview
 * rect (syncGuideOverlayToViewfinder), so guide/metering overlays keep their alignment.
 */
class RawViewfinder @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {
    /**
     * Per-frame WYSIWYG snapshot shared by the NEON CPU and GPU paths: same WB gains,
     * same CCM, same JPEG flag + 7 AgX params, copied from volatiles in offer() so a
     * settings change never tears a frame in flight.
     */
    private interface FrameState {
        val gains: FloatArray
        val matrix: FloatArray
        var jpeg: Boolean
        var exposureEv: Float
        var agxContrast: Float
        var agxSaturation: Float
        var agxPurity: Float
        var agxHue: Float
        var agxShadowEv: Float
        var agxHighlightEv: Float
        var agxGamut: Float
        var highlightShoulder: Float
        var rotation: Int
        var mirrored: Boolean
        var timestamp: Long
        // Zero-copy lens-shading (vignetting) snapshot: tiny HAL gain map packed as
        // a single RGBA16F texture and applied with one hardware-filtered fetch in
        // shader, so the Bayer buffer itself is never copied. Null gains or
        // lensApply=false keeps the identity path.
        var lensGains: FloatArray?
        var lensRows: Int
        var lensCols: Int
        var lensLeft: Int
        var lensTop: Int
        var lensRight: Int
        var lensBottom: Int
        var lensApply: Boolean
        // Quad geometry in sensor coordinates for the lens lookup (NEON path needs it
        // explicitly; the GPU path already carries left/top/step).
        var quadLeft: Int
        var quadTop: Int
        var quadStep: Int
    }

    /**
     * Zero-copy GPU frame: owns a HardwareBuffer reference and a controller image lease,
     * plus the crop geometry and unpack constants the Bayer shader needs. Small enough
     * to allocate per frame; latest-only queueing closes superseded buffers.
     */
    private class GpuFrame(val buffer: android.hardware.HardwareBuffer, private val lease: AutoCloseable) : FrameState, AutoCloseable {
        override fun close() { try { buffer.close() } finally { lease.close() } }
        var width = 0
        var height = 0
        var left = 0
        var top = 0
        var step = 2
        var pitch = 0
        val channels = IntArray(4)
        val levels = FloatArray(4)
        var white = 1023f
        var epoch = 0L
        override val gains = FloatArray(4) { 1f }
        override val matrix = FloatArray(9)
        override var jpeg = false
        override var exposureEv = 0f
        override var agxContrast = 1f
        override var agxSaturation = 1f
        override var agxPurity = 1f
        override var agxHue = 0f
        override var agxShadowEv = 10f
        override var agxHighlightEv = 6.5f
        override var agxGamut = 0f
        override var highlightShoulder = 1f
        override var rotation = 0
        override var mirrored = false
        override var timestamp = 0L
        override var lensGains: FloatArray? = null
        override var lensRows = 1
        override var lensCols = 1
        override var lensLeft = 0
        override var lensTop = 0
        override var lensRight = 1
        override var lensBottom = 1
        override var lensApply = false
        override var quadLeft = 0
        override var quadTop = 0
        override var quadStep = 2
    }

    private class Frame : FrameState {
        // Sized from the sampler contract (square worst case): buffer and validator
        // can never disagree about the maximum extent.
        // GPU-only sessions never use CPU pixels. Keep the same fallback capacity,
        // but avoid retaining three unused 4.45 MiB direct buffers at startup.
        val pixels: ByteBuffer by lazy {
            ByteBuffer.allocateDirect(VfCpuNeon.MAX_EDGE * VfCpuNeon.MAX_EDGE * 4)
                .order(ByteOrder.nativeOrder())
        }
        var width = 0
        var height = 0
        var epoch = 0L
        override var timestamp = 0L
        override var rotation = 0
        override var mirrored = false
        override val gains = FloatArray(4) { 1f }
        override val matrix = FloatArray(9)
        // Cheap scene-referred JPEG snapshot: same WB/CCM as RAW, AgX-lite tonemap.
        // Copied from volatiles in offer() so a settings change never tears a frame.
        override var jpeg = false
        override var exposureEv = 0f
        override var agxContrast = 1f
        override var agxSaturation = 1f
        override var agxPurity = 1f
        override var agxHue = 0f
        override var agxShadowEv = 10f
        override var agxHighlightEv = 6.5f
        override var agxGamut = 0f
        override var highlightShoulder = 1f
        override var lensGains: FloatArray? = null
        override var lensRows = 1
        override var lensCols = 1
        override var lensLeft = 0
        override var lensTop = 0
        override var lensRight = 1
        override var lensBottom = 1
        override var lensApply = false
        override var quadLeft = 0
        override var quadTop = 0
        override var quadStep = 2
    }
    private val lock = Any()
    private val free = ArrayDeque<Frame>().apply { repeat(3) { add(Frame()) } }
    private var pending: Frame? = null
    /** Latest zero-copy frame; superseded entries are closed, never queued. Guarded by [lock]. */
    private var gpuPending: GpuFrame? = null
    /** Sticky NEON fallback after repeated GPU failures; reset per session. */
    @Volatile private var gpuDisabledForSession = false
    /** GL-worker-only consecutive GPU failure count driving [gpuDisabledForSession]. */
    private var consecutiveGpuFailures = 0
    private var gpuActiveLogged = false
    private var lastFallbackReason = ""
    private var stridesLogged = false
    /** Sticky Vulkan skip after repeated compute failures; reset per session. */
    @Volatile private var vulkanDisabledForSession = false
    /** GL-worker-only consecutive Vulkan failure count. */
    private var consecutiveVulkanFailures = 0
    private var vulkanInitialized = false
    private var vulkanExport: android.hardware.HardwareBuffer? = null
    private var vulkanExportWidth = 0
    private var vulkanExportHeight = 0
    /** Recovery re-probe pending on the worker; at most one is ever scheduled. GL worker only. */
    private var vulkanRecoverPosted = false
    /** Session the pending recovery belongs to; stale recoveries stand down. GL worker only. */
    private var vulkanRecoverEpoch = -1L
    /** Current recovery backoff; reset by the next rendered Vulkan frame. GL worker only. */
    private var vulkanRecoverDelayMs = VfGpuImport.VULKAN_RECOVER_INITIAL_DELAY_MS
    private val vulkanRecoverRunnable = Runnable { recoverVulkan() }
    private val thread = HandlerThread("RawViewfinderGL", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)
    private var epoch = 0L
    private var lastSample = 0L
    @Volatile var targetLongEdge = VfResolution.MAX
        private set
    /**
     * WYSIWYG render mode. False = raw-clean Reinhard preview (DNG_ONLY).
     * True = cheap scene-referred JPEG preview: same sampled superpixel + WB/CCM,
     * AgX-lite tonemap tracking [JpegOutputSettings]. Never ISP YUV.
     */
    @Volatile var renderJpeg = false
        private set
    /**
     * Engine override switched by tapping the RAW VF debug overlay. AUTO runs
     * zero-copy GPU with automatic fallback to the native NEON sampler; GPU/CPU
     * force one engine. Volatile: written from the UI thread, read on camera.
     */
    @Volatile var engineMode: VfEngineMode = VfEngineMode.AUTO
        private set
    @Volatile private var agxContrast = 1f
    @Volatile private var agxSaturation = 1f
    @Volatile private var agxPurity = 1f
    @Volatile private var agxHue = 0f
    @Volatile private var agxShadowEv = 10f
    @Volatile private var agxHighlightEv = 6.5f
    @Volatile private var agxGamut = 0f
    @Volatile private var highlightShoulder = 1f
    /** Preview highlight guards (hard p99.5 / soft p95), mirroring the save path. */
    @Volatile private var previewHeadroom = 1f
    @Volatile private var previewSoftHeadroom = 0.85f
    /**
     * Applied adaptive preview EV (correction × strength, EMA-smoothed): the JPEG
     * tonemap multiplies scene-linear rgb by exp2 of this, exactly where the save
     * path applies its development exposure. Always 0 in RAW mode.
     */
    @Volatile private var previewExposureEv = 0f
    /** Save-path strength rule (AUTO/ZSL = auto?1:0, PROGRAM = slider, MANUAL = 0). */
    @Volatile private var previewExposureStrength = 0f
    /** Scratch for the 2 Hz preview-EV estimate; camera thread only. */
    private val previewEvScratch = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
    private var lastPreviewEvMs = 0L
    private var previewEvSeeded = false
    private var sampleIntervalMs = 16L
    private var lastSlowOfferLogMs = 0L
    private var lastSlowRenderLogMs = 0L
    var onStarvation: (() -> Unit)? = null
    private var recoveryAttempts = 0
    private var recoverySinceMs = SystemClock.elapsedRealtime()
    @Volatile private var lastDisplayed = 0L
    @Volatile private var expectedIntervalMs = 33L
    @Volatile private var retryAfterMs = 0L
    // Debug-overlay stats: written on camera + GL threads, read on camera handler.
    @Volatile private var statRawWidth = 0
    @Volatile private var statRawHeight = 0
    @Volatile private var statVfWidth = 0
    @Volatile private var statVfHeight = 0
    @Volatile private var statFps = 0f
    @Volatile private var statFrameMs = 0f
    @Volatile private var statGlActive = false
    @Volatile private var statStarved = false
    @Volatile private var statGpuPath = false
    private var frameIntervalsMs = FloatArray(10)
    private var frameIntervalCount = 0
    private var lastRenderedTimestamp = 0L
    private var copyAccumMs = 0f
    private var copySamples = 0
    // Sampler-only NEON cost accounting (camera thread only, see noteNeonSample).
    private var neonAccumNs = 0L
    private var neonSamples = 0
    private var lastNeonLogMs = 0L
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var window: Surface? = null
    /** Set on the GL worker when the holder surface exists; read on the camera thread. */
    @Volatile private var hasSurface = false
    /**
     * GL sync fence for the previously presented frame (2 frames in flight). EGL fence
     * syncs are @hide, so this uses the public GLES30 equivalent, which needs an ES3
     * context — initializeGl falls back to ES2 with the guard off. Guarded by the GL
     * worker thread only.
     */
    private var previousFence: Long? = null
    /** Written on the GL worker at init; read on the camera thread for path selection. */
    @Volatile private var glEs3 = false
    private var fencesEnabled = true
    private var fenceUnsupportedLogged = false
    /** Watchdog hides stale frames; a SurfaceView ignores View alpha, so hide = clear. */
    @Volatile private var stallCleared = false
    private var program = 0
    private var texture = 0
    /** ESSL 3.00 program + R16UI texture for the zero-copy path; 0 when unavailable. */
    private var gpuProgram = 0
    private var gpuBayerTexture = 0
    /** Import texture for the Vulkan superpixel export buffer (RGBA8, CPU program). */
    private var vkTexture = 0
    private var gpuGainsLoc = -1
    private var gpuColorLoc = -1
    private var gpuPositionLoc = -1
    private var gpuUvLoc = -1
    private var gpuJpegLoc = -1
    private var gpuAgxContrastLoc = -1
    private var gpuAgxSaturationLoc = -1
    private var gpuAgxPurityLoc = -1
    private var gpuAgxHueLoc = -1
    private var gpuAgxShadowEvLoc = -1
    private var gpuAgxHighlightEvLoc = -1
    private var gpuAgxGamutLoc = -1
    private var gpuHighlightShoulderLoc = -1
    private var gpuExposureEvLoc = -1
    private var gpuBayerLoc = -1
    private var gpuQuadBaseLoc = -1
    private var gpuFrameSizeLoc = -1
    private var gpuStepLoc = -1
    private var gpuChansLoc = -1
    private var gpuBlackLoc = -1
    private var gpuInvRangeLoc = -1
    private var gpuLensLoc = -1
    private var gpuLensSizeLoc = -1
    private var gpuLensActiveLoc = -1
    private var gpuApplyLensLoc = -1
    // Cached GL locations: string lookups once per link, not once per frame.
    private var gainsLoc = -1
    private var colorLoc = -1
    private var positionLoc = -1
    private var uvLoc = -1
    private var jpegLoc = -1
    private var agxContrastLoc = -1
    private var agxSaturationLoc = -1
    private var agxPurityLoc = -1
    private var agxHueLoc = -1
    private var agxShadowEvLoc = -1
    private var agxHighlightEvLoc = -1
    private var agxGamutLoc = -1
    private var highlightShoulderLoc = -1
    private var exposureEvLoc = -1
    private var lensLoc = -1
    private var lensSizeLoc = -1
    private var lensActiveLoc = -1
    private var applyLensLoc = -1
    private var quadBaseLoc = -1
    private var frameSizeLoc = -1
    private var stepLoc = -1
    /** Tiny single-RGBA16F lens-shading map texture (1x1 identity when unavailable).
     * Packed half-float, LINEAR + CLAMP_TO_EDGE so the fixed-function sampler does
     * the bilinear interpolation: one fetch per display texel (Mali-friendly, 8
     * bytes/texel vs 16 for RGBA32F). ES3 only; ES2 keeps the identity path. */
    private var lensTexture = 0
    private var lensTexCols = 0
    private var lensTexRows = 0
    private var lensUploadLogged = false
    /** Last uploaded map identity: texture id + size + content key. GL worker only. */
    private var lensUpTexture = 0
    private var lensUpCols = 0
    private var lensUpRows = 0
    private var lensUpHash = 0L
    /** Last camera-thread lens snapshot, reused while the HAL map is unchanged. */
    private var lensCacheHash = 0L
    private var lensCacheSnapshot: VfLensSnapshot? = null
    // Allocated RGBA storage: reuse via glTexSubImage2D when size is unchanged.
    private var texWidth = 0
    private var texHeight = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val coordinates = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var coordinateRotation = Int.MIN_VALUE
    private var coordinateMirrored = false
    private val watch = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val limit = max(2000L, expectedIntervalMs * 3)
            if (now - lastDisplayed > limit) {
                alpha = 0f
                statGlActive = false
                // The surface layer ignores View alpha: actively clear to black once per
                // stall episode so a stale frame never masquerades as a live viewfinder.
                if (!stallCleared) {
                    stallCleared = true
                    worker.post(clear)
                }
                if (lastDisplayed != 0L) statStarved = true
                if (now - recoverySinceMs > limit && recoveryAttempts < 4) {
                    recoverySinceMs = now
                    recoveryAttempts++
                    onStarvation?.invoke()
                }
            } else {
                recoveryAttempts = 0
                recoverySinceMs = now
            }
            if (isAttachedToWindow) postDelayed(this, 250)
        }
    }
    init {
        // RGBA_8888 matches the EGL window config below; set before the surface is created.
        // Default Z-order (surface behind the window) is load-bearing: guide/metering
        // overlays and controls draw in the window above the VF surface hole.
        holder.setFormat(PixelFormat.RGBA_8888)
        holder.addCallback(this)
        alpha = 0f
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(watch) }
    override fun onDetachedFromWindow() {
        removeCallbacks(watch)
        invalidateSession()
        super.onDetachedFromWindow()
    }
    fun dispose() { invalidateSession(); worker.post { worker.removeCallbacks(vulkanRecoverRunnable); releaseGl(); thread.quitSafely() } }

    fun invalidateSession() {
        synchronized(lock) {
            epoch++
            pending?.let(free::addLast)
            pending = null
            gpuPending?.close()
            gpuPending = null
            lastSample = 0
            lastDisplayed = 0
            frameIntervalCount = 0
            lastRenderedTimestamp = 0L
            copyAccumMs = 0f
            copySamples = 0
        }
        statRawWidth = 0
        statRawHeight = 0
        statVfWidth = 0
        statVfHeight = 0
        statFps = 0f
        statFrameMs = 0f
        statGlActive = false
        statStarved = false
        statGpuPath = false
        previewExposureEv = 0f
        previewEvSeeded = false
        lastPreviewEvMs = 0L
        lensCacheHash = 0L
        lensCacheSnapshot = null
        // Session-scoped stickiness only: a new session re-probes the GPU path so a
        // transient gralloc storm never pins the viewfinder to the NEON copy forever.
        gpuDisabledForSession = false
        consecutiveGpuFailures = 0
        vulkanDisabledForSession = false
        consecutiveVulkanFailures = 0
        worker.removeCallbacks(vulkanRecoverRunnable)
        vulkanRecoverPosted = false
        vulkanRecoverDelayMs = VfGpuImport.VULKAN_RECOVER_INITIAL_DELAY_MS
        gpuActiveLogged = false
        lastFallbackReason = ""
        stridesLogged = false
        // Evict cached Vulkan imports (session buffers are gone); the export buffer
        // and device persist and are re-imported on demand.
        worker.post {
            try {
                VfVulkan.resetNative()
            } catch (_: Exception) {
                // Best effort: native state rebuilds on next use.
            }
        }
        post { alpha = 0f; recoveryAttempts = 0; recoverySinceMs = SystemClock.elapsedRealtime() }
    }

    /** Latest sampled sizes, frame rate and render cost for the debug overlay. */
    fun snapshot(): RawVfStats {
        val now = SystemClock.elapsedRealtime()
        val limit = max(2000L, expectedIntervalMs * 3)
        val active = statGlActive && now - lastDisplayed < limit
        return RawVfStats(
            fps = statFps,
            frameMs = statFrameMs,
            vfWidth = statVfWidth,
            vfHeight = statVfHeight,
            rawWidth = statRawWidth,
            rawHeight = statRawHeight,
            glActive = active,
            starved = statStarved || !active,
            gpu = statGpuPath,
            jpeg = renderJpeg,
            exposureEv = previewExposureEv,
            engine = engineMode
        )
    }

    fun expectFrameInterval(nanos: Long) {
        expectedIntervalMs = (nanos / 1_000_000L).coerceAtLeast(16L)
    }

    /** Selectable VF resolution (long edge, 480/640/960/1080). Applies to the next sampled frame. */
    fun setTargetLongEdge(longEdge: Int) {
        targetLongEdge = longEdge.coerceIn(VfResolution.MIN, VfResolution.MAX)
    }

    /** Switch the tonemap without touching the stream. Safe to call from any thread. */
    fun setRenderJpeg(jpeg: Boolean) {
        renderJpeg = jpeg
    }

    /** Force one engine (overlay tap) without touching the stream. Safe from any thread. */
    fun setEngineMode(mode: VfEngineMode) {
        if (engineMode == mode) return
        engineMode = mode
        lastFallbackReason = ""
        Log.i("RawViewfinder", "VF engine override: ${mode.name}")
    }

    /** Overlay-tap cycle: AUTO -> GPU -> CPU -> AUTO. Returns the new mode. */
    fun cycleEngineMode(): VfEngineMode {
        val next = engineMode.next()
        setEngineMode(next)
        return next
    }

    /** Adaptive preview-EV strength pushed by the controller (same rule as saves). */
    fun setPreviewExposureStrength(strength: Float) {
        previewExposureStrength = strength.coerceIn(0f, 1f)
    }

    /**
     * Live-link AgX sliders to the JPEG preview. Only volatile writes; the next sampled
     * frame carries the snapshot so a change never tears a frame in flight.
     * Safe to call from any thread.
     */
    fun setAgx(settings: JpegOutputSettings) {
        val resolved = settings.resolvedForPlatform()
        agxContrast = resolved.agxContrast
        agxSaturation = resolved.agxSaturation
        agxPurity = resolved.agxPurityBoost
        agxHue = resolved.agxHuePreservation
        agxShadowEv = resolved.agxShadowEv
        agxHighlightEv = resolved.agxHighlightEv
        agxGamut = resolved.agxGamutCompression
        highlightShoulder = resolved.highlightShoulder
        previewHeadroom = resolved.highlightHeadroom
        previewSoftHeadroom = resolved.highlightSoftHeadroom
    }

    fun setPreviewTuning(tuning: AdaptiveExposureTuning) {
        val bounded = tuning.bounded()
        previewHeadroom = bounded.highlightHeadroom.toFloat()
        previewSoftHeadroom = bounded.highlightSoftHeadroom.toFloat()
    }

    /** Called inline on the camera handler. All plane access ends before this method returns. */
    fun offer(image: Image, c: CameraCharacteristics, result: CaptureResult?) {
        val now = SystemClock.elapsedRealtime()
        if (!hasSurface || now < retryAfterMs) return
        synchronized(lock) {
            if (now - lastSample < sampleIntervalMs) return
            lastSample = now
        }
        try {
            val plane = image.planes[0]
            if (!stridesLogged) {
                stridesLogged = true
                Log.i("RawViewfinder", "VF raw strides pixelStride=${plane.pixelStride} " +
                    "rowStride=${plane.rowStride} w=${image.width} h=${image.height}")
            }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val crop = result?.get(CaptureResult.SCALER_CROP_REGION) ?: active
            val longEdge = targetLongEdge.coerceIn(VfResolution.MIN, VfResolution.MAX)
            val gpuGeo = RawPreviewGeometry.quadGeometry(image.width, image.height, crop, longEdge)
            statRawWidth = image.width
            statRawHeight = image.height
            statStarved = false
            val channels = RawPreviewGeometry.channels(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0)
            val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val dynamicBlack = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            val white = (result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val levels = FloatArray(4) { i -> dynamicBlack?.get(i) ?: black?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 0f }
            val lensSnap = snapshotLens(c, result, image.width, image.height)
            // Coarse 2 Hz statistic on the user geometry; the grid stride
            // adapts, so the CPU cap below does not skew it.
            updatePreviewExposure(plane, gpuGeo.left, gpuGeo.top, gpuGeo.width, gpuGeo.height, gpuGeo.step, levels, white, now, lensSnap)
            // Each path samples its own geometry: the GPU renders the full
            // user setting, the NEON fallback caps at CPU_MAX so it holds
            // 30 fps instead of crawling at ~3 fps on 1080. The overlay stats
            // track whichever path actually rendered this offer.
            when (engineMode) {
                VfEngineMode.CPU -> {
                    val geo = RawPreviewGeometry.quadGeometry(
                        image.width, image.height, crop, minOf(longEdge, VfResolution.CPU_MAX))
                    statVfWidth = geo.width
                    statVfHeight = geo.height
                    offerCpu(plane, c, result, now, geo.left, geo.top, geo.width, geo.height, geo.step, channels, levels, white, lensSnap)
                }
                VfEngineMode.GPU -> {
                    statVfWidth = gpuGeo.width
                    statVfHeight = gpuGeo.height
                    // Forced GPU never falls back: a dead zero-copy path shows as
                    // starved so the overlay A/B comparison stays honest.
                    offerGpu(image, plane, c, result, now, gpuGeo.left, gpuGeo.top, gpuGeo.width, gpuGeo.height, gpuGeo.step, channels, levels, white, lensSnap)
                }
                VfEngineMode.AUTO -> {
                    statVfWidth = gpuGeo.width
                    statVfHeight = gpuGeo.height
                    if (offerGpu(image, plane, c, result, now, gpuGeo.left, gpuGeo.top, gpuGeo.width, gpuGeo.height, gpuGeo.step, channels, levels, white, lensSnap)) return
                    val geo = RawPreviewGeometry.quadGeometry(
                        image.width, image.height, crop, minOf(longEdge, VfResolution.CPU_MAX))
                    statVfWidth = geo.width
                    statVfHeight = geo.height
                    offerCpu(plane, c, result, now, geo.left, geo.top, geo.width, geo.height, geo.step, channels, levels, white, lensSnap)
                }
            }
        } catch (failure: Exception) {
            retryAfterMs = now + 2000L
            post { alpha = 0f }
            Log.w("RawViewfinder", "RAW sampling failed; hidden system preview stream continues", failure)
        }
    }

    /**
     * Zero-copy branch: hand the gralloc [HardwareBuffer] reference to the GL worker,
     * which imports it as an EGLImage and samples the Bayer quad in the fragment
     * shader. A controller lease keeps the camera Image acquired until GPU reads
     * finish; holding a HardwareBuffer reference alone cannot prevent HAL reuse.
     * Returns false to fall through to the NEON sampler.
     */
    /** Tiny per-frame lens-shading snapshot: HAL map bytes stay on the camera thread,
     * the GL worker only uploads the small float texture. Null model = identity. */
    private class VfLensSnapshot(
        val gains: FloatArray?,
        val rows: Int,
        val cols: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val apply: Boolean,
        val model: LensShadingModel?,
        val pattern: BayerPattern?
    ) {
        companion object {
            val IDENTITY = VfLensSnapshot(null, 1, 1, 0, 0, 1, 1, false, null, null)
        }
    }

    private fun snapshotLens(
        c: CameraCharacteristics, result: CaptureResult?, imageWidth: Int, imageHeight: Int
    ): VfLensSnapshot {
        val alreadyApplied =
            c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) == true
        if (alreadyApplied) {
            val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            return VfLensSnapshot(
                null, 1, 1,
                a?.left ?: 0, a?.top ?: 0,
                a?.right ?: imageWidth, a?.bottom ?: imageHeight,
                false, null, null
            )
        }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val left = active?.left ?: 0
        val top = active?.top ?: 0
        val right = active?.right ?: imageWidth
        val bottom = active?.bottom ?: imageHeight
        val map = try {
            result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (_: Exception) {
            null
        } ?: return VfLensSnapshot(null, 1, 1, left, top, right, bottom, false, null, null)
        return try {
            val rows = map.rowCount
            val cols = map.columnCount
            if (rows <= 0 || cols <= 0) return VfLensSnapshot(null, 1, 1, left, top, right, bottom, false, null, null)
            val gains = FloatArray(map.gainFactorCount)
            map.copyGainFactors(gains, 0)
            if (gains.size != rows * cols * 4 || gains.any { !it.isFinite() || it < 1f }) {
                return VfLensSnapshot(null, 1, 1, left, top, right, bottom, false, null, null)
            }
            // The HAL map is static per session: reuse the cached snapshot (gains
            // array + model) while size, content and active bounds are unchanged
            // instead of reallocating on every camera-thread frame.
            val hash = VfGpuImport.lensContentKey(cols, rows, gains)
            val cached = lensCacheSnapshot
            if (hash == lensCacheHash && cached != null && cached.apply &&
                cached.left == left && cached.top == top &&
                cached.right == right && cached.bottom == bottom
            ) {
                return cached
            }
            val cfaInt = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            val pattern = try {
                if (cfaInt == null) null else BayerPattern.fromCamera2(cfaInt)
            } catch (_: Exception) {
                null
            }
            val model = try {
                LensShadingModel(rows, cols, gains, IntRectSnapshot(left, top, right, bottom), false)
            } catch (_: Exception) {
                null
            }
            VfLensSnapshot(gains, rows, cols, left, top, right, bottom, model != null, model, pattern)
                .also { lensCacheHash = hash; lensCacheSnapshot = it }
        } catch (_: Exception) {
            VfLensSnapshot(null, 1, 1, left, top, right, bottom, false, null, null)
        }
    }

    private fun FrameState.applyLens(lens: VfLensSnapshot, left: Int, top: Int, step: Int) {
        if (lens.apply && lens.gains != null) {
            lensGains = lens.gains
            lensRows = lens.rows
            lensCols = lens.cols
            lensApply = true
        } else {
            lensGains = null
            lensRows = 1
            lensCols = 1
            lensApply = false
        }
        lensLeft = lens.left
        lensTop = lens.top
        lensRight = lens.right
        lensBottom = lens.bottom
        quadLeft = left
        quadTop = top
        quadStep = step
    }

    private fun offerGpu(
        image: Image, plane: Image.Plane, c: CameraCharacteristics, result: CaptureResult?,
        now: Long, left: Int, top: Int, width: Int, height: Int, step: Int,
        channels: IntArray, levels: FloatArray, white: Float, lens: VfLensSnapshot
    ): Boolean {
        // Vulkan tier needs no ES3 (it renders through the ESSL 1.00 program); the
        // EGL-direct tier needs the ES3 Bayer program. Either routes to gpuDraw.
        val vulkanViable = VfVulkan.available && !vulkanDisabledForSession
        val eligibility = VfGpuImport.checkEligible(
            plane.pixelStride, plane.rowStride, image.width,
            glEs3, VfEglImport.available, gpuDisabledForSession
        )
        if (!vulkanViable && !eligibility.eligible) {
            logFallbackOnce(eligibility.reason)
            return false
        }
        val buffer = try {
            image.hardwareBuffer
        } catch (_: Exception) {
            null
        }
        if (buffer == null) {
            logFallbackOnce("hardware-buffer-null")
            return false
        }
        val lease = RawImageOwnership.borrow(image) ?: run {
            buffer.close()
            return false
        }
        val frame = GpuFrame(buffer, lease)
        try {
            frame.width = width
            frame.height = height
            frame.left = left
            frame.top = top
            frame.step = step
            frame.pitch = plane.rowStride / plane.pixelStride
            channels.copyInto(frame.channels)
            levels.copyInto(frame.levels)
            frame.white = white
            snapshotRenderState(c, result, channels, frame)
            frame.applyLens(lens, left, top, step)
            frame.timestamp = now
            // Leave camera-handler time for metadata, shutter and timeout callbacks on
            // slower devices. Rendering stays latest-only instead of building latency.
            noteSampleCost(now, throttleCpuCopy = false)
            synchronized(lock) {
                frame.epoch = epoch
                // Only one path renders: a GPU frame supersedes any queued CPU frame.
                pending?.let(free::addLast)
                pending = null
                gpuPending?.close()
                gpuPending = frame
            }
            worker.removeCallbacks(gpuDraw)
            worker.post(gpuDraw)
            return true
        } catch (failure: Exception) {
            frame.close()
            throw failure
        }
    }

    /** NEON CPU fallback: native-sampled superpixel; lens shading stays in-shader. */
    private fun offerCpu(
        plane: Image.Plane, c: CameraCharacteristics, result: CaptureResult?,
        now: Long, left: Int, top: Int, width: Int, height: Int, step: Int,
        channels: IntArray, levels: FloatArray, white: Float, lens: VfLensSnapshot
    ) {
        val frame = synchronized(lock) {
            // Replace pending before borrowing: a slow renderer never builds a queue.
            pending?.let(free::addLast)
            pending = null
            // A CPU frame supersedes any queued GPU frame: only one path renders.
            gpuPending?.close()
            gpuPending = null
            if (free.isEmpty()) return
            free.removeFirst().also { it.epoch = epoch }
        }
        try {
            frame.width = width
            frame.height = height
            frame.pixels.clear()
            val copyStartedNs = System.nanoTime()
            VfCpuNeon.copy(plane.buffer, plane.rowStride, plane.pixelStride, left, top,
                width, height, step, channels, levels, white, frame.pixels)
            noteNeonSample(copyStartedNs, width, height)
            snapshotRenderState(c, result, channels, frame)
            frame.applyLens(lens, left, top, step)
            frame.timestamp = now
            // Leave camera-handler time for metadata, shutter and timeout callbacks on
            // slower devices. Rendering stays latest-only instead of building latency.
            noteSampleCost(now)
            synchronized(lock) {
                if (frame.epoch != epoch) { free.addLast(frame); return }
                pending?.let(free::addLast)
                pending = frame
            }
            worker.removeCallbacks(draw)
            worker.post(draw)
        } catch (failure: Exception) {
            synchronized(lock) { free.addLast(frame) }
            throw failure
        }
    }

    private fun logFallbackOnce(reason: String) {
        if (reason == lastFallbackReason) return
        lastFallbackReason = reason
        Log.i("RawViewfinder", "VF NEON fallback: $reason")
    }

    /**
     * Sampler-only cost accounting for the NEON fallback: [noteSampleCost]
     * covers the whole offer (lens snapshot, EV estimate), this isolates the
     * native copy so field logs show which half regressed. Camera thread only.
     */
    private fun noteNeonSample(copyStartedNs: Long, width: Int, height: Int) {
        neonAccumNs += System.nanoTime() - copyStartedNs
        neonSamples++
        val now = SystemClock.elapsedRealtime()
        if (now - lastNeonLogMs < NEON_LOG_INTERVAL_MS || neonSamples == 0) return
        lastNeonLogMs = now
        val avgMs = neonAccumNs / 1_000_000f / neonSamples
        Log.i("RawViewfinder", String.format(java.util.Locale.US,
            "NEON sample: avg %.1f ms over %d frames (%dx%d)",
            avgMs, neonSamples, width, height))
        neonAccumNs = 0L
        neonSamples = 0
    }

    /**
     * 2 Hz adaptive preview-EV refresh: the same percentile statistic the saved
     * JPEG uses, on a coarse grid (≤4096 samples, sub-millisecond). EMA-smoothed
     * so 2 Hz steps never flicker the preview. Camera thread only.
     */
    private fun updatePreviewExposure(
        plane: Image.Plane, left: Int, top: Int, width: Int, height: Int, step: Int,
        levels: FloatArray, white: Float, now: Long, lens: VfLensSnapshot = VfLensSnapshot.IDENTITY
    ) {
        val strength = previewExposureStrength
        if (!renderJpeg || strength <= 0f) {
            if (previewExposureEv != 0f) previewExposureEv = 0f
            previewEvSeeded = false
            return
        }
        if (now - lastPreviewEvMs < PREVIEW_EV_INTERVAL_MS) return
        lastPreviewEvMs = now
        val tuning = AdaptiveExposureTuning(
            highlightHeadroom = previewHeadroom.toDouble(),
            highlightSoftHeadroom = previewSoftHeadroom.toDouble()
        ).bounded()
        val correction = VfGpuImport.estimatePreviewCorrectionEv(
            plane.buffer, plane.rowStride, plane.pixelStride,
            left, top, width, height, step, levels, white, previewEvScratch,
            lens = lens.model, pattern = lens.pattern, tuning = tuning
        )
        if (!correction.isFinite()) return
        val applied = (correction * strength).toFloat().coerceIn(-MAX_PREVIEW_EV, MAX_PREVIEW_EV)
        previewExposureEv = if (!previewEvSeeded) {
            previewEvSeeded = true
            applied
        } else {
            previewExposureEv * 0.5f + applied * 0.5f
        }
    }

    /** Shared WYSIWYG snapshot: identical gains/matrix/JPEG/AgX for both paths. */
    private fun snapshotRenderState(
        c: CameraCharacteristics, result: CaptureResult?, channels: IntArray, out: FrameState
    ) {
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        out.gains[0] = gains?.red ?: 1f
        out.gains[1] = (if (channels[1] / 2 == 0) gains?.greenEven else gains?.greenOdd) ?: 1f
        out.gains[2] = (if (channels[2] / 2 == 0) gains?.greenEven else gains?.greenOdd) ?: 1f
        out.gains[3] = gains?.blue ?: 1f
        val matrix = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        for (row in 0..2) for (col in 0..2) out.matrix[col * 3 + row] =
            matrix?.getElement(col, row)?.toFloat() ?: if (row == col) 1f else 0f
        // Snapshot the WYSIWYG render state with the frame so AgX slider moves
        // and RAW/JPEG switches never tear a frame in flight.
        out.jpeg = renderJpeg
        out.exposureEv = previewExposureEv
        out.agxContrast = agxContrast
        out.agxSaturation = agxSaturation
        out.agxPurity = agxPurity
        out.agxHue = agxHue
        out.agxShadowEv = agxShadowEv
        out.agxHighlightEv = agxHighlightEv
        out.agxGamut = agxGamut
        out.highlightShoulder = highlightShoulder
        out.rotation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        out.mirrored = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        expectFrameInterval(maxOf(result?.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
            result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L))
    }

    /** Shared camera-thread cost accounting driving the debug overlay and throttle. */
    private fun noteSampleCost(now: Long, throttleCpuCopy: Boolean = true) {
        val costMs = (SystemClock.elapsedRealtime() - now).toFloat()
        if (costMs >= 34f && now - lastSlowOfferLogMs >= 1000L) {
            lastSlowOfferLogMs = now
            Log.i("RawViewfinder", "Slow RAW offer: ${costMs}ms interval=${sampleIntervalMs}ms")
        }
        copyAccumMs += costMs
        copySamples++
        if (copySamples >= 10) {
            statFrameMs = copyAccumMs / copySamples
            copyAccumMs = 0f
            copySamples = 0
        } else if (statFrameMs == 0f) {
            statFrameMs = costMs
        }
        // 30 Hz budget: floor at one vsync (16 ms), back off only when the
        // copy itself exceeds it. Previously max(34, copy*2) capped at ~15-25 fps.
        sampleIntervalMs = if (throttleCpuCopy) max(16L, (SystemClock.elapsedRealtime() - now) * 2) else 16L
    }

    /**
     * 2 frames in flight, shared by both paths: if the GPU is still presenting the
     * previous frame and a newer frame is already pending, the caller drops this one —
     * the worker never blocks behind the GPU and the camera thread never waits on a
     * swap. Non-blocking poll only. Runs on the GL worker.
     */
    private fun shouldDropForFence(): Boolean {
        val fence = previousFence
        if (!fencesEnabled || fence == null) return false
        val wait = GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 0L)
        return (wait == GLES30.GL_TIMEOUT_EXPIRED || wait == GLES30.GL_WAIT_FAILED) &&
            synchronized(lock) { pending != null || gpuPending != null }
    }

    /** Shared present accounting: FPS average, active flags and reveal. GL worker only. */
    private fun noteFrameRendered(frameEpoch: Long, gpu: Boolean) {
        val renderNow = SystemClock.elapsedRealtime()
        if (lastRenderedTimestamp != 0L) {
            val delta = (renderNow - lastRenderedTimestamp).toFloat().coerceIn(1f, 5000f)
            frameIntervalsMs[frameIntervalCount % frameIntervalsMs.size] = delta
            frameIntervalCount++
            val n = minOf(frameIntervalCount, frameIntervalsMs.size)
            var sum = 0f
            for (i in 0 until n) sum += frameIntervalsMs[i]
            val avg = sum / n
            if (avg > 0f) statFps = 1000f / avg
        }
        lastRenderedTimestamp = renderNow
        statGlActive = true
        statStarved = false
        statGpuPath = gpu
        stallCleared = false
        post { if (frameEpoch == synchronized(lock) { epoch } && SystemClock.elapsedRealtime() - lastDisplayed < max(2000L, expectedIntervalMs * 3)) alpha = 1f }
    }

    /** CPU-program tonemap uniforms shared by the CPU path and the Vulkan tier. */
    private fun applyCpuTonemap(state: FrameState, width: Int, height: Int) {
        glUniform4fv(gainsLoc, 1, state.gains, 0)
        glUniformMatrix3fv(colorLoc, 1, false, state.matrix, 0)
        glUniform1i(jpegLoc, if (state.jpeg) 1 else 0)
        glUniform1f(exposureEvLoc, state.exposureEv)
        glUniform1f(agxContrastLoc, state.agxContrast)
        glUniform1f(agxSaturationLoc, state.agxSaturation)
        glUniform1f(agxPurityLoc, state.agxPurity)
        glUniform1f(agxHueLoc, state.agxHue)
        glUniform1f(agxShadowEvLoc, state.agxShadowEv)
        glUniform1f(agxHighlightEvLoc, state.agxHighlightEv)
        glUniform1f(agxGamutLoc, state.agxGamut)
        glUniform1f(highlightShoulderLoc, state.highlightShoulder)
        bindLens(state, width, height)
    }

    /**
     * Hardware-accelerated lens-shading bind for the CPU program (also used by the
     * Vulkan tier, which renders through this program). Uploads the tiny RGBA16F
     * map when the per-frame snapshot changed size/content class, binds it to unit
     * 1 for the single hardware-filtered fetch, and pushes the quad geometry the
     * lens UV remap needs. Zero-copy is preserved: only this ~4 KB sidecar moves.
     * GL worker only.
     */
    private fun bindLens(state: FrameState, width: Int, height: Int) {
        val active = state.lensApply && glEs3 && lensTexture != 0 &&
            state.lensGains != null && state.lensCols > 0 && state.lensRows > 0
        if (active) uploadLensTexture(state)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, lensTexture)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(lensLoc, 1)
        glUniform2i(lensSizeLoc, state.lensCols, state.lensRows)
        glUniform4i(
            lensActiveLoc, state.lensLeft, state.lensTop,
            state.lensRight, state.lensBottom
        )
        glUniform1i(applyLensLoc, if (active) 1 else 0)
        glUniform2i(quadBaseLoc, state.quadLeft, state.quadTop)
        glUniform2i(frameSizeLoc, width, height)
        glUniform1i(stepLoc, state.quadStep)
    }

    /**
     * Upload the frame's lens map into the shared RGBA16F texture. GL worker only.
     * Two invariants: the bind/upload runs on texture unit 1 (unit 0 holds the
     * image at every call site — binding here used to white out the VF), and
     * uploads happen only when the map identity changes (the HAL map is static
     * per session; per-frame uploads stall the pipeline and heat the phone).
     */
    private fun uploadLensTexture(state: FrameState) {
        val gains = state.lensGains ?: return
        val cols = state.lensCols
        val rows = state.lensRows
        if (cols <= 0 || rows <= 0 || gains.size != cols * rows * 4) return
        val hash = VfGpuImport.lensContentKey(cols, rows, gains)
        if (lensTexture == lensUpTexture && cols == lensUpCols && rows == lensUpRows && hash == lensUpHash) return
        glActiveTexture(GL_TEXTURE1)
        try {
            val half = VfGpuImport.packLensHalf(gains)
            val bytes = ByteBuffer.allocateDirect(half.size * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(half)
            bytes.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensTexture)
            if (cols != lensTexCols || rows != lensTexRows) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, cols, rows, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, bytes
                )
                lensTexCols = cols
                lensTexRows = rows
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, cols, rows,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, bytes
                )
            }
            lensUpTexture = lensTexture
            lensUpCols = cols
            lensUpRows = rows
            lensUpHash = hash
            if (!lensUploadLogged) {
                lensUploadLogged = true
                Log.i("RawViewfinder", "VF lens-shading RGBA16F active ${cols}x$rows")
            }
        } catch (failure: Exception) {
            Log.w("RawViewfinder", "VF lens upload failed; identity covers", failure)
        } finally {
            glActiveTexture(GL_TEXTURE0)
        }
    }

    /** Same single-fetch hardware bind for the ESSL 3.00 Bayer program. GL worker only. */
    private fun bindGpuLens(frame: GpuFrame) {
        val active = frame.lensApply && glEs3 && lensTexture != 0 &&
            frame.lensGains != null && frame.lensCols > 0 && frame.lensRows > 0
        if (active) uploadLensTexture(frame)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, lensTexture)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(gpuLensLoc, 1)
        glUniform2i(gpuLensSizeLoc, frame.lensCols, frame.lensRows)
        glUniform4i(
            gpuLensActiveLoc, frame.lensLeft, frame.lensTop,
            frame.lensRight, frame.lensBottom
        )
        glUniform1i(gpuApplyLensLoc, if (active) 1 else 0)
    }

    /** Rotation/mirror UVs + fullscreen quad attribs. GL worker only. */
    private fun bindQuadAttribs(position: Int, uv: Int, rotation: Int, mirrored: Boolean) {
        // UVs depend only on orientation. Reuse the same eight floats between
        // changes instead of allocating a list and Pairs on every preview frame.
        if (coordinateRotation != rotation || coordinateMirrored != mirrored) {
            coordinates.clear()
            for ((x, y) in listOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f)) {
                val point = RawPreviewGeometry.sensorPoint(x, y, rotation, mirrored)
                coordinates.put(point.first).put(point.second)
            }
            coordinates.flip()
            coordinateRotation = rotation
            coordinateMirrored = mirrored
        }
        glEnableVertexAttribArray(position); glEnableVertexAttribArray(uv)
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, vertices)
        glVertexAttribPointer(uv, 2, GL_FLOAT, false, 0, coordinates)
    }

    /** Preserve the actual failure code: short-circuit checks hid whether
     * rendering failed or EGL lost the display surface/context. */
    private fun checkPresent() {
        val glError = glGetError()
        check(glError == GL_NO_ERROR) { "VF GL draw failed: 0x${glError.toString(16)}" }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            val eglError = EGL14.eglGetError()
            error("VF EGL swap failed: 0x${eglError.toString(16)}")
        }
    }

    /** Shared GL-failure handling: hide until retry, keep the session alive. */
    private fun noteGlFailure(tag: String, failure: Exception) {
        Log.w("RawViewfinder", "$tag; hiding VF until retry (system preview stays hidden)", failure)
        retryAfterMs = SystemClock.elapsedRealtime() + 2000L
        statGlActive = false
        statStarved = true
        releaseGl()
        post { alpha = 0f }
    }

    private val draw = Runnable {
        val frame = synchronized(lock) { pending.also { pending = null } } ?: return@Runnable
        try {
            if (frame.epoch != synchronized(lock) { epoch } || window == null || !hasSurface) return@Runnable
            if (surface == EGL14.EGL_NO_SURFACE) initializeGl(window!!)
            // Dropped frame carries no new cost sample; the queued draw
            // already covers the newer pending frame.
            if (shouldDropForFence()) return@Runnable
            glViewport(0, 0, viewportWidth, viewportHeight)
            glUseProgram(program)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, texture)
            // Reuse storage when size is unchanged: avoids per-frame realloc stalls.
            // Uses GLES20 glTexSubImage2D / glTexImage2D via static imports.
            if (frame.width != texWidth || frame.height != texHeight) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, frame.width, frame.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, frame.pixels)
                texWidth = frame.width
                texHeight = frame.height
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height, GL_RGBA, GL_UNSIGNED_BYTE, frame.pixels)
            }
            applyCpuTonemap(frame, frame.width, frame.height)
            bindQuadAttribs(positionLoc, uvLoc, frame.rotation, frame.mirrored)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            checkPresent()
            insertFrameFence()
            synchronized(lock) { if (frame.epoch == epoch) lastDisplayed = frame.timestamp }
            noteFrameRendered(frame.epoch, gpu = false)
        } catch (failure: Exception) {
            noteGlFailure("GL failed", failure)
        } finally { synchronized(lock) { free.addLast(frame) } }
    }

    /**
     * Zero-copy present, two tiers. Tier 1 (primary): Vulkan superpixel compute into
     * the export buffer, EGL-imported and rendered through the ESSL 1.00 tonemap
     * program. Tier 2: direct EGL import of the HAL buffer through the Bayer program
     * (HALs where that import works). Any failure falls back down the chain to the
     * NEON sampler. The controller defers Image.close until our frame lease closes. Runs on the GL worker.
     */
    private val gpuDraw = Runnable {
        val drawStarted = SystemClock.elapsedRealtime()
        var computeMs = 0L
        val frame = synchronized(lock) { gpuPending.also { gpuPending = null } } ?: return@Runnable
        try {
            if (frame.epoch != synchronized(lock) { epoch } || window == null || !hasSurface) return@Runnable
            if (surface == EGL14.EGL_NO_SURFACE) initializeGl(window!!)
            if (shouldDropForFence()) return@Runnable
            var importBuffer: android.hardware.HardwareBuffer? = null
            var bayer = false
            if (!vulkanDisabledForSession) {
                val computeStarted = SystemClock.elapsedRealtime()
                val code = runVulkanSuperpixel(frame)
                computeMs = SystemClock.elapsedRealtime() - computeStarted
                if (code == VfVulkan.OK && vulkanExport != null) {
                    importBuffer = vulkanExport
                } else {
                    noteVulkanFailure(VfVulkan.describe(code))
                }
            }
            if (importBuffer == null && glEs3 && gpuProgram != 0 && !gpuDisabledForSession) {
                importBuffer = frame.buffer
                bayer = true
            }
            if (importBuffer == null) {
                noteGpuFailure("no-gpu-tier")
                return@Runnable
            }
            val eglImage = try {
                VfEglImport.createEGLImage(importBuffer)
            } catch (_: Exception) {
                0L
            }
            if (eglImage == 0L) {
                if (bayer) noteGpuFailure("egl-import") else noteVulkanFailure("egl-import")
                return@Runnable
            }
            try {
                glViewport(0, 0, viewportWidth, viewportHeight)
                if (!bayer) {
                    glUseProgram(program)
                    glActiveTexture(GL_TEXTURE0)
                    val bindError = VfEglImport.bindEGLImageToTexture2D(eglImage, vkTexture)
                    if (bindError != GL_NO_ERROR) {
                        noteVulkanFailure("egl-bind=$bindError")
                        return@Runnable
                    }
                    applyCpuTonemap(frame, frame.width, frame.height)
                    bindQuadAttribs(positionLoc, uvLoc, frame.rotation, frame.mirrored)
                } else {
                    glUseProgram(gpuProgram)
                    glActiveTexture(GL_TEXTURE0)
                    val bindError = VfEglImport.bindEGLImageToTexture2D(eglImage, gpuBayerTexture)
                    if (bindError != GL_NO_ERROR) {
                        noteGpuFailure("egl-bind=$bindError")
                        return@Runnable
                    }
                    glUniform1i(gpuBayerLoc, 0)
                    glUniform2i(gpuQuadBaseLoc, frame.left, frame.top)
                    glUniform2i(gpuFrameSizeLoc, frame.width, frame.height)
                    glUniform1i(gpuStepLoc, frame.step)
                    glUniform4i(gpuChansLoc, frame.channels[0], frame.channels[1], frame.channels[2], frame.channels[3])
                    glUniform4fv(gpuBlackLoc, 1, frame.levels, 0)
                    val invRange = FloatArray(4) { i -> 1f / (frame.white - frame.levels[i]).coerceAtLeast(1f) }
                    glUniform4fv(gpuInvRangeLoc, 1, invRange, 0)
                    glUniform4fv(gpuGainsLoc, 1, frame.gains, 0)
                    glUniformMatrix3fv(gpuColorLoc, 1, false, frame.matrix, 0)
                    glUniform1i(gpuJpegLoc, if (frame.jpeg) 1 else 0)
                    glUniform1f(gpuAgxContrastLoc, frame.agxContrast)
                    glUniform1f(gpuAgxSaturationLoc, frame.agxSaturation)
                    glUniform1f(gpuAgxPurityLoc, frame.agxPurity)
                    glUniform1f(gpuAgxHueLoc, frame.agxHue)
                    glUniform1f(gpuAgxShadowEvLoc, frame.agxShadowEv)
                    glUniform1f(gpuAgxHighlightEvLoc, frame.agxHighlightEv)
                    glUniform1f(gpuAgxGamutLoc, frame.agxGamut)
                    glUniform1f(gpuHighlightShoulderLoc, frame.highlightShoulder)
                    glUniform1f(gpuExposureEvLoc, frame.exposureEv)
                    bindGpuLens(frame)
                    bindQuadAttribs(gpuPositionLoc, gpuUvLoc, frame.rotation, frame.mirrored)
                }
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                // Both imports need completion before destroying their EGLImage.
                // The Vulkan export is a SINGLE reusable buffer: its next compute
                // dispatch must not overwrite pixels still sampled by this GL draw.
                // A post-swap frame fence/drop heuristic is not a cross-API handoff.
                glFinish()
                checkPresent()
                insertFrameFence()
                synchronized(lock) { if (frame.epoch == epoch) lastDisplayed = frame.timestamp }
                if (bayer) consecutiveGpuFailures = 0 else {
                    consecutiveVulkanFailures = 0
                    // A rendered frame proves the device healthy: repeated
                    // fail/recover cycles keep backing off, recovery resets here.
                    vulkanRecoverDelayMs = VfGpuImport.VULKAN_RECOVER_INITIAL_DELAY_MS
                }
                if (!gpuActiveLogged) {
                    gpuActiveLogged = true
                    Log.i("RawViewfinder", "VF GPU zero-copy active (" + (if (bayer) "egl" else "vulkan") + ")")
                }
                noteFrameRendered(frame.epoch, gpu = true)
                val drawFinished = SystemClock.elapsedRealtime()
                val drawMs = drawFinished - drawStarted
                if (drawMs >= 34L && drawFinished - lastSlowRenderLogMs >= 1000L) {
                    lastSlowRenderLogMs = drawFinished
                    Log.i("RawViewfinder", "Slow RAW draw: total=${drawMs}ms compute=${computeMs}ms age=${drawStarted - frame.timestamp}ms")
                }
            } finally {
                try {
                    VfEglImport.destroyEGLImage(eglImage)
                } catch (_: Exception) {
                    // Best effort: the import is already fully consumed.
                }
            }
        } catch (failure: Exception) {
            noteGlFailure("GPU GL failed", failure)
        } finally {
            try {
                frame.close()
            } catch (_: Exception) {
                // Best effort: the gralloc reference is already accounted.
            }
        }
    }

    /** Vulkan tier: lazy init + export buffer + superpixel dispatch. GL worker only. */
    private fun runVulkanSuperpixel(frame: GpuFrame): Int {
        if (!vulkanInitialized) {
            val spv = loadVulkanSpv() ?: return VfVulkan.PIPELINE_FAILED
            val code = try {
                VfVulkan.initNative(spv)
            } catch (_: Exception) {
                VfVulkan.DEVICE_FAILED
            }
            if (code != VfVulkan.OK) return code
            vulkanInitialized = true
            Log.i("RawViewfinder", "VF Vulkan ready")
        }
        if (vulkanExport == null || vulkanExportWidth != frame.width || vulkanExportHeight != frame.height) {
            try {
                vulkanExport?.close()
            } catch (_: Exception) {
            }
            vulkanExport = null
            vulkanExport = try {
                android.hardware.HardwareBuffer.create(
                    frame.width, frame.height,
                    android.hardware.HardwareBuffer.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        android.hardware.HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                        android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
            } catch (_: Exception) {
                null
            } ?: return VfVulkan.OUTPUT_IMPORT_FAILED
            vulkanExportWidth = frame.width
            vulkanExportHeight = frame.height
        }
        val export = vulkanExport ?: return VfVulkan.OUTPUT_IMPORT_FAILED
        // Re-import after session resets evicted native state (native no-op when cached).
        val ensure = try {
            VfVulkan.ensureOutputNative(export)
        } catch (_: Exception) {
            VfVulkan.OUTPUT_IMPORT_FAILED
        }
        if (ensure != VfVulkan.OK) {
            // A poisoned export buffer never recovers: drop it so next frame reallocates.
            try {
                export.close()
            } catch (_: Exception) {
            }
            if (vulkanExport === export) vulkanExport = null
            return ensure
        }
        val (iparams, fparams) = VfVulkan.packParams(
            frame.channels, frame.left, frame.top, frame.width, frame.height, frame.step,
            frame.pitch, frame.levels, frame.white
        )
        return try {
            VfVulkan.computeNative(frame.buffer, iparams, fparams)
        } catch (_: Exception) {
            VfVulkan.SUBMIT_FAILED
        }
    }

    /** Vulkan tier failure: warn, and skip the tier after repeated failures. */
    private fun noteVulkanFailure(reason: String) {
        consecutiveVulkanFailures++
        if ((VfVulkan.shouldDisableImmediately(reason) ||
                consecutiveVulkanFailures >= VfGpuImport.MAX_CONSECUTIVE_FAILURES) && !vulkanDisabledForSession) {
            vulkanDisabledForSession = true
            Log.w("RawViewfinder", "VF Vulkan failed ($reason); EGL/NEON cover for session")
            if (VfVulkan.shouldRecover(reason)) scheduleVulkanRecovery()
        } else {
            Log.w("RawViewfinder", "VF Vulkan frame failed ($reason); EGL/NEON cover")
        }
    }

    /**
     * Schedule a device-recreation re-probe after the session latch. A fence
     * timeout or submit failure during a GPU storm (e.g. an SR merge) can
     * wedge the queue or lose the device; without this the latch — and every
     * later session re-probe against the dead device — fails forever and the
     * viewfinder sits on the NEON copy. Recovery runs only when the
     * device worked before, so devices without Vulkan never spin. GL worker only.
     */
    private fun scheduleVulkanRecovery() {
        if (vulkanRecoverPosted || !VfVulkan.available || !vulkanInitialized) return
        vulkanRecoverPosted = true
        vulkanRecoverEpoch = synchronized(lock) { epoch }
        worker.postDelayed(vulkanRecoverRunnable, vulkanRecoverDelayMs)
    }

    /** Rate-limited device recreation; success unlatches the tier. GL worker only. */
    private fun recoverVulkan() {
        vulkanRecoverPosted = false
        val currentEpoch = synchronized(lock) { epoch }
        if (vulkanRecoverEpoch != currentEpoch || !vulkanDisabledForSession) return
        // Imports are dropped by the reinit and re-created on demand; the
        // Kotlin export buffer stays valid and is simply re-imported.
        val spv = loadVulkanSpv() ?: return
        val code = try {
            VfVulkan.reinitNative(spv)
        } catch (_: Exception) {
            VfVulkan.DEVICE_FAILED
        }
        if (code != VfVulkan.OK) {
            vulkanRecoverDelayMs = VfGpuImport.nextVulkanRecoverDelayMs(vulkanRecoverDelayMs)
            scheduleVulkanRecovery()
            return
        }
        // Successful creation alone does not prove the queue can render.
        // Back off the next cycle too; only a presented Vulkan frame resets it.
        vulkanRecoverDelayMs = VfGpuImport.nextVulkanRecoverDelayMs(vulkanRecoverDelayMs)
        consecutiveVulkanFailures = 0
        vulkanDisabledForSession = false
        Log.i("RawViewfinder", "VF Vulkan recovered; re-probing zero-copy")
    }

    /** SPIR-V bytes for Vulkan bring-up; null when the asset is missing. GL worker only. */
    private fun loadVulkanSpv(): ByteArray? = try {
        context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
    } catch (e: Exception) {
        Log.w("RawViewfinder", "VF Vulkan SPIR-V missing", e)
        null
    }

    /** GPU import failure: warn, and stick to the NEON copy after repeated failures. */
    private fun noteGpuFailure(reason: String) {
        consecutiveGpuFailures++
        if (consecutiveGpuFailures >= VfGpuImport.MAX_CONSECUTIVE_FAILURES && !gpuDisabledForSession) {
            gpuDisabledForSession = true
            Log.w("RawViewfinder", "VF GPU failed ($reason); sticking to NEON copy for session")
        } else {
            Log.w("RawViewfinder", "VF GPU frame failed ($reason); NEON fallback covers")
        }
    }

    /**
     * Retire the previous frame's fence and insert a new one behind this present, so the
     * next draw can poll (never block on) GPU progress. Runs on the GL worker only.
     */
    private fun insertFrameFence() {
        previousFence?.let { GLES30.glDeleteSync(it) }
        previousFence = null
        if (!glEs3 || !fencesEnabled) return
        val fence = try {
            GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        } catch (_: Exception) {
            null
        }
        if (fence == null || fence == 0L) {
            fencesEnabled = false
            if (!fenceUnsupportedLogged) {
                fenceUnsupportedLogged = true
                Log.i("RawViewfinder", "VF fence unsupported; frames-in-flight guard off (drop-only pending)")
            }
            return
        }
        previousFence = fence
    }

    /** Stall hide for a SurfaceView: clear the surface layer to black (View alpha is ignored). */
    private val clear = Runnable {
        try {
            if (surface == EGL14.EGL_NO_SURFACE || !hasSurface) return@Runnable
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, surface)
        } catch (failure: Exception) {
            Log.w("RawViewfinder", "VF stall clear failed; hiding VF until retry", failure)
            retryAfterMs = SystemClock.elapsedRealtime() + 2000L
            statGlActive = false
            statStarved = true
            releaseGl()
            post { alpha = 0f }
        }
    }

    private fun initializeGl(target: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        // ES3 first (public GL sync fences for the frames-in-flight guard), ES2 as the
        // fallback with the guard off. ESSL 1.00 shaders run unchanged on both.
        glEs3 = false
        for (version in intArrayOf(3, 2)) {
            val bit = if (version == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, bit, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, IntArray(1), 0)) continue
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) continue
            surface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
                continue
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, eglContext)
                surface = EGL14.EGL_NO_SURFACE
                eglContext = EGL14.EGL_NO_CONTEXT
                continue
            }
            glEs3 = version == 3
            break
        }
        check(surface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) { "VF EGL init failed (ES3+ES2)" }
        Log.i("RawViewfinder", "VF GL ES" + if (glEs3) "3 (fences on)" else "2 fallback (fences off)")
        // Let SurfaceFlinger pace this window. swapInterval=0 flooded the Mali
        // BLAST queue during SR work, producing NO_BUFFER_AVAILABLE/fence storms.
        check(EGL14.eglSwapInterval(display, 1)) { "VF swap interval setup failed" }
        Log.i("RawViewfinder", "VF vsync-paced present (swapInterval=1)")
        fun shader(type: Int, source: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, source); glCompileShader(id)
            val status = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { glGetShaderInfoLog(id) }
            return id
        }
        val vertex = shader(GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; varying vec2 tex; void main(){ tex=uv; gl_Position=vec4(position,0.,1.); }")
        val fragment = shader(GL_FRAGMENT_SHADER, VfGpuImport.cpuFragmentShader())
        program = glCreateProgram(); glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        glDeleteShader(vertex); glDeleteShader(fragment)
        val linked = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, linked, 0); check(linked[0] != 0)
        // Cache once per link: superpixel display uses mediump CCM/gamma (Mali-friendly).
        gainsLoc = glGetUniformLocation(program, "gains")
        colorLoc = glGetUniformLocation(program, "color")
        positionLoc = glGetAttribLocation(program, "position")
        uvLoc = glGetAttribLocation(program, "uv")
        jpegLoc = glGetUniformLocation(program, "u_jpeg")
        agxContrastLoc = glGetUniformLocation(program, "u_agxContrast")
        agxSaturationLoc = glGetUniformLocation(program, "u_agxSaturation")
        agxPurityLoc = glGetUniformLocation(program, "u_agxPurity")
        agxHueLoc = glGetUniformLocation(program, "u_agxHue")
        agxShadowEvLoc = glGetUniformLocation(program, "u_agxShadowEv")
        agxHighlightEvLoc = glGetUniformLocation(program, "u_agxHighlightEv")
        agxGamutLoc = glGetUniformLocation(program, "u_agxGamut")
        highlightShoulderLoc = glGetUniformLocation(program, "u_highlightShoulder")
        exposureEvLoc = glGetUniformLocation(program, "u_exposureEv")
        lensLoc = glGetUniformLocation(program, "u_lens")
        lensSizeLoc = glGetUniformLocation(program, "u_lensSize")
        lensActiveLoc = glGetUniformLocation(program, "u_lensActive")
        applyLensLoc = glGetUniformLocation(program, "u_applyLens")
        quadBaseLoc = glGetUniformLocation(program, "u_quadBase")
        frameSizeLoc = glGetUniformLocation(program, "u_frameSize")
        stepLoc = glGetUniformLocation(program, "u_step")
        check(gainsLoc >= 0 && colorLoc >= 0 && positionLoc >= 0 && uvLoc >= 0 &&
            jpegLoc >= 0 && agxContrastLoc >= 0 && agxSaturationLoc >= 0 && agxPurityLoc >= 0 &&
            agxHueLoc >= 0 && agxShadowEvLoc >= 0 && agxHighlightEvLoc >= 0 && agxGamutLoc >= 0 &&
            highlightShoulderLoc >= 0 &&
            exposureEvLoc >= 0 &&
            lensLoc >= 0 && lensSizeLoc >= 0 && lensActiveLoc >= 0 && applyLensLoc >= 0 &&
            quadBaseLoc >= 0 && frameSizeLoc >= 0 && stepLoc >= 0) { "VF uniforms missing" }
        texWidth = 0
        texHeight = 0
        val ids = IntArray(1); glGenTextures(1, ids, 0); texture = ids[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenTextures(1, ids, 0); vkTexture = ids[0]
        glBindTexture(GL_TEXTURE_2D, vkTexture)
        // LINEAR matches the CPU path's filtering exactly (WYSIWYG parity).
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        // Single RGBA16F lens map: hardware LINEAR does the bilinear interpolation
        // (one fetch per display texel). 1x1 identity until the first map arrives.
        // ES2 has no filterable half-float target, so the lens path stays off there.
        lensTexture = 0
        lensTexCols = 0
        lensTexRows = 0
        if (glEs3) {
            try {
                glGenTextures(1, ids, 0); lensTexture = ids[0]
                glBindTexture(GL_TEXTURE_2D, lensTexture)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
                val one = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
                one.asShortBuffer().put(shortArrayOf(0x3c00, 0x3c00, 0x3c00, 0x3c00))
                one.position(0)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, one
                )
                lensTexCols = 1
                lensTexRows = 1
            } catch (failure: Exception) {
                Log.w("RawViewfinder", "VF lens texture unavailable; identity covers", failure)
                lensTexture = 0
            }
        }
        initializeGpuProgram()
    }

    /**
     * Compile the ESSL 3.00 zero-copy program and create its R16UI texture. Soft failure:
     * any compile/link error disables the GPU path and the NEON sampler covers. Runs on
     * the GL worker during [initializeGl].
     */
    private fun initializeGpuProgram() {
        gpuProgram = 0
        gpuBayerTexture = 0
        if (!glEs3) return
        try {
            fun shader(type: Int, source: String): Int {
                val id = glCreateShader(type)
                glShaderSource(id, source); glCompileShader(id)
                val status = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { glGetShaderInfoLog(id) }
                return id
            }
            val vertex = shader(GL_VERTEX_SHADER, VfGpuImport.gpuVertexShader())
            val fragment = shader(GL_FRAGMENT_SHADER, VfGpuImport.gpuFragmentShader())
            val id = glCreateProgram()
            glAttachShader(id, vertex); glAttachShader(id, fragment); glLinkProgram(id)
            glDeleteShader(vertex); glDeleteShader(fragment)
            val linked = IntArray(1); glGetProgramiv(id, GL_LINK_STATUS, linked, 0); check(linked[0] != 0)
            gpuGainsLoc = glGetUniformLocation(id, "gains")
            gpuColorLoc = glGetUniformLocation(id, "color")
            gpuPositionLoc = glGetAttribLocation(id, "position")
            gpuUvLoc = glGetAttribLocation(id, "uv")
            gpuJpegLoc = glGetUniformLocation(id, "u_jpeg")
            gpuAgxContrastLoc = glGetUniformLocation(id, "u_agxContrast")
            gpuAgxSaturationLoc = glGetUniformLocation(id, "u_agxSaturation")
            gpuAgxPurityLoc = glGetUniformLocation(id, "u_agxPurity")
            gpuAgxHueLoc = glGetUniformLocation(id, "u_agxHue")
            gpuAgxShadowEvLoc = glGetUniformLocation(id, "u_agxShadowEv")
            gpuAgxHighlightEvLoc = glGetUniformLocation(id, "u_agxHighlightEv")
            gpuAgxGamutLoc = glGetUniformLocation(id, "u_agxGamut")
            gpuHighlightShoulderLoc = glGetUniformLocation(id, "u_highlightShoulder")
            gpuExposureEvLoc = glGetUniformLocation(id, "u_exposureEv")
            gpuBayerLoc = glGetUniformLocation(id, "u_bayer")
            gpuQuadBaseLoc = glGetUniformLocation(id, "u_quadBase")
            gpuFrameSizeLoc = glGetUniformLocation(id, "u_frameSize")
            gpuStepLoc = glGetUniformLocation(id, "u_step")
            gpuChansLoc = glGetUniformLocation(id, "u_chans")
            gpuBlackLoc = glGetUniformLocation(id, "u_black")
            gpuInvRangeLoc = glGetUniformLocation(id, "u_invRange")
            gpuLensLoc = glGetUniformLocation(id, "u_lens")
            gpuLensSizeLoc = glGetUniformLocation(id, "u_lensSize")
            gpuLensActiveLoc = glGetUniformLocation(id, "u_lensActive")
            gpuApplyLensLoc = glGetUniformLocation(id, "u_applyLens")
            check(gpuGainsLoc >= 0 && gpuColorLoc >= 0 && gpuPositionLoc >= 0 && gpuUvLoc >= 0 &&
                gpuJpegLoc >= 0 && gpuAgxContrastLoc >= 0 && gpuAgxSaturationLoc >= 0 && gpuAgxPurityLoc >= 0 &&
                gpuAgxHueLoc >= 0 && gpuAgxShadowEvLoc >= 0 && gpuAgxHighlightEvLoc >= 0 && gpuAgxGamutLoc >= 0 &&
                gpuHighlightShoulderLoc >= 0 &&
                gpuExposureEvLoc >= 0 &&
                gpuBayerLoc >= 0 && gpuQuadBaseLoc >= 0 && gpuFrameSizeLoc >= 0 && gpuStepLoc >= 0 &&
                gpuChansLoc >= 0 && gpuBlackLoc >= 0 && gpuInvRangeLoc >= 0 &&
                gpuLensLoc >= 0 && gpuLensSizeLoc >= 0 && gpuLensActiveLoc >= 0 && gpuApplyLensLoc >= 0) { "VF GPU uniforms missing" }
            gpuProgram = id
            val ids = IntArray(1); glGenTextures(1, ids, 0); gpuBayerTexture = ids[0]
            glBindTexture(GL_TEXTURE_2D, gpuBayerTexture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            Log.i("RawViewfinder", "VF GPU program ready")
        } catch (failure: Exception) {
            Log.w("RawViewfinder", "VF GPU program failed; NEON fallback covers", failure)
            gpuProgram = 0
            gpuBayerTexture = 0
        }
    }

    private fun releaseGl() {
        // Runs on the GL worker: release native Vulkan imports first, then our refs.
        try {
            VfVulkan.resetNative()
        } catch (_: Exception) {
            // Best effort: the GL context is going away regardless.
        }
        try {
            vulkanExport?.close()
        } catch (_: Exception) {
        }
        vulkanExport = null
        vulkanExportWidth = 0
        vulkanExportHeight = 0
        if (display != EGL14.EGL_NO_DISPLAY) {
            previousFence?.let {
                try {
                    GLES30.glDeleteSync(it)
                } catch (_: Exception) {
                    // Best effort: the display is going away regardless.
                }
            }
            previousFence = null
            glEs3 = false
            fencesEnabled = true
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        surface = EGL14.EGL_NO_SURFACE; eglContext = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
        gainsLoc = -1; colorLoc = -1; positionLoc = -1; uvLoc = -1
        jpegLoc = -1; agxContrastLoc = -1; agxSaturationLoc = -1; agxPurityLoc = -1
        agxHueLoc = -1; agxShadowEvLoc = -1; agxHighlightEvLoc = -1; agxGamutLoc = -1
        highlightShoulderLoc = -1
        exposureEvLoc = -1
        lensLoc = -1; lensSizeLoc = -1; lensActiveLoc = -1; applyLensLoc = -1
        quadBaseLoc = -1; frameSizeLoc = -1; stepLoc = -1
        lensTexture = 0; lensTexCols = 0; lensTexRows = 0
        lensUpTexture = 0; lensUpCols = 0; lensUpRows = 0; lensUpHash = 0L
        texWidth = 0; texHeight = 0
        gpuProgram = 0; gpuBayerTexture = 0; vkTexture = 0
        gpuGainsLoc = -1; gpuColorLoc = -1; gpuPositionLoc = -1; gpuUvLoc = -1
        gpuJpegLoc = -1; gpuAgxContrastLoc = -1; gpuAgxSaturationLoc = -1; gpuAgxPurityLoc = -1
        gpuAgxHueLoc = -1; gpuAgxShadowEvLoc = -1; gpuAgxHighlightEvLoc = -1; gpuAgxGamutLoc = -1
        gpuHighlightShoulderLoc = -1
        gpuExposureEvLoc = -1
        gpuBayerLoc = -1; gpuQuadBaseLoc = -1; gpuFrameSizeLoc = -1; gpuStepLoc = -1
        gpuChansLoc = -1; gpuBlackLoc = -1; gpuInvRangeLoc = -1
        gpuLensLoc = -1; gpuLensSizeLoc = -1; gpuLensActiveLoc = -1; gpuApplyLensLoc = -1
    }

    companion object {
        // WYSIWYG fragment shaders (CPU ESSL 1.00 + GPU ESSL 3.00) are composed from
        // shared pieces in VfGpuImport so both paths run the identical tonemap tail:
        // u_jpeg == 0 is the raw-clean Reinhard preview (+1 EV) with hardware
        // lens-shading, otherwise the cheap scene-referred AgX-lite preview tracking
        // JpegOutputSettings. No AMaZE detail, denoise or grain: those stay save-only.
        /** Preview-EV refresh cadence: the percentile statistic is stable at 2 Hz. */
        private const val PREVIEW_EV_INTERVAL_MS = 500L
        /** Save-path adaptive correction bound (AdaptiveDevelopmentExposure). */
        private const val MAX_PREVIEW_EV = 1.5f
        /** NEON sampler average cadence: one line per 5 s of fallback frames. */
        private const val NEON_LOG_INTERVAL_MS = 5000L
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        worker.post {
            releaseGl()
            window = holder.surface
            val frame = holder.surfaceFrame
            viewportWidth = frame.width().coerceAtLeast(1)
            viewportHeight = frame.height().coerceAtLeast(1)
            hasSurface = holder.surface.isValid
            Log.i("RawViewfinder", "VF surface created ${viewportWidth}x$viewportHeight")
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        worker.post {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
            window = holder.surface
            hasSurface = holder.surface.isValid
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Status string via invalidateSession (overlay goes inactive/starved) + logcat here.
        // The framework owns the Surface: never release it, just drop GL state. The camera
        // session (ImageReader targets) is untouched — only VF display pauses.
        Log.w("RawViewfinder", "VF surface lost; hiding VF until surface returns")
        invalidateSession()
        worker.post { hasSurface = false; window = null; releaseGl() }
    }
}
