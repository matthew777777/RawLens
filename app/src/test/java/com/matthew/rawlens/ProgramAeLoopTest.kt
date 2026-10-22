// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramAeLoopTest {
    private val limits = ExposureBalanceLimits(50, 6400, 100_000L, 66_666_666L, 33_333_333L)
    private val profile = ProgramAeProfile()

    /** Green histogram: mids at [midBin] plus 5 % highlights (lower constraint met). */
    private fun sample(green: Float, midBin: Int = 10): EttrRawSample {
        val bins = Array(4) { IntArray(256) }
        for (c in 1..2) { bins[c][midBin] = 950; bins[c][200] = 50 }
        return EttrRawSample(EttrChannelLevels(0.5f, 0.5f, 0.5f, 0.5f), bins, IntArray(4),
            intArrayOf(1000, 1000, 1000, 1000), centerWeightedGreen = green)
    }

    @Test fun startAppliesTheTargetAtOnce() {
        val loop = ProgramAeLoop()
        assertEquals(ProgramAeLoop.Step.START,
            loop.onSample(sample(0.04f), profile, limits, 100, 10_000_000L, 0L))
        val (changed, settled) = loop.tick(1)!!
        assertTrue(changed)
        assertTrue(settled)
        // Startup: the applied exposure is the AGC target (current x gain).
        val gain = ProgramAgc.computeGain(0.04f.toDouble(), ProgramAgc.Histogram(RawProgramMeter.frameGreenBins(sample(0.04f))),
            1.0, ProgramAgc.ConstraintMode.NORMAL).gain
        assertEquals(1e9 * gain, loop.targetTotal, 1.0)
        assertEquals(loop.targetTotal, loop.filteredTotal, 1e-3)
        val total = loop.iso!!.toDouble() * loop.shutterNanos!!
        assertEquals(loop.targetTotal, total, 0.01 * loop.targetTotal)
    }

    @Test fun laterSamplesGlideThroughTheFilter() {
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.04f), profile, limits, 100, 10_000_000L, 0L)
        repeat(12) { loop.tick(1) }                       // past the startup frames
        val before = loop.iso!!.toDouble() * loop.shutterNanos!!
        assertEquals(ProgramAeLoop.Step.GLIDE,
            loop.onSample(sample(0.01f), profile, limits, loop.iso!!, loop.shutterNanos!!, 0L))
        val f0 = loop.filteredTotal
        loop.tick(1)
        // Far from target after startup: libcamera's speed 0.2 per frame.
        assertEquals(0.2 * loop.targetTotal + 0.8 * f0, loop.filteredTotal, 1e-3)
        val after = loop.iso!!.toDouble() * loop.shutterNanos!!
        assertTrue("brighter, not at target: $before -> $after", after > before && after < loop.targetTotal)
    }

    @Test fun resetRestartsAndClearDropsThePair() {
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.04f), profile, limits, 100, 10_000_000L, 0L)
        loop.tick(1)
        loop.resetLoop()
        assertNull(loop.targetIso)
        assertNull(loop.tick(1))
        assertEquals(ProgramAeLoop.Step.START,
            loop.onSample(sample(0.04f), profile, limits, 100, 10_000_000L, 0L))
        loop.clearLivePair()
        assertNull(loop.iso)
    }

    @Test fun noExposureMeansNoStep() {
        assertNull(ProgramAeLoop().onSample(sample(0.1f), profile, limits, 0, 0L, 0L))
    }
}
