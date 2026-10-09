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

    /**
     * Exact inverse of [sensorPoint]: normalized sensor coordinates back to
     * normalized view coordinates. Face boxes (sensor space) reach the overlay
     * through this so they land where a tap at the same spot would meter.
     */
    fun viewPoint(su: Float, sv: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        fun unmirror(u: Float): Float = if (mirrored) 1f - u else u
        return when (rotation) {
            90 -> unmirror(1f - sv) to su
            180 -> unmirror(1f - su) to 1f - sv
            270 -> unmirror(sv) to 1f - su
            else -> unmirror(su) to sv
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
        // Even output extents: odd widths (e.g. 409x307 at 480p on 12 MP) leave a
        // half-texel edge column that some Adreno drivers sample as a bright/colored
        // line at the display border. Round down (never up past the crop).
        val width = ((right - left) / step / 2 * 2).coerceAtLeast(2)
        val height = ((bottom - top) / step / 2 * 2).coerceAtLeast(2)
        return VfQuadGeometry(left, top, width, height, step)
    }
}

/**
 * Input-signal reading for Vulkan tier probation: buffer peak plus the levels
 * that judge it. A strong signal with a black tier output latches the tier.
 */
internal data class VfInputSignal(val dataMax: Int, val black: Int, val white: Int)

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
    /** Bounded-wait skips (record owns the queue): diagnosing, not failing. */
    val busySkips: Long,
    /** True when the last rendered frame took the zero-copy GPU path (false = NEON CPU). */
    val gpu: Boolean = false,
    /** Tonemap actually rendered: true = calibrated JPEG/AgX, false = RAW/Reinhard. */
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
    SurfaceView(context, attrs), SurfaceHolder.Callback, VfEngine {
    /**
     * Per-frame WYSIWYG snapshot shared by the NEON CPU and GPU paths: same WB gains,
     * same CCM, same calibrated camera-to-ACEScg JPEG color, same JPEG flag + 7 AgX
     * params, copied from volatiles in offer() so a settings change never tears a
     * frame in flight.
     */
    private interface FrameState {
        val gains: FloatArray
        val matrix: FloatArray
        /** Calibrated JPEG color: GLSL column-major camera-to-ACEScg (no EV folded). */
        val camToAces: FloatArray
        /** Camera-space neutral white for JPEG highlight neutralization. */
        val camWhite: FloatArray
        var displayP3: Boolean
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
        /** Quad-relative row of canonical Gr: the G-even/G-odd swap tests this parity. */
        var lensGreenRow: Int
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
        override val camToAces = FloatArray(9)
        override val camWhite = FloatArray(3) { 1f }
        override var displayP3 = false
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
        override var lensGreenRow = 0
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
        override val camToAces = FloatArray(9)
        override val camWhite = FloatArray(3) { 1f }
        // Calibrated scene-referred JPEG snapshot: same color math as the saved
        // JPEG on the superpixel. Copied from volatiles in offer() so a settings
        // change never tears a frame.
        override var displayP3 = false
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
        override var lensGreenRow = 0
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
    private var vfDiagLogged = false
    private var vfSanitizeLogged = false
    /** True while display levels fall back to the data-driven replacement. Reset per session. */
    @Volatile private var vfLevelsFallbackActive = false
    /** Sticky Vulkan skip after repeated compute failures; reset per session. */
    @Volatile private var vulkanDisabledForSession = false
    /** GL-worker-only consecutive Vulkan failure count. */
    private var consecutiveVulkanFailures = 0
    /**
     * Vulkan sub-tier latches (zero-copy import / gpu-copy staging), set on the
     * GL worker by explicit failures or the black-output probe. Both latched
     * implies [vulkanDisabledForSession]. Volatile: reset on the camera thread
     * by [invalidateSession]. Reset per session.
     */
    @Volatile private var vulkanZeroCopyDisabled = false
    @Volatile private var vulkanCopyDisabled = false
    /** Black-output probation verdicts; a verified tier skips probing. GL worker only. */
    private var vulkanZeroCopyVerified = false
    private var vulkanCopyVerified = false
    /** Copy tier has dispatched at least once (gates its probation). GL worker only. */
    private var vulkanCopyActive = false
    /** Latest input-signal reading for tier probation. Camera writes, GL worker reads. */
    @Volatile private var vfInputSignal: VfInputSignal? = null
    /** Cleared once every Vulkan tier is verified or latched (or never used). */
    @Volatile private var vfProbeInputSignal = true
    private var vulkanInitialized = false
    private var vulkanExport: android.hardware.HardwareBuffer? = null
    private var vulkanExportWidth = 0
    private var vulkanExportHeight = 0
    /**
     * Cached EGLImage for [vulkanExport]: the export buffer is stable
     * across frames, so one image serves every Vulkan-tier draw instead
     * of create/bind/destroy gralloc churn per frame (same pixels — the
     * image wraps the buffer, whose contents refresh every dispatch).
     * Identity-keyed: any export realloc/poison drop swaps the buffer and
     * must drop this first. GL worker only.
     */
    private var exportEglImage = 0L
    private var exportEglSource: android.hardware.HardwareBuffer? = null
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
     * True = calibrated scene-referred JPEG preview: the save-path color math
     * (neutralize -> EV -> camera-to-ACEScg -> AgX -> sRGB/P3 -> OETF -> dither)
     * on the sampled superpixel, tracking [JpegOutputSettings]. Never ISP YUV.
     */
    @Volatile var renderJpeg = false
        private set
    /**
     * Engine override switched by tapping the RAW VF debug overlay. AUTO runs
     * zero-copy GPU with automatic fallback down the chain (gpu-copy, EGL, then
     * the native NEON sampler); GPU/CPU force one engine. Volatile: written from
     * the UI thread, read on camera.
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
    /** Display P3 JPEG target, mirrored so the VF shows the saved output gamut. */
    @Volatile private var previewP3 = false
    /** Calibrated color cache; snapshotRenderState runs on the camera thread only. */
    private val calibratedColorCache = VfCalibratedColorCache()
    /** Static DNG calibration, re-read only when the characteristics identity changes. */
    private var calibCharacteristics: CameraCharacteristics? = null
    private var calibStaticMats: List<DoubleArray?> = listOf(null, null, null, null, null, null)
    private var calibIlluminant1: Int? = null
    private var calibIlluminant2: Int? = null
    /**
     * Applied adaptive preview EV (correction × strength, per-frame smoothed):
     * the JPEG tonemap multiplies scene-linear rgb by exp2 of this, exactly
     * where the save path applies its development exposure. Always 0 in RAW
     * mode. Ramps toward [targetPreviewEv] every offered frame so pans glide
     * instead of stepping at the 2 Hz estimator cadence.
     */
    @Volatile private var previewExposureEv = 0f
    /** Latest 2 Hz estimator output; camera thread only. */
    private var targetPreviewEv = 0f
    /** Smoothed calibrated color the JPEG uniforms sample; camera thread only. */
    private val smoothCamToAces = FloatArray(9)
    private val smoothCamWhite = FloatArray(3) { 1f }
    private var smoothColorSeeded = false
    private var lastSmoothMs = 0L
    /** Save-path strength rule (AUTO/ZSL = auto?1:0, PROGRAM = slider, MANUAL = 0). */
    @Volatile private var previewExposureStrength = 0f
    /** Scratch for the 2 Hz preview-EV estimate; camera thread only. */
    private val previewEvScratch = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
    private var lastPreviewEvMs = 0L
    private var previewEvSeeded = false
    @Volatile private var sampleIntervalMs = VfResolution.RATE_FULL_MS
    /**
     * Floor for [sampleIntervalMs]: 30 fps normally, raised by record mode
     * (10 fps) or power-save mode (15 fps) via [updateRateFloor].
     * Volatile: written from the UI thread, read on the camera thread.
     */
    @Volatile private var minSampleIntervalMs = VfResolution.RATE_FULL_MS
    /**
     * True while a Direct-Log take owns the shared Vulkan queue: the VF
     * drops to 480p @<=10 fps so record submits never wait on a VF
     * dispatch (submit-side mutex + shared-queue GPU time are the
     * contention; the VF fence wait itself runs unlocked). Saved edge
     * restores on exit. Any thread.
     */
    @Volatile private var recordMode = false
    /**
     * Power-save mode (system battery saver): the VF drops to 15 fps at most
     * 640 long edge. Composes with [recordMode] (record wins); the user edge
     * is never mutated, the cap applies in offer(). Any thread.
     */
    @Volatile private var powerSave = false
    private var savedLongEdge = VfResolution.MAX
    /**
     * True from the moment an offer posts a draw until that draw finishes
     * (latest-only: at most one queued + one running). Lets a recorder
     * skip offers while the worker is busy instead of churning superseded
     * frames through gralloc leases. Any thread.
     */
    @Volatile private var vfWorkOutstanding = false
    private var lastSlowOfferLogMs = 0L
    private var lastSlowRenderLogMs = 0L
    /** Field-telemetry cadence gate for [noteFrameRendered]; GL worker only. */
    private var lastVfStatsLogMs = 0L
    override var onStarvation: (() -> Unit)? = null
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
    private var gpuCamToAcesLoc = -1
    private var gpuCameraWhiteLoc = -1
    private var gpuDisplayP3Loc = -1
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
    private var gpuBlackQLoc = -1
    private var gpuDenQLoc = -1
    private var gpuLensLoc = -1
    private var gpuLensSizeLoc = -1
    private var gpuLensActiveLoc = -1
    private var gpuApplyLensLoc = -1
    private var gpuLensGreenRowLoc = -1
    // Cached GL locations: string lookups once per link, not once per frame.
    private var gainsLoc = -1
    private var colorLoc = -1
    private var camToAcesLoc = -1
    private var cameraWhiteLoc = -1
    private var displayP3Loc = -1
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
    private var lensGreenRowLoc = -1
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
    /** 1 Hz re-verify gate for the cached snapshot (camera thread only). */
    private var lensRecheckMs = 0L
    private var lensRecheckChars: CameraCharacteristics? = null
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
    override fun dispose() { invalidateSession(); worker.post { worker.removeCallbacks(vulkanRecoverRunnable); releaseGl(); thread.quitSafely() } }

    override fun invalidateSession() {
        // A queued draw that never runs (dispose) must not pin the idle gate.
        vfWorkOutstanding = false
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
        targetPreviewEv = 0f
        previewEvSeeded = false
        smoothColorSeeded = false
        lastSmoothMs = 0L
        lastPreviewEvMs = 0L
        lensCacheHash = 0L
        lensCacheSnapshot = null
        lensRecheckMs = 0L
        lensRecheckChars = null
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
        vfDiagLogged = false
        vfSanitizeLogged = false
        vfLevelsFallbackActive = false
        vulkanZeroCopyDisabled = false
        vulkanCopyDisabled = false
        vulkanZeroCopyVerified = false
        vulkanCopyVerified = false
        vulkanCopyActive = false
        vfInputSignal = null
        vfProbeInputSignal = true
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
    override fun snapshot(): RawVfStats {
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
            busySkips = vulkanBusySkips,
            gpu = statGpuPath,
            jpeg = renderJpeg,
            exposureEv = previewExposureEv,
            engine = engineMode
        )
    }

    override fun expectFrameInterval(nanos: Long) {
        expectedIntervalMs = (nanos / 1_000_000L).coerceAtLeast(16L)
    }

    /** Selectable VF resolution (long edge, 480/640/960/1080). Applies to the next sampled frame. */
    override fun setTargetLongEdge(longEdge: Int) {
        targetLongEdge = longEdge.coerceIn(VfResolution.MIN, VfResolution.MAX)
    }

    /**
     * Direct-Log record throttle: 480p + 10 fps ceiling while a take owns
     * the shared Vulkan queue, previous edge restored on exit. Record
     * submits and VF dispatches serialize on one native mutex, so a
     * full-rate VF steals record frames (PTS gaps = visible stutter when
     * panning) and renders choppily itself behind 4K fused dispatches.
     * Safe to call from any thread; idempotent.
     */
    override fun setRecordMode(active: Boolean) {
        if (recordMode == active) return
        recordMode = active
        if (active) {
            savedLongEdge = targetLongEdge
            targetLongEdge = VfResolution.MIN
            updateRateFloor()
            Log.i("RawViewfinder", "VF record mode on (480p @<=10fps)")
        } else {
            targetLongEdge = savedLongEdge.coerceIn(VfResolution.MIN, VfResolution.MAX)
            updateRateFloor()
            Log.i("RawViewfinder", "VF record mode off (edge=$targetLongEdge)")
        }
    }

    /**
     * Power-save throttle: 15 fps at most 640 long edge while the system
     * battery saver is on. Record mode wins when both are active. The user
     * edge is untouched; the cap applies per offer. Safe from any thread.
     */
    override fun setPowerSave(active: Boolean) {
        if (powerSave == active) return
        powerSave = active
        updateRateFloor()
        Log.i("RawViewfinder", "VF power-save ${if (active) "on (<=640p @<=15fps)" else "off"}")
    }

    /** Recompute the offer-rate floor from record/power-save policy. Any thread. */
    private fun updateRateFloor() {
        val floor = VfResolution.rateFloorMs(recordMode, powerSave)
        minSampleIntervalMs = floor
        sampleIntervalMs = maxOf(sampleIntervalMs, floor)
        if (!recordMode && !powerSave) sampleIntervalMs = floor
    }

    /**
     * True when the worker has no draw queued or running: recorders offer
     * only then, so a slow render never stacks superseded frames behind
     * it. Latest-only semantics make the skip free. Any thread.
     */
    fun isIdleForOffer(): Boolean =
        !vfWorkOutstanding && synchronized(lock) { pending == null && gpuPending == null }

    /** Switch the tonemap without touching the stream. Safe to call from any thread. */
    override fun setRenderJpeg(jpeg: Boolean) {
        renderJpeg = jpeg
    }

    /** Force one engine (overlay tap) without touching the stream. Safe from any thread. */
    override fun setEngineMode(mode: VfEngineMode) {
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
    override fun setPreviewExposureStrength(strength: Float) {
        previewExposureStrength = strength.coerceIn(0f, 1f)
    }

    /**
     * Live-link AgX sliders to the JPEG preview. Only volatile writes; the next sampled
     * frame carries the snapshot so a change never tears a frame in flight.
     * Safe to call from any thread.
     */
    override fun setAgx(settings: JpegOutputSettings) {
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
        previewP3 = resolved.displayP3
    }

    fun setPreviewTuning(tuning: AdaptiveExposureTuning) {
        val bounded = tuning.bounded()
        previewHeadroom = bounded.highlightHeadroom.toFloat()
        previewSoftHeadroom = bounded.highlightSoftHeadroom.toFloat()
    }

    /** Called inline on the camera handler. All plane access ends before this method returns. */
    override fun offer(image: Image, c: CameraCharacteristics, result: CaptureResult?) {
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
            val longEdge = VfResolution.effectiveEdge(targetLongEdge, recordMode, powerSave)
            val gpuGeo = RawPreviewGeometry.quadGeometry(image.width, image.height, crop, longEdge)
            statRawWidth = image.width
            statRawHeight = image.height
            statStarved = false
            val cfaInt = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
            val channels = RawPreviewGeometry.channels(cfaInt)
            val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val dynamicBlack = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            val dynamicWhite = result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            val reportedWhite = (dynamicWhite
                ?: c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val reportedLevels = FloatArray(4) { i -> dynamicBlack?.get(i) ?: black?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 0f }
            val levelsSane = VfLevels.isSane(reportedLevels, reportedWhite)
            // Tier black-output probation needs a fresh input-signal reading
            // until every Vulkan tier is verified or latched; then sampling
            // stops (zero steady-state cost). CPU override never probes, but
            // the first-frame diagnostic always samples, as does an insane
            // reported-levels fallback (its data-driven replacement needs a
            // fresh sample every frame it is active).
            val probing = engineMode != VfEngineMode.CPU && vfProbeInputSignal && VfVulkan.available
            val inputSample = if (probing || !vfDiagLogged || !levelsSane) {
                try {
                    sampleRawCodes(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
                } catch (_: Exception) {
                    null
                }
            } else null
            // Multi-mode HALs (Vivo X300 Ultra default mode) report unusable
            // levels (white=0 or white <= black): toFixedQ6 would throw and
            // hide the VF, or clamp every code to 0 behind an INCONCLUSIVE
            // probe. Derive display levels from the sampled data instead.
            val (levels, white, levelsFallback) = if (levelsSane) {
                Triple(reportedLevels, reportedWhite, false)
            } else if (inputSample != null) {
                val (fbLevels, fbWhite) = VfLevels.fallbackFromSample(inputSample.first, inputSample.second)
                Triple(fbLevels, fbWhite, true)
            } else {
                val (fbLevels, fbWhite) = VfLevels.lastResort()
                Triple(fbLevels, fbWhite, true)
            }
            if (levelsFallback != vfLevelsFallbackActive) {
                vfLevelsFallbackActive = levelsFallback
                if (levelsFallback) {
                    Log.w("RawViewfinder", "VF levels fallback: reported black=${reportedLevels.joinToString(",")} white=$reportedWhite unusable; " +
                        "data-driven black=${levels.joinToString(",")} white=$white")
                } else {
                    Log.i("RawViewfinder", "VF levels recovered to reported black=${levels.joinToString(",")} white=$white")
                }
            }
            val lensSnap = snapshotLens(c, result, image.width, image.height)
            if (probing) {
                inputSignalFromSample(inputSample, levels, white)?.let { vfInputSignal = it }
            }
            if (!vfDiagLogged) {
                vfDiagLogged = true
                logFirstFrameDiag(plane, image.width, image.height, cfaInt, channels,
                    reportedLevels, reportedWhite, levels, white, levelsFallback, gpuGeo, inputSample)
            }
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
                VfEngineMode.AUTO, VfEngineMode.BGU, VfEngineMode.BGU_CPU -> {
                    // BGU modes never reach the legacy engine (the controller
                    // maps them); treat as AUTO defensively so the when
                    // stays total.
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
     * First-frame field diagnostic: the render inputs (black/white levels, CFA
     * mapping, geometry) plus a tiny CPU sample of the actual buffer codes, so
     * a black viewfinder is attributable to data (all codes at black), levels
     * (black above signal), or the GPU tier (sane values, black output).
     * Camera thread only; failures log as unavailable and never hide the VF.
     */
    private fun logFirstFrameDiag(
        plane: Image.Plane, width: Int, height: Int,
        cfa: Int, channels: IntArray,
        reportedLevels: FloatArray, reportedWhite: Float,
        levels: FloatArray, white: Float, levelsFallback: Boolean,
        geo: VfQuadGeometry, sample: Triple<Int, Int, Long>?
    ) {
        val pitch = if (plane.pixelStride > 0) plane.rowStride / plane.pixelStride else -1
        val data = sample?.let { "dataMin=${it.first} dataMax=${it.second} dataMean=${it.third}" }
            ?: "dataSample=failed"
        val levelNote = if (levelsFallback) {
            "reportedBlack=${reportedLevels.joinToString(",")} reportedWhite=$reportedWhite " +
                "fallbackBlack=${levels.joinToString(",")} fallbackWhite=$white"
        } else {
            "black=${levels.joinToString(",")} white=$white"
        }
        Log.i("RawViewfinder",
            "VF first frame raw=${width}x$height cfa=$cfa chans=${channels.joinToString(",")} " +
                "pitch=$pitch $levelNote " +
                "geom=(${geo.left},${geo.top} ${geo.width}x${geo.height} s${geo.step}) $data")
    }

    /**
     * Coarse min/max/mean over ~3k buffer codes; absolute reads only. Null
     * when the plane layout is unusable (hostile strides, empty buffer) or
     * nothing was readable, instead of throwing: the tier probe and the
     * levels fallback treat a null sample as "no signal yet".
     */
    private fun sampleRawCodes(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int
    ): Triple<Int, Int, Long>? {
        if (pixelStride <= 0 || rowStride < 0 || width <= 0 || height <= 0) return null
        // Last readable u16 offset (absolute gets are limit-bounded);
        // every sample stays at or below it even when the HAL's stride
        // claims exceed the buffer's backing store.
        val lastReadable = buffer.limit() - 2
        if (lastReadable < 0) return null
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L
        var n = 0L
        val stepX = maxOf(1, width / 64)
        val stepY = maxOf(1, height / 48)
        var y = 0
        while (y < height) {
            val rowBase = y.toLong() * rowStride.toLong()
            if (rowBase > lastReadable) break
            var x = 0
            while (x < width) {
                val offset = rowBase + x.toLong() * pixelStride.toLong()
                if (offset > lastReadable) break
                val code = buffer.getShort(offset.toInt()).toInt() and 0xffff
                if (code < min) min = code
                if (code > max) max = code
                sum += code
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return null
        return Triple(min, max, sum / n)
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
        // 1 Hz re-verify: skip the per-frame gain-copy + content hash while a
        // snapshot is cached. Active bounds are session-invariant for one
        // characteristics instance, so the cached verdict stays exact.
        val cachedSnap = lensCacheSnapshot
        if (cachedSnap != null && !VfGpuImport.shouldRecheckLensMap(
                SystemClock.elapsedRealtime(), lensRecheckMs,
                lensRecheckChars === c, hasCached = true
            )
        ) {
            return cachedSnap
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
            lensRecheckMs = SystemClock.elapsedRealtime()
            lensRecheckChars = c
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
            snapshotRenderState(c, result, channels, frame, now)
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
            vfWorkOutstanding = true
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
            snapshotRenderState(c, result, channels, frame, now)
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
            vfWorkOutstanding = true
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
     * JPEG uses, on a coarse grid (≤4096 samples, sub-millisecond). Writes the
     * target only; snapshotRenderState ramps the applied value toward it every
     * offered frame so pans glide instead of stepping. Camera thread only.
     */
    private fun updatePreviewExposure(
        plane: Image.Plane, left: Int, top: Int, width: Int, height: Int, step: Int,
        levels: FloatArray, white: Float, now: Long, lens: VfLensSnapshot = VfLensSnapshot.IDENTITY
    ) {
        val strength = previewExposureStrength
        if (!renderJpeg || strength <= 0f) {
            if (previewExposureEv != 0f) previewExposureEv = 0f
            targetPreviewEv = 0f
            previewEvSeeded = false
            smoothColorSeeded = false
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
        targetPreviewEv = applied
        if (!previewEvSeeded) {
            // Snap on seed: no startup ramp from 0 on session/mode entry.
            previewEvSeeded = true
            previewExposureEv = applied
        }
    }

    /** Shared WYSIWYG snapshot: identical gains/matrix/JPEG/AgX for both paths. */
    private fun snapshotRenderState(
        c: CameraCharacteristics, result: CaptureResult?, channels: IntArray, out: FrameState, now: Long
    ) {
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val rawGains = floatArrayOf(
            gains?.red ?: 1f,
            (if (channels[1] / 2 == 0) gains?.greenEven else gains?.greenOdd) ?: 1f,
            (if (channels[2] / 2 == 0) gains?.greenEven else gains?.greenOdd) ?: 1f,
            gains?.blue ?: 1f
        )
        var sanitized = false
        for (i in 0..3) {
            val clean = sanitizeWbGain(rawGains[i])
            if (clean != rawGains[i]) sanitized = true
            out.gains[i] = clean
        }
        val matrix = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        for (row in 0..2) for (col in 0..2) {
            val identity = if (row == col) 1f else 0f
            val raw = matrix?.getElement(col, row)?.toFloat() ?: identity
            val clean = sanitizeCcmElement(raw, identity)
            if (clean != raw) sanitized = true
            out.matrix[col * 3 + row] = clean
        }
        // A bogus HAL value here poisons every presented frame (NaN renders
        // black on Adreno), so a trigger is worth one field-diagnostic line.
        if (sanitized && !vfSanitizeLogged) {
            vfSanitizeLogged = true
            Log.w("RawViewfinder", "VF sanitized non-finite render gains/matrix (gains=${rawGains.joinToString(",")})")
        }
        // Calibrated JPEG color from the same characteristics/result keys the save
        // path freezes into RawFrameMetadata. Skipped entirely in RAW mode (the
        // Reinhard branch never samples these uniforms); in JPEG mode the static
        // DNG matrices re-read only when the characteristics identity changes and
        // the cache re-resolves only when the neutral/gains actually move, so the
        // steady-state cost is one result lookup plus small-array comparisons.
        // out.gains/out.matrix above are the sanitized HAL values the fallback needs.
        if (renderJpeg) {
            if (c !== calibCharacteristics) {
                calibCharacteristics = c
                calibStaticMats = listOf(
                    c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toImmutableDoubles()?.toDoubleArray(),
                    c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toImmutableDoubles()?.toDoubleArray(),
                    c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toImmutableDoubles()?.toDoubleArray(),
                    c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toImmutableDoubles()?.toDoubleArray(),
                    c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toImmutableDoubles()?.toDoubleArray(),
                    c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toImmutableDoubles()?.toDoubleArray()
                )
                calibIlluminant1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
                calibIlluminant2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            }
            val neutralNow = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { rationals ->
                DoubleArray(rationals.size) { rationals[it].toDouble() }
            }
            val gainsSensor = gains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
            val calibrated = calibratedColorCache.resolve(
                calibStaticMats, calibIlluminant1, calibIlluminant2,
                neutralNow, gainsSensor, out.gains, out.matrix
            )
            // Per-frame approach toward the 2 Hz EV target and the AWB-gated
            // color target: pans ramp continuously instead of stepping.
            val dtMs = if (lastSmoothMs == 0L) 0L else (now - lastSmoothMs).coerceIn(0L, 1000L)
            lastSmoothMs = now
            if (previewEvSeeded) {
                previewExposureEv = VfGpuImport.smoothToward(previewExposureEv, targetPreviewEv, dtMs)
            }
            if (!smoothColorSeeded) {
                smoothColorSeeded = true
                calibrated.cameraToAcescgColumnMajor.copyInto(smoothCamToAces)
                calibrated.cameraWhiteNormalized.copyInto(smoothCamWhite)
            } else {
                for (i in 0..8) smoothCamToAces[i] = VfGpuImport.smoothToward(
                    smoothCamToAces[i], calibrated.cameraToAcescgColumnMajor[i], dtMs)
                for (i in 0..2) smoothCamWhite[i] = VfGpuImport.smoothToward(
                    smoothCamWhite[i], calibrated.cameraWhiteNormalized[i], dtMs)
            }
            smoothCamToAces.copyInto(out.camToAces)
            smoothCamWhite.copyInto(out.camWhite)
        }
        // Snapshot the WYSIWYG render state with the frame so AgX slider moves
        // and RAW/JPEG switches never tear a frame in flight.
        out.jpeg = renderJpeg
        out.displayP3 = previewP3
        out.lensGreenRow = VfGpuImport.lensGreenRow(channels)
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
        // 30 Hz budget: floor sits just under the 33.3 ms camera period so a
        // 30 fps stream locks every frame; back off only when the copy itself
        // exceeds it. Previously max(34, copy*2) capped at ~15-25 fps.
        // Record/power-save modes raise the floor (never lowered here).
        sampleIntervalMs = if (throttleCpuCopy) max(minSampleIntervalMs, (SystemClock.elapsedRealtime() - now) * 2)
        else minSampleIntervalMs
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
        // Field telemetry for fps-drop diagnosis: rendered cadence next to the
        // camera's own interval separates app-side drops from a slow sensor
        // (long night exposures deliver <30 fps no viewfinder can exceed).
        if (renderNow - lastVfStatsLogMs >= VF_STATS_LOG_INTERVAL_MS) {
            lastVfStatsLogMs = renderNow
            Log.i("RawViewfinder", String.format(java.util.Locale.US,
                "VF stats: fps=%.1f gpu=%b jpeg=%b engine=%s vf=%dx%d camInterval=%dms",
                statFps, gpu, renderJpeg, engineMode.name,
                statVfWidth, statVfHeight, expectedIntervalMs))
        }
        stallCleared = false
        post { if (frameEpoch == synchronized(lock) { epoch } && SystemClock.elapsedRealtime() - lastDisplayed < max(2000L, expectedIntervalMs * 3)) alpha = 1f }
    }

    /** CPU-program tonemap uniforms shared by the CPU path and the Vulkan tier. */
    private fun applyCpuTonemap(state: FrameState, width: Int, height: Int) {
        glUniform4fv(gainsLoc, 1, state.gains, 0)
        glUniformMatrix3fv(colorLoc, 1, false, state.matrix, 0)
        glUniformMatrix3fv(camToAcesLoc, 1, false, state.camToAces, 0)
        glUniform3fv(cameraWhiteLoc, 1, state.camWhite, 0)
        glUniform1i(displayP3Loc, if (state.displayP3) 1 else 0)
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
        glUniform1i(lensGreenRowLoc, state.lensGreenRow)
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
        glUniform1i(gpuLensGreenRowLoc, frame.lensGreenRow)
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
        // Rewind every draw: some drivers advance the NIO position on
        // glVertexAttribPointer, which would shift the quad/UVs to the display
        // border on the second frame onward.
        vertices.position(0)
        coordinates.position(0)
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
        } finally {
            vfWorkOutstanding = false
            synchronized(lock) { free.addLast(frame) }
        }
    }

    /**
     * GPU present, three tiers. Tier 1 (primary): Vulkan superpixel compute into
     * the export buffer, EGL-imported and rendered through the ESSL 1.00 tonemap
     * program — first zero-copy (imported HAL buffer), then gpu-copy (staging
     * memcpy) when zero-copy fails or probes black. Tier 2: direct EGL import of
     * the HAL buffer through the Bayer program (HALs where that import works).
     * Any failure falls back down the chain to the NEON sampler, same frame when
     * possible, so a broken tier never shows a black viewfinder. The controller
     * defers Image.close until our frame lease closes. Runs on the GL worker.
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
            var vkCopy = false
            if (!vulkanDisabledForSession && !vulkanZeroCopyDisabled) {
                val computeStarted = SystemClock.elapsedRealtime()
                val code = runVulkanSuperpixel(frame, copy = false)
                computeMs = SystemClock.elapsedRealtime() - computeStarted
                if (code == VfVulkan.BUSY) {
                    // Bounded-wait backpressure (record dispatches own the
                    // queue): skip the frame, keep the tier. Must NOT fall
                    // through (the copy tier shares the queue: BUSY too; EGL
                    // is the wrong path for a healthy session) or count as a
                    // failure (no quarantine).
                    noteVulkanBusy()
                    return@Runnable
                }
                if (code == VfVulkan.OK && vulkanExport != null) {
                    if (probeVulkanTier(zeroCopy = true)) importBuffer = vulkanExport
                } else {
                    noteVulkanFailure(VfVulkan.describe(code), zeroCopy = true)
                }
            }
            if (importBuffer == null && !vulkanDisabledForSession && !vulkanCopyDisabled) {
                if (!vulkanCopyVerified) {
                    // First copy dispatch needs a fresh input signal for its
                    // probation (zero-copy may have cleared probing already).
                    vulkanCopyActive = true
                    vfProbeInputSignal = true
                }
                val computeStarted = SystemClock.elapsedRealtime()
                val code = runVulkanSuperpixel(frame, copy = true)
                computeMs = SystemClock.elapsedRealtime() - computeStarted
                if (code == VfVulkan.BUSY) {
                    // Same backpressure gate as the zero-copy tier above.
                    noteVulkanBusy()
                    return@Runnable
                }
                if (code == VfVulkan.OK && vulkanExport != null) {
                    if (probeVulkanTier(zeroCopy = false)) {
                        importBuffer = vulkanExport
                        vkCopy = true
                    }
                } else {
                    noteVulkanFailure(VfVulkan.describe(code), zeroCopy = false)
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
            if (!bayer && importBuffer === vulkanExport) {
                // The Vulkan compute fence-waited inside runVulkanSuperpixel,
                // so the HAL input and its camera lease are fully consumed:
                // GL samples only the export from here. Releasing now
                // instead of after present shortens the gralloc hold by the
                // GL+EGL+swap tail (~10-30ms) and relieves the BufferQueue
                // pressure behind the Mali unlock-error storms. Bayer frames
                // keep the buffer through the draw (GL samples it directly).
                // Idempotent with the outer finally (lease + buffer closes).
                try {
                    frame.close()
                } catch (_: Exception) {
                    // Best effort: the outer finally closes again.
                }
            }
            // The Vulkan export buffer is stable: reuse its cached EGLImage.
            // The Bayer path imports a different HAL buffer per frame and
            // keeps per-frame images (owned = destroyed below).
            val cacheable = !bayer && importBuffer === vulkanExport
            var eglImage = 0L
            var ownsEglImage = true
            if (cacheable && exportEglSource === importBuffer && exportEglImage != 0L) {
                eglImage = exportEglImage
                ownsEglImage = false
            } else {
                eglImage = try {
                    VfEglImport.createEGLImage(importBuffer)
                } catch (_: Exception) {
                    0L
                }
                if (eglImage == 0L) {
                    if (bayer) noteGpuFailure("egl-import") else noteVulkanFailure("egl-import", zeroCopy = !vkCopy)
                    return@Runnable
                }
                if (cacheable) {
                    dropExportEglImage()
                    exportEglSource = importBuffer
                    exportEglImage = eglImage
                    ownsEglImage = false
                }
            }
            try {
                val toneStarted = SystemClock.elapsedRealtime()
                glViewport(0, 0, viewportWidth, viewportHeight)
                if (!bayer) {
                    glUseProgram(program)
                    glActiveTexture(GL_TEXTURE0)
                    val bindError = VfEglImport.bindEGLImageToTexture2D(eglImage, vkTexture)
                    if (bindError != GL_NO_ERROR) {
                        // A stale cached image binds dirty once: drop it so the
                        // next frame recreates, then fail down the tier chain.
                        if (!ownsEglImage) dropExportEglImage()
                        noteVulkanFailure("egl-bind=$bindError", zeroCopy = !vkCopy)
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
                    val (blackQ, denQ) = VfLevels.toFixedQ6(frame.levels, frame.white)
                    glUniform4i(gpuBlackQLoc, blackQ[0], blackQ[1], blackQ[2], blackQ[3])
                    glUniform4i(gpuDenQLoc, denQ[0], denQ[1], denQ[2], denQ[3])
                    glUniform4fv(gpuGainsLoc, 1, frame.gains, 0)
                    glUniformMatrix3fv(gpuColorLoc, 1, false, frame.matrix, 0)
                    glUniformMatrix3fv(gpuCamToAcesLoc, 1, false, frame.camToAces, 0)
                    glUniform3fv(gpuCameraWhiteLoc, 1, frame.camWhite, 0)
                    glUniform1i(gpuDisplayP3Loc, if (frame.displayP3) 1 else 0)
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
                val toneMs = SystemClock.elapsedRealtime() - toneStarted
                checkPresent()
                val swapMs = SystemClock.elapsedRealtime() - toneStarted - toneMs
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
                    val tier = if (bayer) "egl" else if (vkCopy) "vulkan-copy" else "vulkan-zero"
                    Log.i("RawViewfinder", "VF GPU active ($tier)")
                }
                noteFrameRendered(frame.epoch, gpu = true)
                val drawFinished = SystemClock.elapsedRealtime()
                val drawMs = drawFinished - drawStarted
                if (drawMs >= 34L && drawFinished - lastSlowRenderLogMs >= 1000L) {
                    lastSlowRenderLogMs = drawFinished
                    Log.i("RawViewfinder", "Slow RAW draw: total=${drawMs}ms compute=${computeMs}ms tone=${toneMs}ms swap=${swapMs}ms age=${drawStarted - frame.timestamp}ms")
                }
            } finally {
                // Cached export images outlive the draw (destroyed on export
                // realloc/poison, bind failure, or GL teardown); per-frame
                // images destroy here, as before.
                if (ownsEglImage) {
                    try {
                        VfEglImport.destroyEGLImage(eglImage)
                    } catch (_: Exception) {
                        // Best effort: the import is already fully consumed.
                    }
                }
            }
        } catch (failure: Exception) {
            noteGlFailure("GPU GL failed", failure)
        } finally {
            vfWorkOutstanding = false
            try {
                frame.close()
            } catch (_: Exception) {
                // Best effort: the gralloc reference is already accounted.
            }
        }
    }

    /**
     * Drop the cached export EGLImage (stale buffer, bind failure, or GL
     * teardown). Best effort; GL worker only.
     */
    private fun dropExportEglImage() {
        val image = exportEglImage
        exportEglImage = 0L
        exportEglSource = null
        if (image != 0L) {
            try {
                VfEglImport.destroyEGLImage(image)
            } catch (_: Exception) {
                // Best effort: the display may already be gone.
            }
        }
    }

    /**
     * Black-output probation for a Vulkan tier that just dispatched OK. Returns
     * true to present the export, false when the tier latched broken (the caller
     * falls through to the next tier in the same frame, so no black frame is
     * ever shown). Verified tiers skip probing entirely. GL worker only.
     */
    private fun probeVulkanTier(zeroCopy: Boolean): Boolean {
        if (zeroCopy && vulkanZeroCopyVerified) return true
        if (!zeroCopy && vulkanCopyVerified) return true
        val tier = if (zeroCopy) "zero-copy" else "gpu-copy"
        val signal = vfInputSignal ?: return true // input sample not arrived yet: present, keep probing
        // Compute fence already waited; drain queued GL so the export sample
        // cannot race the previous frame's tonemap read. Probation only.
        glFinish()
        val outputMax = try {
            VfVulkan.sampleOutputNative()
        } catch (_: Exception) {
            -1
        }
        if (outputMax < 0) {
            // Export not CPU-lockable: probation can never conclude; assume
            // healthy so this costs one warning instead of a glFinish per frame.
            Log.w("RawViewfinder", "VF $tier output unsampleable; assuming healthy")
            if (zeroCopy) vulkanZeroCopyVerified = true else vulkanCopyVerified = true
            maybeClearInputProbe()
            return true
        }
        return when (probeVfTierOutput(signal.dataMax, signal.black, signal.white, outputMax)) {
            VfTierProbe.VERIFIED -> {
                if (zeroCopy) vulkanZeroCopyVerified = true else vulkanCopyVerified = true
                maybeClearInputProbe()
                Log.i("RawViewfinder", "VF $tier verified (outputMax=$outputMax)")
                true
            }
            // Dark scene or lens cap: present (correct output either way), keep probing.
            VfTierProbe.INCONCLUSIVE -> true
            VfTierProbe.BROKEN -> {
                Log.w("RawViewfinder", "VF $tier black output (inputMax=${signal.dataMax} outputMax=$outputMax); advancing tier")
                latchVulkanSubTier(zeroCopy, "black-output")
                false
            }
        }
    }

    /** Latch one Vulkan sub-tier dead for the session. Never schedules recovery. */
    private fun latchVulkanSubTier(zeroCopy: Boolean, reason: String) {
        val tier = if (zeroCopy) "zero-copy" else "gpu-copy"
        if (zeroCopy) vulkanZeroCopyDisabled = true else vulkanCopyDisabled = true
        consecutiveVulkanFailures = 0
        maybeClearInputProbe()
        if (vulkanZeroCopyDisabled && vulkanCopyDisabled) {
            if (!vulkanDisabledForSession) {
                vulkanDisabledForSession = true
                Log.w("RawViewfinder", "VF Vulkan failed ($reason); EGL/NEON cover for session")
            }
        } else {
            Log.w("RawViewfinder", "VF Vulkan $tier failed ($reason); trying next tier")
        }
    }

    private fun maybeClearInputProbe() {
        // The copy tier only needs a signal once it actually dispatches; a
        // healthy zero-copy session stops probing after one verified frame.
        val zeroResolved = vulkanZeroCopyVerified || vulkanZeroCopyDisabled
        val copyResolved = vulkanCopyVerified || vulkanCopyDisabled || !vulkanCopyActive
        if (zeroResolved && copyResolved) vfProbeInputSignal = false
    }

    /** Vulkan tier: lazy init + export buffer + superpixel dispatch. GL worker only. */
    private fun runVulkanSuperpixel(frame: GpuFrame, copy: Boolean): Int {
        if (!vulkanInitialized) {
            val spv = loadVulkanSpv() ?: return VfVulkan.PIPELINE_FAILED
            val code = try {
                VfVulkan.setPipelineCachePathNative(
                    VulkanPipelineCache.pathFor(context.cacheDir, VulkanPipelineCache.HOST_VF))
                VfVulkan.initNative(spv)
            } catch (_: Exception) {
                VfVulkan.DEVICE_FAILED
            }
            if (code != VfVulkan.OK) return code
            vulkanInitialized = true
            Log.i("RawViewfinder", "VF Vulkan ready")
        }
        if (vulkanExport == null || vulkanExportWidth != frame.width || vulkanExportHeight != frame.height) {
            // The cached EGLImage wraps the old buffer: drop it before the
            // buffer swaps, so the next draw imports the new one.
            dropExportEglImage()
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
                        android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                        // Tier black-output probe locks the export on probation frames only.
                        android.hardware.HardwareBuffer.USAGE_CPU_READ_RARELY
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
            if (exportEglSource === export) dropExportEglImage()
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
            if (copy) VfVulkan.computeCopyNative(frame.buffer, iparams, fparams)
            else VfVulkan.computeNative(frame.buffer, iparams, fparams)
        } catch (_: Exception) {
            VfVulkan.SUBMIT_FAILED
        }
    }

    /** Bounded-wait backpressure: count + occasional line, never a failure. GL worker only. */
    private var vulkanBusySkips = 0L

    private fun noteVulkanBusy() {
        vulkanBusySkips++
        if (vulkanBusySkips % 60L == 1L) {
            Log.i("RawViewfinder", "VF Vulkan busy-skipped $vulkanBusySkips frames (gpu slow)")
        }
    }

    /** Vulkan sub-tier failure: warn, and skip the sub-tier after repeated failures. */
    private fun noteVulkanFailure(reason: String, zeroCopy: Boolean) {
        consecutiveVulkanFailures++
        if (VfVulkan.shouldDisableImmediately(reason) ||
            consecutiveVulkanFailures >= VfGpuImport.MAX_CONSECUTIVE_FAILURES
        ) {
            latchVulkanSubTier(zeroCopy, reason)
            if (VfVulkan.shouldRecover(reason)) scheduleVulkanRecovery()
        } else {
            Log.w("RawViewfinder", "VF Vulkan frame failed ($reason); trying next tier")
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

    /** Rate-limited device recreation; success unlatches the tiers. GL worker only. */
    private fun recoverVulkan() {
        vulkanRecoverPosted = false
        val currentEpoch = synchronized(lock) { epoch }
        if (vulkanRecoverEpoch != currentEpoch ||
            (!vulkanDisabledForSession && !vulkanZeroCopyDisabled && !vulkanCopyDisabled)
        ) return
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
        vulkanZeroCopyDisabled = false
        vulkanCopyDisabled = false
        vulkanZeroCopyVerified = false
        vulkanCopyVerified = false
        vulkanCopyActive = false
        vfProbeInputSignal = true
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
        try {
            val surfWidth = IntArray(1); val surfHeight = IntArray(1)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, surfWidth, 0)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, surfHeight, 0)
            if (surfWidth[0] != viewportWidth || surfHeight[0] != viewportHeight) {
                Log.w("RawViewfinder", "VF EGL size ${surfWidth[0]}x${surfHeight[0]} != viewport ${viewportWidth}x$viewportHeight; syncing viewport")
                viewportWidth = surfWidth[0].coerceAtLeast(1)
                viewportHeight = surfHeight[0].coerceAtLeast(1)
            } else {
                Log.i("RawViewfinder", "VF EGL surface ${surfWidth[0]}x${surfHeight[0]} matches viewport")
            }
        } catch (_: Exception) {
        }
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
        // Cache once per link: the tonemap runs highp where the fragment shader
        // supports it so the JPEG branch matches save-path AgX bit-shape.
        gainsLoc = glGetUniformLocation(program, "gains")
        colorLoc = glGetUniformLocation(program, "color")
        camToAcesLoc = glGetUniformLocation(program, "u_camToAces")
        cameraWhiteLoc = glGetUniformLocation(program, "u_cameraWhite")
        displayP3Loc = glGetUniformLocation(program, "u_displayP3")
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
        lensGreenRowLoc = glGetUniformLocation(program, "u_lensGreenRow")
        quadBaseLoc = glGetUniformLocation(program, "u_quadBase")
        frameSizeLoc = glGetUniformLocation(program, "u_frameSize")
        stepLoc = glGetUniformLocation(program, "u_step")
        check(gainsLoc >= 0 && colorLoc >= 0 && camToAcesLoc >= 0 && cameraWhiteLoc >= 0 &&
            displayP3Loc >= 0 && positionLoc >= 0 && uvLoc >= 0 &&
            jpegLoc >= 0 && agxContrastLoc >= 0 && agxSaturationLoc >= 0 && agxPurityLoc >= 0 &&
            agxHueLoc >= 0 && agxShadowEvLoc >= 0 && agxHighlightEvLoc >= 0 && agxGamutLoc >= 0 &&
            highlightShoulderLoc >= 0 &&
            exposureEvLoc >= 0 &&
            lensLoc >= 0 && lensSizeLoc >= 0 && lensActiveLoc >= 0 && applyLensLoc >= 0 &&
            lensGreenRowLoc >= 0 &&
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
            gpuCamToAcesLoc = glGetUniformLocation(id, "u_camToAces")
            gpuCameraWhiteLoc = glGetUniformLocation(id, "u_cameraWhite")
            gpuDisplayP3Loc = glGetUniformLocation(id, "u_displayP3")
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
            gpuBlackQLoc = glGetUniformLocation(id, "u_blackQ")
            gpuDenQLoc = glGetUniformLocation(id, "u_denQ")
            gpuLensLoc = glGetUniformLocation(id, "u_lens")
            gpuLensSizeLoc = glGetUniformLocation(id, "u_lensSize")
            gpuLensActiveLoc = glGetUniformLocation(id, "u_lensActive")
            gpuApplyLensLoc = glGetUniformLocation(id, "u_applyLens")
            gpuLensGreenRowLoc = glGetUniformLocation(id, "u_lensGreenRow")
            check(gpuGainsLoc >= 0 && gpuColorLoc >= 0 && gpuCamToAcesLoc >= 0 && gpuCameraWhiteLoc >= 0 &&
                gpuDisplayP3Loc >= 0 && gpuPositionLoc >= 0 && gpuUvLoc >= 0 &&
                gpuJpegLoc >= 0 && gpuAgxContrastLoc >= 0 && gpuAgxSaturationLoc >= 0 && gpuAgxPurityLoc >= 0 &&
                gpuAgxHueLoc >= 0 && gpuAgxShadowEvLoc >= 0 && gpuAgxHighlightEvLoc >= 0 && gpuAgxGamutLoc >= 0 &&
                gpuHighlightShoulderLoc >= 0 &&
                gpuExposureEvLoc >= 0 &&
                gpuBayerLoc >= 0 && gpuQuadBaseLoc >= 0 && gpuFrameSizeLoc >= 0 && gpuStepLoc >= 0 &&
                gpuChansLoc >= 0 && gpuBlackQLoc >= 0 && gpuDenQLoc >= 0 &&
                gpuLensLoc >= 0 && gpuLensSizeLoc >= 0 && gpuLensActiveLoc >= 0 && gpuApplyLensLoc >= 0 &&
                gpuLensGreenRowLoc >= 0) { "VF GPU uniforms missing" }
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
        // Drop the cached EGLImage before its buffer and display go away.
        dropExportEglImage()
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
        gainsLoc = -1; colorLoc = -1; camToAcesLoc = -1; cameraWhiteLoc = -1; displayP3Loc = -1
        positionLoc = -1; uvLoc = -1
        jpegLoc = -1; agxContrastLoc = -1; agxSaturationLoc = -1; agxPurityLoc = -1
        agxHueLoc = -1; agxShadowEvLoc = -1; agxHighlightEvLoc = -1; agxGamutLoc = -1
        highlightShoulderLoc = -1
        exposureEvLoc = -1
        lensLoc = -1; lensSizeLoc = -1; lensActiveLoc = -1; applyLensLoc = -1; lensGreenRowLoc = -1
        quadBaseLoc = -1; frameSizeLoc = -1; stepLoc = -1
        lensTexture = 0; lensTexCols = 0; lensTexRows = 0
        lensUpTexture = 0; lensUpCols = 0; lensUpRows = 0; lensUpHash = 0L
        texWidth = 0; texHeight = 0
        gpuProgram = 0; gpuBayerTexture = 0; vkTexture = 0
        gpuGainsLoc = -1; gpuColorLoc = -1; gpuCamToAcesLoc = -1; gpuCameraWhiteLoc = -1
        gpuDisplayP3Loc = -1; gpuPositionLoc = -1; gpuUvLoc = -1
        gpuJpegLoc = -1; gpuAgxContrastLoc = -1; gpuAgxSaturationLoc = -1; gpuAgxPurityLoc = -1
        gpuAgxHueLoc = -1; gpuAgxShadowEvLoc = -1; gpuAgxHighlightEvLoc = -1; gpuAgxGamutLoc = -1
        gpuHighlightShoulderLoc = -1
        gpuExposureEvLoc = -1
        gpuBayerLoc = -1; gpuQuadBaseLoc = -1; gpuFrameSizeLoc = -1; gpuStepLoc = -1
        gpuChansLoc = -1; gpuBlackQLoc = -1; gpuDenQLoc = -1
        gpuLensLoc = -1; gpuLensSizeLoc = -1; gpuLensActiveLoc = -1; gpuApplyLensLoc = -1; gpuLensGreenRowLoc = -1
    }

    companion object {
        // WYSIWYG fragment shaders (CPU ESSL 1.00 + GPU ESSL 3.00) are composed from
        // shared pieces in VfGpuImport so both paths run the identical tonemap tail:
        // u_jpeg == 0 is the raw-clean Reinhard preview (+1 EV) with hardware
        // lens-shading, otherwise the calibrated save-path AgX preview tracking
        // JpegOutputSettings. No AMaZE detail, denoise or grain: those stay save-only.
        /** Preview-EV refresh cadence: the percentile statistic is stable at 2 Hz. */
        private const val PREVIEW_EV_INTERVAL_MS = 500L
        /** Save-path adaptive correction bound (AdaptiveDevelopmentExposure). */
        private const val MAX_PREVIEW_EV = 1.5f
        /** NEON sampler average cadence: one line per 5 s of fallback frames. */
        private const val NEON_LOG_INTERVAL_MS = 5000L
        /** VF stats telemetry cadence: one fps/mode line per 10 s of preview. */
        private const val VF_STATS_LOG_INTERVAL_MS = 10000L
        /** Record-mode offer floor lives in [VfResolution] power policy (10 fps). */
        @Deprecated("Use VfResolution.RATE_RECORD_MS", ReplaceWith("VfResolution.RATE_RECORD_MS"))
        const val RECORD_SAMPLE_INTERVAL_MS = 100L

        /**
         * WB gains multiply the signal: a non-finite or negative HAL value
         * would poison the frame (NaN renders black on Adreno). Unity fallback.
         */
        internal fun sanitizeWbGain(value: Float): Float =
            if (value.isFinite() && value >= 0f) value else 1f

        /** CCM elements may be negative (off-diagonals); only non-finite is rejected. */
        internal fun sanitizeCcmElement(value: Float, identity: Float): Float =
            if (value.isFinite()) value else identity

        /** Black-output probe verdict for a Vulkan tier. */
        internal enum class VfTierProbe { VERIFIED, INCONCLUSIVE, BROKEN }

        /**
         * Input-signal reading for tier black-output probation from a raw-code
         * sample triple (min, max, mean). The probe convicts on
         * strong input + black output, so the signal carries the MAXIMUM
         * sample: feeding the minimum (which sits at black level in every
         * scene, dark or bright) wedges every verdict at INCONCLUSIVE, and
         * a black tier then presents forever while the next tier in the
         * chain never engages. Null sample (sampler threw) yields null and
         * the caller keeps the previous signal.
         */
        internal fun inputSignalFromSample(
            sample: Triple<Int, Int, Long>?, levels: FloatArray, white: Float
        ): VfInputSignal? {
            sample ?: return null
            var blackMax = 0f
            for (b in levels) if (b > blackMax) blackMax = b
            return VfInputSignal(sample.second, blackMax.toInt(), white.toInt())
        }

        /**
         * Tri-state tier probe. Strong input + black output = BROKEN (latch the
         * tier and advance in the same frame). Weak input = INCONCLUSIVE (dark
         * scene or lens cap: present, keep probing — never latch, never verify).
         * Strong input + lit output = VERIFIED (probe no more). The margins are
         * wide on purpose: a working tier turns 12.5% input signal into far
         * more than 2/255 linear output, and a broken import reads exact zeros.
         */
        internal fun probeVfTierOutput(inputMax: Int, black: Int, white: Int, outputMax: Int): VfTierProbe {
            val range = white - black
            if (range <= 0 || inputMax - black <= range / 8) return VfTierProbe.INCONCLUSIVE
            return if (outputMax <= 2) VfTierProbe.BROKEN else VfTierProbe.VERIFIED
        }
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
            val newWidth = width.coerceAtLeast(1)
            val newHeight = height.coerceAtLeast(1)
            if (newWidth != viewportWidth || newHeight != viewportHeight) {
                Log.i("RawViewfinder", "VF surface changed ${viewportWidth}x$viewportHeight -> ${newWidth}x$newHeight")
                viewportWidth = newWidth
                viewportHeight = newHeight
                // EGL window surfaces do not reliably track SurfaceView size changes on
                // all drivers (stale 1080x2400 buffers after layout to 1080x1613 displace
                // the image with a viewport mismatch). Recreate so the next draw binds
                // the new size instead of rendering into a stale surface.
                if (surface != EGL14.EGL_NO_SURFACE) {
                    releaseGl()
                }
            }
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
