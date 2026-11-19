package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.sqrt

class BlackPedestalTest {
    private val a = floatArrayOf(2e-4f, 2e-4f, 2e-4f, 2e-4f)
    private val b = floatArrayOf(4e-6f, 4e-6f, 4e-6f, 4e-6f)

    /**
     * 1024x768 RGGB scene: a bright coloured gradient over most of the frame,
     * and a dark patch (top-left 256x192) whose CFA level is offset + light * colour.
     */
    private fun frame(offset: Float, darkLight: Float, seed: Long = 1): Array<FloatArray> {
        val rng = Random(seed)
        val colour = floatArrayOf(0.45f, 1f, 0.7f)
        return Array(768) { y -> FloatArray(1024) { x ->
            val c = if (y % 2 == 0 && x % 2 == 0) 0 else if (y % 2 == 1 && x % 2 == 1) 2 else 1
            val light = if (x < 256 && y < 192) darkLight else 0.05f + 0.4f * x / 1024f
            val v = offset + light * colour[c]
            (v + rng.nextGaussian() * sqrt(a[0] * maxOf(v, 0f) + b[0])).toFloat()
        } }
    }

    @Test fun unlitNeutralFloorIsMeasured() {
        val e = BlackPedestal.estimate(frame(0.002f, 0f), a, b)
        assertEquals("$e", 0.002, e.pedestal.toDouble(), 0.0002)
    }

    @Test fun litDarkObjectIsNotAPedestal() {
        val e = BlackPedestal.estimate(frame(0f, 0.01f), a, b)
        assertEquals("$e", 0.0, e.pedestal.toDouble(), 1e-9)
    }

    @Test fun applyRescalesWhite() {
        val buf = FloatBuffer.wrap(floatArrayOf(0.002f, 1f, 0.5f))
        BlackPedestal.apply(buf, 3, 0.002f)
        assertEquals(0f, buf.get(0), 1e-7f); assertEquals(1f, buf.get(1), 1e-6f)
        assertEquals((0.5f - 0.002f) / 0.998f, buf.get(2), 1e-6f)
    }
}
