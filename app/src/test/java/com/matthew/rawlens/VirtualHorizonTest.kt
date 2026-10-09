// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VirtualHorizonTest {
    private fun gravityForTilt(tiltDegrees: Float, magnitude: Float = 9.81f): Triple<Float, Float, Float> {
        val radians = Math.toRadians(tiltDegrees.toDouble())
        return Triple(
            (sin(radians) * magnitude).toFloat(),
            (cos(radians) * magnitude).toFloat(),
            0f
        )
    }

    @Test fun `upright portrait is level with zero tilt`() {
        val (gx, gy, gz) = gravityForTilt(0f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertTrue(state.isLevel)
        assertEquals(0f, state.tiltDegrees, 0.01f)
        assertEquals(0f, state.errorDegrees, 0.01f)
    }

    @Test fun `small portrait tilt reports signed error`() {
        val (gx, gy, gz) = gravityForTilt(-3f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertFalse(state.isLevel)
        assertEquals(-3f, state.tiltDegrees, 0.05f)
        assertEquals(-3f, state.errorDegrees, 0.05f)
    }

    @Test fun `clockwise landscape is level at minus ninety`() {
        val (gx, gy, gz) = gravityForTilt(-90f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertTrue(state.isLevel)
        assertEquals(-90f, state.tiltDegrees, 0.05f)
        assertEquals(0f, state.errorDegrees, 0.05f)
    }

    @Test fun `counterclockwise landscape is level at plus ninety`() {
        val (gx, gy, gz) = gravityForTilt(90f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertTrue(state.isLevel)
        assertEquals(90f, state.tiltDegrees, 0.05f)
        assertEquals(0f, state.errorDegrees, 0.05f)
    }

    @Test fun `landscape tilt error is measured from nearest quadrant`() {
        val (gx, gy, gz) = gravityForTilt(-93f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertFalse(state.isLevel)
        assertEquals(-93f, state.tiltDegrees, 0.1f)
        assertEquals(-3f, state.errorDegrees, 0.1f)
    }

    @Test fun `upside down portrait is level at one eighty`() {
        val (gx, gy, gz) = gravityForTilt(180f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertTrue(state.isLevel)
        assertEquals(0f, state.errorDegrees, 0.1f)
    }

    @Test fun `flat device reports flat instead of level`() {
        val flat = VirtualHorizon.compute(0.1f, 0.1f, 9.81f)
        assertTrue(flat.isFlat)
        assertFalse(flat.isLevel)
    }

    @Test fun `level threshold snaps only near quadrant`() {
        val (ix, iy, iz) = gravityForTilt(1f)
        assertTrue(VirtualHorizon.compute(ix, iy, iz).isLevel)
        val (ox, oy, oz) = gravityForTilt(3f)
        assertFalse(VirtualHorizon.compute(ox, oy, oz).isLevel)
    }

    @Test fun `zero vector is flat and safe`() {
        val state = VirtualHorizon.compute(0f, 0f, 0f)
        assertTrue(state.isFlat)
        assertFalse(state.isLevel)
    }
}
