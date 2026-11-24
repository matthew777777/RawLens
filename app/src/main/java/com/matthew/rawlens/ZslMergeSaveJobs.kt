// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

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
    val boundPhysicalId: String?,
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
        /** Releases RAW reader slots as soon as merging finishes, before development/save. */
        fun releaseInputs()
        /** Gyro tracker running: per-frame gyro is reported only then. */
        fun gyroRunning(): Boolean
        /**
         * Frees the shared developer's idle GPU session (AMaZE EGL context,
         * retained scratch textures, merged-develop context) before a merge
         * measures its memory headroom. Writer thread only; the developer is
         * recreated lazily for the save tail.
         */
        fun releaseDevelopCaches() {}
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
            // Source DNGs need the live Images: the controller never stages these bursts.
            check(selected.none { it.staged != null }) { "keepSourceBurst needs camera Images" }
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation,
                    levelOverrides = saveSettings.dngOverrides, boundPhysicalId = saveSettings.boundPhysicalId
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    val sensorResult = physicalCaptureResult(frame.result, saveSettings.boundPhysicalId) ?: frame.result
                    saver.save(
                        frame.image, c, sensorResult, orientation, overrides, metadata, backend,
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
            val metadata = RawFrameMetadataFactory.capture(
                cameraId, frame.view(), c, frame.result, orientation,
                levelOverrides = saveSettings.dngOverrides, boundPhysicalId = saveSettings.boundPhysicalId
            )
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: throw UnsupportedOperationException("HDR+ RAW geometry is not supported")
            val normalization = metadata.normalizationOrNull()
                ?: throw UnsupportedOperationException("HDR+ normalization metadata is missing")
            metadata.rawDevelopmentUnsupportedReason?.let { error(it) }
            val plane = frame.view()
            HdrPlusFrameInput(
                frame = frame, metadata = metadata, result = frame.result,
                layout = RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                    plane.rowStride, plane.pixelStride,
                    geometry.sensorOriginX, geometry.sensorOriginY),
                normalization = normalization, crop = geometry.processingCrop
            )
        }
        val referenceIndex = inputs.size / 2
        host.releaseDevelopCaches()
        host.reportSaveState("HDR+ MERGING")
        val tMerge0 = SystemClock.elapsedRealtime()
        val mergedRaw = try {
            host.hdrPlusProcessor().merge(
                inputs.map {
                    HdrPlusMerge.FrameInput(
                        it.frame.image.takeIf { _ -> it.frame.staged == null }, it.layout, it.crop, it.normalization,
                        timestampNanos = it.frame.timestampNanos,
                        gyroRadiansPerSecond = it.frame.motionRadiansPerSecond,
                        sensitivityIso = it.metadata.sensitivityIso ?: 0,
                        pixels = it.frame.staged?.buffer?.duplicate()?.also { b -> b.rewind() }
                    )
                },
                referenceIndex, settings,
                noiseSource = RawNoiseModel.Source.of(inputs[referenceIndex].metadata)
            )
        } finally {
            host.releaseInputs()
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
        val refMetadata = refInput.metadata.copy(
            compositeSourceCount = inputs.size,
            compositeUsedCount = keptInputs.size
        )
        val refResult = refInput.result
        val merged = requireNotNull(RawPreDemosaicPipeline.process(
            mergedRaw, refMetadata, PreDemosaicSettings()).cfa)
        val developer = host.developer()
        val effectiveMerged = if (denoise.aiEnabled) {
            developer.denoiseCfa(merged, refMetadata) ?: merged
        } else merged
        var referenceDng: String? = null
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
        // B&W photosite mode: one Vulkan pass on the merged CFA feeds both the
        // monochrome DNG and the develop. Any failure keeps the colour path.
        val mono = if (outputSettings.mono.enabled && !refMetadata.quadBayer) runCatching {
            developer.monoFromCfa(effectiveMerged, refMetadata, outputSettings,
                wantMono = outputFormat.includesDng, wantPacked = outputFormat.includesJpeg, merged = true)
        }.onFailure { Log.w(LOG_TAG, "HDR+ B&W failed; saving colour", it) }.getOrNull() else null
        try {
        if (outputFormat.includesDng) {
            if (mono != null) {
                referenceDng = MonoDngWriter.save(context, mono, refMetadata, captureTimeMillis, captureGps,
                    CaptureFileNames.TYPE_HDRPLUS, HdrPlusDngWriter.provenanceBlock(provenance))
            }
            if (mono == null || outputSettings.mono.keepColorDng) {
                val colorDng = DngSaver(context).saveMerged(
                    effectiveMerged, refMetadata, captureTimeMillis, gps = captureGps,
                    displayName = CaptureFileNames.hdrPlusDng(captureTimeMillis),
                    sixteenBit = true, hdrPlusProvenance = provenance,
                    dngCompression = saveSettings.dngCompression
                )
                if (referenceDng == null) referenceDng = colorDng
            }
        }
        if (outputFormat.includesJpeg) {
            // The HDR+ average lives in the single-frame normalized domain, so the
            // merged develop meters through the LDR adaptive path on the merged CFA
            // itself (same highlight guards as single-frame). The DNG above stays
            // linear and dark; only this developed still lifts.
            if (developedFormat == DevelopedFormat.JXL) {
                val developed = developMergedOrMonoJxl(mono, effectiveMerged, refMetadata,
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
                val developed = developMergedOrMonoJpeg(mono, effectiveMerged, refMetadata,
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
                        typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = captureGps,
                        description = HdrPlusDngWriter.provenanceBlock(provenance),
                        monochromeMode = mono?.mode)
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
        } finally {
            mono?.close()
        }
    }

    private fun developMergedOrMonoJxl(
        mono: MonoFrame?, cfa: UnpackedRawCfa, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        look: JpegOutputSettings, jxl: JxlOutputSettings,
    ): DevelopedJxl = if (mono != null) host.developer().developMonoJxl(mono, metadata, settings, look, jxl, merged = true)
        else host.developer().developMergedJxl(cfa, metadata, settings, look = look, jxl = jxl)

    private fun developMergedOrMonoJpeg(
        mono: MonoFrame?, cfa: UnpackedRawCfa, metadata: RawFrameMetadata, settings: RawDevelopmentSettings,
        outputSettings: JpegOutputSettings,
    ): DevelopedJpeg = if (mono != null) host.developer().developMonoJpeg(mono, metadata, settings, outputSettings, merged = true)
        else host.developer().developMergedJpeg(cfa, metadata, settings, outputSettings)

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}

internal class MfsrZslSaveJob(private val context: Context, private val host: HdrPlusZslSaveJob.Host) {
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
        saveSettings: ZslMergeSaveSettings,
        /** LK-R HDR: the last [hdrTail] frames of [selected] are the short / long bracket frames. */
        hdrTail: Int = 0
    ) {
        val developedFormat = saveSettings.developedFormat
        val jxlSettings = saveSettings.jxl
        if (settings.keepSourceBurst) {
            val saver = DngSaver(context)
            val backend = saveSettings.dngBackend
            val overrides = saveSettings.dngOverrides
            // Source DNGs need the live Images: the controller never stages these bursts.
            check(selected.none { it.staged != null }) { "keepSourceBurst needs camera Images" }
            selected.forEachIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    cameraId, frame.image, c, frame.result, orientation,
                    levelOverrides = saveSettings.dngOverrides, boundPhysicalId = saveSettings.boundPhysicalId
                )
                val buffer = frame.image.planes.single().buffer
                val position = buffer.position()
                val limit = buffer.limit()
                try {
                    val sensorResult = physicalCaptureResult(frame.result, saveSettings.boundPhysicalId) ?: frame.result
                    saver.save(
                        frame.image, c, sensorResult, orientation, overrides, metadata, backend,
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
        host.reportSaveState("${settings.label} MERGING")
        val tMerge0 = android.os.SystemClock.elapsedRealtime()
        val metadataOf = { frame: BufferedRawFrame ->
            RawFrameMetadataFactory.capture(
                cameraId, frame.view(), c, frame.result, orientation,
                levelOverrides = saveSettings.dngOverrides, boundPhysicalId = saveSettings.boundPhysicalId
            )
        }
        val hdr = hdrTail > 0 && settings.engine == MfsrEngine.LKR && selected.size - hdrTail >= 1
        // HDR: reference + blur rejection over the 0 EV frames only (a short frame is never the
        // reference); the bracket frames follow in capture order. Images stay open for the merge.
        val ordered = if (hdr) {
            val zero = selected.dropLast(hdrTail)
            val head = if (zero.size >= MfsrSettings.MIN_MERGE_FRAMES)
                MfsrMerge.prepare(zero, settings.lkrMiddleReference, nativeSharpness = true, metadataOf = metadataOf)
            else MfsrMerge.describe(zero, metadataOf)
            head?.let { it + MfsrMerge.describe(selected.takeLast(hdrTail), metadataOf) }
        } else MfsrMerge.prepare(selected, settings.engine == MfsrEngine.LKR && settings.lkrMiddleReference,
            nativeSharpness = settings.engine == MfsrEngine.LKR, metadataOf = metadataOf)
        if (settings.engine == MfsrEngine.LKR) Log.i(LOG_TAG,
            "LK-R selection=${android.os.SystemClock.elapsedRealtime() - tMerge0}ms frames=${selected.size}")
        if (ordered.isNullOrEmpty()) {
            Log.e(LOG_TAG, "${settings.label} prepare produced no usable frames")
            host.reportSaveState("${settings.label} ERROR")
            host.releaseInputs()
            return
        }
        host.releaseDevelopCaches()
        val merged = try {
            // Same shutter / ZSL / save tail for both engines; only the merge differs.
            if (settings.engine == MfsrEngine.LKR)
                MergeGpuQuiet.during { LkrMerge.mergeLive(context, ordered,
                    LkrMerge.Settings(network = settings.lkrNetwork, npuDenoise = settings.lkrNpuDenoise, noPrior = settings.lkrNoPrior, dctPrior = settings.lkrDctPrior, tuning = LkrTuning.overrides(context),
                        zoom = settings.lkrZoom, fullRes = settings.lkrFullRes, hdrBracket = hdr),
                    releaseInputs = host::releaseInputs) }
            else MfsrMerge.merge(context, ordered, settings)
        } finally {
            host.releaseInputs()
        } ?: run {
            Log.e(LOG_TAG, "${settings.label} merge returned null")
            host.reportSaveState("${settings.label} ERROR")
            return
        }
        try {
            val mergeMs = android.os.SystemClock.elapsedRealtime() - tMerge0
            Log.i(LOG_TAG, "${settings.label} timing merge=${mergeMs}ms frames=${merged.keptCount}/${selected.size} " +
                "${merged.width}x${merged.height}")
            val provenance = sharpMergeProvenance(merged, settings, selected.size,
                ordered.take(merged.keptCount).map { it.metadata },
                if (settings.engine == MfsrEngine.LKR && settings.lkrMiddleReference)
                    "auto (sharpest of the middle three, blur rejection)"
                else "auto (sharpness and blur rejection)")
            var referenceDng: String? = null
            if (outputFormat.includesDng) {
                referenceDng = saveMfsrDng(merged, captureTimeMillis, captureGps, provenance)
                host.reportSaveState("${settings.label} DNG SAVED")
            }
            if (outputFormat.includesJpeg) {
                // Meter merged camera-linear RGB; DNG above retains the merge values.
                val development = RawDevelopmentSettings(
                    denoise = denoise,
                    adaptiveExposureStrength = adaptivePreviewStrength(saveSettings.exposureMode, outputSettings),
                    adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                        outputSettings, saveSettings.focusView
                    )
                )
                val developer = host.developer()
                if (developedFormat == DevelopedFormat.JXL) {
                    val developed = developer.developMfsrJxl(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, development,
                        look = outputSettings, jxl = jxlSettings, hdrWhite = merged.hdrWhite)
                    JxlSaver(context).save(developed, merged.refMetadata, merged.refResult,
                        captureTimeMillis = captureTimeMillis,
                        typeSuffix = settings.mergeFileSuffix(), gps = captureGps)
                    host.publishDevelopedJxlScope(developed)
                    host.reportSaveState(if (referenceDng != null) "${settings.label} MERGED+DNG • JXL" else "${settings.label} MERGED • JXL")
                } else {
                    val developed = developer.developMfsrJpeg(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, development, outputSettings, hdrWhite = merged.hdrWhite)
                    try {
                        JpegSaver(context).save(developed, merged.refMetadata,
                            merged.refResult, captureTimeMillis = captureTimeMillis,
                            typeSuffix = settings.mergeFileSuffix(), gps = captureGps,
                            description = LinearRgbDngWriter.provenanceBlock(provenance))
                        host.publishDevelopedScope(developed.bitmap)
                        host.reportSaveState(if (referenceDng != null) "${settings.label} MERGED+DNG" else "${settings.label} MERGED")
                    } finally {
                        recycleUltraHdrGainmapContents(developed)
                        developed.bitmap.recycle()
                    }
                }
            } else {
                host.reportSaveState("${settings.label} DNG SAVED")
            }
        } finally {
            // Native output (MfsrMerge): free once the DNG/JPEG tail is done.
            merged.release()
        }
    }


    private fun saveMfsrDng(
        merged: MfsrMerge.MfsrResult,
        captureTimeMillis: Long,
        captureGps: GpsLocation?,
        provenance: MergeProvenance
    ): String {
        merged.rgb.rewind()
        // C11: striped from the native merge buffer (no 150 MB heap copy)
        return LinearRgbDngSaver(context).saveLinearRgbStriped(
            merged.width, merged.height, merged.rgb.asFloatBuffer(),
            merged.refMetadata, provenance, captureId = captureTimeMillis, gps = captureGps,
            white = merged.hdrWhite
        )
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
    }
}
