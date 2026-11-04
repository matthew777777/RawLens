// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfsrMergeTest {
    private fun cfaOf(pattern: BayerPattern, w: Int = 6, h: Int = 4): UnpackedRawCfa {
        // Color sentinels per physical site (R=10, G=2, B=3): the output
        // must read RGGB everywhere, pinning the physical layout with no
        // reference to the DNG label names.
        val values = FloatArray(w * h) { i ->
            when (pattern.colorAt(i % w, i / w)) {
                CfaColor.RED -> 10f
                CfaColor.GREEN -> 2f
                CfaColor.BLUE -> 3f
            }
        }
        return UnpackedRawCfa(w, h, pattern, values, RawCrop(0, 0, w, h))
    }

    @Test fun decodeRowsEmitsPhysicalRggb() {
        for (pattern in BayerPattern.entries) {
            val cfa = cfaOf(pattern)
            val rows = MfsrMerge.decodeRows(cfa)
            for (y in 0 until cfa.height) for (x in 0 until cfa.width) {
                val want = when {
                    y % 2 == 0 && x % 2 == 0 -> 10f
                    y % 2 == 1 && x % 2 == 1 -> 3f
                    else -> 2f
                }
                assertEquals("pattern $pattern @($x,$y)", want, rows[y][x], 0f)
            }
        }
    }

    /**
     * Camera2 SENSOR_NOISE_PROFILE is in CFA-layout order (row-major sensor
     * cell). The engine's R, G1, B, G2 noise must come from the same sensor
     * cell its pixels come from: tag each sensor cell, decode, compare.
     */
    @Test fun engineNoiseFollowsThePixelMapForEveryPattern() {
        for (pattern in BayerPattern.entries) {
            val w = 6; val h = 4
            val cfa = UnpackedRawCfa(w, h, pattern,
                FloatArray(w * h) { i -> (((i / w) and 1) * 2 + ((i % w) and 1)).toFloat() }, RawCrop(0, 0, w, h))
            val rows = MfsrMerge.decodeRows(cfa)
            // distinct (S, O) per sensor cell, CFA order
            val noise = DoubleArray(8) { if (it % 2 == 0) 1e-4 * (it / 2 + 1) else 1e-7 * (it / 2 + 1) }
            val (alpha, beta) = MfsrMerge.engineNoiseAlphaBeta(noise, pattern)!!
            // engine R (0,0), G1 (0,1), B (1,1), G2 (1,0)
            val phases = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)
            for ((k, yx) in phases.withIndex()) {
                val cell = rows[yx.first][yx.second].toInt()
                assertEquals("$pattern plane $k S", noise[2 * cell], alpha[k], 0.0)
                assertEquals("$pattern plane $k O", noise[2 * cell + 1], beta[k], 0.0)
            }
        }
    }

    @Test fun engineNoiseThreePlanesAndInvalid() {
        val three = doubleArrayOf(3e-4, 1e-7, 1.7e-4, 2e-7, 3.1e-4, 3e-7)
        val (a, b) = MfsrMerge.engineNoiseAlphaBeta(three, BayerPattern.GRBG)!!
        assertArrayEquals(doubleArrayOf(3e-4, 1.7e-4, 3.1e-4, 1.7e-4), a, 0.0)
        assertArrayEquals(doubleArrayOf(1e-7, 2e-7, 3e-7, 2e-7), b, 0.0)
        assertEquals(null, MfsrMerge.engineNoiseAlphaBeta(null, BayerPattern.RGGB))
        assertEquals(null, MfsrMerge.engineNoiseAlphaBeta(DoubleArray(5) { 1e-4 }, BayerPattern.RGGB))
        assertEquals(null, MfsrMerge.engineNoiseAlphaBeta(DoubleArray(8) { Double.NaN }, BayerPattern.RGGB))
    }

    @Test fun learnedGreenSetsLevelProfileSetsShape() {
        val profile = doubleArrayOf(4e-4, 2e-4, 6e-4, 2e-4) to doubleArrayOf(2e-6, 1e-6, 3e-6, 1e-6)
        val (a, b) = MfsrMerge.withLearnedGreen(profile, floatArrayOf(5e-5f, 2e-7f, 30f, 4f))!!
        assertArrayEquals(doubleArrayOf(1e-4, 5e-5, 1.5e-4, 5e-5), a, 1e-9)
        assertArrayEquals(doubleArrayOf(4e-7, 2e-7, 6e-7, 2e-7), b, 1e-12)
        val (a2, _) = MfsrMerge.withLearnedGreen(null, floatArrayOf(5e-5f, 2e-7f))!!
        assertArrayEquals(DoubleArray(4) { 5e-5 }, a2, 1e-9)
        assertEquals(null, MfsrMerge.withLearnedGreen(profile, null))
        assertEquals(null, MfsrMerge.withLearnedGreen(profile, floatArrayOf(0f, 1e-7f)))
    }

    @Test fun decodeRowsRejectsOddDimensions() {
        val cfa = cfaOf(BayerPattern.RGGB, w = 5, h = 4)
        assertThrows(IllegalArgumentException::class.java) { MfsrMerge.decodeRows(cfa) }
    }

    @Test fun decodeRowsRggbIsIdentity() {
        val cfa = cfaOf(BayerPattern.RGGB)
        val rows = MfsrMerge.decodeRows(cfa)
        for (y in 0 until cfa.height) {
            assertArrayEquals(cfa.values.copyOfRange(y * cfa.width, (y + 1) * cfa.width), rows[y], 0f)
        }
    }

    @Test fun mergedRgbReturnsToSensorCoordinatesForEveryBayerPattern() {
        val w = 6
        val h = 4
        for (pattern in BayerPattern.entries) {
            // Unique spatial values expose mirrors that constant color tests miss.
            val sensor = FloatArray(w * h) { it.toFloat() }
            val cfa = UnpackedRawCfa(w, h, pattern, sensor, RawCrop(0, 0, w, h))
            val normalized = MfsrMerge.decodeRows(cfa)
            val rgb = java.nio.FloatBuffer.allocate(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) for (channel in 0..2) {
                rgb.put((y * w + x) * 3 + channel, normalized[y][x] * 3 + channel)
            }
            MfsrMerge.restoreSensorCoordinates(rgb, w, h, pattern)
            for (pixel in sensor.indices) for (channel in 0..2) {
                assertEquals("$pattern pixel=$pixel channel=$channel",
                    sensor[pixel] * 3 + channel, rgb.get(pixel * 3 + channel), 0f)
            }
            assertEquals(0, rgb.position())
        }
    }

    private fun offlineMetadata(index: Int, gains: FloatArray?): RawFrameMetadata {
        // 16x16 still (black 64, white 1023); a 1x1 map covers the frame
        // with one gain per channel.
        return RawFrameMetadata(
            cameraId = "0", timestampNanos = 1000L + index, frameNumber = index.toLong(),
            imageWidth = 16, imageHeight = 16,
            imageCrop = IntRectSnapshot(0, 0, 16, 16), rawPlaneCount = 1, rawPlaneRowStride = 32,
            rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
            sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
            rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
            rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(floatArrayOf(64f, 64f, 64f, 64f)),
            blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
            whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 16 to 16,
            activeArray = IntRectSnapshot(0, 0, 16, 16), preCorrectionActiveArray = null,
            rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 16, 16), "test"),
            lensShadingAlreadyApplied = false,
            lensShadingMap = gains?.let { LensShadingSnapshot(1, 1, ImmutableFloatValues(it)) },
            hotPixels = emptyList(), wbGains = null,
            neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)),
            colorCorrectionTransform = null,
            colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
            forwardMatrix1 = null, forwardMatrix2 = null,
            referenceIlluminant1 = null, referenceIlluminant2 = null, noiseProfile = null,
            sensorPixelMode = null, rawBinningFactorUsed = false, activePhysicalCameraId = null,
            aperture = null, focalLengthMm = null, flashFired = false
        )
    }

    private fun offlineFrame(index: Int, gains: FloatArray?, level: Int = 512): OfflineBurstFrame {
        // Constant RAW level: normalized (512-64)/(1023-64) = 448/959.
        // An all-2.0 map must double every value exactly (x2 is exact
        // in float); mapless frames stay untouched.
        val w = 16
        val h = 16
        val shorts = ShortArray(w * h) { level.toShort() }
        val bytes = java.nio.ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.nativeOrder())
        bytes.asShortBuffer().put(shorts)
        bytes.rewind()
        val metadata = offlineMetadata(index, gains)
        return OfflineBurstFrame(
            bytes, w, h, w * 2, 1000L + index, 10000000L, 100,
            metadata, metadata.normalizationOrNull()
                ?: throw AssertionError("test metadata has no normalization")
        )
    }

    /** C19: frames enter the merge unshaded (raw sensor noise); shading is applied to the merged RGB. */
    @Test fun prepareDecodedLeavesShadingForAfterTheMerge() {
        val twos = floatArrayOf(2f, 2f, 2f, 2f)
        val ordered = MfsrMerge.prepareDecoded(
            listOf(offlineFrame(0, twos), offlineFrame(1, twos), offlineFrame(2, null))
        ) ?: throw AssertionError("prepareDecoded returned null")
        assertEquals(3, ordered.size)
        val plain = 448f / 959f
        for (decoded in ordered) assertEquals("frames stay unshaded", plain, decoded.cfa.values[0], 1e-6f)
    }

    @Test fun shadingAfterMergeScalesEachChannel() {
        val model = LensShadingModel(1, 1, floatArrayOf(2f, 3f, 5f, 4f), IntRectSnapshot(0, 0, 4, 4))
        val rgb = java.nio.FloatBuffer.wrap(FloatArray(4 * 4 * 3) { 0.1f })
        MfsrMerge.applyShadingAfterMerge(rgb, 4, 4, model)
        assertEquals(0.2f, rgb.get(0), 1e-6f)        // R x 2
        assertEquals(0.4f, rgb.get(1), 1e-6f)        // G x mean(3, 5)
        assertEquals(0.4f, rgb.get(2), 1e-6f)        // B x 4
        val done = java.nio.FloatBuffer.wrap(FloatArray(4 * 4 * 3) { 0.1f })
        MfsrMerge.applyShadingAfterMerge(done, 4, 4, model.copy(alreadyApplied = true))
        assertEquals(0.1f, done.get(0), 0f)
    }

    @Test fun offlineSnapshotBuildInterleavesAndValidates() {
        // Four nested R/G-even/G-odd/B planes interleave into Camera2
        // map order; shape/value defects throw (the JSON shell nulls).
        val snap = OfflineBurstMetadata.buildSnapshot(
            1, 1, listOf(listOf(2.0), listOf(3.0), listOf(4.0), listOf(5.0))
        )
        assertEquals(1, snap.rows)
        assertEquals(1, snap.columns)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), snap.gains.toFloatArray(), 0f)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBurstMetadata.buildSnapshot(1, 1, listOf(listOf(2.0), listOf(2.0), listOf(2.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineBurstMetadata.buildSnapshot(
                1, 1, listOf(listOf(0.5), listOf(2.0), listOf(2.0), listOf(2.0))
            )
        }
    }
}
