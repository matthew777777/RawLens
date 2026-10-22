// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramAeLoopTest {
    private val limits = ExposureBalanceLimits(50, 6400, 100_000L, 66_666_666L, 33_333_333L)
    private val profile = ProgramAeProfile()
    private fun sample(green: Float) = EttrRawSample(
        EttrChannelLevels(0.5f, 0.5f, 0.5f, 0.5f), emptyArray(), IntArray(4), IntArray(4),
        centerWeightedGreen = green)

    @Test fun firstSampleFastStartsThenGlides() {
        val loop = ProgramAeLoop()
        assertEquals(ProgramAeLoop.Step.FAST_START, loop.onSample(sample(0.05f), profile, limits, 100, 10_000_000L))
        assertFalse(loop.fastStart)
        assertTrue(loop.iso != null && loop.iso == loop.targetIso)
        // Darker again: a new target, applied pair unchanged -> glide.
        assertEquals(ProgramAeLoop.Step.GLIDE, loop.onSample(sample(0.02f), profile, limits, 100, 10_000_000L))
        val step = loop.rampStep()!!
        assertTrue(step.first)
    }

    @Test fun noBaselineMeansNoStep() {
        assertNull(ProgramAeLoop().onSample(sample(0.1f), profile, limits, 0, 0L))
    }

    @Test fun resetsMatchTheTwoControllerPaths() {
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.05f), profile, limits, 100, 10_000_000L)
        loop.resetLoop()
        assertTrue(loop.fastStart)
        assertNull(loop.targetIso)
        assertTrue(loop.brightnessEma.isNaN())
        assertTrue(loop.iso != null)            // resetLoop keeps the applied pair
        loop.clearLivePair()
        assertNull(loop.iso)
        assertNull(loop.rampStep())
    }
}
