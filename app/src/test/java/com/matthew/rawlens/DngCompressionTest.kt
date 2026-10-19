// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class DngCompressionTest {
    @Test
    fun unknownPreferenceStaysUncompressed() {
        assertEquals(DngCompression.UNCOMPRESSED, DngCompression.fromPreference(null))
        assertEquals(DngCompression.UNCOMPRESSED, DngCompression.fromPreference("UNKNOWN"))
        assertEquals(DngCompression.JXL, DngCompression.fromPreference("jxl"))
    }

    @Test
    fun defaultVersionStays14With13Backward() {
        val tags = TinyDngMetadata.create(
            metadata(), DngMetadataOverrides(), BayerPattern.RGGB,
            doubleArrayOf(0.0, 0.0, 0.0, 0.0), 1023, RawCrop(0, 0, 4000, 3000)
        )
        assertEquals(listOf<Byte>(1, 4, 0, 0), payload(tags, 50706).toList())
        assertEquals(listOf<Byte>(1, 3, 0, 0), payload(tags, 50707).toList())
    }

    @Test
    fun jxlPathWritesDng17() {
        // DNG 1.7.1 spec: main IFD at Compression 52546 mandates
        // DNGVersion and DNGBackwardVersion >= 1.7.0.0.
        val tags = TinyDngMetadata.create(
            metadata(), DngMetadataOverrides(), BayerPattern.RGGB,
            doubleArrayOf(0.0, 0.0, 0.0, 0.0), 1023, RawCrop(0, 0, 4000, 3000),
            dng17 = true
        )
        assertEquals(listOf<Byte>(1, 7, 0, 0), payload(tags, 50706).toList())
        assertEquals(listOf<Byte>(1, 7, 0, 0), payload(tags, 50707).toList())
    }

    private fun payload(tags: TinyDngMetadata, id: Int): ByteArray {
        val index = tags.descriptors.chunked(3).indexOfFirst { it[0] == id }
        if (index < 0) error("Missing tag $id")
        return tags.payloads[index]
    }

    private fun metadata() = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 4000, imageHeight = 3000,
        imageCrop = IntRectSnapshot(0, 0, 4000, 3000), rawPlaneCount = 1, rawPlaneRowStride = 8000,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 4000 to 3000,
        activeArray = IntRectSnapshot(0, 0, 4000, 3000), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 8, 8), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        forwardMatrix2 = null, referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null,
        sensorPixelMode = null, rawBinningFactorUsed = false, activePhysicalCameraId = "wide",
        aperture = 2.8f, focalLengthMm = 4.3f, flashFired = false
    )
}
