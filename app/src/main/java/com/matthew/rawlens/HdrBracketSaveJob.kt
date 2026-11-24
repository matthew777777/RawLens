// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CameraCharacteristics

/**
 * Everything an HDR-bracket save uses, captured at shutter time on the
 * camera thread (W4): the job runs later on the writer thread.
 */
internal data class HdrBracketSaveInput(
    val characteristics: CameraCharacteristics,
    val orientation: Int,
    val cameraId: String,
    val boundPhysicalId: String?,
    /** One identifier for the whole set, independent of serialization latency. */
    val captureId: Long,
    /** One fix for the whole set: every bracket shares the same geotag. */
    val gps: GpsLocation?,
    val dngBackend: DngWriterBackend,
    val dngOverrides: DngMetadataOverrides,
    val dngCompression: DngCompression,
)

/**
 * One HDR bracket's save, run on the writer thread: every bracket frame is
 * written as its own source DNG (IMG_<id>_F00.dng, _F01, ...), no merge.
 * The camera Images stay owned by the enclosing OwnedCaptureJob.
 */
internal class HdrBracketSaveJob(
    private val context: Context,
    private val pending: List<PendingHdrFrame>,
    private val input: HdrBracketSaveInput,
    private val host: RawFrameSaveJob.Host,
) {
    fun run() {
        try {
            val saver = DngSaver(context)
            val names = pending.mapIndexed { index, frame ->
                val metadata = RawFrameMetadataFactory.capture(
                    input.cameraId, frame.image, input.characteristics, frame.result,
                    input.orientation, levelOverrides = input.dngOverrides, boundPhysicalId = input.boundPhysicalId
                )
                val sensorResult = physicalCaptureResult(frame.result, input.boundPhysicalId) ?: frame.result
                saver.save(
                    frame.image, input.characteristics, sensorResult, input.orientation,
                    input.dngOverrides, metadata, input.dngBackend,
                    fileNameSuffix = hdrBracketSuffix(index),
                    captureId = input.captureId,
                    gps = input.gps,
                    dngCompression = input.dngCompression
                )
            }
            Log.i(LOG_TAG, "HDR set=${input.captureId} sources=${names.joinToString()} " +
                "sensorTimestamps=${pending.map { it.image.timestamp }}")
            host.reportSaveState("BRACKETS ×${names.size} SAVED")
        } catch (failure: OutOfMemoryError) {
            Log.e(LOG_TAG, "HDR bracket save ran out of memory", failure)
            host.reportSaveState("BRACKETS SAVE ERROR")
        } catch (failure: Exception) {
            Log.e(LOG_TAG, "HDR bracket save failed", failure)
            host.reportSaveState("BRACKETS SAVE ERROR")
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        fun hdrBracketSuffix(index: Int): String = "F%02d".format(index)
    }
}
