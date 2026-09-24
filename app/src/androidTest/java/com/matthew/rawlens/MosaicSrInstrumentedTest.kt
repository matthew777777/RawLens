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
 * Prompt 5C on-device tests: a device-scale two-frame Mosaic SR reconstruction
 * plus a DNG file round-trip through app cache (never MediaStore). The
 * reconstruction itself is pure JVM/ART code; running it here pins ART
 * arithmetic parity and exercises real file I/O at scale.
 */
@RunWith(AndroidJUnit4::class)
class MosaicSrInstrumentedTest {
    @Test fun deviceScaleTwoFrameReconstructionIsExact() {
        val w = 256; val h = 192
        val quadsW = w / 2; val quadsH = h / 2
        fun frame(values: FloatArray, dx: Float = 0f): RawSrBayerMerge.MergeFrame {
            val precision = RawSrKernelCovariance.MatrixField(
                quadsW, quadsH, FloatArray(quadsW * quadsH * 4) { 1e-6f }
            )
            val tiles = List(quadsW * quadsH) { RawSrTileFlow(0f, 0f, dx, 0f, 0f, true) }
            return RawSrBayerMerge.MergeFrame(
                width = w, height = h, samples = values,
                sensorPattern = BayerPattern.RGGB, sensorLeft = 0, sensorTop = 0,
                precision = precision,
                flow = RawSrAlignmentField(quadsW, quadsH, 1, quadsW, quadsH, tiles),
                robustness = RawSrRobustness.FrameRobustness(
                    quadsW, quadsH, FloatArray(quadsW * quadsH) { 1f }, IntArray(quadsW * quadsH)
                )
            )
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val result = MosaicSrReconstructor.reconstruct(
            frame(FloatArray(w * h) { 0.3f }),
            listOf(frame(FloatArray(w * h) { 0.7f }))
        )
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertEquals(362, result.width)
        assertEquals(270, result.height)
        assertEquals(BayerPattern.RGGB, result.pattern)
        assertEquals(result.width * result.height, result.cfa.size)
        for (v in result.cfa) assertEquals(0.5f, v, 1e-5f)
        assertTrue(result.fallback.none { it })
        android.util.Log.i("MosaicSr5C", "256x192 two-frame reconstruction took ${elapsed}ms")
    }

    @Test fun mosaicDngFileRoundTripAtTargetScale() {
        val w = 362; val h = 270
        val samples = FloatArray(w * h) { i ->
            val x = (i % w).toFloat() / w
            (((i / w).toFloat() / h) * 0.6f + x * 0.4f).coerceIn(0f, 1f)
        }
        val image = MosaicSrCfa(w, h, BayerPattern.RGGB, samples)
        val metadata = metadata()
        val provenance = MosaicSrProvenance(
            selectedFrames = 8, acceptedFrames = 8, rejectedFrames = 0,
            referenceTimestampNs = 424242L, sourceWidth = 256, sourceHeight = 192,
            sourceCameraId = "0", lensShadingApplied = true
        )
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("mosaic_sr_", ".dng", File(cache, "rawsr-debug").apply { mkdirs() })
        try {
            FileOutputStream(file).use { MosaicSrDngWriter.write(it, image, metadata, provenance) }
            val bytes = FileInputStream(file).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x4949, buffer.getShort(0).toInt() and 0xffff)
            val tags = HashMap<Int, Int>()
            val count = buffer.getShort(8).toInt() and 0xffff
            var stripOffset = 0; var byteCount = 0
            for (i in 0 until count) {
                val p = 10 + i * 12
                val tag = buffer.getShort(p).toInt() and 0xffff
                tags[tag] = buffer.getInt(p + 8)
                if (tag == 273) stripOffset = buffer.getInt(p + 8)
                if (tag == 279) byteCount = buffer.getInt(p + 8)
            }
            assertEquals(w, tags.getValue(256))
            assertEquals(h, tags.getValue(257))
            assertEquals(32803, tags.getValue(262))
            assertEquals(w * h * 2, byteCount)
            assertEquals(stripOffset + byteCount, bytes.size)
            // Spot-check first, middle, and last triple-turned-single samples.
            for (i in listOf(0, w * h / 2, w * h - 1)) {
                assertEquals(
                    LinearRgbDngWriter.quantize(samples[i]),
                    buffer.getShort(stripOffset + i * 2).toInt() and 0xffff
                )
            }
        } finally {
            file.delete()
        }
    }

    private fun metadata() = RawFrameMetadata(
        cameraId = "0", timestampNanos = 424242L, frameNumber = 1, imageWidth = 256, imageHeight = 192,
        imageCrop = IntRectSnapshot(0, 0, 256, 192), rawPlaneCount = 1, rawPlaneRowStride = 512,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 256 to 192,
        activeArray = IntRectSnapshot(0, 0, 256, 192), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 256, 192), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = ImmutableDoubleValues(DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 }),
        colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = null, forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )
}
