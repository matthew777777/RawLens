// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class BurstSharpnessTest {
    private val w = 128
    private val h = 128
    private fun isGreen(x: Int, y: Int) = ((x + y) and 1) == 1  // RGGB

    /** Fine texture, optionally box-blurred horizontally over [blur] px (motion blur). */
    private fun scene(blur: Int, gain: Float = 1f): (Int, Int) -> Float = { x, y ->
        var s = 0f
        for (k in 0 until blur) s += 0.5f + 0.3f * sin((x + k) * 1.3f + y * 0.7f).toFloat()
        gain * s / blur
    }

    @Test fun motionBlurLowersSharpnessAndBrightnessDoesNot() {
        val sharp = BurstSharpness.measure(w, h, ::isGreen, scene(1))
        val brighter = BurstSharpness.measure(w, h, ::isGreen, scene(1, gain = 2f))
        val blurred = BurstSharpness.measure(w, h, ::isGreen, scene(4))
        assertEquals(sharp, brighter, sharp * 1e-6)
        assertTrue("blurred $blurred vs sharp $sharp", blurred < 0.8 * sharp)
    }

    @Test fun referenceIsSharpestOfNewestThree() {
        // Capture order; frame 0 is the sharpest overall but oldest.
        val plan = BurstSharpness.plan(listOf(2.0, 1.0, 1.0, 1.05, 1.1, 1.08), minFrames = 2)
        assertEquals(4, plan.order.first())
        // Frame 1 (1.0 < 0.8 * 1.1? no: 0.88) — all >= 0.8 * 1.1 stay; frame 0 sorts first after ref.
        assertEquals(listOf(4, 0, 5, 3, 1, 2), plan.order)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test fun blurredFramesAreRejectedButMinimumIsKept() {
        val plan = BurstSharpness.plan(listOf(0.3, 0.5, 1.0), minFrames = 2)
        assertEquals(listOf(2), plan.order.take(1))
        // Both under 0.8: the sharpest of them is kept to reach the minimum.
        assertEquals(listOf(2, 1), plan.order)
        assertEquals(listOf(0), plan.rejected)
    }
}
