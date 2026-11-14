package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class SceneNoiseTest {
    @Test fun recoversPerPlaneShotAndReadNoise() {
        val random = Random(918)
        val a = doubleArrayOf(0.0002, 0.0004, 0.0008, 0.0012)
        val b = 0.00001
        val phaseToPlane = intArrayOf(0, 1, 3, 2)
        val image = Array(512) { y -> FloatArray(512) { x ->
            // Each 4x4 patch is constant, with brightness varying across x.
            val level = 0.1 + 0.7 * (x / 4) / 127.0
            val plane = phaseToPlane[(y % 2) * 2 + x % 2]
            (level + random.nextGaussian() * sqrt(a[plane] * level + b)).toFloat()
        } }
        val (actualA, actualB) = SceneNoise.estimate(image)
        for (i in 0..3) {
            // MAD variance fits are statistical, especially the intercept.
            assertEquals(a[i], actualA[i], a[i] * 0.3)
            assertTrue(actualB[i] >= 0.0)
            assertEquals(a[i] * 0.4 + b, actualA[i] * 0.4 + actualB[i], (a[i] * 0.4 + b) * 0.2)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsClippedData() {
        SceneNoise.estimate(Array(128) { FloatArray(128) { 1f } })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroVarianceInsteadOfInventingNoise() {
        SceneNoise.estimate(Array(128) { FloatArray(128) { 0.5f } })
    }
}
