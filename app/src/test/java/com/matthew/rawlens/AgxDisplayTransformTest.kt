// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgxDisplayTransformTest {
    @Test
    fun provenanceIsVersionPinned() {
        assertEquals("2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad", AgxDisplayTransform.REFERENCE_COMMIT)
        assertEquals(
            "1e3212b67f2954721a4336c68fef1904204873835896e25c9ba77f9030aa42cd",
            AgxDisplayTransform.REFERENCE_SHA256
        )
    }

    @Test
    fun neutralRampIsMonotonicAndMatchesPinnedReference() {
        val inputs = floatArrayOf(0f, 0.18f, 1f, 16f, 65_504f)
        var previous = Float.NEGATIVE_INFINITY
        inputs.forEach { input ->
            val output = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(input, input, input))
            assertTrue(output.all(Float::isFinite))
            assertTrue("neutral channel split: ${output.contentToString()}",
                output.max() - output.min() < 1e-4f)
            assertTrue("neutral ramp is not monotonic", output.average() >= previous)
            previous = output.average().toFloat()
        }
        assertRgb(
            floatArrayOf(0.21487383f, 0.21484137f, 0.21480779f),
            AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(0.18f, 0.18f, 0.18f))
        )
        // Scene-linear soft shoulder (knee 0.9, scale 0.8) compresses 16->1.7 before AgX,
        // so extreme neutrals plateau at ~0.699 instead of the pinned 0.96 white.
        assertRgb(
            floatArrayOf(0.69943166f, 0.69943148f, 0.69943166f),
            AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(16f, 16f, 16f))
        )
    }

    @Test
    fun officialBaseHighlightShoulderRollsOffMonotonicallyAndReachesAPlateau() {
        // Sample equal exposure steps through and beyond Filament AgX's +4.026069 EV ceiling.
        val inputs = floatArrayOf(0.18f, 0.36f, 0.72f, 1.44f, 2.88f, 5.76f, 11.52f, 23.04f, 46.08f)
        val outputs = inputs.map { input ->
            AgxDisplayTransform.acescgToOutputLinearSrgb(
                floatArrayOf(input, input, input),
                purityBoost = 1f
            ).average().toFloat()
        }
        outputs.zipWithNext().forEach { (lower, upper) ->
            assertTrue("highlight response reversed: $outputs", upper >= lower)
        }
        assertTrue("AgX shoulder did not compress highlights: $outputs",
            outputs[7] - outputs[6] < outputs[3] - outputs[2])
        assertTrue("AgX output exceeded its SDR display boundary: $outputs", outputs.all { it <= 1.001f })

        val plateau = AgxDisplayTransform.acescgToOutputLinearSrgb(
            floatArrayOf(65_504f, 65_504f, 65_504f),
            purityBoost = 1f
        )
        val aboveCeiling = AgxDisplayTransform.acescgToOutputLinearSrgb(
            floatArrayOf(46.08f, 46.08f, 46.08f),
            purityBoost = 1f
        )
        assertRgb(aboveCeiling, plateau, 1e-4f)
    }

    @Test
    fun saturatedPrimariesMatchPinnedReferenceBeforeFinalGamutClip() {
        // Soft shoulder compresses channel 1.0->0.994, shifting primaries slightly
        // darker vs the pinned Filament reference; midtones stay bit-exact.
        assertRgb(
            floatArrayOf(0.93440771f, 0.08021203f, 0.09488785f),
            AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(1f, 0f, 0f))
        )
        val green = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(0f, 1f, 0f))
        assertRgb(floatArrayOf(-0.20412612f, 0.67982507f, 0.01576497f), green)
        assertTrue("pre-gamut negative was clipped", green[0] < 0f)
        assertRgb(
            floatArrayOf(-0.05184369f, 0.14744416f, 0.79181671f),
            AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(0f, 0f, 1f))
        )
    }

    @Test
    fun postToneMapPurityBoostPreservesNeutralsAndIncreasesChroma() {
        val neutral = floatArrayOf(0.18f, 0.18f, 0.18f)
        val neutralBase = AgxDisplayTransform.acescgToOutputLinearSrgb(neutral, 1f)
        val neutralBoosted = AgxDisplayTransform.acescgToOutputLinearSrgb(neutral, 2f)
        assertRgb(neutralBase, neutralBoosted, 1e-4f)

        val saturated = floatArrayOf(1f, 0.1f, 0.05f)
        val base = AgxDisplayTransform.acescgToOutputLinearSrgb(saturated, 1f)
        val boosted = AgxDisplayTransform.acescgToOutputLinearSrgb(saturated, 2f)
        assertTrue(boosted.max() - boosted.min() > base.max() - base.min())
        assertTrue(boosted.all(Float::isFinite))
    }

    @Test
    fun negativeAndVeryBrightFiniteInputsCannotProduceNanOrInfinity() {
        listOf(
            floatArrayOf(-1f, 0.5f, 2f),
            floatArrayOf(-65_504f, -1f, 65_504f),
            // Maximum finite value representable by the RGBA16F scene texture.
            floatArrayOf(65_504f, 65_504f, 65_504f)
        ).forEach { input ->
            val output = AgxDisplayTransform.acescgToOutputLinearSrgb(input)
            assertTrue("non-finite output for ${input.contentToString()}", output.all(Float::isFinite))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(Float.NaN, 0f, 0f))
        }
    }

    @Test
    fun transformIsContinuousAroundLogAndSrgbBoundaries() {
        val center = floatArrayOf(0.0031308f, 0.18f, 1f)
        center.forEach { value ->
            val low = AgxDisplayTransform.acescgToOutputLinearSrgb(
                floatArrayOf(value - 1e-6f, value - 1e-6f, value - 1e-6f)
            )
            val high = AgxDisplayTransform.acescgToOutputLinearSrgb(
                floatArrayOf(value + 1e-6f, value + 1e-6f, value + 1e-6f)
            )
            assertTrue(high.indices.all { abs(high[it] - low[it]) < 1e-3f })
        }
        val oetfLow = AgxDisplayTransform.srgbOetf(0.0031308f - 1e-7f)
        val oetfHigh = AgxDisplayTransform.srgbOetf(0.0031308f + 1e-7f)
        assertTrue(abs(oetfHigh - oetfLow) < 1e-4f)
    }

    @Test
    fun sceneHighlightSoftShoulderPreservesMidtonesAndCompressesSun() {
        // Knee 0.9 / scale 0.8: C1 at knee, asymptote 1.7. Mirrors GLSL in
        // agx_srgb8.glsl and VfGpuImport tail; garden sky 0.69 untouched.
        assertEquals(0.18f, AgxDisplayTransform.compressHighlight(0.18f), 1e-6f)
        assertEquals(0.9f, AgxDisplayTransform.compressHighlight(0.9f), 1e-6f)
        assertEquals(0.99400246f, AgxDisplayTransform.compressHighlight(1f), 1e-5f)
        assertEquals(1.4846829f, AgxDisplayTransform.compressHighlight(1.95f), 1e-4f)
        assertEquals(1.7f, AgxDisplayTransform.compressHighlight(16f), 1e-4f)
        assertEquals(1.7f, AgxDisplayTransform.compressHighlight(65_504f), 1e-4f)
        var prev = Float.NEGATIVE_INFINITY
        listOf(0.18f, 0.9f, 1f, 1.95f, 16f, 65_504f).forEach {
            val v = AgxDisplayTransform.compressHighlight(it)
            assertTrue("not monotonic at $it -> $v", v >= prev)
            prev = v
        }
    }

    @Test
    fun finalBoundaryClipsOnceThenDithersDeterministicallyInEncodedSpace() {
        val outOfGamut = floatArrayOf(-0.2f, 0.18f, 1.5f)
        assertRgb(floatArrayOf(0f, 0.18f, 1f), AgxDisplayTransform.finalGamutClip(outOfGamut), 0f)
        val first = AgxDisplayTransform.quantizeSrgb8(outOfGamut, 17, 29)
        val second = AgxDisplayTransform.quantizeSrgb8(outOfGamut, 17, 29)
        assertTrue(first.contentEquals(second))
        assertEquals(0, first[0].toInt() and 0xff)
        assertEquals(255, first[2].toInt() and 0xff)
        assertTrue((first[1].toInt() and 0xff) in 117..119)
    }

    private fun assertRgb(expected: FloatArray, actual: FloatArray, tolerance: Float = CPU_TOLERANCE) {
        expected.indices.forEach { index ->
            assertEquals("channel $index", expected[index], actual[index], tolerance)
        }
    }

    private companion object {
        // Kotlin CPU reference evaluates the pinned decimal constants at Float precision.
        const val CPU_TOLERANCE = 5e-5f
    }
}
