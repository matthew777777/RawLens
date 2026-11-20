package com.rawlens.srpipeline.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureTransferTest {
    private val t = Sr2xTruthTest()
    private val truth = Array(512) { i -> Array(512) { j -> val v = t.box(j.toDouble(), i.toDouble()).toFloat(); floatArrayOf(v, v, v) } }

    @Test fun identityIsUnitTransfer() {
        for (b in TextureTransfer.measure(truth, truth, 1, 360, 40, 128)) {
            assertEquals("$b", 1.0, b.transfer, 1e-9); assertTrue("$b", b.errDb < -100)
        }
    }

    @Test fun blurLosesHighBandsAndNoiseRaisesErrorNotTransfer() {
        val blurred = Array(512) { i -> Array(512) { j -> FloatArray(3) { c ->
            var s = 0f; for (dy in -1..1) for (dx in -1..1) s += truth[(i + dy).coerceIn(0, 511)][(j + dx).coerceIn(0, 511)][c]; s / 9 } } }
        val b = TextureTransfer.measure(blurred, truth, 1, 360, 40, 128)
        assertTrue("$b", b.first().transfer > 0.9 && b.last().transfer < 0.3)
        val rng = java.util.Random(1)
        val noisy = Array(512) { i -> Array(512) { j -> FloatArray(3) { truth[i][j][it] + 0.01f * rng.nextGaussian().toFloat() } } }
        val n = TextureTransfer.measure(noisy, truth, 1, 360, 40, 128)
        // The scene's 160-wave texture is too sparse to measure under noise
        // (the bench uses its own dense patch); here only that noise raises
        // the error, not the transfer, in the well-populated low band.
        assertEquals("$n", 1.0, n.first().transfer, 0.05)
        assertTrue("$n", n.last().errDb > n.first().errDb)
    }
}
