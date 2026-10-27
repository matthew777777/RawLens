// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log

/**
 * Everything a single-frame save uses, captured at shutter time on the camera
 * thread (W4, docs/refactor/save-writers-study.md): the job runs later on a
 * writer thread, after the user may have changed settings or lens.
 */
internal data class RawFrameSaveInput(
    val characteristics: CameraCharacteristics,
    val orientation: Int,
    val frameMetadata: RawFrameMetadata,
    val outputFormat: CaptureFormat,
    val outputSettings: JpegOutputSettings,
    val denoise: DenoiseSettings,
    val adaptiveExposureStrength: Float,
    val sharedAdaptiveExposure: SharedAdaptiveExposure,
    val developedFormat: DevelopedFormat,
    val jxl: JxlOutputSettings,
    val focusView: Pair<Float, Float>?,
    val dngOverrides: DngMetadataOverrides,
    val dngBackend: DngWriterBackend,
    val dngCompression: DngCompression,
    val gps: GpsLocation?,
    val captureTimeMillis: Long,
    val fileNameSuffix: String?,
    val subfolder: String?,
    val captureLabel: String?,
)

/**
 * One RAW frame's save, run on a writer thread: the DNG (original or
 * AI-denoised) and, when the capture
 * format includes it, the developed JPEG or JXL. The camera Image stays owned
 * by the enclosing OwnedCaptureJob. Extracted verbatim from
 * RawCameraController.saveRawFrame (W3); algorithm stages are called, not
 * changed.
 */
