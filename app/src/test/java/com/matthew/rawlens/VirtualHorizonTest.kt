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

    private fun gravityForRollPitch(
        rollDegrees: Float, pitchDegrees: Float, magnitude: Float = 9.81f
    ): Triple<Float, Float, Float> {
        val roll = Math.toRadians(rollDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        return Triple(
            (sin(roll) * cos(pitch) * magnitude).toFloat(),
            (cos(roll) * cos(pitch) * magnitude).toFloat(),
            (-sin(pitch) * magnitude).toFloat()
        )
    }

    @Test fun `pitch reports signed elevation to sky and ground`() {
        val (ux, uy, uz) = gravityForRollPitch(0f, 10f)
        val up = VirtualHorizon.compute(ux, uy, uz)
        assertEquals(10f, up.pitchDegrees, 0.05f)
        assertTrue(up.pitchDegrees > 0f)
        val (dx, dy, dz) = gravityForRollPitch(0f, -10f)
        val down = VirtualHorizon.compute(dx, dy, dz)
        assertEquals(-10f, down.pitchDegrees, 0.05f)
    }

    @Test fun `level requires both roll and pitch level`() {
        val (rx, ry, rz) = gravityForRollPitch(0f, 10f)
        val pitched = VirtualHorizon.compute(rx, ry, rz)
        assertTrue(pitched.isRollLevel)
        assertFalse(pitched.isPitchLevel)
        assertFalse(pitched.isLevel)
        val (lx, ly, lz) = gravityForRollPitch(0f, 1f)
        val both = VirtualHorizon.compute(lx, ly, lz)
        assertTrue(both.isPitchLevel)
        assertTrue(both.isLevel)
    }

    @Test fun `pitch is measured in landscape too`() {
        val (gx, gy, gz) = gravityForRollPitch(-93f, 10f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertFalse(state.isFlat)
        assertEquals(-3f, state.errorDegrees, 0.1f)
        assertEquals(10f, state.pitchDegrees, 0.1f)
        assertFalse(state.isLevel)
    }

    @Test fun `steep pitch is flat for roll but keeps pitch`() {
        val (gx, gy, gz) = gravityForRollPitch(0f, 80f)
        val state = VirtualHorizon.compute(gx, gy, gz)
        assertTrue(state.isFlat)
        assertEquals(80f, state.pitchDegrees, 0.5f)
        assertFalse(state.isLevel)
        val (dx, dy, dz) = gravityForRollPitch(0f, -90f)
        assertEquals(-90f, VirtualHorizon.compute(dx, dy, dz).pitchDegrees, 0.5f)
    }

    @Test fun `pitch bubble fraction is centered clamped and signed`() {
        assertEquals(0f, VirtualHorizon.pitchOffsetFraction(0f), 0.001f)
        assertEquals(0.5f, VirtualHorizon.pitchOffsetFraction(15f), 0.001f)
        assertEquals(-0.5f, VirtualHorizon.pitchOffsetFraction(-15f), 0.001f)
        assertEquals(1f, VirtualHorizon.pitchOffsetFraction(45f), 0.001f)
        assertEquals(-1f, VirtualHorizon.pitchOffsetFraction(-45f), 0.001f)
    }

    @Test fun `pitched to sky is not level even with perfect roll`() {
        // Upright roll, camera elevated 10°: gy = cos(pitch), gz = -sin(pitch).
        val pitch = Math.toRadians(10.0)
        val state = VirtualHorizon.compute(
            0f, (cos(pitch) * 9.81).toFloat(), (-sin(pitch) * 9.81).toFloat()
        )
        assertFalse(state.isFlat)
        assertFalse(state.isLevel)
    }

    @Test fun `zero vector is flat and safe`() {
        val state = VirtualHorizon.compute(0f, 0f, 0f)
        assertTrue(state.isFlat)
        assertFalse(state.isLevel)
    }
}
