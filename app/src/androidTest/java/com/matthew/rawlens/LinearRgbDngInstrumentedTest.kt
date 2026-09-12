// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prompt 5B on-device tests: the Linear RGB prime DNG round-trips through a
 * real file at device scale, parsed back tag-by-tag. Writes go to the app
 * cache directory — never MediaStore — so the test cannot pollute the gallery.
 */
@RunWith(AndroidJUnit4::class)
class LinearRgbDngInstrumentedTest {
    @Test fun gradientRoundTripAtDeviceScale() {
        val w = 512; val h = 384
        val rgb = FloatArray(w * h * 3) { i ->
            val p = i / 3
            val x = (p % w).toFloat() / w
            val y = (p / w).toFloat() / h
            when (i % 3) {
                0 -> x * 1.2f - 0.05f // over-white headroom plus a negative foot
                1 -> y
                else -> (x + y) / 2f
            }
        }
        val bytes = writeRead(MergedLinearRgb(w, h, rgb), metadata(), provenance())
        val parsed = Parsed(bytes)
        assertEquals(w, parsed.entry(256).value)
        assertEquals(h, parsed.entry(257).value)
        assertEquals(listOf(16, 16, 16), parsed.shorts(258))
        assertEquals(34892, parsed.entry(262).value)
        assertEquals(3, parsed.entry(277).value)
        val stripOffset = parsed.entry(273).value
        val byteCount = parsed.entry(279).value
        assertEquals(w * h * 3 * 2, byteCount)
        assertEquals(stripOffset + byteCount, bytes.size)
        for (i in rgb.indices) {
            assertEquals(
                "sample $i", LinearRgbDngWriter.quantize(rgb[i]),
                parsed.u16(stripOffset + i * 2)
            )
        }
    }

    @Test fun provenanceOrientationAndScalingSurvive() {
        val w = 64; val h = 48
        val bytes = writeRead(
            MergedLinearRgb(w, h, FloatArray(w * h * 3) { 0.25f }),
            metadata().copy(exifOrientation = 6),
            provenance().copy(referenceTimestampNs = Long.MIN_VALUE, outputScale = 1)
        )
        val parsed = Parsed(bytes)
        assertEquals(6, parsed.entry(274).value)
        assertEquals(65535, parsed.entry(50717).value)
        val block = parsed.ascii(270)
        for (key in listOf(
            "algorithm=RawLens-RawSr/4D-scale1", "selectedFrames=30", "acceptedFrames=27",
            "rejectedFrames=3", "referenceTimestampNs=unknown", "outputScale=1",
            "sourceCameraId=0", "derivation=DerivedFromRawBurst"
        )) assertTrue("missing $key", block.contains(key))
        // Scaling policy is exactly the documented constant.
        assertTrue(block.contains("quantization=clamp[0,1]*65535 round-half-up"))
    }

    @Test fun nonFiniteInputIsRejectedOnDevice() {
        try {
            MergedLinearRgb(2, 2, FloatArray(12) { if (it == 5) Float.NaN else 0.1f })
            fail("NaN must not reach the writer")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- helpers ----

    private fun provenance() = MergeProvenance(
        algorithmVersion = LinearRgbDngWriter.ALGORITHM_VERSION,
        selectedFrames = 30, acceptedFrames = 27, rejectedFrames = 3,
        referenceTimestampNs = 987654321L, outputScale = 1,
        sourceCameraId = "0", lensShadingApplied = true
    )

    private fun metadata() = RawFrameMetadata(
        cameraId = "0", timestampNanos = 987654321L, frameNumber = 1, imageWidth = 512, imageHeight = 384,
        imageCrop = IntRectSnapshot(0, 0, 512, 384), rawPlaneCount = 1, rawPlaneRowStride = 1024,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 512 to 384,
        activeArray = IntRectSnapshot(0, 0, 512, 384), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 512, 384), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = ImmutableDoubleValues(DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 }),
        colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = null, forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

    private fun writeRead(
        image: MergedLinearRgb, metadata: RawFrameMetadata, provenance: MergeProvenance
    ): ByteArray {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("linear_rgb_prime_", ".dng", File(cache, "rawsr-debug").apply { mkdirs() })
        try {
            FileOutputStream(file).use { LinearRgbDngWriter.write(it, image, metadata, provenance) }
            assertTrue(file.length() > 0)
            return FileInputStream(file).use { it.readBytes() }
        } finally {
            file.delete()
        }
    }

    private class Parsed(val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data class Field(val type: Int, val count: Int, val value: Int)

        val entries: Map<Int, Field> = buildMap {
            val count = buffer.getShort(8).toInt() and 0xffff
            for (i in 0 until count) {
                val p = 10 + i * 12
                put(
                    buffer.getShort(p).toInt() and 0xffff,
                    Field(buffer.getShort(p + 2).toInt() and 0xffff, buffer.getInt(p + 4), buffer.getInt(p + 8))
                )
            }
        }

        fun entry(tag: Int) = entries.getValue(tag)
        fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff

        private fun payload(tag: Int): Int {
            val field = entry(tag)
            assertEquals(2, field.type)
            val unit = 1
            return if (field.count * unit <= 4) error("inline ascii") else field.value
        }

        fun shorts(tag: Int): List<Int> {
            val field = entry(tag)
            assertEquals(3, field.type)
            return if (field.count * 2 <= 4) {
                List(field.count) { (field.value shr (it * 16)) and 0xffff }
            } else {
                val off = field.value
                List(field.count) { u16(off + it * 2) }
            }
        }

        fun ascii(tag: Int): String {
            val field = entry(tag)
            assertEquals(2, field.type)
            val bytes = ByteArray(field.count) { buffer.get(payload(tag) + it) }
            return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        }
    }
}