internal class RawFrameSaveJob(
    private val context: Context,
    private val image: Image,
    private val result: TotalCaptureResult,
    private val input: RawFrameSaveInput,
    private val host: Host,
) {
    /** Controller services the job needs; all safe to call from a writer thread. */
    interface Host {
        /** Shared developer (lazily created; JPEG/JXL/AI work is serialized by the caller's executor). */
        fun developer(): RawDevelopmentCoordinator
        fun pendingSaves(): Int
        /** Posts a status line to the camera thread if the session is still current. */
        fun reportSaveState(message: String)
        fun publishDevelopedScope(bitmap: Bitmap)
        fun publishDevelopedJxlScope(developed: DevelopedJxl)
        fun endJpegProcessing()
    }

    fun run() {
            try {
                var dngName: String? = null
                var jpegName: String? = null
                var dngFailure: Exception? = null
                var jpegFailure: Exception? = null
                val developer = host.developer()
                val devSettings = RawDevelopmentSettings(
                    denoise = input.denoise,
                    adaptiveExposureStrength = input.adaptiveExposureStrength,
                    sharedAdaptiveExposure = input.sharedAdaptiveExposure,
                    adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(
                        input.outputSettings, input.focusView
                    )
                )
                // The AI-denoised CFA is shared by the denoised-DNG write
                // and the JPEG; null means the legacy paths below run
                // unchanged.
                val aiCfa: UnpackedRawCfa? = if (input.denoise.aiEnabled &&
                    (input.outputFormat.includesDng || input.outputFormat.includesJpeg)
                ) {
                    val aiPlane = image.planes.singleOrNull()?.buffer
                    if (aiPlane != null) {
                        developer.denoiseCaptureCfa(aiPlane, input.frameMetadata, devSettings)
                    } else null
                } else null
                val denCfa: UnpackedRawCfa? = aiCfa
                fun saveOriginalDng(): String? = try {
                    DngSaver(context).save(
                        image, input.characteristics, result, input.orientation, input.dngOverrides,
                        input.frameMetadata, input.dngBackend, captureId = input.captureTimeMillis,
                        gps = input.gps, fileNameSuffix = input.fileNameSuffix,
                        subfolder = input.subfolder, dngCompression = input.dngCompression
                    ).also {
                        Log.i(
                            LOG_TAG,
                            "DNG saved name=$it pendingSaves=${host.pendingSaves()}"
                        )
                    }
                } catch (failure: Exception) {
                    dngFailure = failure
                    Log.e(LOG_TAG, "DNG save failed", failure)
                    null
                }
                if (aiCfa != null) {
                    if (input.outputFormat.includesDng) {
                        try {
                            dngName = DngSaver(context).saveAiDenoised(
                                aiCfa, input.frameMetadata, captureId = input.captureTimeMillis,
                                fileNameSuffix = input.fileNameSuffix, gps = input.gps,
                                subfolder = input.subfolder
                            )
                            Log.i(
                                LOG_TAG,
                                "AI DNG saved name=$dngName pendingSaves=${host.pendingSaves()}"
                            )
                        } catch (failure: Exception) {
                            dngFailure = failure
                            Log.e(LOG_TAG, "AI DNG save failed", failure)
                        }
                        if (input.denoise.saveOriginalDng) {
                            val ogName = saveOriginalDng()
                            if (dngName == null) dngName = ogName
                        }
                    }
                } else if (input.outputFormat.includesDng) {
                    dngName = saveOriginalDng()
                }
                if (input.outputFormat.includesJpeg) {
                    try {
                        Log.i(
                            LOG_TAG,
                            "RAW development input timestamp=${input.frameMetadata.timestampNanos} " +
                                "frame=${input.frameMetadata.frameNumber} cfa=${input.frameMetadata.cfaPattern} " +
                                "geometry=${input.frameMetadata.bufferGeometry}" +
                                if (aiCfa != null) " aiDenoised" else ""
                        )
                        if (input.developedFormat == DevelopedFormat.JXL) {
                            val developed = if (denCfa != null) {
                                developer.developCfaJxl(
                                    denCfa, input.frameMetadata,
                                    settings = devSettings,
                                    look = input.outputSettings,
                                    jxl = input.jxl
                                )
                            } else {
                                val rawPlane = image.planes.singleOrNull()?.buffer
                                    ?: throw UnsupportedOperationException("RAW image must have one plane")
                                developer.developJxl(
                                    rawPlane, input.frameMetadata,
                                    settings = devSettings,
                                    look = input.outputSettings,
                                    jxl = input.jxl
                                )
                            }
                            jpegName = JxlSaver(context).save(
                                developed, input.frameMetadata, result,
                                captureTimeMillis = input.captureTimeMillis,
                                typeSuffix = input.fileNameSuffix,
                                gps = input.gps,
                                subfolder = input.subfolder
                            )
                            host.publishDevelopedJxlScope(developed)
                            Log.i(
                                LOG_TAG,
                                "Developed JXL saved name=$jpegName " +
                                    "pendingSaves=${host.pendingSaves()}"
                            )
                        } else {
                            val developed = if (denCfa != null) {
                                developer.developCfaJpeg(
                                    denCfa, input.frameMetadata,
                                    settings = devSettings,
                                    outputSettings = input.outputSettings
                                )
                            } else {
                                val rawPlane = image.planes.singleOrNull()?.buffer
                                    ?: throw UnsupportedOperationException("RAW image must have one plane")
                                developer.developJpeg(
                                    rawPlane, input.frameMetadata,
                                    settings = devSettings,
                                    outputSettings = input.outputSettings
                                )
                            }
                            try {
                                jpegName = JpegSaver(context).save(
                                    developed, input.frameMetadata, result,
                                    captureTimeMillis = input.captureTimeMillis,
                                    typeSuffix = input.fileNameSuffix,
                                    gps = input.gps,
                                    subfolder = input.subfolder
                                )
                                host.publishDevelopedScope(developed.bitmap)
                                Log.i(
                                    LOG_TAG,
                                    "Developed JPEG saved name=$jpegName " +
                                        "pendingSaves=${host.pendingSaves()}"
                                )
                            } finally {
                                recycleUltraHdrGainmapContents(developed)
                                developed.bitmap.recycle()
                            }
                        }
                    } catch (failure: Exception) {
                        jpegFailure = failure
                        Log.e(LOG_TAG, "RAW developed still development/save failed", failure)
                    }
                }
                val label = input.captureLabel?.let { " • $it" }.orEmpty() +
                    if (aiCfa != null) " • AI" else ""
                val shadingNote = if (
                    input.frameMetadata.lensShadingMap == null &&
                    !input.frameMetadata.lensShadingAlreadyApplied
                ) " • NO LENS MAP" else ""
                host.reportSaveState(formatSaveOutcome(
                    input.developedFormat, jpegName, dngName, jpegFailure, dngFailure, label, shadingNote
                ))
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "Capture artifact save failed", failure)
                host.reportSaveState("SAVE ERROR: ${failure.message ?: failure.javaClass.simpleName}")
            } finally {
                if (input.outputFormat.includesJpeg) host.endJpegProcessing()
            }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"

        /** Short UI status for the saved artifacts; full names stay in the log. */
        fun formatSaveOutcome(
            developedFormat: DevelopedFormat,
            jpegName: String?,
            dngName: String?,
            jpegFailure: Exception?,
            dngFailure: Exception?,
            label: String,
            shadingNote: String
        ): String {
            val failed = jpegFailure != null || dngFailure != null
            val developedToken = developedFormat.token
            val outcome = StatusText.saveOutcome(
                dngSaved = dngName != null,
                jpegSaved = jpegName != null,
                failed = failed,
                developed = developedToken
            )
            if (failed && jpegName == null && dngName == null) return outcome
            // Keep at most the first short label segment (e.g. "ZSL 1/3"); drop the rest.
            val shortLabel = label.split("•").getOrNull(1)?.trim()?.take(12)?.let { " • $it" }.orEmpty()
            val failedSuffix = when {
                jpegFailure != null && dngFailure != null -> " • $developedToken+DNG ERR"
                jpegFailure != null -> " • $developedToken ERR"
                dngFailure != null -> " • DNG ERR"
                else -> ""
            }
            return StatusText.compact(outcome + shortLabel + failedSuffix)
        }
    }
}
