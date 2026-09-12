package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MergedDevelopProcessorTest {
    // ---- confidence ----

    @Test fun confidenceIsRcOverAcceptedFrames() {
        assertEquals(0.5, MergedDevelopWeights.confidence(4f, 8), 1e-12)
        assertEquals(1.0, MergedDevelopWeights.confidence(8f, 8), 1e-12)
        assertEquals(0.0, MergedDevelopWeights.confidence(0f, 8), 1e-12)
    }

    @Test fun confidenceClampsAndRejectsInvalidRc() {
        assertEquals(1.0, MergedDevelopWeights.confidence(30f, 8), 1e-12)
        assertEquals(0.0, MergedDevelopWeights.confidence(-1f, 8), 1e-12)
        assertEquals(0.0, MergedDevelopWeights.confidence(Float.NaN, 8), 1e-12)
        assertEquals(0.0, MergedDevelopWeights.confidence(Float.POSITIVE_INFINITY, 8), 1e-12)
    }

    // ---- blend ----

    @Test fun blendMaxSaturatesAboveOne() {
        assertEquals(0.0, MergedDevelopWeights.blendMax(0f), 1e-12)
        assertEquals(0.2, MergedDevelopWeights.blendMax(0.2f), 1e-6)
        assertEquals(1.0, MergedDevelopWeights.blendMax(1f), 1e-12)
        assertEquals(1.0, MergedDevelopWeights.blendMax(4f), 1e-12)
    }

    @Test fun blendFallsWithSupport() {
        // No support (reference fallback): full ceiling. Full support: none.
        assertEquals(0.2, MergedDevelopWeights.blend(0f, 8, 0.2f), 1e-6)
        assertEquals(0.0, MergedDevelopWeights.blend(8f, 8, 0.2f), 1e-12)
        assertEquals(0.1, MergedDevelopWeights.blend(4f, 8, 0.2f), 1e-6)
    }

    // ---- developPixel ----

    @Test fun disabledDenoiseIsPureTransform() {
        val block = uniformBlock(0.2, 0.3, 0.4)
        val out = MergedDevelopWeights.developPixel(
            block, rc = 0f, acceptedFrames = 8, denoiseEnabled = false, strength = 1f,
            matrix = IDENTITY, cameraWhiteNormalized = ONE
        )
        assertArrayEquals(doubleArrayOf(0.2, 0.3, 0.4), out, 1e-12)
    }

    @Test fun uniformFieldIsUnchangedByDenoise() {
        val block = uniformBlock(0.2, 0.3, 0.4)
        val out = MergedDevelopWeights.developPixel(
            block, rc = 0f, acceptedFrames = 8, denoiseEnabled = true, strength = 1f,
            matrix = IDENTITY, cameraWhiteNormalized = ONE
        )
        assertArrayEquals(doubleArrayOf(0.2, 0.3, 0.4), out, 1e-12)
    }

    @Test fun fullSupportDisablesSpatialDenoise() {
        // 0.5 stays below the 0.70 neutralize ramp, isolating the denoise gate.
        val block = impulseBlock(center = doubleArrayOf(0.5, 0.0, 0.0))
        val out = MergedDevelopWeights.developPixel(
            block, rc = 8f, acceptedFrames = 8, denoiseEnabled = true, strength = 1f,
            matrix = IDENTITY, cameraWhiteNormalized = ONE
        )
        assertArrayEquals(doubleArrayOf(0.5, 0.0, 0.0), out, 1e-12)
    }

    @Test fun noSupportBlendsImpulseTowardNeighborhoodMean() {
        val block = impulseBlock(center = doubleArrayOf(0.8, 0.0, 0.0))
        val out = MergedDevelopWeights.developPixel(
            block, rc = 0f, acceptedFrames = 8, denoiseEnabled = true, strength = 1f,
            matrix = IDENTITY, cameraWhiteNormalized = ONE
        )
        // Full blend toward the 3x3 mean (center weight 4/16, rest zero):
        // 0.2 sits below the 0.70 neutralize ramp, so no white mixing fires.
        assertEquals(4.0 * 0.8 / 16.0, out[0], 1e-12)
        assertEquals(0.0, out[1], 1e-12)
        assertEquals(0.0, out[2], 1e-12)
    }

    @Test fun negativeCenterClampsButNegativeNeighborsContributeRaw() {
        val block = uniformBlock(-0.5, 0.4, 0.4)
        val out = MergedDevelopWeights.developPixel(
            block, rc = 0f, acceptedFrames = 1, denoiseEnabled = true, strength = 1f,
            matrix = IDENTITY, cameraWhiteNormalized = ONE
        )
        // Center clamps to 0; neighbors stay raw (-0.5), so the mean is
        // negative, clamps to 0, and full blend keeps 0. Mirrors the shader.
        assertEquals(0.0, out[0], 1e-12)
        assertEquals(0.4, out[1], 1e-12)
    }

    @Test fun highlightNeutralizeConvergesOnWhiteBeforeMatrix() {
        val block = uniformBlock(0.9, 0.9, 0.9)
        val white = doubleArrayOf(1.0, 0.9, 0.8)
        val out = MergedDevelopWeights.developPixel(
            block, rc = 8f, acceptedFrames = 8, denoiseEnabled = false, strength = 0f,
            matrix = IDENTITY, cameraWhiteNormalized = white
        )
        // 0.9 is deep in the smoothstep ramp: output must move toward white,
        // identically on all channels only if white were unity — here B < R.
        assertTrue(out[0] > 0.9 && out[0] <= 1.0)
        assertTrue(out[2] < out[0])
    }

    // ---- input contract ----

    @Test fun inputDefaultsToFullFrameCrop() {
        val input = MergedTextureJpegInput(
            mergedTextureId = 7, width = 64, height = 48, effectiveCountTextureId = 9,
            acceptedFrames = 4, referenceMetadata = metadata()
        )
        assertEquals(RawCrop(0, 0, 64, 48), input.crop)
    }

    @Test fun inputRejectsDeadTexturesAndBadCounts() {
        val good = MergedTextureJpegInput(
            mergedTextureId = 7, width = 64, height = 48, effectiveCountTextureId = 9,
            acceptedFrames = 4, referenceMetadata = metadata()
        )
        assertThrows(IllegalArgumentException::class.java) { good.copy(mergedTextureId = 0) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(effectiveCountTextureId = 0) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(acceptedFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(crop = RawCrop(60, 0, 8, 8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(crop = RawCrop(0, 0, 0, 8))
        }
    }

    @Test fun inputNeedsNoBayerMetadata() {
        // The entry point consumes developed camera RGB, so reference metadata
        // without any Bayer/CFA/normalization data must be acceptable: no
        // second demosaic can be hiding behind a CFA requirement.
        val bare = metadata().copy(cfaPattern = null, blackLevels = null, whiteLevel = null)
        val input = MergedTextureJpegInput(
            mergedTextureId = 7, width = 64, height = 48, effectiveCountTextureId = 9,
            acceptedFrames = 4, referenceMetadata = bare
        )
        assertNull(input.referenceMetadata.cfaPattern)
        assertNull(input.referenceMetadata.blackLevels)
    }

    @Test fun inputPreservesReferenceOrientationForExifSaver() {
        // Pixels are never rotated by this path; orientation stays EXIF-carried
        // via the same reference metadata object the caller hands to JpegSaver.
        for (orientation in listOf(1, 3, 6, 8)) {
            val input = MergedTextureJpegInput(
                mergedTextureId = 7, width = 64, height = 48, effectiveCountTextureId = 9,
                acceptedFrames = 4, referenceMetadata = metadata().copy(exifOrientation = orientation)
            )
            assertEquals(orientation, input.referenceMetadata.exifOrientation)
        }
    }

    private fun uniformBlock(r: Double, g: Double, b: Double) =
        DoubleArray(27) { i -> doubleArrayOf(r, g, b)[i % 3] }

    private fun impulseBlock(center: DoubleArray): DoubleArray {
        val block = DoubleArray(27)
        block[12] = center[0]; block[13] = center[1]; block[14] = center[2]
        return block
    }

    private fun metadata() = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 8, imageHeight = 8,
        imageCrop = IntRectSnapshot(0, 0, 8, 8), rawPlaneCount = 1, rawPlaneRowStride = 16,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 8 to 8,
        activeArray = IntRectSnapshot(0, 0, 8, 8), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 8, 8), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(doubleArrayOf(1.0, 1.0, 1.0)), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = camera2Frozen(IDENTITY), forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

    private fun camera2Frozen(rowMajor: DoubleArray) = ImmutableDoubleValues(DoubleArray(9) { index ->
        rowMajor[(index % 3) * 3 + (index / 3)]
    })

    private companion object {
        val ONE = doubleArrayOf(1.0, 1.0, 1.0)
        val IDENTITY = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}
