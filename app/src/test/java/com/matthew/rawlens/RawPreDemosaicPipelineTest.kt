// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class RawPreDemosaicPipelineTest {
    @Test
    fun lensShadingUsesCfaChannelsBilinearInterpolationAndNoClamp() {
        val input = cfa(
            width = 2,
            height = 2,
            values = floatArrayOf(-0.25f, 0.5f, 0.5f, 0.75f)
        )
        val gains = floatArrayOf(
            2f, 3f, 4f, 5f,
            4f, 5f, 6f, 7f,
            6f, 7f, 8f, 9f,
            8f, 9f, 10f, 11f
        )
        val result = LensShadingCorrector.apply(
            input,
            LensShadingModel(2, 2, gains, IntRectSnapshot(0, 0, 2, 2))
        )

        assertEquals(-0.5f, result.cfa.values[0], EPSILON) // R, top-left
        assertEquals(2.5f, result.cfa.values[1], EPSILON) // G-even, top-right
        assertEquals(4f, result.cfa.values[2], EPSILON) // G-odd, bottom-left
        assertEquals(8.25f, result.cfa.values[3], EPSILON) // B, bottom-right
        assertEquals(11f, result.saturationMap.levelAt(1, 1), EPSILON)
    }

    @Test
    fun alreadyAppliedLensShadingIsIdentity() {
        val input = cfa(2, 2, floatArrayOf(-1f, .5f, 1f, 2f))
        val result = LensShadingCorrector.apply(
            input,
            LensShadingModel(
                1, 1, floatArrayOf(2f, 2f, 2f, 2f),
                IntRectSnapshot(0, 0, 2, 2), alreadyApplied = true
            )
        )
        assertEquals(input.values.toList(), result.cfa.values.toList())
        assertEquals(1f, result.saturationMap.levelAt(0, 0), EPSILON)
    }

    @Test
    fun metadataHotPixelCoordinatesAreConvertedFromSensorToCrop() {
        val values = FloatArray(25) { .25f }
        values[2 * 5 + 2] = 5f
        val input = cfa(5, 5, values, sensorLeft = 100, sensorTop = 200)
        val stats = RawDefectCorrector.correctInPlace(
            input,
            listOf(IntPointSnapshot(102, 202))
        )
        assertEquals(.25f, input.values[12], EPSILON)
        assertEquals(1, stats.metadataDefectsCorrected)
        assertEquals(0, stats.automaticallyDetectedDefects)
    }

    @Test
    fun optionalMadDetectorCorrectsHotAndDeadOutliers() {
        val values = FloatArray(49) { .4f }
        values[2 * 7 + 2] = 2f
        values[4 * 7 + 4] = -.5f
        val input = cfa(7, 7, values)
        val stats = RawDefectCorrector.correctInPlace(
            input,
            emptyList(),
            DefectCorrectionSettings(autoDetect = true, minimumAbsoluteDeviation = .1f)
        )
        assertEquals(.4f, input.values[2 * 7 + 2], EPSILON)
        assertEquals(.4f, input.values[4 * 7 + 4], EPSILON)
        assertEquals(2, stats.automaticallyDetectedDefects)
    }

    private fun cfa(
        width: Int,
        height: Int,
        values: FloatArray,
        sensorLeft: Int = 0,
        sensorTop: Int = 0
    ) = UnpackedRawCfa(
        width,
        height,
        BayerPattern.RGGB.shifted(sensorLeft, sensorTop),
        values,
        RawCrop(0, 0, width, height),
        sensorLeft,
        sensorTop
    )

    private companion object {
        const val EPSILON = 1e-5f
    }
}
