// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.media.Image
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ScopeMeterNativeTest {
    @Test fun `grid step pins the sqrt-truncation formula`() {
        // 12 MP scope grid: 2000x1500 blocks over 8000.
        assertEquals(19, meterGridStep(2000L * 1500, SCOPE_TARGET_BLOCKS))
        // 12 MP ETTR full-density scan.
        assertEquals(9, meterGridStep(4000L * 3000, 128_000))
        // PROGRAM center region at sparse density.
        assertEquals(19, meterGridStep(2800L * 2100, 16_000))
        // Full-frame guard scan.
        assertEquals(54, meterGridStep(4000L * 3000, 4_096))
    }

    @Test fun `grid step truncates and floors at one`() {
        assertEquals(1, meterGridStep(8000, 8000))
        assertEquals(1, meterGridStep(8000 * 4 - 1, 8000)) // sqrt(3.99) -> 1
        assertEquals(2, meterGridStep(8000 * 4, 8000))
        assertEquals(1, meterGridStep(100, 8000)) // sparse target, dense frame
        assertEquals(1, meterGridStep(0, 8000)) // degenerate span
    }

    @Test fun `shared density consts match the samplers they mirror`() {
        assertEquals(8_000, SCOPE_TARGET_BLOCKS)
        assertEquals(RawEttrSampler.GUARD_PIXELS,
            ScopeMeterNative.EttrDensity.MINIMAL.targetPx)
        assertEquals(RawEttrSampler.TARGET_BLOCKS_PROGRAM * 4,
            ScopeMeterNative.EttrDensity.PROGRAM.targetPx)
        assertEquals(RawEttrSampler.TARGET_BLOCKS * 4,
            ScopeMeterNative.EttrDensity.FULL.targetPx)
    }

    @Test fun `iparams layout is dense and JNI-mirrored`() {
        assertEquals(18, ScopeMeterNative.IP_COUNT)
        val ids = intArrayOf(
            ScopeMeterNative.IP_WHITE, ScopeMeterNative.IP_CFA,
            ScopeMeterNative.IP_BLACK0, ScopeMeterNative.IP_BLACK1,
            ScopeMeterNative.IP_BLACK2, ScopeMeterNative.IP_BLACK3,
            ScopeMeterNative.IP_USE_LUT, ScopeMeterNative.IP_SCOPE_STEP,
            ScopeMeterNative.IP_ETTR_STEP, ScopeMeterNative.IP_EL,
            ScopeMeterNative.IP_ET, ScopeMeterNative.IP_ER,
            ScopeMeterNative.IP_EB, ScopeMeterNative.IP_GUARD_STEP,
            ScopeMeterNative.IP_DO_GUARD, ScopeMeterNative.IP_W,
            ScopeMeterNative.IP_H, ScopeMeterNative.IP_ROW_STRIDE
        )
        assertArrayEquals(IntArray(18) { it }, ids.sortedArray())
    }

    @Test fun `full density always scans the whole frame without guard`() {
        val req = ScopeMeterNative.ScopeMeterRequest(null, ProgramMetering.SPOT,
            ScopeMeterNative.EttrDensity.FULL)
        assertArrayEquals(intArrayOf(0, 0, 4000, 3000),
            ScopeMeterNative.resolveRegion(4000, 3000, req))
        assertFalse(ScopeMeterNative.resolveGuard(req))
    }

    @Test fun `program density follows metering with the cropped-mode guard`() {
        val spot = ScopeMeterNative.ScopeMeterRequest(null, ProgramMetering.SPOT,
            ScopeMeterNative.EttrDensity.PROGRAM)
        assertArrayEquals(RawEttrSampler.meteringScanRegion(4000, 3000, ProgramMetering.SPOT),
            ScopeMeterNative.resolveRegion(4000, 3000, spot))
        assertTrue(ScopeMeterNative.resolveGuard(spot))
        val avg = ScopeMeterNative.ScopeMeterRequest(null, ProgramMetering.AVERAGE,
            ScopeMeterNative.EttrDensity.PROGRAM)
        assertArrayEquals(intArrayOf(0, 0, 4000, 3000),
            ScopeMeterNative.resolveRegion(4000, 3000, avg))
        assertFalse(ScopeMeterNative.resolveGuard(avg))
    }

    @Test fun `scatter hist slices channels and carries the agx flag`() {
        val flat = IntArray(256) { it }
        val linear = ScopeMeterNative.scatterHist(flat, false)
        assertArrayEquals(IntArray(64) { it }, linear.red)
        assertArrayEquals(IntArray(64) { 64 + it }, linear.green)
        assertArrayEquals(IntArray(64) { 128 + it }, linear.blue)
        assertArrayEquals(IntArray(64) { 192 + it }, linear.luminance)
        assertFalse(linear.agxApplied)
        assertTrue(ScopeMeterNative.scatterHist(flat, true).agxApplied)
    }

    @Test fun `scatter wave slices per-channel parades`() {
        val stride = 48 * 96
        val flat = IntArray(stride * 3) { it % 997 }
        val wave = ScopeMeterNative.scatterWave(flat, true)
        assertEquals(96, wave.columns)
        assertEquals(48, wave.levels)
        assertArrayEquals(flat.copyOfRange(0, stride), wave.red)
        assertArrayEquals(flat.copyOfRange(stride, stride * 2), wave.green)
        assertArrayEquals(flat.copyOfRange(stride * 2, stride * 3), wave.blue)
        assertTrue(wave.agxApplied)
    }

    @Test fun `scatter ettr assembles levels means and guard`() {
        // One hot bin per channel: percentile lands on (200.5)/256.
        val ebins = IntArray(256 * 4)
        val counts = IntArray(8)
        for (ch in 0..3) {
            ebins[ch * 256 + 200] = 100
            counts[ch] = ch // saturated passthrough
            counts[ch + 4] = 100 // totals passthrough
        }
        // wsum/ww = 25 (clamped to 1), ssum/scnt = 2 (clamped to 1).
        val frame = ScopeMeterNative.scatterEttr(ebins, counts,
            doubleArrayOf(100.0, 4.0, 6.0, 3.0), IntArray(256 * 4), false)
        val level = 200.5f / 256
        assertEquals(level, frame.levels.r, 0f)
        assertEquals(level, frame.levels.gr, 0f)
        assertEquals(1.0f, frame.centerWeightedGreen, 0f)
        assertEquals(1.0f, frame.spotGreen, 0f)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), frame.saturated)
        assertArrayEquals(intArrayOf(100, 100, 100, 100), frame.totals)
        assertTrue(frame.guardHottest.isNaN()) // no guard scan
    }

    @Test fun `scatter ettr NaN rules match the kotlin sampler`() {
        val ebins = IntArray(256 * 4)
        val counts = IntArray(8)
        val frame = ScopeMeterNative.scatterEttr(ebins, counts,
            doubleArrayOf(0.0, 0.0, 0.0, 0.0), IntArray(256 * 4), false)
        assertTrue(frame.centerWeightedGreen.isNaN())
        assertTrue(frame.spotGreen.isNaN())
        assertEquals(0f, frame.levels.hottest, 0f) // empty bins
    }

    @Test fun `scatter ettr guard hottest sums guard totals`() {
        val ebins = IntArray(256 * 4)
        val counts = IntArray(8)
        counts[4] = 1 // keep totals valid; bins empty
        counts[5] = 1
        counts[6] = 1
        counts[7] = 1
        val gbins = IntArray(256 * 4)
        gbins[100] = 50 // ch0 -> (100.5)/256
        gbins[256 + 250] = 50 // ch1 -> (250.5)/256, hottest
        val frame = ScopeMeterNative.scatterEttr(ebins, counts,
            doubleArrayOf(0.0, 0.0, 0.0, 0.0), gbins, true)
        assertEquals(250.5f / 256, frame.guardHottest, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `scatter ettr rejects misshapen buffers`() {
        ScopeMeterNative.scatterEttr(IntArray(10), IntArray(8),
            DoubleArray(4), IntArray(256 * 4), false)
    }

    @Test fun `scatter focus masks through the tested pipeline`() {
        val rows = 24
        val energy = FloatArray(96 * rows)
        val mean = FloatArray(96 * rows)
        // Two adjacent passing cells survive the despeckle.
        energy[0] = 1f
        energy[1] = 1f
        mean[0] = 1f
        mean[1] = 1f
        val frame = ScopeMeterNative.scatterFocus(energy, mean, rows, 90, true)
        assertEquals(96, frame.cols)
        assertEquals(rows, frame.rows)
        assertEquals(90, frame.rotation)
        assertTrue(frame.mirrored)
        assertTrue(frame.mask[0])
        assertTrue(frame.mask[1])
        assertEquals(2, frame.mask.count { it })
    }

    @Test fun `scratch allocates the fused geometry`() {
        val scratch = ScopeMeterNative.Scratch()
        assertTrue(scratch.ensure(4000, 3000, 72))
        assertEquals(64 * 4 * 4, scratch.hist.capacity())
        assertEquals(48 * 96 * 3 * 4, scratch.wave.capacity())
        assertEquals(256 * 4 * 4, scratch.ebins.capacity())
        assertEquals(4 * 2 * 4, scratch.ecounts.capacity())
        assertEquals(4 * 8, scratch.egreen.capacity())
        assertEquals(256 * 4 * 4, scratch.gbins.capacity())
        assertEquals(96 * 72 * 4, scratch.fenergy.capacity())
        assertEquals(96 * 72 * 4, scratch.fmean.capacity())
        assertEquals(1024 * 4, scratch.lut.capacity())
        assertEquals(18, scratch.iparams.size)
        // Same geometry reuses; changed geometry reallocates.
        val hist = scratch.hist
        assertTrue(scratch.ensure(4000, 3000, 72))
        assertTrue(scratch.hist === hist)
        assertTrue(scratch.ensure(4000, 3000, 73))
        assertFalse(scratch.hist === hist)
    }

    @Test fun `sample returns null without native code`() {
        // JVM has no rawLensScopeMeter: the facade must decline (fallback)
        // before touching the frame.
        assertFalse(ScopeMeterNative.available)
        val image = Mockito.mock(Image::class.java)
        val cal = ScopeMeterNative.MeterCalibration(0, 0, 0, 0, 0, 1023)
        val req = ScopeMeterNative.ScopeMeterRequest(null)
        assertNull(ScopeMeterNative.sample(image, cal, req))
    }

    @Test fun `calibration black phases map in order`() {
        val cal = ScopeMeterNative.MeterCalibration(0, 10, 11, 12, 13, 1023)
        assertEquals(10, cal.blackAt(0))
        assertEquals(11, cal.blackAt(1))
        assertEquals(12, cal.blackAt(2))
        assertEquals(13, cal.blackAt(3))
    }
}
