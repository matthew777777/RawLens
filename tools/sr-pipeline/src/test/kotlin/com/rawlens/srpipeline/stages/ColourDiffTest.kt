package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

class ColourDiffTest {
    private fun rggb(r: Float, g: Float, b: Float) = Array(32) { y -> FloatArray(32) { x ->
        when { y % 2 == 0 && x % 2 == 0 -> r; y % 2 == 1 && x % 2 == 1 -> b; else -> g } } }

    @Test fun neutralDifferenceIsZeroAndRoundTrips() {
        val wbR = 2f; val wbB = 1.5f
        val f = rggb(0.2f, 0.4f, 0.4f / 1.5f)          // neutral grey under this WB
        val d = ColourDiff.toRows(f, wbR, wbB)
        assertEquals(0f, d[10][10], 1e-6f)              // R site
        assertEquals(0f, d[11][11], 1e-6f)              // B site
        assertEquals(0.4f, d[10][11], 0f)               // G untouched
        val px = arrayOf(arrayOf(floatArrayOf(d[10][10], 0.4f, d[11][11])))
        ColourDiff.reconstruct(px, wbR, wbB)
        assertEquals(0.2f, px[0][0][0], 1e-6f); assertEquals(0.4f / 1.5f, px[0][0][2], 1e-6f)
    }

    @Test fun endToEndFlatColourIsPreserved() {
        val f = rggb(0.3f, 0.5f, 0.2f)
        val cfg = PipelineConfig(scale = 1f, factors = listOf(1, 2), tileSizes = listOf(8, 8),
            robustnessEnabled = false, colourDiff = floatArrayOf(1.8f, 1.4f))
        val out = EndToEnd.run(f, listOf({ f }), cfg)
        val p = out[16][16]
        assertEquals(0.3f, p[0], 1e-4f); assertEquals(0.5f, p[1], 1e-4f); assertEquals(0.2f, p[2], 1e-4f)
    }
}
