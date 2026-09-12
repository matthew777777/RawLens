// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prompt 5D on-device integration: the real CPU mosaic chain over synthetic
 * Bayer buffers through reconstruction into parsed DNG bytes, and the real
 * GPU Bayer-direct merge through float readback into parsed DNG bytes. No
 * camera and no MediaStore involved; app cache is unused — everything stays
 * in memory.
 */
@RunWith(AndroidJUnit4::class)
class RawSrMergeJobInstrumentedTest {
    @Test fun cpuMosaicChainToParsedDng() {
        val w = 128; val h = 96
        val ref = packed(w, h, seed = 11)
        val mov = packed(w, h, seed = 11)
        val chain = RawSrMergeJob.mosaicChain(
            listOf(
                RawSrMergeJob.MosaicInput(ref.first, ref.second),
                RawSrMergeJob.MosaicInput(mov.first, mov.second)
            ),
            0,
            RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 3)
        )
        assertEquals(listOf(1), chain.survivorIndices)
        val result = MosaicSrReconstructor.reconstruct(chain.reference, chain.moving)
        assertEquals(result.width * result.height, result.cfa.size)
        val provenance = MosaicSrProvenance(
            selectedFrames = 2, acceptedFrames = 2, rejectedFrames = 0,
            referenceTimestampNs = 100L, sourceWidth = w, sourceHeight = h,
            sourceCameraId = "0", lensShadingApplied = true
        )
        val bytes = ByteArrayOutputStream().also {
            MosaicSrDngWriter.write(
                it, MosaicSrCfa(result.width, result.height, result.pattern, result.cfa),
                ref.second, provenance
            )
        }.toByteArray()
        val tags = parseTags(bytes)
        assertEquals(result.width, tags.getValue(256))
        assertEquals(result.height, tags.getValue(257))
        assertEquals(32803, tags.getValue(262))
        assertEquals(1, tags.getValue(277))
        assertEquals(65535, tags.getValue(50717))
    }

    @Test fun gpuMergeToParsedLinearDng() {
        val w = 64; val h = 48
        val ref = packed(w, h, seed = 7)
        val mov = packed(w, h, seed = 7)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31RawSrProcessor(context).use { processor ->
            processor.processPacked(listOf(ref.first, mov.first)) { output ->
                // The merge session's context is current inside the callback;
                // querying extensions anywhere earlier would run context-less.
                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
                assumeTrue("float FBO readback unavailable", "GL_EXT_color_buffer_float" in extensions)
                assertTrue("merge kept ${output.acceptedFrames}", output.acceptedFrames >= 1)
                val rgba = RawSrMergeJob.readRgbaFloat(output.mergedTextureId, output.width, output.height)
                assertEquals(output.width * output.height * 4, rgba.size)
                assertTrue(rgba.all { it.isFinite() })
                val bytes = ByteArrayOutputStream().also {
                    LinearRgbDngWriter.write(
                        it,
                        MergedLinearRgb(output.width, output.height, MergedLinearRgb.toTriplets(rgba)),
                        ref.second,
                        MergeProvenance(
                            LinearRgbDngWriter.ALGORITHM_VERSION,
                            selectedFrames = 2, acceptedFrames = output.acceptedFrames,
                            rejectedFrames = 2 - output.acceptedFrames,
                            referenceTimestampNs = 100L, outputScale = 1,
                            sourceCameraId = "0", lensShadingApplied = true
                        )
                    )
                }.toByteArray()
                val tags = parseTags(bytes)
                assertEquals(output.width, tags.getValue(256))
                assertEquals(output.height, tags.getValue(257))
                assertEquals(34892, tags.getValue(262))
            }
        }
    }

    // ---- synthetic Bayer buffers ----

    private fun packed(w: Int, h: Int, seed: Int): Pair<RawSrPackedFrame, RawFrameMetadata> {
        val metadata = metadataFor(w, h)
        val random = kotlin.random.Random(seed)
        val codes = ShortArray(w * h) { (100 + random.nextInt(800)).toShort() }
        val buffer = ByteBuffer.allocateDirect(codes.size * 2).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(codes)
        return RawSrPackedFrame.fromMetadata(buffer, metadata) to metadata
    }

    private fun metadataFor(w: Int, h: Int) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = w, imageHeight = h,
        imageCrop = IntRectSnapshot(0, 0, w, h), rawPlaneCount = 1, rawPlaneRowStride = w * 2,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = w to h,
        activeArray = IntRectSnapshot(0, 0, w, h), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, w, h), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = ImmutableDoubleValues(DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 }),
        colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = null, forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null,
        noiseProfile = ImmutableDoubleValues(doubleArrayOf(2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6)),
        sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

    private fun parseTags(bytes: ByteArray): Map<Int, Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, buffer.getShort(0).toInt() and 0xffff)
        val count = buffer.getShort(8).toInt() and 0xffff
        return buildMap {
            for (i in 0 until count) {
                val p = 10 + i * 12
                put(buffer.getShort(p).toInt() and 0xffff, buffer.getInt(p + 8))
            }
        }
    }
}
