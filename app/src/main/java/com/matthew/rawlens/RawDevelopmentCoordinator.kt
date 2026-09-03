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
    private val amaze = Gles31AmazeProcessor(context)
    private val jpegOutput = Gles31JpegOutputProcessor(context)
    private val adaptiveWorkspace = AdaptiveDevelopmentExposure.workspace()

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
                denoise = settings.denoise,
                noiseModel = CfaNoiseModel.from(metadata.noiseProfile),
                fusedOutputSettings = fusedOutputSettings,
                consume = consumeOutput
            )
        } else {
            amaze.process(
                requireNotNull(cpuCfa),
                cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix(),
                cameraWhiteNormalized = transform.glslCameraWhiteNormalized(),
                denoise = settings.denoise,
                noiseModel = CfaNoiseModel.from(metadata.noiseProfile),
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
        fusedOutputSettings = outputSettings.takeIf { !settings.denoise.enabled }
    ) { frame ->
        if (frame.texture.internalFormat == AmazeTextureFormat.RGBA8) {
            jpegOutput.processEncoded(frame.texture, outputSettings)
        } else {
            jpegOutput.process(frame.texture, outputSettings, settings.denoise)
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
            denoise: Boolean = false
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
            // Denoise peak: original + final RGBA16F frames plus five 768² RGBA32F
            // scratch images (512 tile + 128 px halo on each side).  The recursive
            // wavelet pyramid deliberately stays fp32 to avoid quantizing shadow chroma.
            // Both full-resolution CFA textures are released before this phase.
            val denoisePeak = if (denoise) {
                raw + cfa + pixels * 16L + 5L * 768L * 768L * 16L
            } else 0L
            val accountedPeak = maxOf(
                raw + 2L * cfa,
                raw + cfa + cfa + amaze + if (denoise) cfa else 0L,
                raw + cfa + pixels * 8L + rgba8 + readback + bitmap + gainmap,
                denoisePeak
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
