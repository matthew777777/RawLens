// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/**
 * The JPEG VF must develop with the same calibrated color the saved JPEG uses.
 * Fixture matrices are the IMG_20261007_150239_488 DNG tags (Xiaomi Redmi Note
 * 15 Pro) transposed into Camera2 API storage order.
 */
class VfCalibratedColorTest {
    companion object {
        /** DNG row-major tag order -> Camera2 frozen[column * 3 + row] storage. */
        private fun storage(vararg rowMajor: Double): ImmutableDoubleValues {
            require(rowMajor.size == 9)
            return ImmutableDoubleValues(
                doubleArrayOf(
                    rowMajor[0], rowMajor[3], rowMajor[6],
                    rowMajor[1], rowMajor[4], rowMajor[7],
                    rowMajor[2], rowMajor[5], rowMajor[8]
                )
            )
        }

        private val IDENTITY = ImmutableDoubleValues(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        )

        private fun dngMetadata(): SceneLinearColorMetadata = SceneLinearColorMetadata(
            asShotNeutral = ImmutableDoubleValues(doubleArrayOf(0.5361256545, 1.0, 0.64160401)),
            wbGains = ImmutableFloatValues(floatArrayOf(1.86523f, 1f, 1f, 1.55859f)),
            cameraCalibration1 = IDENTITY,
            cameraCalibration2 = IDENTITY,
            forwardMatrix1 = storage(
                0.6731414795, 0.1950378418, 0.09602355957,
                0.276184082, 0.8182067871, -0.09440612793,
                0.02165222168, -0.2324523926, 1.036010742
            ),
            forwardMatrix2 = storage(
                0.5744934082, 0.1840057373, 0.2057037354,
                0.1938171387, 0.7453765869, 0.06079101562,
                -0.01449584961, -0.5286865234, 1.368408203
            ),
            colorMatrix1 = storage(
                0.6667938232, -0.1588897705, -0.08573913574,
                -0.5739440918, 1.389785767, 0.1430206299,
                -0.137878418, 0.2651519775, 0.6036224365
            ),
            colorMatrix2 = storage(
                1.531463623, -0.4696044922, -0.215057373,
                -0.4762268066, 1.445327759, 0.006698608398,
                -0.07174682617, 0.2387237549, 0.2329559326
            ),
            referenceIlluminant1 = 21,
            referenceIlluminant2 = 17
        )
    }

