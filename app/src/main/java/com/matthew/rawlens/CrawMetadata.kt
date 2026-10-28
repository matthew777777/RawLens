// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import org.json.JSONArray
import org.json.JSONObject

/** CRAW metadata spelling and nesting are part of the on-disk contract. */
internal object CrawMetadata {
    fun container(base: String, chars: CameraCharacteristics, realtime: Boolean,
                  audioRate: Int, audioChannels: Int, motion: Boolean, orientation: Int,
                  recordingType: String = "VIDEO", audioFloat: Boolean = false): String {
        val json = JSONObject(base)
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        require(cfa != null && cfa in 0..3) { "CRAW requires a Bayer sensor" }
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
        json.put("zoomRatioIsDigital", chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) != true)
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            json.put("exposureCompensationStep", it.toDouble())
        }
        // Lower-camel twin of UniqueCameraModel for readers that follow the
        // lower-camel JSON naming (PhotonCamera emits both; DNG exporters
        // copy UniqueCameraModel to tag 50708).
        json.optString("UniqueCameraModel", "").takeIf { it.isNotEmpty() }?.let {
            if (!json.has("uniqueCameraModel")) json.put("uniqueCameraModel", it)
        }
        json.put("realtimeTimestamps", realtime)
        json.put("hasMotion", motion)
        // IMU_ samples (gyro and accel) are raw Android sensor axes; readers
        // map them to the image with sensorOrientation and lensFacing.
        json.put("imuAxes", "android")
        json.put("lensFacing", if (chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT) "front" else "back")
        // Retain the old field for RawLens readers, but decoders read extraData.
        json.put("audioSampleRate", audioRate)
        val extra = json.optJSONObject("extraData") ?: JSONObject()
        extra.put("audioSampleRate", audioRate)
        extra.put("audioChannels", if (audioRate > 0) audioChannels else 0)
        if (audioRate > 0) extra.put("audioFormat", if (audioFloat) "F32" else "S16")
        extra.put("formatName", "CRAW")
        extra.put("frameRate", RawVideoRecorder.FPS)
        extra.put("recordingType", recordingType)
        extra.put("useAccurateTimestamp", realtime)
        extra.put("mediaLayout", "embedded")
        json.put("extraData", extra)
        return json.toString()
    }

    /**
     * Frame metadata with container timestamps rebased to [originNs] (the
     * first acquired frame's sensor timestamp). Readers do 32-bit ms math
     * that absolute boot-time timestamps overflow, breaking audio sync
     * entirely — so
     * every on-disk timestamp is recording-relative. The result match still uses absolute capture
     * timestamps; only the serialized strings are rebased.
     */
    fun frame(base: String, result: CaptureResult, originNs: Long): String {
        val json = JSONObject(base)
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        require(timestamp == json.getLong("timestamp")) { "Mismatched RAW capture metadata" }
        require(originNs != Long.MIN_VALUE) { "Timestamp origin not set" }
        val neutral = requireNotNull(result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)) {
            "RAW capture has no white balance metadata"
        }
        json.put("asShotNeutral", JSONArray(neutral.map { it.toDouble() }))
        json.put("exposureTime", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        json.put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
        // Optional EXIF capture facts survive a CRAW dump and later offline development.
        json.put("aperture", result.get(CaptureResult.LENS_APERTURE))
        json.put("focalLengthMm", result.get(CaptureResult.LENS_FOCAL_LENGTH))
        json.put("focusDistanceDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
        json.put("flashState", result.get(CaptureResult.FLASH_STATE))
        val controlsOff = result.get(CaptureResult.CONTROL_MODE) == CaptureResult.CONTROL_MODE_OFF
        json.put("aeMode", if (controlsOff) 0 else result.get(CaptureResult.CONTROL_AE_MODE))
        json.put("awbMode", if (controlsOff) 0 else result.get(CaptureResult.CONTROL_AWB_MODE))
        json.put("exposureCompensationSteps", result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION))
        json.put("digitalZoomRatio", result.get(CaptureResult.CONTROL_ZOOM_RATIO))
        // Rolling-shutter readout (first to last row, ns): Gyroflow's
        // frame_readout_time for rolling-shutter correction.
        result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { json.put("rollingShutterSkew", it) }
        val rel = maxOf(0L, timestamp - originNs).toString()
        json.put("metadataTimestamp", rel)
        json.put("metadataMatched", true)
        json.put("timestamp", rel)
        json.put("filename", rel)
        json.put("originalWidth", json.getInt("width"))
        json.put("originalHeight", json.getInt("height"))
        json.put("rowStride", json.getInt("width") * 2)
        json.put("pixelFormat", "raw16")
        json.put("isCompressed", true)
        return json.toString()
    }
}
