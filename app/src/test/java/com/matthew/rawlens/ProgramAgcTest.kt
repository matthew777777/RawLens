// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values hand-derived from libcamera agc_channel.cpp / histogram.cpp @ 0bf3fc5. */
class ProgramAgcTest {
    private fun hist(vararg binCount: Pair<Int, Int>, bins: Int = 256) =
        ProgramAgc.Histogram(IntArray(bins).also { a -> for ((b, n) in binCount) a[b] += n })

    @Test fun quantileAndInterQuantileMeanMatchLibipa() {
        val uniform = ProgramAgc.Histogram(IntArray(100) { 1 })
        assertEquals(50.0, uniform.quantile(0.5), 1e-9)
        assertEquals(98.0, uniform.quantile(0.98), 1e-9)
        // Bins 98 and 99, one sample each, midpoints 98.5 and 99.5.
        assertEquals(99.0, uniform.interQuantileMean(0.98, 1.0), 1e-9)
    }

    @Test fun gainIteratesToTheYTarget() {
        // Mids at bin 10 with 5 % highlights at bin 200 (lower constraint met);
        // metered mean = the histogram mean, as for an AVERAGE scan.
        val h = hist(10 to 950, 200 to 50)
        val mean = (950 * 10.5 + 50 * 200.5) / 1000 / 256
        val g = ProgramAgc.computeGain(mean, h, 1.0, ProgramAgc.ConstraintMode.NORMAL)
        // Iteration stops once one more pass would change the gain by < 1 %:
        // the clipped mean then sits within ~1 % of the target.
        assertEquals(ProgramAgc.Y_TARGET, ProgramAgc.meteredY(mean, h, g.gain), 0.01 * ProgramAgc.Y_TARGET + 0.001)
        assertEquals(ProgramAgc.Y_TARGET, g.targetY, 0.0)
        // Clipping of the pushed highlights needs more gain than the linear 0.16 / mean.
        assertTrue(g.gain > ProgramAgc.Y_TARGET / (mean + .001))
    }

    @Test fun lowerConstraintLiftsAllDarkScenes() {
        val h = hist(10 to 1000)
        val g = ProgramAgc.computeGain(0.04, h, 1.0, ProgramAgc.ConstraintMode.NORMAL)
        // One occupied bin: the q0.98..1.0 slice spans 10.98..11.0 (libipa
        // fractional quantiles), so the lower bound lifts the gain to 0.3 / iqm.
        val iqm = h.interQuantileMean(0.98, 1.0)
        assertEquals(0.3 * 256 / iqm, g.gain, 1e-9)
        assertEquals(0.3, g.targetY, 0.0)
    }

    @Test fun upperConstraintCapsInHighlightMode() {
        val h = hist(10 to 900, 250 to 100)
        val g = ProgramAgc.computeGain(0.02, h, 1.0, ProgramAgc.ConstraintMode.HIGHLIGHT)
        val iqm = h.interQuantileMean(0.98, 1.0)
        assertEquals(0.8 * 256 / iqm, g.gain, 1e-9)
        assertTrue(g.gain < 1.0)
    }

    @Test fun evGainScalesTheTargetUpToTheLimit() {
        val h = hist(10 to 950, 200 to 50)
        assertEquals(0.32, ProgramAgc.computeGain(0.04, h, 2.0, ProgramAgc.ConstraintMode.NORMAL).targetY, 1e-12)
        assertEquals(0.9, ProgramAgc.computeGain(0.04, h, 16.0, ProgramAgc.ConstraintMode.NORMAL).targetY, 0.0)
    }

    @Test fun saturationModelClipsPushedHighlights() {
        // Half the samples at level ~0.8: x2 clips them at 1.
        val h = hist(50 to 500, 204 to 500)
        val y = ProgramAgc.meteredY(0.4, h, 2.0)
        assertTrue("clipped mean must be below 0.8, was $y", y < 0.8)
    }

    @Test fun filterBranches() {
        // Startup frames adapt instantly.
        assertEquals(2000.0, ProgramAgc.filter(1000.0, 2000.0, 1, 0), 0.0)
        // Far from target: speed 0.2.
        assertEquals(1200.0, ProgramAgc.filter(1000.0, 2000.0, 1, 20), 1e-9)
        // Within +-20 %: sqrt(0.2).
        assertEquals(1900.0 + Math.sqrt(0.2) * 100.0, ProgramAgc.filter(1900.0, 2000.0, 1, 20), 1e-9)
        // Within +-2 %: unchanged.
        assertEquals(1990.0, ProgramAgc.filter(1990.0, 2000.0, 1, 20), 0.0)
        // Several frames compound the per-frame filter.
        val two = ProgramAgc.filter(ProgramAgc.filter(1000.0, 2000.0, 1, 20), 2000.0, 1, 21)
        assertEquals(two, ProgramAgc.filter(1000.0, 2000.0, 2, 20), 1e-9)
    }

    @Test fun desaturateOnlyForHighTargetsWithLowGain() {
        assertTrue(ProgramAgc.desaturate(ProgramAgc.Gain(0.5, 0.8)))
        assertFalse(ProgramAgc.desaturate(ProgramAgc.Gain(0.95, 0.8)))
        assertFalse(ProgramAgc.desaturate(ProgramAgc.Gain(0.5, 0.3)))
    }

    @Test fun flickerRoundsDownToWholePeriods() {
        assertEquals(20_000_000L to 1.25, ProgramAgc.roundForFlicker(25_000_000L, 10_000_000L))
        assertEquals(5_000_000L to 1.0, ProgramAgc.roundForFlicker(5_000_000L, 10_000_000L))
        assertEquals(25_000_000L to 1.0, ProgramAgc.roundForFlicker(25_000_000L, 0L))
    }
}
