// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import java.nio.ByteOrder

/**
 * Everything an HDR-bracket save uses, captured at shutter time on the
 * camera thread (W4): the job runs later on the writer thread.
 */
internal data class HdrBracketSaveInput(
    val characteristics: CameraCharacteristics,
    val orientation: Int,
    val cameraId: String,
    val outputFormat: CaptureFormat,
    val outputSettings: JpegOutputSettings,
    val denoise: DenoiseSettings,
    /** One identifier for the whole set, independent of serialization/development latency. */
    val captureId: Long,
    /** One fix for the whole set: every bracket shares the same geotag. */
    val gps: GpsLocation?,
    val dngBackend: DngWriterBackend,
    val dngOverrides: DngMetadataOverrides,
    val dngCompression: DngCompression,
    val developedFormat: DevelopedFormat,
    val jxl: JxlOutputSettings,
    val focusView: Pair<Float, Float>?,
    val saveEachBracket: Boolean,
    val saveDebugFrames: Boolean,
)

/**
 * One HDR bracket's save, run on the writer thread: optional per-frame
 * source DNGs, then the aligned + merged CFA (sequential-DCG trio, or the
 * translation + FlowNet + frequency-merge route), the merged DNG and the
 * developed JPEG/JXL. The camera Images stay owned by the enclosing
 * OwnedCaptureJob. Extracted verbatim from RawCameraController.saveHdrBracket
 * (A1, docs/refactor/controller-remaining-plan.md); algorithm stages are
 * called, not changed.
 */
