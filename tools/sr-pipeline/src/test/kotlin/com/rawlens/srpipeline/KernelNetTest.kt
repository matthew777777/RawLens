package com.rawlens.srpipeline

import com.rawlens.srpipeline.stages.KernelNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelNetTest {
    @Test
    fun bundledModelParsesToTheGraphsWeightCount() {
        val w = KernelNet.bundledWeights()
        assertEquals(9635, KernelNet.weightCount)
        assertEquals(KernelNet.weightCount, w.size)
        assertTrue(w.all { it.isFinite() })
    }

    @Test
    fun rggbFlipsMatchDngReadFlipToRggb() {
        assertEquals(false to false, KernelNet.rggbFlips(intArrayOf(0, 1, 1, 2)))
        assertEquals(true to true, KernelNet.rggbFlips(intArrayOf(2, 1, 1, 0)))
        assertEquals(true to false, KernelNet.rggbFlips(intArrayOf(1, 0, 2, 1)))
        assertEquals(false to true, KernelNet.rggbFlips(intArrayOf(1, 2, 0, 1)))
    }

    @Test
    fun sigmaIsPhotonCameraKernelSigma() {
        val s = KernelNet.sigma(floatArrayOf(2e-4f, 4e-4f, 6e-4f, 9f), floatArrayOf(1e-6f, 2e-6f, 3e-6f, 9f))
        assertEquals(kotlin.math.sqrt(4e-4f * 0.5f + 2e-6f), s, 1e-9f)
    }

    @Test
    fun mapSizeIsTheStrideTwoOutput() {
        assertEquals(1020 to 765, KernelNet.mapSize(4080, 3060))
    }
}
