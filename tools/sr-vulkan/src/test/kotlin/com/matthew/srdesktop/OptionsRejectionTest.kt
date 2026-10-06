// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.srdesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop rejection/bracketing flags: parsing plus shared-policy wiring. */
class OptionsRejectionTest {
    private fun base() = arrayOf("--in", "in", "--out", "out")

    @Test fun defaultsAreNullPolicy() {
        val opts = Options.parse(base(), "t")
        assertNull(opts.maxFrames)
        assertNull(opts.minReliableFrac)
        assertNull(opts.minMeanR)
        assertNull(opts.minSupportFrac)
        assertNull(opts.bracketStops)
        assertEquals(
            com.matthew.rawlens.RawSrFrameRejection.Policy(),
            Options.rejectionPolicy(opts)
        )
    }

    @Test fun overridesFlowIntoSharedPolicy() {
        val opts = Options.parse(
            base() + arrayOf(
                "--max-frames", "4",
                "--min-reliable-frac", "0.1",
                "--min-mean-r", "0.03",
                "--min-support-frac", "0.2"
            ),
            "t"
        )
        val policy = Options.rejectionPolicy(opts)
        assertEquals(4, policy.maxMergeFrames)
        assertEquals(0.1, policy.minReliableFraction, 0.0)
        assertEquals(0.03, policy.minMeanRobustness, 0.0)
        assertEquals(0.2, policy.minSupportFraction, 0.0)
    }

    @Test fun badThresholdFailsParsing() {
        try {
            Options.parse(base() + arrayOf("--min-mean-r", "nope"), "t")
            throw AssertionError("expected parse failure")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message?.contains("min-mean-r") == true)
        }
    }

    @Test fun kernelMpyDefaultsToHalfAndParses() {
        // Predicted-kernel default is 0.5 (eszdman recommendation); 1.0
        // restores the verbatim upstream law.
        val opts = Options.parse(base(), "t")
        assertEquals(
            com.matthew.rawlens.RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MPY,
            opts.kernelnetKernelMpy, 0f
        )
        val verbatim = Options.parse(base() + arrayOf("--kernelnet-kernel-mpy", "1.0"), "t")
        assertEquals(1.0f, verbatim.kernelnetKernelMpy, 0f)
    }

    @Test fun bracketCoverageProbeDoesNotThrow() {
        // Smoke: logging-only probe over a 3-stop dir (null = missing EXIF).
        Options.reportBracketCoverage("t", listOf(0.5, 1.0, 2.0, null), 1, "-1,0,1")
        Options.reportBracketCoverage("t", listOf(0.5, 1.0), 0, "bogus")
        Options.reportBracketCoverage("t", listOf(0.5, 1.0), 0, null)
    }
}
