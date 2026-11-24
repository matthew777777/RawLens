// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming CFA DNG export for rawr Hexadeca sums + Quad Detail remosaic. */
object LapisHexaDng {
    const val ALGORITHM = "rawr-49b013db/Hexadeca-sum-Quad-Detail"

    /** Built while the camera Image is still open (the factory reads its plane geometry). */
    @JvmStatic
    fun metadata(image: Image, characteristics: CameraCharacteristics, result: TotalCaptureResult,
                 exifOrientation: Int): Any =
        RawFrameMetadataFactory.capture("0", image, characteristics, result, exifOrientation)

    /** Small cross-process sidecar; matrices use the existing offline-merge contract. */
    @JvmStatic
    fun writeDevelopMetadata(file: File, metadata: Any, width: Int, height: Int, black: Float) {
        val m = metadata as RawFrameMetadata
        val container = org.json.JSONObject().put("sensorArrangment", "gbrg")
            .put("blackLevel", org.json.JSONArray(listOf(black * 4, black * 4, black * 4, black * 4))).put("whiteLevel", 4092)
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

    internal fun fields(metadata: RawFrameMetadata, width: Int, height: Int, black: Float,
                        description: String, captureTime: Long): TiffFields {
        val base = HdrPlusDngWriter.buildFields(BayerPattern.GBRG, metadata, null, DngCompression.UNCOMPRESSED)
        return TiffFields().apply {
            base.payloads.forEachIndexed { index, bytes ->
                val tag = base.descriptors[index * 3]
                if (tag != 50714 && tag != 50717) this.tag(tag, base.descriptors[index * 3 + 1], bytes)
            }
            ascii(270, description)
            ascii(306, DngExifDirectory.dateTime(captureTime))
            rationals(50714, DoubleArray(4) { black.toDouble() * 4 })
            longs(50717, 4092)
            longs(50829, 0, 0, height, width)
            rationals(50719, doubleArrayOf(0.0, 0.0))
            rationals(50720, doubleArrayOf(width.toDouble(), height.toDouble()))
            bytes(50710, byteArrayOf(0, 1, 2))
            shorts(50711, 1)
        }
    }

    @JvmStatic
    fun write(file: File, bayer16: ByteBuffer, width: Int, height: Int, metadata: Any,
              timestampNs: Long, black: Float, phaseFit: Boolean) {
        val m = metadata as RawFrameMetadata
        require(width > 0 && height > 0 && bayer16.capacity().toLong() >= width.toLong() * height * 2)
        val time = System.currentTimeMillis()
        val description = "$ALGORITHM; source 16320x12240 RAW10, 4x4 GBRG; " +
            "exact 2x2 same-colour sums to Quad; Detail remosaic to ${width}x${height} GBRG CFA; " +
            "phase fit=$phaseFit; x4 source code scale, black=${black * 4}, white=4092; " +
            "reference timestamp=$timestampNs ns; single frame, CPU reference; " +
            "no RGB intermediate; shading/distortion calibration unavailable for this readout."
        val rowsPerStrip = 64
        FileOutputStream(file).use { out ->
            TinyDngImageWriter.write(out, width, height, 1, 16, 1, 32803, rowsPerStrip,
                fields(m, width, height, black, description, time), DngExifDirectory.fields(m, time),
                null, (height + rowsPerStrip - 1) / rowsPerStrip) { strip ->
                val y = strip * rowsPerStrip
                val bytes = ByteArray(width * minOf(rowsPerStrip, height - y) * 2)
                bayer16.duplicate().apply { position(y * width * 2); get(bytes) }
                bytes
            }
            out.fd.sync()
        }
    }
}
