// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLogRecorderTest {
    @Test
    fun relativeUsRebasesToRecordingStart() {
        assertEquals(0L, DirectLogRecorder.relativeUs(1_000_000_000L, 1_000_000_000L))
        assertEquals(33_333L, DirectLogRecorder.relativeUs(1_033_333_000L, 1_000_000_000L))
    }

    @Test
    fun constantPtsUsStartsAtZeroOnIdealGrid() {
        assertEquals(0L, DirectLogRecorder.constantPtsUs(0, 30))
        assertEquals(33_333L, DirectLogRecorder.constantPtsUs(1, 30))
        assertEquals(66_666L, DirectLogRecorder.constantPtsUs(2, 30))
        assertEquals(0L, DirectLogRecorder.constantPtsUs(0, 24))
        assertEquals(41_666L, DirectLogRecorder.constantPtsUs(1, 24))
    }

    @Test
    fun constantPtsUsGapsAreUniformWithinOneMicrosecond() {
        // CFR pin: every consecutive gap is floor/ceil of the period —
        // no sensor jitter, no drift over a 10-minute take.
        for (fps in intArrayOf(24, 30)) {
            var prev = DirectLogRecorder.constantPtsUs(0, fps)
            for (i in 1L..fps * 600L) {
                val cur = DirectLogRecorder.constantPtsUs(i, fps)
                val gap = cur - prev
                assertTrue("fps=$fps i=$i gap=$gap", gap == 1_000_000L / fps || gap == 1_000_000L / fps + 1)
                prev = cur
            }
            // Drift-free: frame fps*N lands exactly on second N.
            assertEquals(600_000_000L, DirectLogRecorder.constantPtsUs(fps * 600L, fps))
        }
    }

    @Test
    fun constantPtsUsRejectsBadInput() {
        try {
            DirectLogRecorder.constantPtsUs(0, 0)
            assertTrue("fps=0 must throw", false)
        } catch (_: IllegalArgumentException) {}
        try {
            DirectLogRecorder.constantPtsUs(-1, 30)
            assertTrue("frameIndex=-1 must throw", false)
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun wbGainsFallbackMatchesProbe() {
        assertArrayEquals(
            VfLogGrade.PROBE_GAINS, DirectLogRecorder.wbGains(null), 0f
        )
    }

    @Test
    fun preresultGateDropsOnlyTheUnboundedHead() {
        // Result-less head frames drop (no probe-WB color pop in the file).
        assertTrue(DirectLogRecorder.shouldDropPreresult(false, 0, 1))
        assertTrue(DirectLogRecorder.shouldDropPreresult(false, 0, DirectLogRecorder.MAX_PRERESULT_DROPS))
        // Past the budget a result-less HAL still records (fallback colors).
        assertTrue(!DirectLogRecorder.shouldDropPreresult(false, 0, DirectLogRecorder.MAX_PRERESULT_DROPS + 1))
        // A present result (exact or stale) always records.
        assertTrue(!DirectLogRecorder.shouldDropPreresult(true, 0, 1))
        // Once grading, never gate (result-less cannot recur anyway).
        assertTrue(!DirectLogRecorder.shouldDropPreresult(false, 1, 1))
    }

    @Test
    fun ccmFallbackIsIdentity() {
        assertArrayEquals(
            VfLogGrade.IDENTITY_CCM, DirectLogRecorder.ccmMatrix(null, null), 0f
        )
    }

    @Test
    fun ccmPrefersStaticBakeOverIdentity() {
        val baked = floatArrayOf(
            1.5f, -0.4f, -0.1f,
            -0.3f, 1.2f, 0.1f,
            0.0f, -0.2f, 1.2f
        )
        val got = DirectLogRecorder.ccmMatrix(null, baked)
        assertArrayEquals(baked, got, 0f)
        // Defensive copy: mutating the result must not poison the cache.
        got[0] = 99f
        assertEquals(1.5f, baked[0], 0f)
    }

    @Test
    fun aacBitratePrefersQualityOverSize() {
        assertEquals(128_000, AacAudioEncoder.bitrateFor(2))
        assertEquals(64_000, AacAudioEncoder.bitrateFor(1))
    }

    @Test
    fun packShadingPacksDimsAndGains() {
        val gains = FloatArray(2 * 2 * 4) { 1f }
        val packed = DirectLogRecorder.packShading(2, 2, 0, 0, 960, 540, gains)
        assertTrue(packed != null)
        assertArrayEquals(intArrayOf(2, 2, 0, 0, 960, 540), packed!!.first)
        assertArrayEquals(ShortArray(2 * 2 * 4) { 0x3C00.toShort() }, packed.second)
    }

    @Test
    fun floatToHalfBitsPinsKnownValues() {
        assertEquals(0x3C00.toShort(), DirectLogRecorder.floatToHalfBits(1f))
        assertEquals(0x4000.toShort(), DirectLogRecorder.floatToHalfBits(2f))
        assertEquals(0x3800.toShort(), DirectLogRecorder.floatToHalfBits(0.5f))
        assertEquals(0x3E00.toShort(), DirectLogRecorder.floatToHalfBits(1.5f))
        assertEquals(0x4200.toShort(), DirectLogRecorder.floatToHalfBits(3f))
        assertEquals(0x4400.toShort(), DirectLogRecorder.floatToHalfBits(4f))
        assertEquals(0x0000.toShort(), DirectLogRecorder.floatToHalfBits(0f))
        assertEquals(0x7C00.toShort(), DirectLogRecorder.floatToHalfBits(Float.POSITIVE_INFINITY))
        // Round-to-nearest-even on ties: halfway between 1.0 (0x3C00) and
        // 1.0009765625 (0x3C01) rounds to even (0x3C00).
        assertEquals(0x3C00.toShort(), DirectLogRecorder.floatToHalfBits(1.00048828125f))
    }

    @Test
    fun packShadingRejectsBadShapes() {
        val gains = FloatArray(2 * 2 * 4) { 1f }
        // Zero/negative dims.
        assertEquals(null, DirectLogRecorder.packShading(0, 2, 0, 0, 960, 540, gains))
        assertEquals(null, DirectLogRecorder.packShading(2, -1, 0, 0, 960, 540, gains))
        // Over the native SSBO cap (mirrors kShadeMaxCells).
        assertEquals(null, DirectLogRecorder.packShading(65, 2, 0, 0, 960, 540, gains))
        // Gain count must be rows*cols*4.
        assertEquals(null, DirectLogRecorder.packShading(2, 2, 0, 0, 960, 540, FloatArray(15) { 1f }))
        // Empty active rect.
        assertEquals(null, DirectLogRecorder.packShading(2, 2, 0, 0, 0, 540, gains))
        assertEquals(null, DirectLogRecorder.packShading(2, 2, 10, 10, 5, 540, gains))
    }

    @Test
    fun packShadingRejectsBadContent() {
        // Camera2 gains are finite and >= 1 (LensShadingModel rules).
        assertEquals(null, DirectLogRecorder.packShading(1, 1, 0, 0, 64, 64, floatArrayOf(1f, 1f, 0.5f, 1f)))
        assertEquals(null, DirectLogRecorder.packShading(1, 1, 0, 0, 64, 64, floatArrayOf(1f, Float.NaN, 1f, 1f)))
        assertEquals(null, DirectLogRecorder.packShading(1, 1, 0, 0, 64, 64, floatArrayOf(1f, 1f, 1f, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun muxedFpsIsZeroUntilTwoSamples() {
        assertEquals(0f, DirectLogRecorder.muxedFps(0, 0L, 8_000_000_000L), 0f)
        assertEquals(0f, DirectLogRecorder.muxedFps(5, 0L, 8_000_000_000L), 0f)
        // A single sample spans no interval yet (no divide-by-near-zero spike).
        assertEquals(0f, DirectLogRecorder.muxedFps(1, 1_500_000_000L, 1_533_333_000L), 0f)
        assertEquals(0f, DirectLogRecorder.muxedFps(2, 1_500_000_000L, 1_500_000_000L), 0f)
    }

    @Test
    fun muxedFpsPinsThirtyFpsTake() {
        // Device-observed open-gate take: 194 samples over 6.4667s of muxed output.
        val fps = DirectLogRecorder.muxedFps(194, 1_500_000_000L, 1_500_000_000L + 6_466_700_000L)
        assertEquals(30.0f, fps, 0.05f)
    }

    @Test
    fun muxedFpsExcludesStartupDeadTime() {
        // Same take on an 8s wall clock: naive samples/wall reads 194/8 = 24.3fps;
        // anchored at the first muxed sample it reads the true muxed rate.
        val fps = DirectLogRecorder.muxedFps(194, 1_533_300_000L, 8_000_000_000L)
        assertEquals(30.0f, fps, 0.05f)
    }
}
