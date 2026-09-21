// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RawDevelopmentSettings(
    val preDemosaic: PreDemosaicSettings = PreDemosaicSettings(),
    val exposureEv: Double = 0.0,
    val denoise: DenoiseSettings = DenoiseSettings(),
    val adaptiveExposureStrength: Float = 0f,
    val sharedAdaptiveExposure: SharedAdaptiveExposure? = null
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
                if (cpuCfa != null) AdaptiveDevelopmentExposure.analyze(cpuCfa, adaptiveWorkspace)
                else AdaptiveDevelopmentExposure.analyzeRaw(
                    rawPlane, layout, normalization, geometry.processingCrop, lensModel,
                    adaptiveWorkspace
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
     * denoised CFA (always RGGB in the local frame) or null when AI is
     * unavailable — inference failure, OOM, or unsupported geometry all fall
     * back silently. Never throws for AI reasons; callers use `?: cfa`.
     */
    fun denoiseCfa(cfa: UnpackedRawCfa, metadata: RawFrameMetadata): UnpackedRawCfa? {
        if (!aiDenoiser.isReady()) return null
        return try {
            aiDenoiser.denoise(cfa, CfaNoiseModel.from(metadata.noiseProfile))
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI CFA denoise failed, falling back", failure)
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
        if (!settings.denoise.aiEnabled || !aiDenoiser.isReady()) return null
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
            denoiseCfa(cfa, metadata)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI capture denoise failed, falling back", failure)
            null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "AI capture denoise OOM, falling back", oom)
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
                AdaptiveDevelopmentExposure.analyze(cfa, adaptiveWorkspace)
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

    /** Develops an unclamped, normalized CFA produced by RAW HDR merging. */
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

    @Deprecated("Use developJpeg so output color/gainmap metadata is retained")
    fun developJpegBitmap(rawPlane: ByteBuffer, metadata: RawFrameMetadata, settings: RawDevelopmentSettings = RawDevelopmentSettings()): Bitmap =
        developJpeg(rawPlane, metadata, settings).bitmap

    /** Releases cached programs and the thread-confined EGL session after queued saves finish. */
    fun close() {
        jpegOutput.close()
        amaze.close()
        MemoryLeakDiagnostics.sample("development-coordinator-closed", expectGlReleased = true)
    }

    companion object {
        private const val LOG_TAG = "RawLensDevelop"
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
            // + channel-last packed input (5ch @ quarter res = 5B/px) + packed
            // output (4ch = 4B/px) + denoised CFA. Native tiled inference adds
            // only fixed-size tile workspace on top (flat vs MP count).
            val aiPeak = if (aiDenoise) {
                raw + 2L * cfa + 9L * pixels
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
