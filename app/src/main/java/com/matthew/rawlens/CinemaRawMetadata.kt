// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import org.json.JSONArray
import org.json.JSONObject

/** MotionCam's metadata spelling and nesting are part of the on-disk contract. */
internal object CinemaRawMetadata {
    fun container(base: String, chars: CameraCharacteristics, realtime: Boolean,
                  audioRate: Int, motion: Boolean, orientation: Int): String {
        val json = JSONObject(base)
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        require(cfa != null && cfa in 0..3) { "MCRAW requires a Bayer sensor" }
        json.put("sensorArrangment", arrayOf("rggb", "grbg", "gbrg", "bggr")[cfa])
        val black = requireNotNull(chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN))
        json.put("blackLevel", JSONArray(List(4) { black.getOffsetForIndex(it % 2, it / 2) }))
        json.put("whiteLevel", requireNotNull(chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)))
        fun matrix(name: String, value: ColorSpaceTransform?) {
            if (value != null) json.put(name, JSONArray(List(9) {
                value.getElement(it % 3, it / 3).toDouble()
            }))
        }
        matrix("colorMatrix1", chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))
        matrix("colorMatrix2", chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))
        matrix("forwardMatrix1", chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
        matrix("forwardMatrix2", chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
        matrix("calibrationMatrix1", chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        matrix("calibrationMatrix2", chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
        json.put("referenceIlluminant1", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
        json.put("referenceIlluminant2", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2))
        json.put("sensorOrientation", orientation)
        json.put("realtimeTimestamps", realtime)
        json.put("hasMotion", motion)
        json.put("gyroMapperVersion", GyroCameraFrameMapper.MAPPER_VERSION)
        // Retain the old field for RawLens readers, but decoders read extraData.
        json.put("audioSampleRate", audioRate)
        val extra = json.optJSONObject("extraData") ?: JSONObject()
        extra.put("audioSampleRate", audioRate)
        extra.put("audioChannels", if (audioRate > 0) 1 else 0)
        extra.put("formatName", "MediaCinemaRAW")
        extra.put("frameRate", RawVideoRecorder.FPS)
        extra.put("recordingType", "VIDEO")
        extra.put("useAccurateTimestamp", realtime)
        extra.put("mediaLayout", "embedded")
        json.put("extraData", extra)
        return json.toString()
    }

    fun frame(base: String, result: CaptureResult): String {
        val json = JSONObject(base)
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        require(timestamp == json.getLong("timestamp")) { "Mismatched RAW capture metadata" }
        val neutral = requireNotNull(result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)) {
            "RAW capture has no white balance metadata"
        }
        json.put("asShotNeutral", JSONArray(neutral.map { it.toDouble() }))
        json.put("exposureTime", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        json.put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
        json.put("metadataTimestamp", timestamp.toString())
        json.put("metadataMatched", true)
        json.put("timestamp", timestamp.toString())
        json.put("filename", timestamp.toString())
        json.put("originalWidth", json.getInt("width"))
        json.put("originalHeight", json.getInt("height"))
        json.put("rowStride", json.getInt("width") * 2)
        json.put("pixelFormat", "raw16")
        json.put("isCompressed", true)
        return json.toString()
    }
}
