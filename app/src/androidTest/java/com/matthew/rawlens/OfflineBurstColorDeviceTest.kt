// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Rational
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real JSON/Camera2 boundaries: asymmetric matrices must survive CRAW replay and DNG export. */
@RunWith(AndroidJUnit4::class)
class OfflineBurstColorDeviceTest {
    private val forward = doubleArrayOf(
        .539063, .351563, .078125, .179688, .960938, -.140625, .039063, -.421875, 1.210938)
    private val color = doubleArrayOf(
        .921875, -.343750, -.109375, -.421875, 1.257813, .132813, -.132813, .281250, .398438)
    private val calibration = doubleArrayOf(1.039063, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, .976563)

    private fun transform(values: DoubleArray) = ColorSpaceTransform(
        Array(9) { Rational(Math.round(values[it] * 1_000_000).toInt(), 1_000_000) })

    private fun metadata(): RawFrameMetadata {
        val container = JSONObject().put("sensorArrangment", "rggb")
            .put("blackLevel", JSONArray(listOf(0, 0, 0, 0))).put("whiteLevel", 65535)
            .put("referenceIlluminant1", 21).put("referenceIlluminant2", 21)
        for ((name, values) in listOf("colorMatrix" to color, "forwardMatrix" to forward,
            "calibrationMatrix" to calibration)) {
            val live = transform(values)
            // Same row-major on-disk contract as CrawMetadata.container.
            for (i in 1..2) container.put("$name$i", JSONArray(List(9) {
                live.getElement(it % 3, it / 3).toDouble()
            }))
        }
        val frame = JSONObject().put("width", 4080).put("height", 3064)
            .put("timestamp", "0").put("iso", 50).put("exposureTime", 593000L)
            .put("asShotNeutral", JSONArray(listOf(.423828, 1.0, .619141)))
        return OfflineBurstMetadata.synthesize(container.toString(), frame.toString(), 0)
    }

    @Test fun replayMatchesLiveCaptureAndPreservesBlueSky() {
        val replay = metadata()
        for ((actual, values) in listOf(
            replay.colorMatrix1 to color, replay.colorMatrix2 to color,
            replay.forwardMatrix1 to forward, replay.forwardMatrix2 to forward,
            replay.cameraCalibration1 to calibration, replay.cameraCalibration2 to calibration)) {
            assertArrayEquals(transform(values).toImmutableDoubles()!!.toDoubleArray(),
                actual!!.toDoubleArray(), 0.0)
        }
        val scene = SceneLinearColorProcessor.processRgba(
            floatArrayOf((131.1188f - 64f) / 959f, (255.8094f - 64f) / 959f,
                (211.7992f - 64f) / 959f, 1f),
            SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(replay), 0.0))
        val rgb = AgxDisplayTransform.acescgToOutputLinearSrgb(scene.copyOf(3))
        assertTrue("Sky hue changed: ${rgb.contentToString()}", rgb[2] > rgb[1] && rgb[1] > rgb[0])
    }

    @Test fun mergedDngExportsOriginalRowMajorMatrices() {
        val fields = HdrPlusDngWriter.buildFields(BayerPattern.RGGB, metadata(), null,
            DngCompression.UNCOMPRESSED)
        for ((tag, expected) in listOf(50721 to color, 50722 to color,
            50964 to forward, 50965 to forward, 50723 to calibration, 50724 to calibration)) {
            val index = fields.descriptors.chunked(3).indexOfFirst { it[0] == tag }
            assertTrue("Missing matrix tag $tag", index >= 0)
            val bytes = ByteBuffer.wrap(fields.payloads[index]).order(ByteOrder.LITTLE_ENDIAN)
            val actual = DoubleArray(9) { bytes.int.toDouble() / bytes.int.toDouble() }
            assertArrayEquals("DNG tag $tag", expected, actual, 1e-6)
        }
    }
}
