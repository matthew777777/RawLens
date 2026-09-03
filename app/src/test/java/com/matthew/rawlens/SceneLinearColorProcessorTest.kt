// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLinearColorProcessorTest {
    @Test
    fun neutralIsPreservedThroughWhiteBalanceD50D60AndAcescg() {
        val transform = SceneLinearColorProcessor.resolve(
            metadata(neutral = doubleArrayOf(0.5, 1.0, 0.25), forward1 = IDENTITY)
        )
        val output = SceneLinearColorProcessor.processRgba(
            floatArrayOf(0.5f, 1f, 0.25f, 0.3f),
            transform
        )

        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), output, 2e-4f)
        assertArrayEquals(doubleArrayOf(2.0, 1.0, 4.0), transform.whiteBalance.toDoubleArray(), 1e-9)
        assertEquals(WhiteBalanceSource.AS_SHOT_NEUTRAL, transform.whiteBalanceSource)
    }

    @Test
    fun nonSymmetricForwardMatrixProvesCamera2TransposeDirection() {
        val forward = doubleArrayOf(
            0.70, 0.20, 0.06422,
            0.10, 0.80, 0.10,
            0.02, 0.10, 0.70521
        )
        val transform = SceneLinearColorProcessor.resolve(
            metadata(neutral = ONE, forward1 = forward)
        )

        assertArrayEquals(forward, transform.cameraToXyzD50.toDoubleArray(), 1e-9)
        assertEquals(CameraToXyzPolicy.FORWARD_MATRIX, transform.cameraToXyzPolicy)
    }

    @Test
    fun d50ToD60BradfordMapsWhiteExactly() {
        val mapped = map(
            SceneLinearColorProcessor.d50ToD60Matrix(),
            SceneLinearColorProcessor.d50White()
        )
        assertArrayEquals(SceneLinearColorProcessor.d60White(), mapped, 2e-9)
    }

    @Test
    fun acescgMatrixMapsAp1PrimaryAndD60White() {
        val xyzToAces = SceneLinearColorProcessor.xyzD60ToAcescgMatrix()
        val ap1RedXyz = doubleArrayOf(0.6624541811, 0.2722287168, -0.0055746495)

        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), map(xyzToAces, ap1RedXyz), 2e-9)
        assertArrayEquals(
            doubleArrayOf(1.0, 1.0, 1.0),
            map(xyzToAces, SceneLinearColorProcessor.d60White()),
            2e-9
        )
    }

    @Test
    fun exposureIsTechnicalPowerOfTwoAndRangeStaysUnclamped() {
        val transform = SceneLinearColorProcessor.resolve(
            metadata(neutral = ONE, forward1 = IDENTITY),
            exposureEv = 1.0
        )
        val output = SceneLinearColorProcessor.processRgba(
            floatArrayOf(-0.25f, -0.25f, -0.25f, 0f, 2f, 2f, 2f, 0f),
            transform
        )

        assertArrayEquals(floatArrayOf(-0.5f, -0.5f, -0.5f, 1f), output.copyOfRange(0, 4), 2e-4f)
        assertArrayEquals(floatArrayOf(4f, 4f, 4f, 1f), output.copyOfRange(4, 8), 2e-4f)
        assertTrue(output.all(Float::isFinite))
    }

    @Test
    fun invalidForwardFallsBackToColorMatrixAndMalformedEverythingFailsClosed() {
        val fallback = SceneLinearColorProcessor.resolve(
            metadata(neutral = ONE, forward1 = doubleArrayOf(0.0, 0.0, 0.0), color1 = IDENTITY)
        )
        assertEquals(CameraToXyzPolicy.COLOR_MATRIX_BRADFORD_FALLBACK, fallback.cameraToXyzPolicy)
        assertTrue(fallback.warnings.any { "ColorMatrix" in it })

        assertThrows(UnsupportedOperationException::class.java) {
            SceneLinearColorProcessor.resolve(metadata(neutral = ONE))
        }
    }

    @Test
    fun invalidNeutralUsesGainsThenUnityWithoutNan() {
        val gains = metadata(
            neutral = doubleArrayOf(0.0, Double.NaN, 1.0),
            gains = floatArrayOf(2f, 1f, 1f, 4f),
            forward1 = IDENTITY
        )
        val fromGains = SceneLinearColorProcessor.resolve(gains)
        assertEquals(WhiteBalanceSource.CAMERA2_GAINS, fromGains.whiteBalanceSource)
        assertArrayEquals(doubleArrayOf(2.0, 1.0, 4.0), fromGains.whiteBalance.toDoubleArray(), 1e-9)

        val unity = SceneLinearColorProcessor.resolve(metadata(forward1 = IDENTITY))
        assertEquals(WhiteBalanceSource.UNITY_FALLBACK, unity.whiteBalanceSource)
        assertTrue(unity.cameraToAcescg.toDoubleArray().all(Double::isFinite))
    }

    @Test
    fun cameraCalibrationAndAnalogBalanceAreAppliedInDngOrder() {
        val calibration = doubleArrayOf(
            2.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 0.5
        )
        val transform = SceneLinearColorProcessor.resolve(
            metadata(
                neutral = ONE,
                analogBalance = doubleArrayOf(1.0, 2.0, 1.0),
                calibration1 = calibration,
                forward1 = IDENTITY
            )
        )
        val cameraToXyz = transform.cameraToXyzD50.toDoubleArray()

        // FM * D * inverse(AB * CC), with D derived after inverse(AB * CC) neutral.
        assertArrayEquals(
            doubleArrayOf(
                1.92844, 0.0, 0.0,
                0.0, 2.0, 0.0,
                0.0, 0.0, 1.65042
            ),
            cameraToXyz,
            1e-9
        )
    }

    @Test
    fun dualIlluminantPolicyIsPinnedDeterministicAndBounded() {
        val actualForward1 = doubleArrayOf(
            0.6731414795, 0.1950378418, 0.09602355957,
            0.276184082, 0.8182067871, -0.09440612793,
            0.02165222168, -0.2324523926, 1.036010742
        )
        val actualForward2 = doubleArrayOf(
            0.5744934082, 0.1840057373, 0.2057037354,
            0.1938171387, 0.7453765869, 0.06079101562,
            -0.01449584961, -0.5286865234, 1.368408203
        )
        val actualColor1 = doubleArrayOf(
            0.6667938232, -0.1588897705, -0.08573913574,
            -0.5739440918, 1.389785767, 0.1430206299,
            -0.137878418, 0.2651519775, 0.6036224365
        )
        val actualColor2 = doubleArrayOf(
            1.531463623, -0.4696044922, -0.215057373,
            -0.4762268066, 1.445327759, 0.006698608398,
            -0.07174682617, 0.2387237549, 0.2329559326
        )
        val input = metadata(
            neutral = doubleArrayOf(0.4807511866, 1.0, 0.595348835),
            calibration1 = IDENTITY,
            calibration2 = IDENTITY,
            forward1 = actualForward1,
            forward2 = actualForward2,
            color1 = actualColor1,
            color2 = actualColor2,
            illuminant1 = 21,
            illuminant2 = 17
        )
        val first = SceneLinearColorProcessor.resolve(input)
        val second = SceneLinearColorProcessor.resolve(input)

        assertTrue(first.interpolationFactor in 0.0..1.0)
        assertEquals(first.interpolationFactor, second.interpolationFactor, 0.0)
        assertArrayEquals(first.cameraToAcescg.toDoubleArray(), second.cameraToAcescg.toDoubleArray(), 0.0)
    }

    @Test
    fun cpuAndGlslColumnMajorContractAgreeWithinDocumentedTolerance() {
        val transform = SceneLinearColorProcessor.resolve(
            metadata(
                neutral = doubleArrayOf(0.7, 1.0, 0.6),
                forward1 = doubleArrayOf(
                    0.70, 0.20, 0.06422,
                    0.10, 0.80, 0.10,
                    0.02, 0.10, 0.70521
                )
            ),
            exposureEv = -0.75
        )
        val input = floatArrayOf(-0.125f, 0.75f, 3.5f)
        val cpu = SceneLinearColorProcessor.processRgba(
            floatArrayOf(input[0], input[1], input[2], 1f), transform
        )
        val glsl = SceneLinearColorProcessor.evaluateGlslContract(
            input,
            transform.glslColumnMajorMatrix()
        )

        assertArrayEquals(cpu.copyOfRange(0, 3), glsl, SceneLinearColorProcessor.CPU_GLSL_TOLERANCE)
    }

    @Test
    fun maximumResolutionColorStageAddsNoFullFrameGpuTarget() {
        val estimate = RawDevelopmentCoordinator.estimateMemory(4080, 3060)
        assertEquals(24_969_600L, estimate.rawImageBytes)
        assertEquals(49_939_200L, estimate.unpackedCfaBytes)
        assertEquals(99_878_400L, estimate.preDemosaicCfaCopiesBytes)
        assertEquals(49_939_200L, estimate.amazeUploadStagingBytes)
        assertEquals(0L, estimate.additionalSceneLinearGpuBytes)
        assertEquals(
            estimate.rawImageBytes + 2L * estimate.unpackedCfaBytes + estimate.amazeGpuBytes,
            estimate.minimumAccountedBytes
        )
        assertEquals(96L * 1024L * 1024L, estimate.jvmNativeOverheadReserveBytes)
        assertEquals(
            estimate.minimumAccountedBytes + estimate.jvmNativeOverheadReserveBytes,
            estimate.conservativePeakBytes
        )
    }

    @Test
    fun cameraWhiteIsAsShotNeutralNormalizedInSensorSpace() {
        val transform = SceneLinearColorProcessor.resolve(
            metadata(neutral = doubleArrayOf(0.5, 1.0, 0.25), forward1 = IDENTITY)
        )

        assertArrayEquals(
            floatArrayOf(0.5f, 1.0f, 0.25f),
            transform.glslCameraWhiteNormalized(),
            1e-6f
        )
    }

    @Test
    fun clippedCameraHighlightConvergesToNeutralBeforeColorMatrix() {
        val white = floatArrayOf(0.5f, 1.0f, 0.25f)

        assertArrayEquals(
            floatArrayOf(0.6f, 0.55f, 0.45f),
            SceneLinearColorProcessor.neutralizeCameraHighlight(
                floatArrayOf(0.6f, 0.55f, 0.45f), white
            ),
            1e-6f
        )
        assertArrayEquals(
            white,
            SceneLinearColorProcessor.neutralizeCameraHighlight(
                floatArrayOf(1.0f, 0.70f, 0.72f), white
            ),
            1e-6f
        )
    }

    private fun metadata(
        neutral: DoubleArray? = null,
        gains: FloatArray? = null,
        analogBalance: DoubleArray? = null,
        calibration1: DoubleArray? = null,
        calibration2: DoubleArray? = null,
        forward1: DoubleArray? = null,
        forward2: DoubleArray? = null,
        color1: DoubleArray? = null,
        color2: DoubleArray? = null,
        illuminant1: Int? = 21,
        illuminant2: Int? = 17
    ) = SceneLinearColorMetadata(
        asShotNeutral = neutral?.let(::ImmutableDoubleValues),
        wbGains = gains?.let(::ImmutableFloatValues),
        analogBalance = analogBalance?.let(::ImmutableDoubleValues),
        cameraCalibration1 = calibration1?.let(::camera2Frozen),
        cameraCalibration2 = calibration2?.let(::camera2Frozen),
        forwardMatrix1 = forward1?.let(::camera2Frozen),
        forwardMatrix2 = forward2?.let(::camera2Frozen),
        colorMatrix1 = color1?.let(::camera2Frozen),
        colorMatrix2 = color2?.let(::camera2Frozen),
        referenceIlluminant1 = illuminant1,
        referenceIlluminant2 = illuminant2
    )

    /** Encode mathematical row-major processing matrices in Camera2's frozen tag ordering. */
    private fun camera2Frozen(rowMajor: DoubleArray): ImmutableDoubleValues {
        if (rowMajor.size != 9) return ImmutableDoubleValues(rowMajor)
        return ImmutableDoubleValues(DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            rowMajor[column * 3 + row]
        })
    }

    private fun map(matrix: DoubleArray, vector: DoubleArray) = doubleArrayOf(
        matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
        matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
        matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
    )

    private companion object {
        val ONE = doubleArrayOf(1.0, 1.0, 1.0)
        val IDENTITY = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}
