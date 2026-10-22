// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log

/**
 * Save settings a ZSL merge job reads, frozen at shutter time like every
 * other save (W4): the job runs later on the writer thread.
 */
internal data class ZslMergeSaveSettings(
    val dngBackend: DngWriterBackend,
    val dngOverrides: DngMetadataOverrides,
    val dngCompression: DngCompression,
    val developedFormat: DevelopedFormat,
    val jxl: JxlOutputSettings,
    val exposureMode: CaptureExposureMode,
    val focusView: Pair<Float, Float>?,
)

/**
 * ZSL merge save jobs (HDR+ and MFSR), run on the writer thread with
 * settings frozen at shutter time. Extracted verbatim from
 * RawCameraController (ZSL merge jobs, docs/refactor/controller-remaining-plan.md);
 * the controller keeps ring selection, queue admission and completion.
 */
internal class HdrPlusZslSaveJob(private val context: Context, private val host: Host) {
    interface Host : RawFrameSaveJob.Host {
        /** Shared HDR+ Vulkan merge (lazily created, synchronized). */
        fun hdrPlusProcessor(): HdrPlusMerge
        /** Gyro tracker running: per-frame gyro is reported only then. */
        fun gyroRunning(): Boolean
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
    fun run(
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
        saveSettings: ZslMergeSaveSettings
    ) {
        val developedFormat = saveSettings.developedFormat
        val jxlSettings = saveSettings.jxl
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = saveSettings.dngBackend
            val overrides = saveSettings.dngOverrides
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
                        dngCompression = saveSettings.dngCompression
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
        host.reportSaveState("HDR+ MERGING")
        val tMerge0 = SystemClock.elapsedRealtime()
        val mergedRaw = try {
            host.hdrPlusProcessor().merge(
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
            host.reportSaveState("HDR+ ERROR")
            return
        }
        val mergeMs = SystemClock.elapsedRealtime() - tMerge0
        // The decision was computed inside our own synchronized merge call;
        // fall back to keep-all only if the volatile was never published.
        val decision = host.hdrPlusProcessor().lastDecision
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
        val developer = host.developer()
        val effectiveMerged = if (denoise.aiEnabled) {
            developer.denoiseCfa(merged, refMetadata) ?: merged
        } else merged
        var referenceDng: String? = null
        if (outputFormat.includesDng) {
            val timings = host.hdrPlusProcessor().lastTimings
            val motion = host.hdrPlusProcessor().lastMotion
            // Same snapshot discipline as lastTimings: merge is synchronized
            // and this read follows our own merge call on the saver thread.
            val gyroReported = host.gyroRunning()
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
                dngCompression = saveSettings.dngCompression
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
                            saveSettings.exposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, saveSettings.focusView
                        )
                    ), look = outputSettings, jxl = jxlSettings)
                JxlSaver(context).save(developed, refMetadata, refResult,
                    captureTimeMillis = captureTimeMillis,
                    typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps)
                host.publishDevelopedJxlScope(developed)
                host.reportSaveState(if (referenceDng != null) "HDR+ MERGED+DNG • JXL" else "HDR+ MERGED • JXL")
            } else {
                val developed = developer.developMergedJpeg(effectiveMerged, refMetadata,
                    RawDevelopmentSettings(
                        denoise = denoise,
                        exposureEv = 0.0,
                        adaptiveExposureStrength = adaptivePreviewStrength(
                            saveSettings.exposureMode, outputSettings
                        ),
                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                            outputSettings, saveSettings.focusView
                        )
                    ), outputSettings)
                try {
                    JpegSaver(context).save(developed, refMetadata,
                        refResult, captureTimeMillis = captureTimeMillis,
                        typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps)
                    host.publishDevelopedScope(developed.bitmap)
                    host.reportSaveState(if (referenceDng != null) "HDR+ MERGED+DNG" else "HDR+ MERGED")
                } finally {
                    recycleUltraHdrGainmapContents(developed)
                    developed.bitmap.recycle()
                }
            }
        } else {
            host.reportSaveState("HDR+ DNG SAVED")
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}

internal class MfsrZslSaveJob(private val context: Context, private val host: RawFrameSaveJob.Host) {
    /**
     * ZSL sharp merge job: unpack + sharpest-first order, native-resolve
     * SR merge (scale 1, RGB out), linear DNG via [LinearRgbDngSaver]
     * plus developed JPEG (Amaze bypassed through the sharp RGB tail;
     * same rcd_final color contract and AGX/encode as the CFA path).
     */
    fun run(
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
        saveSettings: ZslMergeSaveSettings
    ) {
        val developedFormat = saveSettings.developedFormat
        val jxlSettings = saveSettings.jxl
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = saveSettings.dngBackend
            val overrides = saveSettings.dngOverrides
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
                        dngCompression = saveSettings.dngCompression
                    )
                } finally {
                    buffer.limit(limit)
                    buffer.position(position)
                }
            }
        }
        host.reportSaveState("MFSR MERGING")
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
            host.reportSaveState("MFSR ERROR")
            selected.forEach { RawImageOwnership.release(it.image) }
            return
        }
        val merged = try {
            MfsrMerge.merge(context, ordered, settings)
        } finally {
            selected.forEach { RawImageOwnership.release(it.image) }
        } ?: run {
            Log.e(LOG_TAG, "MFSR merge returned null")
            host.reportSaveState("MFSR ERROR")
            return
        }
        val mergeMs = android.os.SystemClock.elapsedRealtime() - tMerge0
        Log.i(LOG_TAG, "MFSR timing merge=${mergeMs}ms frames=${merged.keptCount}/${selected.size} " +
            "${merged.width}x${merged.height}")
        var referenceDng: String? = null
        if (outputFormat.includesDng) {
            referenceDng = saveMfsrDng(merged, cameraId, captureTimeMillis, captureGps, selected)
            host.reportSaveState("MFSR DNG SAVED")
        }
        if (outputFormat.includesJpeg) {
            // The merge output is already RGB: no Amaze demosaic, fixed
            // reference exposure (adaptive metering has no RGB leg yet).
            // ZSL bursts never use ETTR, so no residual headroom cap.
            val developer = host.developer()
            if (developedFormat == DevelopedFormat.JXL) {
                val developed = developer.developMfsrJxl(merged.rgb, merged.width, merged.height,
                    merged.refMetadata, RawDevelopmentSettings(denoise = denoise),
                    look = outputSettings, jxl = jxlSettings)
                JxlSaver(context).save(developed, merged.refMetadata, merged.refResult,
                    captureTimeMillis = captureTimeMillis,
                    typeSuffix = CaptureFileNames.TYPE_MFSR, gps = captureGps)
                host.publishDevelopedJxlScope(developed)
                host.reportSaveState(if (referenceDng != null) "MFSR MERGED+DNG • JXL" else "MFSR MERGED • JXL")
            } else {
                val developed = developer.developMfsrJpeg(merged.rgb, merged.width, merged.height,
                    merged.refMetadata, RawDevelopmentSettings(denoise = denoise), outputSettings)
                try {
                    JpegSaver(context).save(developed, merged.refMetadata,
                        merged.refResult, captureTimeMillis = captureTimeMillis,
                        typeSuffix = CaptureFileNames.TYPE_MFSR, gps = captureGps)
                    host.publishDevelopedScope(developed.bitmap)
                    host.reportSaveState(if (referenceDng != null) "MFSR MERGED+DNG" else "MFSR MERGED")
                } finally {
                    recycleUltraHdrGainmapContents(developed)
                    developed.bitmap.recycle()
                }
            }
        } else {
            host.reportSaveState("MFSR DNG SAVED")
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

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}
