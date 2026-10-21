// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RawDevelopmentSettings(
    val preDemosaic: PreDemosaicSettings = PreDemosaicSettings(),
    val exposureEv: Double = 0.0,
    val denoise: DenoiseSettings = DenoiseSettings(),
    val adaptiveExposureStrength: Float = 0f,
    val sharedAdaptiveExposure: SharedAdaptiveExposure? = null,
    val adaptiveExposureTuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
    /**
     * Non-null enables HDR-merge adaptive exposure in [RawDevelopmentCoordinator.developMergedJpeg]:
     * the merge's display compensation ([exposureEv]) is folded into the analysis and the
     * HDR-tuned correction is added on top, so high-DR interiors lift to natural brightness
     * while spikes ride AgX's range. HDR bracket path only; null (default) keeps the
     * single-frame LDR adaptive path below, which uniform HDR+/fusion merges use: their
     * same-exposure average lives in the identical normalized domain as a single frame
     * (nothing above white to roll off), so [adaptiveExposureStrength] scales the
     * [AdaptiveDevelopmentExposure.analyze] correction on the merged CFA itself.
     */
    val hdrAdaptiveTuning: HdrAdaptiveTuning? = null,
    /**
     * Capture-time ETTR headroom ([EttrSettings.headroomEv]), or null when the frame was
     * not ETTR-exposed. ETTR frames are already optimally bright: verified-bright frames
     * keep zero positive lift (a bright haze gradient needs ~0 EV to stay blue), darker
     * frames fall back to the residual-to-target cap. Single-shot path only (HDR brackets
     * never use ETTR).
     */
    val ettrHeadroomEv: Float? = null
)

data class SceneLinearGpuFrame(
    val texture: AmazeGpuOutput,
    val preDemosaic: PreDemosaicResult,
    val colorTransform: ResolvedSceneLinearTransform
)

/**
 * Resolves the adaptive correction for a merged CFA ([RawDevelopmentCoordinator.developMergedJpeg]).
 * Pure JVM (no GL): an explicit [RawDevelopmentSettings.hdrAdaptiveTuning] (HDR brackets)
 * folds the display compensation into [AdaptiveDevelopmentExposure.analyzeHdr]. Otherwise
 * the choice follows measured content, not the call site: a merge carrying above-white
 * signal (multi-exposure highlights, or lens-boosted bright corners an LDR white-pin
 * would crush) meters through the HDR path with the user's shoulder, while content at
 * or below white meters through the single-frame [AdaptiveDevelopmentExposure.analyze]
 * on the merged CFA itself. Returns null when no adaptive path is armed, preserving
 * the legacy fixed-exposure develop.
 */
internal fun resolveMergedAdaptive(
    cfa: UnpackedRawCfa,
    settings: RawDevelopmentSettings,
    workspace: AdaptiveDevelopmentExposure.Workspace = AdaptiveDevelopmentExposure.workspace()
): AdaptiveExposureResult? {
    settings.hdrAdaptiveTuning?.let {
        return AdaptiveDevelopmentExposure.analyzeHdr(
            cfa, Math.pow(2.0, settings.exposureEv), workspace, it
        )
    }
    if (settings.adaptiveExposureStrength > 0f) {
        return (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
            if ((cfa.values.maxOrNull() ?: 0f) > ABOVE_WHITE_CONTENT) {
                val ldrTuning = settings.adaptiveExposureTuning.bounded()
                AdaptiveDevelopmentExposure.analyzeHdr(
                    cfa, Math.pow(2.0, settings.exposureEv), workspace,
                    HdrAdaptiveTuning(
                        highlightShoulder = ldrTuning.highlightShoulder,
                        centerWeight = ldrTuning.centerWeight,
                        focusX = ldrTuning.focusX,
                        focusY = ldrTuning.focusY
                    )
                )
            } else {
                AdaptiveDevelopmentExposure.analyze(
                    cfa, workspace, settings.adaptiveExposureTuning, settings.ettrHeadroomEv
                )
            }
        }
    }
    return null
}

/**
 * Above-white signal threshold for merged-content routing: sensor saturation
 * is 1.0, so anything above it is multi-exposure highlight data or
 * lens-boosted bright content the LDR white-pin would crush.
 */
internal const val ABOVE_WHITE_CONTENT = 1.0f

data class RawDevelopmentMemoryEstimate(
    val rawImageBytes: Long,
    val unpackedCfaBytes: Long,
    /** Worst live preprocessing set: unpacked/source plus lens-shading-corrected CFA. */
    val preDemosaicCfaCopiesBytes: Long,
    val amazeGpuBytes: Long,
    /** Direct FloatBuffer required by glTexSubImage2D; it can coexist with the CFA array. */
    val amazeUploadStagingBytes: Long,
    /** Zero: camera-to-ACEScg is fused into AMaZE's final RGBA16F write. */
    val additionalSceneLinearGpuBytes: Long,
    val encodedOutputGpuBytes: Long,
    val readbackBytes: Long,
    val bitmapBytes: Long,
    /** Optional Android 14 Ultra HDR gain texture, readback and quarter-resolution Bitmap. */
    val ultraHdrGainmapBytes: Long,
    /** Bitmap.compress streams JPEG bytes; this excludes encoder-internal/native overhead. */
    val encodedJpegHeapBytes: Long,
    val minimumAccountedBytes: Long,
    /** Conservative allowance for GL driver, EGL, Bitmap/JPEG encoder, JVM, and allocator state. */
    val jvmNativeOverheadReserveBytes: Long,
    val conservativePeakBytes: Long
)

/**
 * Production coordinator for RAW validation through JPEG-ready sRGB pixels. Sharpening and denoising
 * are deliberately omitted. The intermediate scene-linear callback remains unclamped ACEScg.
 */
class RawDevelopmentCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val amaze = Gles31AmazeProcessor(context)
    private val jpegOutput = Gles31JpegOutputProcessor(context)
    private val jxlOutput = Gles31JxlOutputProcessor(context)
    // SR merge path: separate instances for the retained develop EGL context.
    // GL program and texture names are context-local, so the AMaZE-context
    // instances above must never touch merged-frame work even on the same
    // thread. The merge itself runs on Vulkan (no EGL); the bridge below
    // downloads its images and re-uploads them here for the GLES develop.
    private val mergedDevelop = Gles31MergedDevelopProcessor(context)
    private val mergedJpegOutput = Gles31JpegOutputProcessor(context)
    private var mergedEgl: Gles31AmazeProcessor.EglComputeContext? = null
    private val adaptiveWorkspace = AdaptiveDevelopmentExposure.workspace()
    private val aiDenoiser by lazy { RawNindDenoiser(appContext) }
    private val waveletDenoiser by lazy { WaveletDenoiser(appContext) }

    fun probe(width: Int, height: Int): AmazeCapability = amaze.probe(width, height)

    /** Enable-time Vulkan preload for the post-demosaic wavelet stage. */
    fun prewarmWavelet() = waveletDenoiser.ensureInit()

    // Highlight-reconstruction Vulkan device: opened lazily on the first
    // large-ROI photo, never closed (process lifetime, like the wavelet
    // handle). Each photo builds its own GpuContext borrowing it, so Vk
    // sessions stay confined to their develop worker thread; programs,
    // images, and staging release per photo. Any failure permanently
    // disables the Vulkan arm — the CPU twin stays.
    private val hlVkLock = Any()
    @Volatile private var hlVkHandle: VulkanHub.Handle? = null
    @Volatile private var hlVkDead = false

    // Highlight-stage CPU pool: convert/pack row bands and pre-stage tiles
    // split over four daemon workers. Bands/tiles are disjoint and reduces
    // order-fixed, so parallel runs are bit-identical to sequential —
    // the parity gates cannot tell. Process lifetime, like the handles.
    private val hlWorkers: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
            Thread(r, "hl-worker").apply { isDaemon = true }
        }

    /** Per-photo HL Vulkan session, or null when Vulkan is unavailable. */
    internal fun hlGpuContext(): VulkanHub.GpuContext? {
        if (hlVkDead) return null
        val handle = hlVkHandle ?: synchronized(hlVkLock) {
            hlVkHandle ?: try {
                VulkanHub.open().also { hlVkHandle = it }
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "HL Vulkan unavailable, CPU only", failure)
                hlVkDead = true
                return null
            }
        }
        return try {
            VulkanHub.GpuContext(handle, appContext.assets)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "HL Vulkan session failed, CPU only", failure)
            null
        }
    }

    fun <T> develop(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        fusedOutputSettings: JpegOutputSettings? = null,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        MemoryLeakDiagnostics.stillCapture()
        val startedAt = SystemClock.elapsedRealtime()
        if (metadata.quadBayer &&
            metadata.rawDevelopmentUnsupportedReason == RawBayerLayout.QUAD_REQUIRES_DEMOSAIC) {
            return developQuadBayer(rawPlane, metadata, settings, consume)
        }
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
            ?: throw UnsupportedOperationException("RAW buffer geometry is not proven")
        val normalization = metadata.normalizationOrNull()
            ?: throw UnsupportedOperationException("RAW black/white/CFA normalization metadata is invalid")
        val rowStride = metadata.rawPlaneRowStride
            ?: throw UnsupportedOperationException("RAW row stride is missing")
        val pixelStride = metadata.rawPlanePixelStride
            ?: throw UnsupportedOperationException("RAW pixel stride is missing")
        // Packed planes carry stride 0, which RawPlaneLayout rightly refuses:
        // only unpacked frames get a layout. Packed frames unpack through
        // the bridge below, and their adaptive metering analyzes the
        // resulting CFA instead of sampling the packed plane.
        val packedFormat = PackedRawFormat.ofInt(metadata.rawImageFormat)
        val layout = if (packedFormat == null) RawPlaneLayout(
            metadata.imageWidth,
            metadata.imageHeight,
            rowStride,
            pixelStride,
            geometry.sensorOriginX,
            geometry.sensorOriginY
        ) else null
        // Sparse metadata defects and the optional MAD detector still require the reference CPU
        // implementation. Normal captures take the zero-copy-to-JVM direct GPU path.
        // NOTE: AI Bayer denoise is applied by the caller (denoiseCaptureCfa /
        // denoiseCfa, then developCfaJpeg) so one capture pays for exactly one
        // inference shared by the denoised DNG and the JPEG. develop() itself
        // stays AI-free on purpose: an internal hook here would re-run
        // inference on CFAs the controller already denoised.
        val directGpu = pixelStride == Short.SIZE_BYTES && rowStride % Short.SIZE_BYTES == 0 &&
            metadata.hotPixels.isEmpty() && !settings.preDemosaic.defectCorrection.autoDetect
        val lensModel = RawPreDemosaicPipeline.lensShadingModel(metadata)
        val preparedAt: Long
        val preDemosaic: PreDemosaicResult
        val cpuCfa: UnpackedRawCfa?
        if (directGpu) {
            cpuCfa = null
            preDemosaic = PreDemosaicResult(
                null,
                RawPreDemosaicPipeline.lensShadingStatus(metadata),
                DefectCorrectionStats(0, 0)
            )
            preparedAt = SystemClock.elapsedRealtime()
        } else {
            val unpacked = if (packedFormat == null) {
                RawSensorUnpacker.unpackNormalized(
                    rawPlane, requireNotNull(layout),
                    normalization, geometry.processingCrop, ByteOrder.nativeOrder()
                )
            } else {
                RawPackedUnpacker.unpackFrameNormalized(
                    rawPlane, metadata.imageWidth, metadata.imageHeight,
                    rowStride, pixelStride, metadata.rawImageFormat, normalization,
                    geometry.sensorOriginX, geometry.sensorOriginY,
                    geometry.processingCrop, ByteOrder.nativeOrder()
                )
            }
            preDemosaic = RawPreDemosaicPipeline.process(unpacked, metadata, settings.preDemosaic)
            cpuCfa = requireNotNull(preDemosaic.cfa)
            preparedAt = SystemClock.elapsedRealtime()
        }
        // Direct RAW sampling avoids materializing a 50 MB FloatArray just for exposure metering.
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                if (cpuCfa != null) AdaptiveDevelopmentExposure.analyze(
                    cpuCfa, adaptiveWorkspace, settings.adaptiveExposureTuning,
                    settings.ettrHeadroomEv
                )
                else AdaptiveDevelopmentExposure.analyzeRaw(
                    // Packed frames never reach here: their branch above
                    // always produces a CFA, so analyze() wins instead.
                    rawPlane, requireNotNull(layout),
                    normalization, geometry.processingCrop, lensModel,
                    adaptiveWorkspace, settings.adaptiveExposureTuning, settings.ettrHeadroomEv
                )
            }
        } else null
        val adaptiveAt = SystemClock.elapsedRealtime()
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata),
            resolvedExposureEv
        )
        val transformAt = SystemClock.elapsedRealtime()
        // Post-demosaic wavelet (resolver parity): single-frame only,
        // never Quad-Bayer (no camera-linear tap there yet), never fused
        // (the wavelet stage needs the unfused RGBA16F frame).
        val waveletPlan = resolveDenoise(settings.denoise, mergedPath = false).wavelet &&
            !metadata.quadBayer
        // Highlight reconstruction runs on the same camera-linear tap but is
        // independent of wavelet: the toggle alone requests the tap, runs the
        // RT stage on the readback, and re-uploads. Never fused (like wavelet,
        // it needs the unfused RGBA16F frame); off restores the exact old path.
        val hlPlan = (fusedOutputSettings?.hlReconstruction ?: false) && !metadata.quadBayer
        val cameraLinearPlan = waveletPlan || hlPlan
        val effectiveFused = if (cameraLinearPlan) null else fusedOutputSettings
        val consumeOutput: (AmazeGpuOutput) -> T = { output ->
            Log.i(LOG_TAG, "Demosaic done: ${SystemClock.elapsedRealtime() - transformAt}ms")
            if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                output.internalFormat == AmazeTextureFormat.RGBA16F
            ) {
                if (waveletPlan) {
                    consumeWaveletDenoised(
                        output, metadata, normalization, transform, preDemosaic,
                        settings.denoise.wavelet,
                        fusedOutputSettings?.hlReconstruction ?: false,
                        { hlGpuContext() },
                        consume
                    )
                } else {
                    consumeHlReconstructed(
                        output, metadata, normalization, transform, preDemosaic,
                        fusedOutputSettings?.hlReconstruction ?: false,
                        { hlGpuContext() },
                        consume
                    )
                }
            } else {
                consume(SceneLinearGpuFrame(output, preDemosaic, transform))
            }
        }
        // The HL tap must see true channel values: sensor-white
        // desaturation would force unclipped brights toward neutral white
        // and manufacture phantom clipping (RT runs recovery pre-desat).
        // Wavelet-alone keeps the LDR window (denoise input unchanged).
        val tapWhiteWindow = if (hlPlan) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        fun runAmaze(): T {
            return if (directGpu) {
                // directGpu needs pixelStride 2, which packed planes never
                // have, so layout is non-null on exactly this branch.
                amaze.processRaw(
                    GpuRawAmazeInput(
                        rawPlane, requireNotNull(layout), geometry.processingCrop, normalization, lensModel
                    ),
                    cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                    cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                    fusedOutputSettings = effectiveFused,
                    whiteWindow = tapWhiteWindow,
                    cameraLinear = cameraLinearPlan,
                    consume = consumeOutput
                )
            } else {
                amaze.process(
                    requireNotNull(cpuCfa),
                    cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                    cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                    fusedOutputSettings = effectiveFused,
                    whiteWindow = tapWhiteWindow,
                    cameraLinear = cameraLinearPlan,
                    consume = consumeOutput
                )
            }
        }
        // Still-JPEG demosaic backend (Quad-Bayer always stays AMaZE).
        // RCD failures fall back to AMaZE: the JPEG must never fail for
        // a demosaic choice.
        var demosaicUsed = "AMAZE"
        val result = if (fusedOutputSettings?.demosaic == JpegDemosaic.RCD) {
            try {
                demosaicUsed = "RCD"
                if (directGpu) {
                    amaze.processRcdRaw(
                        GpuRawAmazeInput(
                            rawPlane, requireNotNull(layout), geometry.processingCrop, normalization, lensModel
                        ),
                        cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                        cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                        cameraLinear = cameraLinearPlan,
                        consume = consumeOutput
                    )
                } else {
                    amaze.processRcdCfa(
                        requireNotNull(cpuCfa),
                        cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                        cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                        cameraLinear = cameraLinearPlan,
                        consume = consumeOutput
                    )
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "RCD JPEG failed, falling back to AMaZE", e)
                demosaicUsed = "AMAZE(fallback)"
                runAmaze()
            }
        } else {
            runAmaze()
        }
        val completedAt = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "RAW development ${metadata.imageWidth}x${metadata.imageHeight}: " +
                "preprocessCpu=${preparedAt - startedAt}ms " +
                "path=${if (directGpu) "GPU_RAW" else "CPU_FALLBACK"} " +
                "demosaic=$demosaicUsed " +
                "adaptive=${adaptiveAt - preparedAt}ms " +
                "color=${transformAt - adaptiveAt}ms " +
                "adaptiveEV=${adaptive?.correctionEv ?: 0.0} appliedEV=$resolvedExposureEv " +
                "GPU+output=${completedAt - transformAt}ms total=${completedAt - startedAt}ms"
        )
        return result
    }

    /**
     * Post-demosaic wavelet detour: reads the camera-linear frame back,
     * runs the profiled wavelet (Vulkan), converts to ACEScg, and hands a
     * standard scene-linear frame to [consume]. Any wavelet failure falls
     * back to the un-denoised readback (no re-demosaic); the JPEG never
     * fails for denoise reasons. JPEG only — the DNG path is untouched.
     */
    private fun <T> consumeWaveletDenoised(
        output: AmazeGpuOutput,
        metadata: RawFrameMetadata,
        normalization: RawNormalization,
        transform: ResolvedSceneLinearTransform,
        preDemosaic: PreDemosaicResult,
        settings: WaveletSettings,
        hlReconstruct: Boolean = true,
        gpuProvider: (() -> VulkanHub.GpuContext?)? = null,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val t0 = SystemClock.elapsedRealtime()
        val camera = try {
            amaze.readbackHalfFloat(output)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Wavelet readback failed, developing without denoise", failure)
            return consumeFallbackCameraLinear(output, transform, preDemosaic, consume)
        }
        val t1 = SystemClock.elapsedRealtime()
        val blackAvg = normalization.blackLevels.average()
        val (noiseA, noiseB) = WaveletDenoiser.noiseAB(
            metadata.noiseProfile?.toDoubleArray(),
            normalization.whiteLevel.toDouble() - blackAvg
        )
        val wb = FloatArray(3) { transform.whiteBalance[it].toFloat() }.let { gains ->
            val g = gains[1].takeIf { it.isFinite() && it > 0f } ?: 1f
            floatArrayOf(gains[0] / g, 1f, gains[2] / g)
        }
        // RT "Color propagation" on the demosaiced camera-linear frame
        // (pre-WB): repairs clipped-channel color before denoise/WB. Any
        // highlight failure returns the readback untouched, so the capture
        // never fails for reconstruction reasons. Per-stage ms go to logcat
        // under "HlRecon" for the Vulkan optimization pass.
        val reconstructed = HlReconstructionStage.applyIfEnabled(
            camera, output.width, output.height, hlReconstruct,
            hlStageInputs(metadata, normalization), gpuProvider, hlWorkers
        )
        val t2 = SystemClock.elapsedRealtime()
        val denoised = ByteBuffer.allocateDirect(reconstructed.remaining()).order(ByteOrder.nativeOrder())
        val ok = try {
            waveletDenoiser.process(
                reconstructed, denoised, output.width, output.height,
                settings.bounded(), noiseA, noiseB, wb
            )
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Wavelet denoise failed, developing without denoise", failure)
            false
        }
        val upload = if (ok) denoised else reconstructed
        val t3 = SystemClock.elapsedRealtime()
        var t4 = 0L
        val result = amaze.transformCameraLinear(
            upload, output.width, output.height,
            transform.glslColumnMajorMatrix()
        ) { aces ->
            t4 = SystemClock.elapsedRealtime()
            consume(SceneLinearGpuFrame(aces, preDemosaic, transform))
        }
        val t5 = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "Wavelet tap stages: readback=${t1 - t0}ms hl=${t2 - t1}ms wavelet=${t3 - t2}ms " +
                "upload+toAces=${t4 - t3}ms output=${t5 - t4}ms"
        )
        return result
    }

    /** Levels for the highlight stage from capture metadata (sRGB luminance
     * row when the device ships no color matrix). */
    private fun hlStageInputs(
        metadata: RawFrameMetadata,
        normalization: RawNormalization
    ) = HlReconstructionStage.Inputs(
        whiteLevel = normalization.whiteLevel,
        blackLevels = normalization.blackLevels.toFloatArray(),
        xyzRow = metadata.colorMatrix1?.takeIf { it.size >= 9 }?.let {
            floatArrayOf(it[3].toFloat(), it[4].toFloat(), it[5].toFloat())
        } ?: floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    )

    /**
     * Highlight reconstruction without wavelet: same camera-linear tap and
     * re-upload, no denoise. Lets the HL toggle work standalone — wavelet
     * stays off unless the user enables it.
     */
    private fun <T> consumeHlReconstructed(
        output: AmazeGpuOutput,
        metadata: RawFrameMetadata,
        normalization: RawNormalization,
        transform: ResolvedSceneLinearTransform,
        preDemosaic: PreDemosaicResult,
        hlReconstruct: Boolean,
        gpuProvider: (() -> VulkanHub.GpuContext?)? = null,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val t0 = SystemClock.elapsedRealtime()
        val camera = try {
            amaze.readbackHalfFloat(output)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "HL readback failed, developing without reconstruction", failure)
            return consumeFallbackCameraLinear(output, transform, preDemosaic, consume)
        }
        val reconstructed = HlReconstructionStage.applyIfEnabled(
            camera, output.width, output.height, hlReconstruct,
            hlStageInputs(metadata, normalization), gpuProvider, hlWorkers
        )
        val t2 = SystemClock.elapsedRealtime()
        var t3 = 0L
        val result = amaze.transformCameraLinear(
            reconstructed, output.width, output.height,
            transform.glslColumnMajorMatrix()
        ) { aces ->
            t3 = SystemClock.elapsedRealtime()
            consume(SceneLinearGpuFrame(aces, preDemosaic, transform))
        }
        val t4 = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "HL tap stages: readback+hl=${t2 - t0}ms upload+toAces=${t3 - t2}ms output=${t4 - t3}ms"
        )
        return result
    }

    /**
     * Readback itself failed (no CPU pixels): convert on-GPU straight from
     * the camera-linear texture. The JPEG still develops, un-denoised —
     * the capture never fails for wavelet reasons.
     */
    private fun <T> consumeFallbackCameraLinear(
        output: AmazeGpuOutput,
        transform: ResolvedSceneLinearTransform,
        preDemosaic: PreDemosaicResult,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        return amaze.transformCameraTexture(
            output, transform.glslColumnMajorMatrix()
        ) { aces -> consume(SceneLinearGpuFrame(aces, preDemosaic, transform)) }
    }

    private fun <T> developQuadBayer(
        rawPlane: ByteBuffer, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
            ?: throw UnsupportedOperationException("Quad-Bayer buffer geometry is not proven")
        val normalization = metadata.normalizationOrNull()
            ?: throw UnsupportedOperationException("Quad-Bayer normalization metadata is invalid")
        // Packed quad frames decode to tight 16-bit first; unpacked frames
        // flow through untouched. Either way QuadBayerPreparation keeps its
        // 16-bit layout contract.
        val quadPacked = PackedRawFormat.ofInt(metadata.rawImageFormat)
        val quadRowStride = requireNotNull(metadata.rawPlaneRowStride)
        val quadPlane: ByteBuffer
        val quadLayout: RawPlaneLayout
        if (quadPacked == null) {
            quadPlane = rawPlane
            quadLayout = RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                quadRowStride, requireNotNull(metadata.rawPlanePixelStride),
                geometry.sensorOriginX, geometry.sensorOriginY)
        } else {
            val shorts = RawPackedUnpacker.decodePlaneToShorts(
                rawPlane, metadata.imageWidth, metadata.imageHeight, quadRowStride, 0,
                quadPacked, ByteOrder.nativeOrder()
            )
            val bytes = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(shorts)
            bytes.rewind()
            quadPlane = bytes
            quadLayout = RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                metadata.imageWidth * 2, 2,
                geometry.sensorOriginX, geometry.sensorOriginY)
        }
        val frame = QuadBayerPreparation.unpack(
            quadPlane, quadLayout,
            normalization, geometry.processingCrop, RawPreDemosaicPipeline.lensShadingModel(metadata)
        )
        val defects = RawDefectCorrector.correctInPlace(
            frame.samples, metadata.hotPixels, settings.preDemosaic.defectCorrection, sameColorPeriod = 4
        )
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(
                    frame.samples, adaptiveWorkspace, settings.adaptiveExposureTuning,
                    settings.ettrHeadroomEv
                )
            }
        } else null
        val exposure = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), exposure)
        // Do not expose grouped samples through the regular Bayer callback contract.
        val prepared = PreDemosaicResult(null, RawPreDemosaicPipeline.lensShadingStatus(metadata), defects)
        Log.i(LOG_TAG, "Full-resolution Quad-Bayer development ${frame.samples.width}x${frame.samples.height}")
        return amaze.processQuadBayer(frame,
            cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
            cameraWhiteNormalized = transform.glslCameraWhiteNormalized()) {
            consume(SceneLinearGpuFrame(it, prepared, transform))
        }
    }

    fun developJpeg(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg = developWithTileDropRetry(
        "single-frame ${metadata.imageWidth}x${metadata.imageHeight}"
    ) {
        develop(
            rawPlane,
            metadata,
            settings,
            fusedOutputSettings = outputSettings
        ) { frame ->
            if (frame.texture.internalFormat == AmazeTextureFormat.RGBA8) {
                jpegOutput.processEncoded(frame.texture, outputSettings)
            } else {
                jpegOutput.process(frame.texture, outputSettings)
            }
        }
    }

    /**
     * AI Bayer denoise for an already unpacked, pre-demosaic CFA (the exact
     * training domain: normalized, lens-shading-corrected). Returns the
     * denoised CFA (with the original local pattern) or null when AI is
     * unavailable — inference failure, OOM, or unsupported geometry all fall
     * back silently. Never throws for AI reasons; callers use `?: cfa`.
     */
    fun denoiseCfa(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        strength: Float = 1f
    ): UnpackedRawCfa? {
        return try {
            aiDenoiser.denoise(cfa, CfaNoiseModel.from(metadata.noiseProfile), strength)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI CFA denoise failed, falling back", failure)
            null
        }
    }

    /**
     * Bayer-model denoise+demosaic for an already unpacked, pre-demosaic CFA
     * (same normalized training domain as [denoiseCfa]; no noise profile
     * needed — the model handles arbitrary gain). Returns full-resolution
     * denoised camRGB (bypasses AMaZE) or null when the bayer model is
     * unavailable — inference failure, OOM, or unsupported geometry all fall
     * back silently. Never throws for AI reasons; callers use `?:` fallback.
     */
    fun denoiseCfaBayerRgb(cfa: UnpackedRawCfa, strength: Float = 1f): BayerDenoisedRgb? {
        if (!aiDenoiser.isBayerReady()) return null
        return try {
            aiDenoiser.denoiseBayerRgb(cfa, strength)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI bayer CFA denoise failed, falling back", failure)
            null
        }
    }

    /**
     * Runs unpack + pre-demosaic + AI denoise for a fresh capture plane and
     * returns the denoised CFA for shared use (denoised-DNG write-back and
     * JPEG-from-denoised), so one capture pays for exactly one inference.
     * Returns null when AI is disabled/unavailable; any failure falls back
     * silently and the caller runs the normal development path instead.
     */
    fun denoiseCaptureCfa(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings()
    ): UnpackedRawCfa? {
        if (!settings.denoise.aiEnabled || settings.denoise.aiStrength == 0f) return null
        return try {
            metadata.rawDevelopmentUnsupportedReason?.let { return null }
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: return null
            val normalization = metadata.normalizationOrNull() ?: return null
            val rowStride = metadata.rawPlaneRowStride ?: return null
            val pixelStride = metadata.rawPlanePixelStride ?: return null
            val unpacked = RawPackedUnpacker.unpackFrameNormalized(
                rawPlane, metadata.imageWidth, metadata.imageHeight,
                rowStride, pixelStride, metadata.rawImageFormat, normalization,
                geometry.sensorOriginX, geometry.sensorOriginY,
                geometry.processingCrop, ByteOrder.nativeOrder()
            )
            val prepared = RawPreDemosaicPipeline.process(unpacked, metadata, settings.preDemosaic)
            val cfa = prepared.cfa ?: return null
            denoiseCfa(cfa, metadata, settings.denoise.aiStrength)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI capture denoise failed, falling back", failure)
            null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "AI capture denoise OOM, falling back", oom)
            null
        }
    }

    /**
     * Runs unpack + pre-demosaic + bayer-model denoise+demosaic for a fresh
     * capture plane and returns full-resolution denoised camRGB for the color
     * pipeline (instead of a CFA for AMaZE), so one capture pays for exactly
     * one inference. Returns null when AI is disabled/unavailable; any
     * failure falls back silently.
     */
    fun denoiseCaptureCfaBayerRgb(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings()
    ): BayerDenoisedRgb? {
        if (!settings.denoise.aiEnabled || !aiDenoiser.isBayerReady()) return null
        return try {
            metadata.rawDevelopmentUnsupportedReason?.let { return null }
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: return null
            val normalization = metadata.normalizationOrNull() ?: return null
            val rowStride = metadata.rawPlaneRowStride ?: return null
            val pixelStride = metadata.rawPlanePixelStride ?: return null
            val unpacked = RawPackedUnpacker.unpackFrameNormalized(
                rawPlane, metadata.imageWidth, metadata.imageHeight,
                rowStride, pixelStride, metadata.rawImageFormat, normalization,
                geometry.sensorOriginX, geometry.sensorOriginY,
                geometry.processingCrop, ByteOrder.nativeOrder()
            )
            val prepared = RawPreDemosaicPipeline.process(unpacked, metadata, settings.preDemosaic)
            val cfa = prepared.cfa ?: return null
            denoiseCfaBayerRgb(cfa, settings.denoise.aiStrength)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI bayer capture denoise failed, falling back", failure)
            null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "AI bayer capture denoise OOM, falling back", oom)
            null
        }
    }

    /**
     * Develops a JPEG from an in-memory CFA (AI-denoised capture or HDR
     * merge) honoring the full development settings, including adaptive
     * exposure analyzed on the given CFA.
     */
    fun developCfaJpeg(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(
                    cfa, adaptiveWorkspace, settings.adaptiveExposureTuning,
                    settings.ettrHeadroomEv
                )
            }
        } else null
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        val waveletPlan = resolveDenoise(settings.denoise, mergedPath = false).wavelet &&
            !metadata.quadBayer
        val hlPlan = outputSettings.hlReconstruction && !metadata.quadBayer
        val cameraLinearPlan = waveletPlan || hlPlan
        // Same blend-free tap contract as develop(): the HL stage must see
        // true channel values (see the tapWhiteWindow note there).
        val tapWhiteWindow = if (hlPlan) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developWithTileDropRetry("cfa-frame ${cfa.width}x${cfa.height}") {
            amaze.process(
                cfa,
                clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = if (cameraLinearPlan) null else outputSettings,
                whiteWindow = tapWhiteWindow,
                cameraLinear = cameraLinearPlan
            ) { output ->
                if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                    output.internalFormat == AmazeTextureFormat.RGBA16F
                ) {
                    val normalization = metadata.normalizationOrNull()
                    if (normalization == null) {
                        Log.w(LOG_TAG, "Camera-linear tap skipped: CFA normalization metadata is invalid")
                        amaze.transformCameraTexture(
                            output, transform.glslColumnMajorMatrix()
                        ) { aces -> finishJpeg(aces, outputSettings) }
                    } else if (waveletPlan) {
                        consumeWaveletDenoised(
                            output, metadata, normalization, transform,
                            PreDemosaicResult(
                                null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)
                            ),
                            settings.denoise.wavelet,
                            outputSettings.hlReconstruction,
                            { hlGpuContext() }
                        ) { frame -> finishJpeg(frame.texture, outputSettings) }
                    } else {
                        consumeHlReconstructed(
                            output, metadata, normalization, transform,
                            PreDemosaicResult(
                                null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)
                            ),
                            outputSettings.hlReconstruction,
                            { hlGpuContext() }
                        ) { frame -> finishJpeg(frame.texture, outputSettings) }
                    }
                } else {
                    finishJpeg(output, outputSettings)
                }
            }
        }
    }

    private fun finishJpeg(
        output: AmazeGpuOutput,
        outputSettings: JpegOutputSettings
    ): DevelopedJpeg {
        return if (output.internalFormat == AmazeTextureFormat.RGBA8) {
            jpegOutput.processEncoded(output, outputSettings)
        } else {
            jpegOutput.process(output, outputSettings)
        }
    }

    /** Develops an unclamped, normalized CFA produced by RAW HDR merging. */
    // Future SR hook: route SR merges through this develop path.
    fun developMergedJpeg(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        // Callers AI-denoise the merged CFA first (denoiseCfa) and share it
        // between the merged-DNG write and this develop; no internal hook here
        // so inference never runs twice for one capture.
        // HDR brackets add HDR-tuned adaptive exposure on top of the display
        // compensation: without it the develop restores reference brightness
        // exactly, which meters high-DR scenes dark. Uniform same-exposure
        // merges (HDR+/fusion) meter through the single-frame LDR path on the
        // merged CFA itself when adaptive strength is armed (null tuning plus
        // zero strength keeps the legacy fixed exposure).
        val mergedAdaptive = resolveMergedAdaptive(cfa, settings, adaptiveWorkspace)
        val resolvedExposureEv = settings.exposureEv +
            (mergedAdaptive?.correctionEv ?: 0.0) *
            if (settings.hdrAdaptiveTuning != null) 1.0
            else settings.adaptiveExposureStrength.coerceIn(0f, 1f).toDouble()
        if (mergedAdaptive != null) {
            Log.i(LOG_TAG, "Merged develop displayEV=${"%.2f".format(settings.exposureEv)} " +
                "adaptiveEV=${"%.2f".format(mergedAdaptive.correctionEv)} " +
                "appliedEV=${"%.2f".format(resolvedExposureEv)} " +
                "lowKey=${mergedAdaptive.lowKey}")
        }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        // HDR merges develop through a softer highlight shoulder so rescued
        // short-frame highlights keep gradation instead of flattening on the
        // full-strength asymptote (0.5 recovers the outdoor trees on the
        // IMG_20261008_142321_830 interior while rendering the interior
        // identically; harder user choices are respected via min()).
        // Uniform merges keep the user's shoulder untouched.
        val developOutput = if (settings.hdrAdaptiveTuning != null) {
            outputSettings.copy(
                highlightShoulder = minOf(outputSettings.highlightShoulder, HDR_HIGHLIGHT_SHOULDER)
            )
        } else outputSettings
        // HDR merges keep saturated-highlight color: only the exactly-1.0
        // all-frames-clipped fallback neutralizes (uniform merges keep the
        // LDR window).
        val whiteWindow = if (settings.hdrAdaptiveTuning != null) floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_END
        ) else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developWithTileDropRetry("merged-frame ${cfa.width}x${cfa.height}") {
            amaze.process(
                cfa,
                clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = developOutput,
                whiteWindow = whiteWindow
            ) { output ->
                if (output.internalFormat == AmazeTextureFormat.RGBA8) {
                    jpegOutput.processEncoded(output, developOutput)
                } else {
                    jpegOutput.process(output, developOutput)
                }
            }
        }
    }

    /**
     * Develops an already-demosaiced linear RGB frame (sharp-merge SR
     * output, camera-native linear, top row first) to a display JPEG.
     * Amaze is bypassed — the frame is already RGB — but the color
     * contract is identical to [developMergedJpeg]: the same rcd_final
     * tail (negative clip, sensor-white desaturation, camera-to-ACEScg)
     * feeds the same [jpegOutput] AGX/encode. Exposure is fixed
     * ([RawDevelopmentSettings.exposureEv]; adaptive metering analyzes
     * CFA and has no RGB leg yet), matching the legacy fixed-exposure
     * develop. No tile-drop retry: one full-frame dispatch, not AMaZE
     * tiling (see [developWithTileDropRetry]).
     */
    fun developMfsrJpeg(
        rgb: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), settings.exposureEv
        )
        return amaze.processMfsrRgb(
            rgb, width, height,
            transform.glslColumnMajorMatrix(),
            transform.glslCameraWhiteNormalized()
        ) { output ->
            jpegOutput.process(output, outputSettings)
        }
    }

    /**
     * Develops once, then verifies no AMaZE tile was dropped before the bitmap may be saved.
     * A dropped tile (an exact-zero tile next to lit content, which no real scene can produce)
     * retries the develop a single time on the still-owned inputs; a persistent drop throws
     * instead of saving a corrupt JPEG (the DNG is already written by then, so no capture data
     * is lost). Uniformly black frames are a dark scene, never a drop, and pass through. The SR
     * texture path is intentionally excluded: it develops with one full-frame dispatch rather
     * than AMaZE tiling.
     */
    private fun developWithTileDropRetry(tag: String, once: () -> DevelopedJpeg): DevelopedJpeg {
        val first = once()
        val drops = DevelopTileDropDetector.findZeroTiles(
            first.bitmap.width, first.bitmap.height
        ) { x, y -> first.bitmap.getPixel(x, y) }
        if (drops.isEmpty()) return first
        first.bitmap.recycle()
        Log.w(LOG_TAG, "Dropped GPU tile(s) $drops in $tag; retrying develop once")
        val second = once()
        val redrops = DevelopTileDropDetector.findZeroTiles(
            second.bitmap.width, second.bitmap.height
        ) { x, y -> second.bitmap.getPixel(x, y) }
        if (redrops.isEmpty()) {
            Log.i(LOG_TAG, "Tile-drop retry recovered $tag (first attempt dropped $drops)")
            return second
        }
        second.bitmap.recycle()
        throw IllegalStateException(
            "GPU tile drop persisted after retry in $tag: first=$drops retry=$redrops"
        )
    }

    /**
     * Develops the live merged camera-RGB texture straight to a
     * JPEG-ready Bitmap without a second demosaic or repeated RAW corrections.
     *
     * Must run with a current EGL context holding [input] textures (call
     * [developMergedImagesJpeg] from inside the merge consume block; it owns
     * the develop context). Applies the reference camera-to-working-colour
     * transform.
     * The merged texture already carries reference fallback, and prime DNG
     * pixels are never read here. Orientation is EXIF-carried via [input]
     * reference metadata at save time; output pixels are never rotated.
     */
    fun developMergedTextureJpeg(
        input: MergedTextureJpegInput,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        // Covers developMergedImagesJpeg too (it delegates here).
        MemoryLeakDiagnostics.stillCapture()
        return mergedDevelop.develop(input, settings) { scene ->
            require(scene.internalFormat == AmazeTextureFormat.RGBA16F) {
                "Merged develop must emit scene-linear RGBA16F"
            }
            mergedJpegOutput.process(scene, outputSettings)
        }
    }

    /**
     * Vulkan-merge variant of [developMergedTextureJpeg]: downloads the live
     * merged images in bands, re-uploads them as GL textures on the retained
     * develop EGL context, and delegates to the texture engine. Must run
     * while the merged images are live. Peak transient is one upload band
     * (~17MB), never the whole ~200MB frame; the bridge textures are
     * deleted before returning.
     */
    fun developMergedImagesJpeg(
        mergedImageId: Int,
        rcImageId: Int,
        width: Int,
        height: Int,
        acceptedFrames: Int,
        referenceMetadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        val egl = mergedEgl ?: Gles31AmazeProcessor.EglComputeContext().also { mergedEgl = it }
        egl.makeCurrent()
        val mergedTex = uploadBridgeRgba(mergedImageId, width, height)
        try {
            val rcTex = uploadBridgeR32(rcImageId, width / 2, height / 2)
            try {
                return developMergedTextureJpeg(
                    MergedTextureJpegInput(
                        mergedTex, width, height, rcTex, acceptedFrames, referenceMetadata
                    ),
                    settings,
                    outputSettings
                )
            } finally {
                GLES31.glDeleteTextures(1, intArrayOf(rcTex), 0)
                MemoryLeakDiagnostics.glTextureReleased((width / 2).toLong() * (height / 2) * 4L)
            }
        } finally {
            GLES31.glDeleteTextures(1, intArrayOf(mergedTex), 0)
            MemoryLeakDiagnostics.glTextureReleased(width.toLong() * height * 16L)
        }
    }

    private fun uploadBridgeRgba(imageId: Int, width: Int, height: Int): Int {
        val tex = IntArray(1)
        GLES31.glGenTextures(1, tex, 0)
        check(tex[0] != 0) { "Could not allocate merged bridge texture" }
        MemoryLeakDiagnostics.glTextureAllocated(width.toLong() * height * 16L)
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex[0])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, width, height)
            var y = 0
            while (y < height) {
                val rows = minOf(BRIDGE_BAND_ROWS, height - y)
                val band = vkDownloadRgba32fRegion(imageId, 0, y, width, rows)
                GLES31.glTexSubImage2D(
                    GLES31.GL_TEXTURE_2D, 0, 0, y, width, rows,
                    GLES31.GL_RGBA, GLES31.GL_FLOAT, java.nio.FloatBuffer.wrap(band)
                )
                y += rows
            }
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) {
                "Merged bridge upload failed; no merged JPEG may be produced"
            }
            return tex[0]
        } catch (t: Throwable) {
            GLES31.glDeleteTextures(1, tex, 0)
            MemoryLeakDiagnostics.glTextureReleased(width.toLong() * height * 16L)
            throw t
        }
    }

    private fun uploadBridgeR32(imageId: Int, width: Int, height: Int): Int {
        val tex = IntArray(1)
        GLES31.glGenTextures(1, tex, 0)
        check(tex[0] != 0) { "Could not allocate robustness bridge texture" }
        MemoryLeakDiagnostics.glTextureAllocated(width.toLong() * height * 4L)
        try {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex[0])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_R32F, width, height)
            var y = 0
            while (y < height) {
                val rows = minOf(BRIDGE_BAND_ROWS, height - y)
                val band = vkDownloadR32fRegion(imageId, 0, y, width, rows)
                GLES31.glTexSubImage2D(
                    GLES31.GL_TEXTURE_2D, 0, 0, y, width, rows,
                    GLES31.GL_RED, GLES31.GL_FLOAT, java.nio.FloatBuffer.wrap(band)
                )
                y += rows
            }
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) {
                "Robustness bridge upload failed; no merged JPEG may be produced"
            }
            return tex[0]
        } catch (t: Throwable) {
            GLES31.glDeleteTextures(1, tex, 0)
            MemoryLeakDiagnostics.glTextureReleased(width.toLong() * height * 4L)
            throw t
        }
    }

    /**
     * Single-frame JPEG XL develop: same RAW validation, demosaic, and AgX
     * path as [developJpeg], but the output stage renders to RGBA16F and
     * reads back half-float pixels, so [JxlSaver] can encode up to 16-bit
     * (plus 16/32-bit float) instead of 8-bit. Fusion is disabled (null):
     * the fused RGBA8 texture cannot feed a >8-bit encoder.
     *
     * @param look shared AgX look/metering contract (demosaic backend, HL
     * reconstruction, adaptive tuning); @param jxl owns only the container
     * options.
     */
    fun developJxl(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings()
    ): DevelopedJxl = developJxlWithTileDropRetry(
        "single-frame JXL ${metadata.imageWidth}x${metadata.imageHeight}"
    ) {
        develop(rawPlane, metadata, settings) { frame ->
            finishJxl(frame.texture, look, jxl)
        }
    }

    /**
     * Denoised-CFA JPEG XL twin of [developCfaJpeg]: identical adaptive
     * metering, HL/wavelet camera-linear tail, and AMaZE path, finishing
     * through the half-float JXL output stage (fusion disabled) instead of
     * the 8-bit JPEG one.
     */
    fun developCfaJxl(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings()
    ): DevelopedJxl {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(
                    cfa, adaptiveWorkspace, settings.adaptiveExposureTuning,
                    settings.ettrHeadroomEv
                )
            }
        } else null
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        val waveletPlan = resolveDenoise(settings.denoise, mergedPath = false).wavelet &&
            !metadata.quadBayer
        val hlPlan = look.hlReconstruction && !metadata.quadBayer
        val cameraLinearPlan = waveletPlan || hlPlan
        // Same blend-free tap contract as develop(): the HL stage must see
        // true channel values (see the tapWhiteWindow note there).
        val tapWhiteWindow = if (hlPlan) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developJxlWithTileDropRetry("cfa-frame JXL ${cfa.width}x${cfa.height}") {
            amaze.process(
                cfa,
                clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = null,
                whiteWindow = tapWhiteWindow,
                cameraLinear = cameraLinearPlan
            ) { output ->
                if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                    output.internalFormat == AmazeTextureFormat.RGBA16F
                ) {
                    val normalization = metadata.normalizationOrNull()
                    if (normalization == null) {
                        Log.w(LOG_TAG, "Camera-linear tap skipped: CFA normalization metadata is invalid")
                        amaze.transformCameraTexture(
                            output, transform.glslColumnMajorMatrix()
                        ) { aces -> finishJxl(aces, look, jxl) }
                    } else if (waveletPlan) {
                        consumeWaveletDenoised(
                            output, metadata, normalization, transform,
                            PreDemosaicResult(
                                null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)
                            ),
                            settings.denoise.wavelet,
                            look.hlReconstruction,
                            { hlGpuContext() }
                        ) { frame -> finishJxl(frame.texture, look, jxl) }
                    } else {
                        consumeHlReconstructed(
                            output, metadata, normalization, transform,
                            PreDemosaicResult(
                                null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)
                            ),
                            look.hlReconstruction,
                            { hlGpuContext() }
                        ) { frame -> finishJxl(frame.texture, look, jxl) }
                    }
                } else {
                    finishJxl(output, look, jxl)
                }
            }
        }
    }

    /**
     * Merged-CFA JPEG XL twin of [developMergedJpeg]: identical merged
     * adaptive metering (including the HDR-bracket shoulder cap, applied to
     * the shared look) and white-window contract, finishing through the
     * half-float JXL output stage instead of the 8-bit JPEG one.
     */
    fun developMergedJxl(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings()
    ): DevelopedJxl {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        // Callers AI-denoise the merged CFA first (denoiseCfa) and share it
        // between the merged-DNG write and this develop; no internal hook here
        // so inference never runs twice for one capture.
        val mergedAdaptive = resolveMergedAdaptive(cfa, settings, adaptiveWorkspace)
        val resolvedExposureEv = settings.exposureEv +
            (mergedAdaptive?.correctionEv ?: 0.0) *
            if (settings.hdrAdaptiveTuning != null) 1.0
            else settings.adaptiveExposureStrength.coerceIn(0f, 1f).toDouble()
        if (mergedAdaptive != null) {
            Log.i(LOG_TAG, "Merged JXL develop displayEV=${"%.2f".format(settings.exposureEv)} " +
                "adaptiveEV=${"%.2f".format(mergedAdaptive.correctionEv)} " +
                "appliedEV=${"%.2f".format(resolvedExposureEv)} " +
                "lowKey=${mergedAdaptive.lowKey}")
        }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        // Same softer HDR shoulder as the JPEG twin so rescued short-frame
        // highlights keep gradation; uniform merges keep the user's shoulder.
        val lookCapped = if (settings.hdrAdaptiveTuning != null) {
            look.copy(
                highlightShoulder = minOf(look.highlightShoulder, HDR_HIGHLIGHT_SHOULDER)
            )
        } else look
        val whiteWindow = if (settings.hdrAdaptiveTuning != null) floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_END
        ) else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developJxlWithTileDropRetry("merged-frame JXL ${cfa.width}x${cfa.height}") {
            amaze.process(
                cfa,
                clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = null,
                whiteWindow = whiteWindow
            ) { output ->
                finishJxl(output, lookCapped, jxl)
            }
        }
    }

    /**
     * MFSR-RGB JPEG XL twin of [developMfsrJpeg]: Amaze stays bypassed (the
     * frame is already RGB) under the identical color contract, finishing
     * through the half-float JXL output stage. No tile-drop retry, like the
     * JPEG twin: one full-frame dispatch, not AMaZE tiling.
     */
    fun developMfsrJxl(
        rgb: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings()
    ): DevelopedJxl {
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), settings.exposureEv
        )
        return amaze.processMfsrRgb(
            rgb, width, height,
            transform.glslColumnMajorMatrix(),
            transform.glslCameraWhiteNormalized()
        ) { output ->
            finishJxl(output, look, jxl)
        }
    }

    private fun finishJxl(
        output: AmazeGpuOutput,
        look: JpegOutputSettings,
        jxl: JxlOutputSettings
    ): DevelopedJxl {
        require(output.internalFormat == AmazeTextureFormat.RGBA16F) {
            "JXL develop needs the scene-linear RGBA16F frame, not fused RGBA8"
        }
        require(output.colorDomain == AmazeColorDomain.ACESCG) {
            "JXL develop needs scene-linear ACEScg, not camera-linear (wavelet tail missing?)"
        }
        return jxlOutput.process(output, look, jxl)
    }

    /**
     * Tile-drop retry for the half-float JXL path, mirroring
     * [developWithTileDropRetry]: a dropped (unwritten, all-zero) GPU tile
     * is retried once, and a persistent drop fails loudly. Samples RGB
     * only; alpha is ignored.
     */
    private fun developJxlWithTileDropRetry(tag: String, once: () -> DevelopedJxl): DevelopedJxl {
        val first = once()
        if (findJxlZeroTiles(first).isEmpty()) return first
        Log.w(LOG_TAG, "Dropped GPU tile(s) in $tag; retrying develop once")
        val second = once()
        val redrops = findJxlZeroTiles(second)
        if (redrops.isEmpty()) {
            Log.i(LOG_TAG, "Tile-drop retry recovered $tag")
            return second
        }
        throw IllegalStateException("GPU tile drop persisted after retry in $tag: retry=$redrops")
    }

    private fun findJxlZeroTiles(frame: DevelopedJxl): List<DevelopTile> {
        val view = frame.halfRgba.duplicate().order(java.nio.ByteOrder.nativeOrder())
        val rowShorts = frame.width * 4
        return DevelopTileDropDetector.findZeroTiles(frame.width, frame.height) { x, y ->
            // Any nonzero half sample keeps the tile alive: OR the full
            // 16-bit patterns (no truncation) so near-black subnormals in a
            // single channel cannot masquerade as a dropped tile.
            val base = (y * rowShorts + x * 4) * 2
            val r = view.getShort(base).toInt() and 0xffff
            val g = view.getShort(base + 2).toInt() and 0xffff
            val b = view.getShort(base + 4).toInt() and 0xffff
            r or g or b
        }
    }

    /** Releases cached programs and the thread-confined EGL session after queued saves finish. */
    fun close() {
        // The merged pair runs on the retained develop context (created on
        // the first merged save); make it current before deleting its
        // programs. With no merged save the closes are safe no-ops.
        mergedEgl?.makeCurrent()
        mergedJpegOutput.close()
        mergedDevelop.close()
        mergedEgl?.close()
        mergedEgl = null
        jxlOutput.close()
        jpegOutput.close()
        amaze.close()
        MemoryLeakDiagnostics.sample(
            "development-coordinator-closed",
            expectGlReleased = true,
            expectNativeReleased = true
        )
    }

    companion object {
        private const val LOG_TAG = "RawLensDevelop"
        /**
         * HDR-merge highlight-shoulder ceiling: full strength flattens rescued
         * short-frame highlights on the 1.7 asymptote (outdoor trees go white);
         * 0.5 keeps their gradation through AgX's range while rendering
         * sub-knee content identically (verified on IMG_20261008_142321_830).
         */
        const val HDR_HIGHLIGHT_SHOULDER = 0.5f
        /** Band height for the bridge uploads: 256 rows peak at ~17MB transient. */
        private const val BRIDGE_BAND_ROWS = 256
        fun estimateMemory(
            width: Int,
            height: Int,
            ultraHdr: Boolean = false,
            aiDenoise: Boolean = false
        ): RawDevelopmentMemoryEstimate {
            require(width > 0 && height > 0)
            val pixels = width.toLong() * height
            val raw = pixels * 2L
            val cfa = pixels * Float.SIZE_BYTES
            val amaze = AmazePipelineContract.estimatedGpuBytes(width, height)
            val rgba8 = pixels * 4L
            val readback = pixels * 4L
            val bitmap = pixels * 4L
            val gainPixels = ((width + 3) / 4).toLong() * ((height + 3) / 4).toLong()
            // GPU gain texture + direct readback + Android Bitmap, all RGBA8.
            val gainmap = if (ultraHdr) gainPixels * 12L else 0L
            // AI peak (managed heap + direct buffers, transient): unpacked CFA
            // + channel-last packed input + model output + result buffer.
            // Tiny: 5ch input @ quarter res (5B/px) + 4ch packed out (4B/px).
            // Bayer: 4ch input @ quarter res (4B/px) + 3ch camRGB @ full res
            // (12B/px). Native tiled inference adds only fixed-size tile
            // workspace on top (flat vs MP count). Size for the worst path.
            val aiPeak = if (aiDenoise) {
                raw + 2L * cfa + 16L * pixels
            } else 0L
            val accountedPeak = maxOf(
                raw + 2L * cfa,
                raw + cfa + cfa + amaze,
                raw + cfa + pixels * 8L + rgba8 + readback + bitmap + gainmap,
                aiPeak
            )
            val overheadReserve = 96L * 1024L * 1024L
            return RawDevelopmentMemoryEstimate(
                rawImageBytes = raw,
                unpackedCfaBytes = cfa,
                preDemosaicCfaCopiesBytes = 2L * cfa,
                amazeGpuBytes = amaze,
                amazeUploadStagingBytes = cfa,
                additionalSceneLinearGpuBytes = 0L,
                encodedOutputGpuBytes = rgba8,
                readbackBytes = readback,
                bitmapBytes = bitmap,
                ultraHdrGainmapBytes = gainmap,
                encodedJpegHeapBytes = 0L,
                // AMaZE scratch is released before output allocation, so phases do not sum.
                minimumAccountedBytes = accountedPeak,
                jvmNativeOverheadReserveBytes = overheadReserve,
                conservativePeakBytes = accountedPeak + overheadReserve
            )
        }
    }
}
