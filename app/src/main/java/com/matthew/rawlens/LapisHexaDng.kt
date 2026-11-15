// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root-worker side of the 200 MP -> 50 MP version-2 path: serialises the Vulkan
 * output (linear camera RGB, uint16 normalised to the gain-corrected white level)
 * as a LinearRaw DNG through the app's TinyDNG writer. Quantisation is exact:
 * code/65535 re-quantises to the same code.
 */
object LapisHexaDng {
    const val ALGORITHM = "RawLens-Hexadeca200/v2-residual-interpolation"

    /** Built while the camera Image is still open (the factory reads its plane geometry). */
    @JvmStatic
    fun metadata(image: Image, characteristics: CameraCharacteristics, result: TotalCaptureResult,
                 exifOrientation: Int): Any =
        RawFrameMetadataFactory.capture("0", image, characteristics, result, exifOrientation)

    /** Small cross-process sidecar; matrices use the existing offline-merge contract. */
    @JvmStatic
    fun writeDevelopMetadata(file: File, metadata: Any, width: Int, height: Int) {
        val m = metadata as RawFrameMetadata
        val container = org.json.JSONObject().put("sensorArrangment", "gbrg")
            .put("blackLevel", org.json.JSONArray(listOf(0, 0, 0, 0))).put("whiteLevel", 65535)
        fun matrix(key: String, v: ImmutableDoubleValues?) {
            v?.let { container.put(key, org.json.JSONArray(List(9) { i -> it[(i % 3) * 3 + i / 3] })) }
        }
        matrix("colorMatrix1", m.colorMatrix1); matrix("colorMatrix2", m.colorMatrix2)
        matrix("calibrationMatrix1", m.cameraCalibration1); matrix("calibrationMatrix2", m.cameraCalibration2)
        matrix("forwardMatrix1", m.forwardMatrix1); matrix("forwardMatrix2", m.forwardMatrix2)
        m.referenceIlluminant1?.let { container.put("referenceIlluminant1", it) }
        m.referenceIlluminant2?.let { container.put("referenceIlluminant2", it) }
        val frame = org.json.JSONObject().put("width", width).put("height", height)
            .put("timestamp", m.timestampNanos.toString()).put("exifOrientation", m.exifOrientation)
        m.neutralColorPoint?.let { frame.put("asShotNeutral", org.json.JSONArray(it.toDoubleArray().toList())) }
        m.sensitivityIso?.let { frame.put("iso", it) }
        m.exposureTimeNanos?.let { frame.put("exposureTime", it) }
        m.aperture?.let { frame.put("aperture", it) }
        m.focalLengthMm?.let { frame.put("focalLengthMm", it) }
        m.focusDistanceM?.takeIf { it > 0f }?.let { frame.put("focusDistanceDiopters", 1f / it) }
        file.writeText(org.json.JSONObject().put("container", container).put("frame", frame).toString())
    }

    @JvmStatic
    fun write(file: File, rgb16: ByteBuffer, width: Int, height: Int, metadata: Any, timestampNs: Long, details: String) {
        val provenance = MergeProvenance(
            algorithmVersion = ALGORITHM, selectedFrames = 1, acceptedFrames = 1, rejectedFrames = 0,
            referenceTimestampNs = timestampNs, outputScale = 1, sourceCameraId = "0",
            lensShadingApplied = false, engineLabel = "200 MP 4x4 block CFA, single frame", details = details)
        val shorts = rgb16.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FileOutputStream(file).use { out ->
            LinearRgbDngWriter.writeStriped(out, width, height, metadata as RawFrameMetadata, provenance,
                captureTimeMillis = System.currentTimeMillis()) { startY, rows, rgb ->
                val base = startY * width * 3
                for (i in 0 until rows * width * 3) rgb[i] = (shorts.get(base + i).toInt() and 0xffff) / 65535f
            }
            out.fd.sync()
        }
    }
}