internal class HdrBracketSaveJob(
    private val context: Context,
    private val pending: List<PendingHdrFrame>,
    private val input: HdrBracketSaveInput,
    private val host: RawFrameSaveJob.Host,
) {
    fun run() {
        val c = input.characteristics
        val orientation = input.orientation
        val cameraId = input.cameraId
        val outputFormat = input.outputFormat
        val outputSettings = input.outputSettings
        val denoise = input.denoise
        val captureId = input.captureId
        val captureGps = input.gps
        val captureDngBackend = input.dngBackend
        val captureDngOverrides = input.dngOverrides
        val captureDngCompression = input.dngCompression
        val captureDevelopedFormat = input.developedFormat
        val captureJxlSettings = input.jxl
        val captureFocusView = input.focusView
        val saveEachBracket = input.saveEachBracket
        val saveDebugFrames = input.saveDebugFrames
        try {
            if (saveEachBracket || saveDebugFrames) {
                val saver = DngSaver(context)
                val backend = captureDngBackend
                val overrides = captureDngOverrides
                val names = pending.mapIndexed { index, frame ->
                    val metadata = RawFrameMetadataFactory.capture(
                        cameraId, frame.image, c, frame.result, orientation
                    )
                    // The same Image is unpacked for merging next. Do not let a writer
                    // backend's buffer cursor changes alter the RAW plane origin.
                    val buffer = frame.image.planes.single().buffer
                    val position = buffer.position()
                    val limit = buffer.limit()
                    try {
                        saver.save(
                            frame.image, c, frame.result, orientation, overrides, metadata, backend,
                            fileNameSuffix = hdrBracketSuffix(index),
                            captureId = captureId,
                            gps = captureGps,
                            dngCompression = captureDngCompression
                        )
                    } finally {
                        buffer.limit(limit)
                        buffer.position(position)
                    }
                }
                Log.i(LOG_TAG, "HDR set=$captureId sources=${names.joinToString()} " +
                    "sensorTimestamps=${pending.map { it.image.timestamp }}")
                host.reportSaveState("HDR ×${names.size} SAVED")
                if (saveEachBracket) return
            }
            val snapshots = pending.map { frame ->
                val metadata = RawFrameMetadataFactory.capture(cameraId, frame.image, c, frame.result, orientation)
                val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                    ?: throw UnsupportedOperationException("HDR RAW geometry is not supported")
                val normalization = metadata.normalizationOrNull()
                    ?: throw UnsupportedOperationException("HDR normalization metadata is missing")
                val plane = frame.image.planes.single()
                val unpacked = RawPackedUnpacker.unpackFrameNormalized(
                    plane.buffer, metadata.imageWidth, metadata.imageHeight,
                    plane.rowStride, plane.pixelStride, frame.image.format, normalization,
                    geometry.sensorOriginX, geometry.sensorOriginY,
                    geometry.processingCrop, ByteOrder.nativeOrder())
                // Darktable consumes rawprepare output: black subtraction/white normalization
                // only. Lens gains before merging would corrupt the saturation envelope.
                metadata.rawDevelopmentUnsupportedReason?.let { error(it) }
                val prepared = unpacked
                // Darktable merge falls back to f/22, 8mm when EXIF is missing.
                val aperture = frame.result.get(CaptureResult.LENS_APERTURE)
                    ?.takeIf { it.isFinite() && it > 0f } ?: HdrRawMerge.FALLBACK_APERTURE
                Triple(metadata, frame.result, HdrMergeFrame(prepared,
                    metadata.exposureTimeNanos ?: error("HDR exposure time missing"),
                    metadata.sensitivityIso ?: error("HDR ISO missing"), aperture,
                    focalLength = frame.result.get(CaptureResult.LENS_FOCAL_LENGTH)
                        ?.takeIf { it.isFinite() && it > 0f } ?: HdrRawMerge.FALLBACK_FOCAL_LENGTH,
                    noiseModel = CfaNoiseModel.from(metadata.noiseProfile)))
            }
            // Use the bracket's neutral/middle exposure as the geometric reference. Sorting
            // by actual exposure remains correct if the camera clamps one requested shutter.
            val exposures = snapshots.map {
                it.third.exposureTimeNanos.toDouble() * it.third.sensitivityIso
            }
            // Sequential-DCG trio (0, -2, 0 at fixed ISO) takes the
            // lightweight local-alignment path; every other bracket keeps
            // the legacy translation + FlowNet + Wiener route below.
            val seqFrames = snapshots.map { it.third }
            val seqTrio = HdrSeqMerge.isTrio(seqFrames)
            var referenceIndex = HdrBracketConfig.referenceIndex(exposures)
            var evRelative = HdrBracketConfig.evRelativeToReference(
                exposures.map { it as Double? }, referenceIndex)
            val mergedRaw: UnpackedRawCfa
            val dropped = ArrayList<Pair<Int, Double>>()
            if (seqTrio) {
                val seq = HdrSeqMerge.mergeTrio(seqFrames)
                referenceIndex = seq.refIndex
                evRelative = HdrBracketConfig.evRelativeToReference(
                    exposures.map { it as Double? }, referenceIndex)
                mergedRaw = seq.cfa
                for (i in seqFrames.indices) {
                    val ev = evRelative.getOrNull(i)
                    val evTag = if (ev == null) "?" else "%+.1f".format(ev)
                    val verdict = if (i == referenceIndex) "Reference(sharpest 0EV)" else "Accepted"
                    Log.i(LOG_TAG, "SEQ " + HdrBracketConfig.frameLogLine(
                        i, ev?.let { kotlin.math.round(it).toInt() } ?: 0, verdict,
                        "ev=$evTag", isReference = i == referenceIndex))
                }
                Log.i(LOG_TAG, "SEQ timing align=${seq.timings.alignMs}ms " +
                    "merge=${seq.timings.mergeMs}ms rescue=${seq.timings.rescueMs}ms " +
                    "kept=${"%.1f".format(seq.keptFraction * 100)}% " +
                    "rescued=${"%.1f".format(seq.rescuedFraction * 100)}% " +
                    "${mergedRaw.width}x${mergedRaw.height}")
            } else {
            val aligner = HdrFlowNetAligner(context)
            val reference = snapshots[referenceIndex].third
            // Reference model input rendered once (raw); per-frame exposure
            // matching is a cheap linear pass inside the loop.
            val rawRef = aligner.renderRawInput(reference.cfa)
            val tAlign0 = SystemClock.elapsedRealtime()
            val aligned = snapshots.mapIndexed { index, item ->
                if (index == referenceIndex) item.third
                else {
                    // HDR+-style fast translation always runs (no native deps), so a
                    // bracket stays aligned even when FlowNet is unavailable/rejected.
                    // FlowNet is kept as the dense refinement on top when valid.
                    val shift = runCatching {
                        HdrBracketAligner.estimateShift(reference, item.third)
                    }.getOrNull()
                    val shiftTimings = HdrBracketAligner.lastTimings
                    Log.i(LOG_TAG, "HDR shift frame=$index " +
                        "proxy=${shiftTimings.proxyMs}ms " +
                        "coarse=${shiftTimings.coarseMs}ms " +
                        "fine=${shiftTimings.fineMs}ms shift=$shift")
                    // Coarse-to-fine with a single interpolating resample: the
                    // pre-shift is an even-integer pure reindex (lossless, no
                    // smoothing), FlowNet estimates the residual incl. the
                    // sub-pixel remainder, and the merge warps once.
                    val even = shift?.let { HdrBracketAligner.snapEven(it) }
                    val preShifted = if (even != null) item.third.copy(
                        cfa = HdrBracketAligner.warpShiftedEven(item.third.cfa, even)
                    ) else item.third
                    val base = aligner.scaleInput(
                        rawRef, aligner.exposureMatchScale(preShifted, reference))
                    val dense = aligner.alignWithBase(base, preShifted)
                    val flowTimings = aligner.lastTimings
                    Log.i(LOG_TAG, "HDR flow frame=$index " +
                        "render=${flowTimings.renderMs}ms " +
                        "infer=${flowTimings.inferMs}ms " +
                        "check=${flowTimings.checkMs}ms " +
                        "accepted=${dense != null}")
                    // A non-null coarse shift must not hide a rejected
                    // dense refinement: retain the measured subpixel part.
                    val flow = HdrBracketAligner.refinedFlow(shift, dense)
                    // No field at all; merge the frame unwarped (identity)
                    // rather than aborting the bracket.
                    if (flow != null) item.third.copy(flow = flow) else item.third
                }
            }
            val tMerge0 = SystemClock.elapsedRealtime()
            // The legacy frequency-domain merge is the production path.
            val legacyOptions = HdrRawMerge.Options(alternateDropDcRejectFrac = 0.9)
            val onDropped: (Int, HdrTileDeghost.TileStats) -> Unit =
                { index, stats -> dropped.add(index to stats.dcRejectFrac) }
            mergedRaw = HdrRawMerge.merge(aligned, referenceIndex, legacyOptions, onDropped)
            // Raymerge-style F-list: one line per frame with its EV,
            // reference anchor, and merge verdict (dropped = fully ghosted
            // alternate collapsed to the reference during deghost).
            for (i in aligned.indices) {
                val ev = evRelative.getOrNull(i)
                val evTag = if (ev == null) "?" else "%+.1f".format(ev)
                val verdict = if (i == referenceIndex) "Reference"
                else dropped.find { it.first == i }?.let {
                    "DROPPED (ghost reject=${"%.0f".format(it.second * 100)}%)"
                } ?: "Accepted"
                Log.i(LOG_TAG, "HDR " + HdrBracketConfig.frameLogLine(
                    i, ev?.let { kotlin.math.round(it).toInt() } ?: 0, verdict,
                    "ev=$evTag", isReference = i == referenceIndex))
            }
            Log.i(LOG_TAG, "HDR timing align=${tMerge0 - tAlign0}ms " +
                "merge=${SystemClock.elapsedRealtime() - tMerge0}ms " +
                "frames=${aligned.size} dropped=${dropped.size} " +
                "${reference.cfa.width}x${reference.cfa.height}")
            }
            // Bake reference lens correction once, after saturation weighting. Float DNG
            // carries these corrected pixels and must not carry a second gain-map opcode.
            val merged = requireNotNull(RawPreDemosaicPipeline.process(
                mergedRaw, snapshots[referenceIndex].first, PreDemosaicSettings()).cfa)
            var referenceDng: String? = null
            val developer = host.developer()
            // Merged CFA is lens-corrected normalized data: the exact AI
            // training domain. Denoise once here and share the result
            // between the merged-DNG write and the JPEG below.
            val effectiveMerged = if (denoise.aiEnabled) {
                developer.denoiseCfa(merged, snapshots[referenceIndex].first) ?: merged
            } else merged
            // Shortest-frame normalization compensation, shared by the JPEG
            // develop and the DNG BaselineExposure tag below.
            val bracketExposures = snapshots.map {
                it.third.exposureTimeNanos.toDouble() * it.third.sensitivityIso
            }.sorted()
            val displayEv = kotlin.math.ln(
                bracketExposures[bracketExposures.size / 2] / bracketExposures.first()
            ) / kotlin.math.ln(2.0)
            if (outputFormat.includesDng || saveDebugFrames) {
                val evList = evRelative.joinToString(",") {
                    if (it == null) "?" else "%+.1f".format(it)
                }
                val dropList = if (dropped.isEmpty()) "none"
                else dropped.joinToString(",") { (index, frac) ->
                    "F$index(reject=${"%.0f".format(frac * 100)}%)"
                }
                referenceDng = DngSaver(context).saveMerged(
                    effectiveMerged, snapshots[referenceIndex].first, captureId, gps = captureGps,
                    baselineExposureEv = displayEv, dngCompression = captureDngCompression
                )
            }
            if (outputFormat.includesJpeg) {
                // The HDR develop caps the shoulder (see developMergedJpeg):
                // the meter must use the capped value so it sees what AgX sees.
                if (captureDevelopedFormat == DevelopedFormat.JXL) {
                    val developed = developer.developMergedJxl(effectiveMerged, snapshots[referenceIndex].first,
                        RawDevelopmentSettings(denoise = denoise, exposureEv = displayEv,
                            hdrAdaptiveTuning = HdrAdaptiveTuning(
                                highlightShoulder = minOf(
                                    outputSettings.highlightShoulder,
                                    RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER
                                ),
                                focusX = captureFocusView?.first,
                                focusY = captureFocusView?.second
                            )), look = outputSettings, jxl = captureJxlSettings)
                    val name = JxlSaver(context).save(developed,
                        snapshots[referenceIndex].first, snapshots[referenceIndex].second,
                        captureTimeMillis = captureId,
                        typeSuffix = CaptureFileNames.TYPE_HDR, gps = captureGps)
                    host.publishDevelopedJxlScope(developed)
                    host.reportSaveState(if (referenceDng != null) "HDR MERGED+DNG • JXL" else "HDR MERGED • JXL")
                } else {
                    val developed = developer.developMergedJpeg(effectiveMerged, snapshots[referenceIndex].first,
                        RawDevelopmentSettings(denoise = denoise, exposureEv = displayEv,
                            hdrAdaptiveTuning = HdrAdaptiveTuning(
                                highlightShoulder = minOf(
                                    outputSettings.highlightShoulder,
                                    RawDevelopmentCoordinator.HDR_HIGHLIGHT_SHOULDER
                                ),
                                focusX = captureFocusView?.first,
                                focusY = captureFocusView?.second
                            )), outputSettings)
                    try {
                        val name = JpegSaver(context).save(developed, snapshots[referenceIndex].first,
                            snapshots[referenceIndex].second, captureTimeMillis = captureId,
                            typeSuffix = CaptureFileNames.TYPE_HDR, gps = captureGps)
                        host.publishDevelopedScope(developed.bitmap)
                        host.reportSaveState(if (referenceDng != null) "HDR MERGED+DNG" else "HDR MERGED")
                    } finally {
                        recycleUltraHdrGainmapContents(developed)
                        developed.bitmap.recycle()
                    }
                }
            } else {
                host.reportSaveState("HDR DNG SAVED")
            }
        } catch (failure: OutOfMemoryError) {
            // A full-resolution HDR bracket is close to the managed-heap ceiling on some
            // devices. Always release the owned Images and restore capture state instead of
            // allowing the writer thread's Error to terminate the whole application.
            Log.e(LOG_TAG, "HDR bracket output ran out of memory", failure)
            host.reportSaveState("HDR SAVE ERROR")
        } catch (failure: Exception) {
            Log.e(LOG_TAG, "HDR bracket output failed", failure)
            host.reportSaveState("HDR SAVE ERROR")
        } finally {
            if (!saveEachBracket && outputFormat.includesJpeg) host.endJpegProcessing()
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        fun hdrBracketSuffix(index: Int): String = "F%02d".format(index)
    }
}
