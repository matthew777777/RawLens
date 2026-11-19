package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class LateralCaTest {
    /**
     * Colour-difference image (ch1 = G, ch0 = wbR - G, ch2 = wbB - G) of a
     * rings + ramp scene with R and B magnified by (1 + k) about the centre.
     */
    private fun scene(w: Int, h: Int, kR: Double, kB: Double): FloatBuffer {
        val cx = w / 2.0; val cy = h / 2.0
        fun f(x: Double, y: Double): Double {
            val dx = x - cx; val dy = y - cy
            return 0.5 + 0.2 * sin(sqrt(dx * dx + dy * dy) / 5.0) + 0.15 * sin(x / 11.0 + y / 17.0)
        }
        // observed R(p) = true(c + (p - c)/(1 + k)); pixel centres at +.5
        fun obs(x: Double, y: Double, k: Double) = f(cx + (x - cx) / (1 + k), cy + (y - cy) / (1 + k))
        val b = FloatBuffer.allocate(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + 0.5; val py = y + 0.5
            val g = f(px, py)
            b.put(3 * (y * w + x), (0.8 * obs(px, py, kR) - g).toFloat())
            b.put(3 * (y * w + x) + 1, g.toFloat())
            b.put(3 * (y * w + x) + 2, (0.6 * obs(px, py, kB) + 0.05 - g).toFloat())
        }
        return b
    }

    @Test fun recoversRadialScale() {
        val fit = LateralCa.estimate(scene(1200, 900, 0.0012, -0.0008), 1200, 900, 1f)
        assertEquals("kR $fit", 0.0012, fit.kR.toDouble(), 0.00003)
        assertEquals("kB $fit", -0.0008, fit.kB.toDouble(), 0.00003)
        assertTrue(fit.cornerShift(1200, 900) >= LateralCa.MIN_CORNER_SHIFT_PX)
    }

    @Test fun noAberrationGivesZero() {
        val fit = LateralCa.estimate(scene(1200, 900, 0.0, 0.0), 1200, 900, 1f)
        assertTrue("$fit", abs(fit.kR) < 0.00003 && abs(fit.kB) < 0.00003)
        assertTrue(fit.cornerShift(1200, 900) < LateralCa.MIN_CORNER_SHIFT_PX)
    }
}
