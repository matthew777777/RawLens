// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.io.Closeable
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
    val hdrAdaptiveTuning: HdrAdaptiveTuning? = null
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
                    cfa, workspace, settings.adaptiveExposureTuning
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
    private val portraitBokeh = Gles31PortraitBokeh(context).also {
        PortraitDebugDump.dir = context.getExternalFilesDir("portrait")
    }
    // SR merge path: separate instances for the retained develop EGL context.
    // GL program and texture names are context-local, so the AMaZE-context
    // instances above must never touch merged-frame work even on the same
    // thread. The merge itself runs on Vulkan (no EGL); the bridge below
    // downloads its images and re-uploads them here for the GLES develop.
    private val mergedDevelop = Gles31MergedDevelopProcessor(context)
    private val mergedJpegOutput = Gles31JpegOutputProcessor(context)
    private var mergedEgl: Gles31AmazeProcessor.EglComputeContext? = null
    private val adaptiveWorkspace = AdaptiveDevelopmentExposure.workspace()

    private fun resolveMfsrExposure(
        rgb: ByteBuffer, width: Int, height: Int, settings: RawDevelopmentSettings,
        metadata: RawFrameMetadata, output: JpegOutputSettings
    ): Double {
        if (settings.adaptiveExposureStrength <= 0f) return settings.exposureEv
        val result = AdaptiveDevelopmentExposure.analyzeRgb(
            rgb, width, height, adaptiveWorkspace, settings.adaptiveExposureTuning
        )
        val correction = if (output.toneMapper == PhotoToneMapper.BASIC) {
            val transform = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata))
            minOf(result.correctionEv, BasicRgbExposureGuard.limitEv(rgb, width, height,
                transform.cameraToAcescg.toDoubleArray(), output.highlightSoftHeadroom.toDouble(),
                output.highlightHeadroom.toDouble(), output.displayP3))
        } else result.correctionEv
        android.util.Log.i("BasicRgbExposure", "sensorEV=${result.correctionEv} guardedEV=$correction")
        return settings.exposureEv + correction *
            settings.adaptiveExposureStrength.coerceIn(0f, 1f)
    }
    private val rawDenoiser = RawDenoiser(appContext)
    private val captureSharpener by lazy { CaptureSharpener() }

    fun probe(width: Int, height: Int): AmazeCapability = amaze.probe(width, height)

    /** Enable-time Vulkan preload for the RAW denoiser. */
    fun prewarmRawDenoise() = rawDenoiser.ensureInit()

    // Coordinator-owned device and cached pipeline; per-photo images release in the runner.
    @Volatile private var hlVkHandle: VulkanHub.Handle? = null
    private var opposedSession: OpposedHighlightsVulkan.Session? = null

    private fun opposedSession(): OpposedHighlightsVulkan.Session {
        opposedSession?.let { return it }
        val cache = VulkanPipelineCache.file(appContext.cacheDir, VulkanPipelineCache.HOST_OPPOSED)
        val handle = hlVkHandle ?: VulkanHub.openVulkan10().also {
            it.loadPipelineCache(cache)
            hlVkHandle = it
        }
        return OpposedHighlightsVulkan.Session(handle, appContext.assets).also {
            opposedSession = it
            handle.savePipelineCache(cache)
        }
    }

    private var monoSession: MonoVulkan? = null

    /** B&W session on the coordinator's Vulkan 1.0 device (shared with opposed). */
    private fun monoSession(): MonoVulkan {
        monoSession?.let { return it }
        val cache = VulkanPipelineCache.file(appContext.cacheDir, VulkanPipelineCache.HOST_OPPOSED)
        val handle = hlVkHandle ?: VulkanHub.openVulkan10().also {
            it.loadPipelineCache(cache)
            hlVkHandle = it
        }
        return MonoVulkan(handle, appContext.assets).also {
            monoSession = it
            handle.savePipelineCache(cache)
        }
    }

    /** Called on the serial save worker before capture; no CFA or large staging allocation. */
    fun prewarmHighlights() { runCatching { opposedSession() } }

    private fun reconstructBayer(cfa: UnpackedRawCfa, enabled: Boolean, metadata: RawFrameMetadata, owned: Boolean = false): UnpackedRawCfa {
        if (!enabled) return cfa
        val clipping = OpposedHighlights.clipMaskOf(cfa)
        if (clipping != null) { if (!clipping.any) return cfa }
        else if (cfa.values.none { it >= OpposedHighlights.CLIP }) return cfa
        // Opposed references must be achromatic in the same neutral basis as demosaic.
        // Clipping remains in the unbalanced sensor domain; only reference averages
        // are balanced and converted back, so WB is not applied twice.
        val neutral = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), 0.0)
            .glslCameraWhiteNormalized()
        val balance = FloatArray(3) { 1f / neutral[it] }
        val started = SystemClock.elapsedRealtime()
        return try {
            opposedSession().run(cfa, RawPreDemosaicPipeline.lensShadingModel(metadata), owned, balance).cfa.also {
                Log.i(LOG_TAG, "Opposed Bayer Vulkan ${cfa.width}x${cfa.height}: ${SystemClock.elapsedRealtime() - started}ms")
            }
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Opposed Vulkan failed; using Bayer CPU reference", failure)
            OpposedHighlights.cpu(cfa, RawPreDemosaicPipeline.lensShadingModel(metadata), balance)
        }
    }

    fun <T> develop(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        fusedOutputSettings: JpegOutputSettings? = null,
        allowOutputFusion: Boolean = true,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        fusedOutputSettings?.let { amaze.chromaCleanup = it.chromaCleanup }
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
        // Single-frame captures only; merges are denoised by their merge.
        val denoised = if (settings.denoise.rawDenoise.active) rawDenoiser.process(
            rawPlane, metadata, geometry, normalization, RawPreDemosaicPipeline.lensShadingModel(metadata),
            settings.denoise.rawDenoise
        ) else null
        return (denoised ?: Closeable {}).use {
            developBayer(rawPlane, denoised, geometry, normalization, metadata, settings, fusedOutputSettings,
                allowOutputFusion, startedAt, consume)
        }
    }

    /** Bayer develop of the sensor plane or, when present, of its [denoised] replacement. */
    private fun <T> developBayer(
        sensorPlane: ByteBuffer, denoised: DenoisedRaw?, geometry: RawBufferGeometry.Supported,
        sensorNormalization: RawNormalization, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        fusedOutputSettings: JpegOutputSettings?, allowOutputFusion: Boolean, startedAt: Long,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val rawPlane = denoised?.plane ?: sensorPlane
        val normalization = denoised?.normalization ?: sensorNormalization
        val rowStride = denoised?.layout?.rowStride ?: metadata.rawPlaneRowStride
            ?: throw UnsupportedOperationException("RAW row stride is missing")
        val pixelStride = denoised?.layout?.pixelStride ?: metadata.rawPlanePixelStride
            ?: throw UnsupportedOperationException("RAW pixel stride is missing")
        // Packed planes carry stride 0, which RawPlaneLayout rightly refuses:
        // only unpacked frames get a layout. Packed frames unpack through
        // the bridge below, and their adaptive metering analyzes the
        // resulting CFA instead of sampling the packed plane.
        val packedFormat = if (denoised != null) null else PackedRawFormat.ofInt(metadata.rawImageFormat)
        val layout = denoised?.layout ?: if (packedFormat == null) RawPlaneLayout(
            metadata.imageWidth,
            metadata.imageHeight,
            rowStride,
            pixelStride,
            geometry.sensorOriginX,
            geometry.sensorOriginY
        ) else null
        // Metering and clip evidence read the sensor samples: the night exposure rule measures their noise.
        val sensorLayout = denoised?.sourceLayout ?: layout
        val meterPlane = denoised?.sourcePlane ?: sensorPlane
        // Sparse metadata defects and the optional MAD detector still require the reference CPU
        // implementation. Normal captures take the zero-copy-to-JVM direct GPU path.
        val opposedEnabled = (fusedOutputSettings?.hlReconstruction ?: false) && !metadata.quadBayer
        val directGpu = !opposedEnabled && pixelStride == Short.SIZE_BYTES && rowStride % Short.SIZE_BYTES == 0 &&
            (denoised != null || metadata.hotPixels.isEmpty()) && !settings.preDemosaic.defectCorrection.autoDetect
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
            // Denoised values are smoothed: take the opposed clip evidence from the untouched sensor plane.
            val opposedSource = if (opposedEnabled && denoised != null) unpacked.copy(
                opposedClipping = OpposedHighlights.clipMaskFromRawSensor(
                    meterPlane, requireNotNull(sensorLayout), sensorNormalization, geometry.processingCrop
                )
            ) else unpacked
            preDemosaic = RawPreDemosaicPipeline.process(
                opposedSource, metadata, settings.preDemosaic, opposedClipEvidence = opposedEnabled
            )
            cpuCfa = requireNotNull(preDemosaic.cfa)
            preparedAt = SystemClock.elapsedRealtime()
        }
        val captureClip = if (settings.denoise.captureSharpening.enabled && !metadata.quadBayer && packedFormat == null) {
            CaptureSharpeningClipState.fromRawSensor(meterPlane, requireNotNull(sensorLayout), sensorNormalization, geometry.processingCrop)
        } else cpuCfa?.captureSharpeningClipState
        // Direct RAW sampling avoids materializing a 50 MB FloatArray just for exposure metering.
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                if (cpuCfa != null && denoised == null) AdaptiveDevelopmentExposure.analyze(
                    cpuCfa, adaptiveWorkspace, settings.adaptiveExposureTuning,
                    singleFrame = true
                )
                else AdaptiveDevelopmentExposure.analyzeRaw(
                    // Packed frames never reach here: their branch above
                    // always produces a CFA, so analyze() wins instead.
                    meterPlane, requireNotNull(sensorLayout),
                    sensorNormalization, geometry.processingCrop, lensModel,
                    adaptiveWorkspace, settings.adaptiveExposureTuning,
                    singleFrame = true
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
        val demosaicCfa = cpuCfa?.let { reconstructBayer(it, opposedEnabled, metadata, owned = true) }
        val transformAt = SystemClock.elapsedRealtime()
        // Capture sharpening needs the pre-WB camera-linear frame; the denoised luma is then added after it.
        // Otherwise the luma is added inside the final (fused) demosaic pass.
        val cameraLinearPlan = settings.denoise.captureSharpening.enabled && !metadata.quadBayer
        // Every demosaic path lens-shades its output now (direct RCD in rcd_final), so the
        // luma residual is shaded with it.
        val luma = denoised?.luma
        val effectiveFused = if (cameraLinearPlan || !allowOutputFusion) null else fusedOutputSettings
        val consumeOutput: (AmazeGpuOutput) -> T = { output ->
            Log.i(LOG_TAG, "Demosaic done: ${SystemClock.elapsedRealtime() - transformAt}ms")
            if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                output.internalFormat == AmazeTextureFormat.RGBA16F
            ) {
                consumeCameraLinearTail(
                    output, metadata, transform, preDemosaic, settings.denoise.captureSharpening,
                    normalization.sensorPattern.shifted(
                        geometry.sensorOriginX + geometry.processingCrop.left,
                        geometry.sensorOriginY + geometry.processingCrop.top), captureClip, luma, consume
                )
            } else {
                consume(SceneLinearGpuFrame(output, preDemosaic, transform))
            }
        }
        val tapWhiteWindow = if (opposedEnabled) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END)
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
                    luma = luma,
                    consume = consumeOutput
                )
            } else {
                amaze.process(
                    requireNotNull(demosaicCfa),
                    cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                    cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                    fusedOutputSettings = effectiveFused,
                    whiteWindow = tapWhiteWindow,
                    cameraLinear = cameraLinearPlan,
                    luma = luma,
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
                        luma = luma,
                        whiteWindow = tapWhiteWindow,
                        consume = consumeOutput
                    )
                } else {
                    amaze.processRcdCfa(
                        requireNotNull(demosaicCfa),
                        cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                        cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                        cameraLinear = cameraLinearPlan,
                        luma = luma,
                        whiteWindow = tapWhiteWindow,
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
     * Post-demosaic camera-linear tail: capture sharpening, then the RAW
     * denoiser's luma residual (after sharpening, so grain is not sharpened)
     * together with the WB/color matrix to ACEScg.
     */
    private fun <T> consumeCameraLinearTail(
        output: AmazeGpuOutput,
        metadata: RawFrameMetadata,
        transform: ResolvedSceneLinearTransform,
        preDemosaic: PreDemosaicResult,
        sharpening: CaptureSharpeningSettings,
        pattern: BayerPattern,
        clipState: CaptureSharpeningClipState?,
        luma: RawLumaResidual?,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val matrix = transform.glslColumnMajorMatrix()
        val camera = amaze.readbackHalfFloat(output, nativeOwned = true)
        try {
            captureSharpener.process(camera, output.width, output.height,
                CaptureSharpeningSettings.whiteBalance(metadata.wbGains?.toFloatArray()), pattern, sharpening, clipState)
            return amaze.transformCameraLinear(camera, output.width, output.height, matrix, luma) { aces ->
                consume(SceneLinearGpuFrame(aces, preDemosaic, transform))
            }
        } finally {
            HdrPlusVulkan.freeBuffer(camera)
        }
    }

    private fun sharpeningClipState(cfa: UnpackedRawCfa): CaptureSharpeningClipState? =
        cfa.captureSharpeningClipState ?: cfa.highlightClipping?.let {
            CaptureSharpeningClipState.fromSensorCfa(cfa)
        }

    private fun capturePattern(metadata: RawFrameMetadata): BayerPattern {
        val pattern = requireNotNull(metadata.normalizationOrNull()).sensorPattern
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported ?: return pattern
        return pattern.shifted(geometry.sensorOriginX + geometry.processingCrop.left,
            geometry.sensorOriginY + geometry.processingCrop.top)
    }

    /** Shared render-only tail for merged RGB; sharpen before the camera-to-ACEScg matrix. */
    private fun <T> consumeCaptureSharpened(
        output: AmazeGpuOutput, transform: ResolvedSceneLinearTransform,
        pattern: BayerPattern, sharpening: CaptureSharpeningSettings,
        metadata: RawFrameMetadata,
        clipState: CaptureSharpeningClipState? = null,
        externalContext: Boolean = false,
        consume: (AmazeGpuOutput) -> T
    ): T {
        if (!sharpening.enabled) return consume(output)
        require(output.colorDomain == AmazeColorDomain.CAMERA_LINEAR)
        val pixels = if (externalContext) amaze.readbackHalfFloatCurrentContext(output, nativeOwned = true) else amaze.readbackHalfFloat(output, nativeOwned = true)
        try {
            val wb = CaptureSharpeningSettings.whiteBalance(metadata.wbGains?.toFloatArray())
            captureSharpener.process(pixels, output.width, output.height, wb, pattern, sharpening, clipState)
            return if (externalContext) amaze.transformCameraLinearFromExternalContext(
                pixels, output.width, output.height, transform.glslColumnMajorMatrix(), consume)
            else amaze.transformCameraLinear(pixels, output.width, output.height,
                transform.glslColumnMajorMatrix(), null, consume)
        } finally {
            HdrPlusVulkan.freeBuffer(pixels)
        }
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
                    frame.samples, adaptiveWorkspace, settings.adaptiveExposureTuning
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
            fusedOutputSettings = outputSettings,
            // Portrait needs the scene-linear texture: no fused AgX/encode.
            allowOutputFusion = !outputSettings.portrait.enabled
        ) { frame ->
            if (frame.texture.internalFormat == AmazeTextureFormat.RGBA8) {
                jpegOutput.processEncoded(frame.texture, outputSettings)
            } else {
                applyPortrait(frame.texture, outputSettings, metadata)
                jpegOutput.process(frame.texture, outputSettings)
            }
        }
    }

    /**
     * Develops a JPEG from an in-memory CFA (imported DNG or HDR
     * merge) honoring the full development settings, including adaptive
     * exposure analyzed on the given CFA.
     */
    fun developCfaJpeg(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings(),
        /** LK-R HDR merge: level of the sensor clip in [rgb] (MfsrResult.hdrWhite); 1 otherwise. */
        hdrWhite: Float = 1f
    ): DevelopedJpeg {
        amaze.chromaCleanup = outputSettings.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(
                    cfa, adaptiveWorkspace, settings.adaptiveExposureTuning
                )
            }
        } else null
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        val cameraLinearPlan = settings.denoise.captureSharpening.enabled && !metadata.quadBayer
        val tapWhiteWindow = if (outputSettings.hlReconstruction && !metadata.quadBayer) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        val reconstructedCfa = reconstructBayer(cfa, outputSettings.hlReconstruction && !metadata.quadBayer, metadata)
        return developWithTileDropRetry("cfa-frame ${cfa.width}x${cfa.height}") {
            amaze.process(
                reconstructedCfa,
                clipPoint = reconstructedCfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = if (cameraLinearPlan) null else outputSettings,
                whiteWindow = tapWhiteWindow,
                cameraLinear = cameraLinearPlan
            ) { output ->
                if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                    output.internalFormat == AmazeTextureFormat.RGBA16F
                ) {
                    consumeCameraLinearTail(
                        output, metadata, transform,
                        PreDemosaicResult(null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)),
                        settings.denoise.captureSharpening, cfa.pattern, sharpeningClipState(cfa), null
                    ) { frame -> finishJpeg(frame.texture, outputSettings) }
                } else {
                    finishJpeg(output, outputSettings)
                }
            }
        }
    }

    /**
     * B&W from a fresh RAW plane: unpack + pre-demosaic corrections (defects,
     * lens shading) + optional opposed highlights, then the photosite mono
     * pass. Never demosaics. Quad-Bayer is not supported (caller keeps colour).
     */
    fun monoFromRaw(
        rawPlane: ByteBuffer, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        look: JpegOutputSettings, wantMono: Boolean, wantPacked: Boolean,
    ): MonoFrame {
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
            ?: throw UnsupportedOperationException("RAW buffer geometry is not proven")
        val normalization = metadata.normalizationOrNull()
            ?: throw UnsupportedOperationException("RAW black/white/CFA normalization metadata is invalid")
        val rowStride = metadata.rawPlaneRowStride ?: throw UnsupportedOperationException("RAW row stride is missing")
        val pixelStride = metadata.rawPlanePixelStride ?: throw UnsupportedOperationException("RAW pixel stride is missing")
        val packedFormat = PackedRawFormat.ofInt(metadata.rawImageFormat)
        val unpacked = if (packedFormat == null) {
            RawSensorUnpacker.unpackNormalized(
                rawPlane, RawPlaneLayout(metadata.imageWidth, metadata.imageHeight, rowStride, pixelStride,
                    geometry.sensorOriginX, geometry.sensorOriginY),
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
        val cfa = requireNotNull(RawPreDemosaicPipeline.process(
            unpacked, metadata, settings.preDemosaic, opposedClipEvidence = look.hlReconstruction
        ).cfa)
        return monoFromCfa(cfa, metadata, look, wantMono, wantPacked)
    }

    /** B&W from an already-corrected CFA (HDR+ merge). */
    fun monoFromCfa(
        cfa: UnpackedRawCfa, metadata: RawFrameMetadata, look: JpegOutputSettings,
        wantMono: Boolean, wantPacked: Boolean, merged: Boolean = false,
    ): MonoFrame {
        require(!metadata.quadBayer) { "B&W photosite mode needs a 2x2 Bayer CFA" }
        cfa.requireAmazeCompatible()
        val neutral = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), 0.0)
            .glslCameraWhiteNormalized()
        val balance = FloatArray(3) { 1f / neutral[it] }
        // Opposed keeps clipped skies gradated in luma exactly as in colour.
        val opposed = look.hlReconstruction && (!merged || cfa.highlightClipping != null)
        val source = reconstructBayer(cfa, opposed, metadata)
        return monoSession().run(source, look.mono.mode, balance, neutral, wantMono, wantPacked)
    }

    /** Develops a [MonoFrame] through the normal ACEScg -> AgX/Basic -> JPEG tail. */
    fun developMonoJpeg(
        frame: MonoFrame, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        outputSettings: JpegOutputSettings, merged: Boolean = false,
    ): DevelopedJpeg {
        MemoryLeakDiagnostics.stillCapture()
        val transform = monoTransform(frame, metadata, settings, merged)
        val camera = requireNotNull(frame.packed) { "B&W develop needs the packed camera-linear plane" }
        return amaze.transformCameraLinear(camera, frame.width, frame.height, transform.glslColumnMajorMatrix()) {
            finishJpeg(it, outputSettings)
        }
    }

    fun developMonoJxl(
        frame: MonoFrame, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        look: JpegOutputSettings, jxl: JxlOutputSettings, merged: Boolean = false,
    ): DevelopedJxl {
        MemoryLeakDiagnostics.stillCapture()
        val transform = monoTransform(frame, metadata, settings, merged)
        val camera = requireNotNull(frame.packed) { "B&W develop needs the packed camera-linear plane" }
        return amaze.transformCameraLinear(camera, frame.width, frame.height, transform.glslColumnMajorMatrix()) {
            finishJxl(it, look, jxl)
        }
    }

    private fun monoTransform(
        frame: MonoFrame, metadata: RawFrameMetadata, settings: RawDevelopmentSettings, merged: Boolean,
    ): ResolvedSceneLinearTransform {
        // Same metering as the colour develop of the same CFA.
        val correction = if (merged) {
            resolveMergedAdaptive(frame.cfa, settings, adaptiveWorkspace)?.correctionEv?.let {
                it * if (settings.hdrAdaptiveTuning != null) 1.0
                else settings.adaptiveExposureStrength.coerceIn(0f, 1f).toDouble()
            } ?: 0.0
        } else if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(frame.cfa, adaptiveWorkspace, settings.adaptiveExposureTuning)
            }.correctionEv * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        } else 0.0
        return SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), settings.exposureEv + correction)
    }

    /**
     * Portrait bokeh, in place on the scene-linear texture (current EGL context).
     * Never fails the JPEG: any error leaves the picture unblurred.
     */
    private fun applyPortrait(output: AmazeGpuOutput, outputSettings: JpegOutputSettings, metadata: RawFrameMetadata) {
        val portrait = outputSettings.portrait
        if (!portrait.enabled) return
        if (output.internalFormat != AmazeTextureFormat.RGBA16F || output.colorDomain != AmazeColorDomain.ACESCG) return
        val nets = PortraitNetsHolder.get(appContext) ?: run {
            Log.w(LOG_TAG, "Portrait skipped: no depth network on this build")
            return
        }
        runCatching {
            portraitBokeh.apply(output, portrait, nets, metadata.exifOrientation, metadata.frameNumber.toInt())
        }.onSuccess {
            Log.i(LOG_TAG, "Portrait ${output.width}x${output.height} ${PortraitSettings.label(portrait.fNumber)}: " +
                "total=${it.totalMs}ms nets=${it.netsMs}ms gpu=${it.gpuMs}ms focus=${"%.3f".format(it.focus)} " +
                "person=${"%.3f".format(it.personFraction)} layers=${it.layers} (${it.nets})")
        }.onFailure { Log.e(LOG_TAG, "Portrait failed; saving without blur", it) }
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
        outputSettings: JpegOutputSettings = JpegOutputSettings(),
        /** LK-R HDR merge: level of the sensor clip in [rgb] (MfsrResult.hdrWhite); 1 otherwise. */
        hdrWhite: Float = 1f
    ): DevelopedJpeg {
        amaze.chromaCleanup = outputSettings.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
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
        // Same-exposure HDR+ uses sensor thresholds. Brackets require merge provenance;
        // an unknown radiance CFA must never be treated as a sensor-white measurement.
        val opposed = outputSettings.hlReconstruction && !metadata.quadBayer &&
            (settings.hdrAdaptiveTuning == null || cfa.highlightClipping != null)
        val reconstructed = reconstructBayer(cfa, opposed, metadata)
        val whiteWindow = if (opposed) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else if (settings.hdrAdaptiveTuning != null) floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_END
        ) else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developWithTileDropRetry("merged-frame ${cfa.width}x${cfa.height}") {
            amaze.process(
                reconstructed,
                clipPoint = reconstructed.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = if ((settings.denoise.captureSharpening.enabled && !metadata.quadBayer) ||
                    outputSettings.portrait.enabled) null else developOutput,
                whiteWindow = whiteWindow,
                cameraLinear = settings.denoise.captureSharpening.enabled && !metadata.quadBayer
            ) { output ->
                if (output.colorDomain == AmazeColorDomain.CAMERA_LINEAR) {
                    consumeCaptureSharpened(output, transform, reconstructed.pattern,
                        settings.denoise.captureSharpening, metadata, sharpeningClipState(cfa)) { aces ->
                        applyPortrait(aces, developOutput, metadata)
                        finishJpeg(aces, developOutput)
                    }
                } else if (output.internalFormat == AmazeTextureFormat.RGBA8) {
                    jpegOutput.processEncoded(output, developOutput)
                } else {
                    applyPortrait(output, developOutput, metadata)
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
     * feeds the same [jpegOutput] AGX/encode. Adaptive exposure meters
     * the merged camera-linear RGB before colour conversion, using the
     * same placement policy and strength as the CFA path. No tile-drop retry: one full-frame dispatch, not AMaZE
     * tiling (see [developWithTileDropRetry]).
     */
    fun developMfsrJpeg(
        rgb: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings(),
        /** LK-R HDR merge: level of the sensor clip in [rgb] (MfsrResult.hdrWhite); 1 otherwise. */
        hdrWhite: Float = 1f
    ): DevelopedJpeg {
        amaze.chromaCleanup = outputSettings.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolveMfsrExposure(rgb, width, height, settings, metadata, outputSettings)
        )
        return amaze.processMfsrRgb(
            rgb, width, height,
            transform.glslColumnMajorMatrix(),
            transform.glslCameraWhiteNormalized(),
            cameraLinear = settings.denoise.captureSharpening.enabled,
            whiteScale = hdrWhite
        ) { output ->
            consumeCaptureSharpened(output, transform,
                capturePattern(metadata),
                settings.denoise.captureSharpening, metadata) { aces -> jpegOutput.process(aces, outputSettings) }
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
        outputSettings: JpegOutputSettings = JpegOutputSettings(),
        /** LK-R HDR merge: level of the sensor clip in [rgb] (MfsrResult.hdrWhite); 1 otherwise. */
        hdrWhite: Float = 1f
    ): DevelopedJpeg {
        amaze.chromaCleanup = outputSettings.chromaCleanup
        // Covers developMergedImagesJpeg too (it delegates here).
        MemoryLeakDiagnostics.stillCapture()
        return mergedDevelop.develop(input, settings, cameraLinear = settings.denoise.captureSharpening.enabled,
            chromaCleanup = outputSettings.chromaCleanup) { scene ->
            require(scene.internalFormat == AmazeTextureFormat.RGBA16F) {
                "Merged develop must emit scene-linear RGBA16F"
            }
            if (scene.colorDomain == AmazeColorDomain.CAMERA_LINEAR) {
                val metadata = input.referenceMetadata
                val transform = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), settings.exposureEv)
                val pattern = capturePattern(metadata).shifted(input.crop.left, input.crop.top)
                consumeCaptureSharpened(scene, transform, pattern, settings.denoise.captureSharpening, metadata, externalContext = true) {
                    aces -> jpegOutput.process(aces, outputSettings)
                }
            } else mergedJpegOutput.process(scene, outputSettings)
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
        outputSettings: JpegOutputSettings = JpegOutputSettings(),
        /** LK-R HDR merge: level of the sensor clip in [rgb] (MfsrResult.hdrWhite); 1 otherwise. */
        hdrWhite: Float = 1f
    ): DevelopedJpeg {
        amaze.chromaCleanup = outputSettings.chromaCleanup
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
        develop(rawPlane, metadata, settings, fusedOutputSettings = look, allowOutputFusion = false) { frame ->
            finishJxl(frame.texture, look, jxl)
        }
    }

    /**
     * Denoised-CFA JPEG XL twin of [developCfaJpeg]: identical adaptive
     * metering, HL/capture-sharpening camera-linear tail, and AMaZE path, finishing
     * through the half-float JXL output stage (fusion disabled) instead of
     * the 8-bit JPEG one.
     */
    fun developCfaJxl(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        look: JpegOutputSettings = JpegOutputSettings(),
        jxl: JxlOutputSettings = JxlOutputSettings(),
        hdrWhite: Float = 1f
    ): DevelopedJxl {
        amaze.chromaCleanup = look.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(
                    cfa, adaptiveWorkspace, settings.adaptiveExposureTuning
                )
            }
        } else null
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        val cameraLinearPlan = settings.denoise.captureSharpening.enabled && !metadata.quadBayer
        val tapWhiteWindow = if (look.hlReconstruction && !metadata.quadBayer) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        val reconstructedCfa = reconstructBayer(cfa, look.hlReconstruction && !metadata.quadBayer, metadata)
        return developJxlWithTileDropRetry("cfa-frame JXL ${cfa.width}x${cfa.height}") {
            amaze.process(
                reconstructedCfa,
                clipPoint = reconstructedCfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = null,
                whiteWindow = tapWhiteWindow,
                cameraLinear = cameraLinearPlan
            ) { output ->
                if (cameraLinearPlan && output.colorDomain == AmazeColorDomain.CAMERA_LINEAR &&
                    output.internalFormat == AmazeTextureFormat.RGBA16F
                ) {
                    consumeCameraLinearTail(
                        output, metadata, transform,
                        PreDemosaicResult(null, LensShadingStatus.ALREADY_APPLIED, DefectCorrectionStats(0, 0)),
                        settings.denoise.captureSharpening, cfa.pattern, sharpeningClipState(cfa), null
                    ) { frame -> finishJxl(frame.texture, look, jxl) }
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
        jxl: JxlOutputSettings = JxlOutputSettings(),
        hdrWhite: Float = 1f
    ): DevelopedJxl {
        amaze.chromaCleanup = look.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
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
        // Same-exposure HDR+ uses sensor thresholds. Brackets require merge provenance;
        // an unknown radiance CFA must never be treated as a sensor-white measurement.
        val opposed = look.hlReconstruction && !metadata.quadBayer &&
            (settings.hdrAdaptiveTuning == null || cfa.highlightClipping != null)
        val reconstructed = reconstructBayer(cfa, opposed, metadata)
        val whiteWindow = if (opposed) SceneLinearColorProcessor.WHITE_WINDOW_OFF
        else if (settings.hdrAdaptiveTuning != null) floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_HDR_END
        ) else floatArrayOf(
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_START,
            SceneLinearColorProcessor.WHITE_WINDOW_LDR_END
        )
        return developJxlWithTileDropRetry("merged-frame JXL ${cfa.width}x${cfa.height}") {
            amaze.process(
                reconstructed,
                clipPoint = reconstructed.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = null,
                whiteWindow = whiteWindow,
                cameraLinear = settings.denoise.captureSharpening.enabled && !metadata.quadBayer
            ) { output ->
                if (output.colorDomain == AmazeColorDomain.CAMERA_LINEAR) {
                    consumeCaptureSharpened(output, transform, reconstructed.pattern,
                        settings.denoise.captureSharpening, metadata, sharpeningClipState(cfa)) { aces -> finishJxl(aces, lookCapped, jxl) }
                } else finishJxl(output, lookCapped, jxl)
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
        jxl: JxlOutputSettings = JxlOutputSettings(),
        hdrWhite: Float = 1f
    ): DevelopedJxl {
        amaze.chromaCleanup = look.chromaCleanup
        MemoryLeakDiagnostics.stillCapture()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolveMfsrExposure(rgb, width, height, settings, metadata, look)
        )
        return amaze.processMfsrRgb(
            rgb, width, height,
            transform.glslColumnMajorMatrix(),
            transform.glslCameraWhiteNormalized(),
            cameraLinear = settings.denoise.captureSharpening.enabled,
            whiteScale = hdrWhite
        ) { output ->
            consumeCaptureSharpened(output, transform,
                capturePattern(metadata),
                settings.denoise.captureSharpening, metadata) { aces -> finishJxl(aces, look, jxl) }
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
            "JXL develop needs scene-linear ACEScg, not camera-linear (camera-linear tail missing?)"
        }
        return jxlOutput.process(output, look, jxl)
    }

    private fun developJxlWithTileDropRetry(tag: String, once: () -> DevelopedJxl): DevelopedJxl =
        try {
            once()
        } catch (failure: JxlGpuOutputException) {
            Log.w(LOG_TAG, "Invalid GPU output in $tag; retrying develop once", failure)
            once()
        }

    /** Releases cached programs and the thread-confined EGL session after queued saves finish. */
    fun close() {
        rawDenoiser.close()
        captureSharpener.release()
        // The merged pair runs on the retained develop context (created on
        // the first merged save); make it current before deleting its
        // programs. With no merged save the closes are safe no-ops.
        mergedEgl?.makeCurrent()
        mergedJpegOutput.close()
        mergedDevelop.close()
        mergedEgl?.close()
        mergedEgl = null
        amaze.makeProcessingContextCurrent()
        jxlOutput.close()
        jpegOutput.close()
        portraitBokeh.close()
        amaze.close()
        opposedSession?.close()
        opposedSession = null
        monoSession?.close()
        monoSession = null
        hlVkHandle?.close()
        hlVkHandle = null
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
            ultraHdr: Boolean = false
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
            val accountedPeak = maxOf(
                raw + 2L * cfa,
                raw + cfa + cfa + amaze,
                raw + cfa + pixels * 8L + rgba8 + readback + bitmap + gainmap
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
