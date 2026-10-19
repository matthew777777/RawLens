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

    @Test fun prepareDecodedAppliesLensShadingMap() {
        val twos = floatArrayOf(2f, 2f, 2f, 2f)
        val ordered = MfsrMerge.prepareDecoded(
            listOf(offlineFrame(0, twos), offlineFrame(1, twos), offlineFrame(2, null))
        ) ?: throw AssertionError("prepareDecoded returned null")
        assertEquals(3, ordered.size)
        val plain = 448f / 959f
        for (decoded in ordered) {
            val hasMap = decoded.source.metadata.lensShadingMap != null
            val got = decoded.cfa.values[0]
            if (hasMap) {
                assertEquals("corrected frame doubles", plain * 2f, got, 0f)
            } else {
                assertEquals("mapless frame untouched", plain, got, 1e-6f)
            }
        }
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
