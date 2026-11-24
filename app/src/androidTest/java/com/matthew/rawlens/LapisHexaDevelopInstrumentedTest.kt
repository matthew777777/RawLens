// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

@RunWith(AndroidJUnit4::class)
class LapisHexaDevelopInstrumentedTest {
    @Test fun fullResolutionRootJpegWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hexaJpegCapture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = LapisRootCapture.capture(context, LapisRootProfile.Mode.HEXADECA_200,
            LapisRootCapture.Settings(400, 20000000L, 1, listOf(2f, 1f, 1f, 1.5f), 0f),
            outputFormat = CaptureFormat.JPEG_DNG)
        assertTrue(names.contains(".jpg"))
        assertTrue(names.contains(".dng"))
    }

    @Test fun workerSidecarAndReducedBayerProduceJpeg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = File(context.cacheDir, "hexa-develop-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val matrix = listOf(.67, -.15, -.08, -.57, 1.38, .14, -.13, .26, .60)
            val container = JSONObject().put("sensorArrangment", "gbrg")
                .put("blackLevel", JSONArray(listOf(64, 64, 64, 64))).put("whiteLevel", 1023)
                .put("colorMatrix1", JSONArray(matrix)).put("referenceIlluminant1", 21)
            val frame = JSONObject().put("width", 64).put("height", 48).put("timestamp", "1234")
                .put("exifOrientation", 6).put("iso", 200).put("exposureTime", 20000000L)
                .put("asShotNeutral", JSONArray(listOf(.6, 1.0, .5)))
            val original = OfflineBurstMetadata.synthesize(container.toString(), frame.toString(), 0, "0").copy(
                lensShadingMap = LensShadingSnapshot(2, 2,
                    ImmutableFloatValues(FloatArray(16) { 1f + it * .02f })),
                lensShadingAlreadyApplied = false)
            val sidecar = File(job, "develop-metadata.json")
            LapisHexaDng.writeDevelopMetadata(sidecar, original, 64, 48, 64f)
            val json = JSONObject(sidecar.readText())
            val restored = OfflineBurstMetadata.synthesize(json.getJSONObject("container").toString(),
                json.getJSONObject("frame").toString(), 0, "0")
            assertArrayEquals(original.colorMatrix1!!.toDoubleArray(), restored.colorMatrix1!!.toDoubleArray(), 0.0)
            assertArrayEquals(original.neutralColorPoint!!.toDoubleArray(), restored.neutralColorPoint!!.toDoubleArray(), 0.0)
            assertEquals(6, restored.exifOrientation)
            assertEquals(2, restored.lensShadingMap!!.rows)
            assertArrayEquals(original.lensShadingMap!!.gains.toFloatArray(),
                restored.lensShadingMap!!.gains.toFloatArray(), 0f)
            assertEquals(256f, restored.blackLevels!![0])
            assertEquals(4092f, restored.whiteLevel)
            val pixels = File(job, "develop-bayer16.bin")
            RandomAccessFile(pixels, "rw").use { io ->
                io.setLength(64L * 48 * 2)
                val values = io.channel.map(FileChannel.MapMode.READ_WRITE, 0, io.length())
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until 64 * 48) values.put(i, (500 + (i % 64) * 30).toShort())
            }
            val name = LapisHexaDevelop.save(context, job, 64, 48, System.currentTimeMillis(),
                JpegOutputSettings(), DenoiseSettings())
            assertTrue(name.endsWith(".jpg", ignoreCase = true))
            assertFalse(File(job, "develop-rgb32.tmp").exists())
            assertTrue(pixels.isFile)
        } finally { job.deleteRecursively() }
    }
}
