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
    val adaptiveExposureTuning: AdaptiveExposureTuning = AdaptiveExposureTuning()
)

data class SceneLinearGpuFrame(
    val texture: AmazeGpuOutput,
    val preDemosaic: PreDemosaicResult,
    val colorTransform: ResolvedSceneLinearTransform
)

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

    fun probe(width: Int, height: Int): AmazeCapability = amaze.probe(width, height)

    fun <T> develop(
        rawPlane: ByteBuffer,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        fusedOutputSettings: JpegOutputSettings? = null,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
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
        val layout = RawPlaneLayout(
            metadata.imageWidth,
            metadata.imageHeight,
            rowStride,
            pixelStride,
            geometry.sensorOriginX,
            geometry.sensorOriginY
        )
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
            val unpacked = RawSensorUnpacker.unpackNormalized(
                rawPlane, layout, normalization, geometry.processingCrop, ByteOrder.nativeOrder()
            )
            preDemosaic = RawPreDemosaicPipeline.process(unpacked, metadata, settings.preDemosaic)
            cpuCfa = requireNotNull(preDemosaic.cfa)
            preparedAt = SystemClock.elapsedRealtime()
        }
        // Direct RAW sampling avoids materializing a 50 MB FloatArray just for exposure metering.
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                if (cpuCfa != null) AdaptiveDevelopmentExposure.analyze(
                    cpuCfa, adaptiveWorkspace, settings.adaptiveExposureTuning
                )
                else AdaptiveDevelopmentExposure.analyzeRaw(
                    rawPlane, layout, normalization, geometry.processingCrop, lensModel,
                    adaptiveWorkspace, settings.adaptiveExposureTuning
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
        val consumeOutput: (AmazeGpuOutput) -> T = { output ->
            consume(SceneLinearGpuFrame(output, preDemosaic, transform))
        }
        val result = if (directGpu) {
            amaze.processRaw(
                GpuRawAmazeInput(
                    rawPlane, layout, geometry.processingCrop, normalization, lensModel
                ),
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = fusedOutputSettings,
                consume = consumeOutput
            )
        } else {
            amaze.process(
                requireNotNull(cpuCfa),
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                fusedOutputSettings = fusedOutputSettings,
                consume = consumeOutput
            )
        }
        val completedAt = SystemClock.elapsedRealtime()
        Log.i(
            LOG_TAG,
            "RAW development ${metadata.imageWidth}x${metadata.imageHeight}: " +
                "preprocessCpu=${preparedAt - startedAt}ms " +
                "path=${if (directGpu) "GPU_RAW" else "CPU_FALLBACK"} " +
                "adaptive=${adaptiveAt - preparedAt}ms " +
                "color=${transformAt - adaptiveAt}ms " +
                "adaptiveEV=${adaptive?.correctionEv ?: 0.0} appliedEV=$resolvedExposureEv " +
                "GPU+output=${completedAt - transformAt}ms total=${completedAt - startedAt}ms"
        )
        return result
    }

    private fun <T> developQuadBayer(
        rawPlane: ByteBuffer, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        consume: (SceneLinearGpuFrame) -> T
    ): T {
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
            ?: throw UnsupportedOperationException("Quad-Bayer buffer geometry is not proven")
        val normalization = metadata.normalizationOrNull()
            ?: throw UnsupportedOperationException("Quad-Bayer normalization metadata is invalid")
        val frame = QuadBayerPreparation.unpack(
            rawPlane,
            RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                requireNotNull(metadata.rawPlaneRowStride), requireNotNull(metadata.rawPlanePixelStride),
                geometry.sensorOriginX, geometry.sensorOriginY),
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
    ): DevelopedJpeg = develop(
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
            val layout = RawPlaneLayout(
                metadata.imageWidth, metadata.imageHeight,
                rowStride, pixelStride,
                geometry.sensorOriginX, geometry.sensorOriginY
            )
            val unpacked = RawSensorUnpacker.unpackNormalized(
                rawPlane, layout, normalization, geometry.processingCrop, ByteOrder.nativeOrder()
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
            val layout = RawPlaneLayout(
                metadata.imageWidth, metadata.imageHeight,
                rowStride, pixelStride,
                geometry.sensorOriginX, geometry.sensorOriginY
            )
            val unpacked = RawSensorUnpacker.unpackNormalized(
                rawPlane, layout, normalization, geometry.processingCrop, ByteOrder.nativeOrder()
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
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        val adaptive = if (settings.adaptiveExposureStrength > 0f) {
            (settings.sharedAdaptiveExposure ?: SharedAdaptiveExposure()).resolve {
                AdaptiveDevelopmentExposure.analyze(cfa, adaptiveWorkspace, settings.adaptiveExposureTuning)
            }
        } else null
        val resolvedExposureEv = settings.exposureEv +
            (adaptive?.correctionEv ?: 0.0) * settings.adaptiveExposureStrength.coerceIn(0f, 1f)
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), resolvedExposureEv
        )
        return amaze.process(
            cfa,
            clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
            cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
            cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
            fusedOutputSettings = outputSettings
        ) { output -> finishJpeg(output, outputSettings) }
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

    /** Develops an unclamped, normalized CFA produced by RAW HDR/SR merging. */
    fun developMergedJpeg(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg {
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        cfa.requireAmazeCompatible()
        // Callers AI-denoise the merged CFA first (denoiseCfa) and share it
        // between the merged-DNG write and this develop; no internal hook here
        // so inference never runs twice for one capture.
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata.from(metadata), settings.exposureEv
        )
        return amaze.process(
            cfa,
            clipPoint = cfa.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
            cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
            cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
            fusedOutputSettings = outputSettings
        ) { output ->
            if (output.internalFormat == AmazeTextureFormat.RGBA8) {
                jpegOutput.processEncoded(output, outputSettings)
            } else {
                jpegOutput.process(output, outputSettings)
            }
        }
    }

    /**
     * Develops the live merged camera-RGB texture straight to a
     * JPEG-ready Bitmap without a second demosaic or repeated RAW corrections.
     *
     * Must run with a current EGL context holding [input] textures (call
     * [developMergedImagesJpeg] from inside
     * `VkRawSrProcessor.processPacked.consume`; it owns the develop
     * context). Applies the reference camera-to-working-colour transform.
     * The merged texture already carries reference fallback, and prime DNG
     * pixels are never read here. Orientation is EXIF-carried via [input]
     * reference metadata at save time; output pixels are never rotated.
     */
    fun developMergedTextureJpeg(
        input: MergedTextureJpegInput,
        settings: RawDevelopmentSettings = RawDevelopmentSettings(),
        outputSettings: JpegOutputSettings = JpegOutputSettings()
    ): DevelopedJpeg = mergedDevelop.develop(input, settings) { scene ->
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F) {
            "Merged develop must emit scene-linear RGBA16F"
        }
        mergedJpegOutput.process(scene, outputSettings)
    }

    /**
     * Vulkan-merge variant of [developMergedTextureJpeg]: downloads the live
     * merged images in bands, re-uploads them as GL textures on the retained
     * develop EGL context, and delegates to the texture engine. Must run
     * inside `VkRawSrProcessor.processPacked.consume` while the images are
     * live. Peak transient is one upload band (~17MB), never the whole
     * ~200MB frame; the bridge textures are deleted before returning.
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

    @Deprecated("Use developJpeg so output color/gainmap metadata is retained")
    fun developJpegBitmap(rawPlane: ByteBuffer, metadata: RawFrameMetadata, settings: RawDevelopmentSettings = RawDevelopmentSettings()): Bitmap =
        developJpeg(rawPlane, metadata, settings).bitmap

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
        jpegOutput.close()
        amaze.close()
        MemoryLeakDiagnostics.sample("development-coordinator-closed", expectGlReleased = true)
    }

    companion object {
        private const val LOG_TAG = "RawLensDevelop"
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