    @Test fun `resolve matches the save-path transform for the captured DNG fixture`() {
        val metadata = dngMetadata()
        val expected = SceneLinearColorProcessor.resolve(metadata, 0.0)
        val actual = VfCalibratedColorResolver.resolve(
            metadata,
            floatArrayOf(1.86523f, 1f, 1f, 1.55859f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        assertTrue(actual.calibrated)
        assertArrayEquals(
            expected.glslColumnMajorMatrix(), actual.cameraToAcescgColumnMajor, 1e-7f
        )
        assertArrayEquals(
            expected.glslCameraWhiteNormalized(), actual.cameraWhiteNormalized, 1e-7f
        )
    }

    @Test fun `shader-side preview EV equals save-path folded exposure`() {
        // The VF multiplies camera RGB by exp2(preview EV) before its (EV-free)
        // matrix; saves fold EV into the matrix. Linearity makes them identical.
        val metadata = dngMetadata()
        val folded = SceneLinearColorProcessor.resolve(metadata, 1.5).cameraToAcescg.toDoubleArray()
        val plain = SceneLinearColorProcessor.resolve(metadata, 0.0).cameraToAcescg.toDoubleArray()
        val gain = 2.0.pow(1.5)
        for (i in folded.indices) {
            assertEquals(plain[i] * gain, folded[i], 1e-9 * kotlin.math.abs(folded[i]) + 1e-12)
        }
    }

    @Test fun `fallback maps identity HAL through sRGB to ACEScg`() {
        val actual = VfCalibratedColorResolver.fallback(
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        assertFalse(actual.calibrated)
        // SRGB_TO_ACESCG in GLSL column-major order.
        assertArrayEquals(
            floatArrayOf(
                0.613063711512f, 0.070193650692f, 0.020619704477f,
                0.339477393287f, 0.916368854813f, 0.109587044446f,
                0.047380526502f, 0.013450721344f, 0.870001252085f
            ),
            actual.cameraToAcescgColumnMajor, 1e-6f
        )
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), actual.cameraWhiteNormalized, 0f)
    }

    @Test fun `fallback folds WB gains and sanitizes a hostile HAL`() {
        val actual = VfCalibratedColorResolver.fallback(
            floatArrayOf(0.5f, 2f, 2f, 8f),
            floatArrayOf(
                Float.NaN, Float.POSITIVE_INFINITY, 0f,
                0f, Float.NEGATIVE_INFINITY, 0f,
                0f, 0f, Float.NaN
            )
        )
        assertFalse(actual.calibrated)
        assertTrue(actual.cameraToAcescgColumnMajor.all(Float::isFinite))
        assertTrue(actual.cameraWhiteNormalized.all(Float::isFinite))
        // Neutral [2, 0.5, 0.125] normalized by its peak.
        assertArrayEquals(floatArrayOf(1f, 0.25f, 0.0625f), actual.cameraWhiteNormalized, 1e-6f)
    }

    @Test fun `resolve falls back without throwing when calibration is missing`() {
        val actual = VfCalibratedColorResolver.resolve(
            SceneLinearColorMetadata(
                asShotNeutral = null, wbGains = null,
                cameraCalibration1 = null, cameraCalibration2 = null,
                forwardMatrix1 = null, forwardMatrix2 = null,
                colorMatrix1 = null, colorMatrix2 = null,
                referenceIlluminant1 = null, referenceIlluminant2 = null
            ),
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        assertFalse(actual.calibrated)
        assertTrue(actual.cameraToAcescgColumnMajor.all(Float::isFinite))
        assertTrue(actual.cameraWhiteNormalized.all(Float::isFinite))
    }

    @Test fun `cache resolves once per distinct input`() {
        val cache = VfCalibratedColorCache()
        val metadata = dngMetadata()
        val staticMats = listOf(
            metadata.cameraCalibration1?.toDoubleArray(),
            metadata.cameraCalibration2?.toDoubleArray(),
            metadata.forwardMatrix1?.toDoubleArray(),
            metadata.forwardMatrix2?.toDoubleArray(),
            metadata.colorMatrix1?.toDoubleArray(),
            metadata.colorMatrix2?.toDoubleArray()
        )
        val neutral = metadata.asShotNeutral?.toDoubleArray()
        val gainsSensor = metadata.wbGains?.toFloatArray()
        val gains = floatArrayOf(1.86523f, 1f, 1f, 1.55859f)
        val ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun resolve(currentNeutral: DoubleArray?) = cache.resolve(
            staticMats, metadata.referenceIlluminant1, metadata.referenceIlluminant2,
            currentNeutral, gainsSensor, gains, ccm
        )
        val first = resolve(neutral)
        // Same values, fresh arrays: the hit must not depend on aliasing.
        assertSame(first, resolve(neutral?.copyOf()))
        val second = resolve(doubleArrayOf(0.5, 1.0, 0.6))
        assertNotSame(first, second)
        assertTrue(second.calibrated)
    }

    @Test fun `cache copies inputs so later mutation cannot poison a hit`() {
        val cache = VfCalibratedColorCache()
        val metadata = dngMetadata()
        val staticMats = listOf(
            metadata.cameraCalibration1?.toDoubleArray(),
            metadata.cameraCalibration2?.toDoubleArray(),
            metadata.forwardMatrix1?.toDoubleArray(),
            metadata.forwardMatrix2?.toDoubleArray(),
            metadata.colorMatrix1?.toDoubleArray(),
            metadata.colorMatrix2?.toDoubleArray()
        )
        val neutral = doubleArrayOf(0.5361256545, 1.0, 0.64160401)
        val gains = floatArrayOf(1.86523f, 1f, 1f, 1.55859f)
        val ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val first = cache.resolve(staticMats, 21, 17, neutral, null, gains, ccm)
        // Mutate every caller array in place, as the per-frame snapshot does.
        staticMats.forEach { it?.fill(0.0) }
        neutral.fill(0.0)
        gains.fill(1f)
        ccm.fill(0f)
        // The poisoned arrays now differ from the frozen copies: must re-resolve
        // (into the HAL fallback) instead of returning the calibrated hit.
        val second = cache.resolve(staticMats, 21, 17, neutral, null, gains, ccm)
        assertNotSame(first, second)
        assertFalse(second.calibrated)
    }
}
