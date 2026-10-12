// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BguSampleTest {
    private fun plane8(): ByteBuffer {
        // 8x8 u16 codes: code(x, y) = (x + y * 8) * 16 (0..1008).
        val buf = ByteBuffer.allocate(8 * 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 8) for (x in 0 until 8) {
            buf.putShort((y * 8 + x) * 2, ((x + y * 8) * 16).toShort())
        }
        return buf
    }

    @Test fun samplesQuadsPlanar() {
        val out = ByteArray(2 * 2 * 4)
        val ok = BguSample.sampleLowFrame(
            plane8(), 16, 2, 0, 0, 4, 2, 2,
            intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
            out
        )
        assertTrue(ok)
        // Texel (0,0) <- quad (0,0): codes 0, 16, 128, 144.
        assertEquals(0.toByte(), out[0])
        assertEquals(4.toByte(), out[4])
        assertEquals(32.toByte(), out[8])
        assertEquals(36.toByte(), out[12])
        // Texel (1,0) <- quad (4,0): codes 64, 80, 192, 208.
        assertEquals(16.toByte(), out[1])
        assertEquals(20.toByte(), out[5])
        assertEquals(48.toByte(), out[9])
        assertEquals(52.toByte(), out[13])
        // Texel (0,1) <- quad (0,4): codes 512, 528, 640, 656.
        assertEquals(128.toByte(), out[2])
        assertEquals(132.toByte(), out[6])
        assertEquals(160.toByte(), out[10])
        assertEquals(164.toByte(), out[14])
    }

    @Test fun samplesStridedPlane() {
        // Same codes at pixelStride 4 (rowStride 32).
        val buf = ByteBuffer.allocate(8 * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 8) for (x in 0 until 8) {
            buf.putShort(y * 32 + x * 4, ((x + y * 8) * 16).toShort())
        }
        val out = ByteArray(2 * 2 * 4)
        val ok = BguSample.sampleLowFrame(
            buf, 32, 4, 0, 0, 4, 2, 2,
            intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
            out
        )
        assertTrue(ok)
        assertEquals(0.toByte(), out[0])
        assertEquals(4.toByte(), out[4])
        assertEquals(32.toByte(), out[8])
        assertEquals(36.toByte(), out[12])
    }

    @Test fun clampsAndBlackLevel() {
        val out = ByteArray(1 * 1 * 4)
        val ok = BguSample.sampleLowFrame(
            plane8(), 16, 2, 6, 6, 4, 1, 1,
            intArrayOf(0, 1, 2, 3), FloatArray(4) { 64f }, FloatArray(4) { 1f / (1023 - 64) },
            out
        )
        assertTrue(ok)
        // Quad (6,6): codes 864, 880, 992, 1008 -> (code-64)/959.
        assertEquals(213.toByte(), out[0]) // (800/959*255+.5) = 213.2 -> 213
        assertEquals(217.toByte(), out[1]) // (816/959*255+.5) = 217.5 -> 217 (banker's? no: toInt truncates 217.45 -> 217)
        assertEquals(247.toByte(), out[2])
        assertEquals(251.toByte(), out[3])
    }

    @Test fun lensGlideFadesInFromUnity() {
        // First arrival: t=0 emits unity (no pop vs unlensed), t=1 the map.
        assertEquals(1f, BguViewfinder.glideGain(1f, 1.39f, 0f), 1e-6f)
        assertEquals(1.39f, BguViewfinder.glideGain(1f, 1.39f, 1f), 1e-6f)
        // Midpoint is the average: linear glide.
        assertEquals(1.195f, BguViewfinder.glideGain(1f, 1.39f, 0.5f), 1e-6f)
        // Per-offer step for the worst observed edge gain (1.39x) stays tiny:
        // 0.39/24 offers ~ 0.016 gain per frame — a smooth gradient, not a pop.
        val step = (1.39f - 1f) / BguViewfinder.LENS_GLIDE_OFFERS
        assertTrue("glide step $step too coarse", step < 0.02f)
    }

    @Test fun aeFeedForwardCancelsSensorStep() {
        // Doubling exposure (or ISO) is +1 EV of sensor light.
        val base = BguViewfinder.aeExposureLog2(20_000_000L, 1000)
        assertEquals(1.0, BguViewfinder.aeExposureLog2(40_000_000L, 1000) - base, 1e-9)
        assertEquals(1.0, BguViewfinder.aeExposureLog2(20_000_000L, 2000) - base, 1e-9)
        assertTrue(BguViewfinder.aeExposureLog2(0L, 1000).isNaN())
        assertTrue(BguViewfinder.aeExposureLog2(20_000_000L, 0).isNaN())
        // First call seeds: no compensation against itself.
        val (ref0, norm0) = BguViewfinder.aeUpdate(Double.NaN, base, BguViewfinder.AE_REF_ALPHA)
        assertEquals(base, ref0, 1e-9)
        assertEquals(0f, norm0, 1e-6f)
        // A +1 EV sensor step is cancelled ~fully on the same offer: the
        // reference (alpha 0.01) barely moves, normEv ~= -1.
        val (ref1, norm1) = BguViewfinder.aeUpdate(ref0, base + 1.0, BguViewfinder.AE_REF_ALPHA)
        assertEquals(0.01, ref1 - ref0, 1e-9)
        assertEquals(-0.99f, norm1, 1e-6f)
        // Holding the new exposure converges the reference: compensation
        // migrates into the (scene-only) meter target, sum never moves.
        var ref = ref1
        var norm = norm1
        repeat(2000) {
            val (r, n) = BguViewfinder.aeUpdate(ref, base + 1.0, BguViewfinder.AE_REF_ALPHA)
            ref = r; norm = n
        }
        assertEquals(0f, norm, 1e-3f)
        // The observed 1/49 -> 1/100 step (~-1.03 EV) cancels the same way.
        val dark = BguViewfinder.aeExposureLog2(10_000_000L, 8000)
        val (_, normStep) = BguViewfinder.aeUpdate(dark + 1.03, dark, BguViewfinder.AE_REF_ALPHA)
        assertEquals(1.02f, normStep, 0.02f)
    }

    @Test fun colorHoldFillsNullResultFromLastGood() {
        // Last-good preview color (daylight-ish): the recording case, where
        // the recorder offers frames with result=null and unity/identity
        // would flash green.
        val hold = ColorHold(
            wb = floatArrayOf(1.9f, 1f, 1f, 1.5f),
            ccm = FloatArray(9) { if (it % 4 == 0) 1.2f else 0.1f },
            neutral = doubleArrayOf(0.4, 1.0, 0.7),
            gainsSensor = floatArrayOf(1.9f, 1f, 1f, 1.5f)
        )
        val held = BguViewfinder.resolveColorHold(null, null, null, null, hold)
        assertArrayEquals(hold.wb, held.wb, 0f)
        assertArrayEquals(hold.ccm, held.ccm, 0f)
        assertArrayEquals(hold.neutral, held.neutral, 0.0)
        assertArrayEquals(hold.gainsSensor, held.gainsSensor, 0f)
        // No hold ever seeded: unity/identity defaults (unchanged fallback).
        val fresh = BguViewfinder.resolveColorHold(null, null, null, null, null)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), fresh.wb, 0f)
        assertArrayEquals(FloatArray(9) { if (it % 4 == 0) 1f else 0f }, fresh.ccm, 0f)
        assertNull(fresh.neutral)
        assertNull(fresh.gainsSensor)
        // Fresh result parts win over a stale hold; missing parts mix.
        val mixed = BguViewfinder.resolveColorHold(
            floatArrayOf(2.1f, 1f, 1f, 1.3f), null, null,
            floatArrayOf(2.1f, 1f, 1f, 1.3f), hold
        )
        assertArrayEquals(floatArrayOf(2.1f, 1f, 1f, 1.3f), mixed.wb, 0f)
        assertArrayEquals(hold.ccm, mixed.ccm, 0f)
        assertArrayEquals(hold.neutral, mixed.neutral, 0.0)
        assertArrayEquals(floatArrayOf(2.1f, 1f, 1f, 1.3f), mixed.gainsSensor, 0f)
        // Defensive copies: mutating inputs after resolve is contained.
        val wbIn = floatArrayOf(2f, 1f, 1f, 1f)
        val out = BguViewfinder.resolveColorHold(wbIn, null, null, null, hold)
        wbIn[0] = 99f
        hold.wb[1] = 99f
        assertEquals(2f, out.wb[0], 0f)
        assertEquals(1f, out.wb[1], 0f)
    }

    @Test fun rejectsOutOfBounds() {
        val out = ByteArray(2 * 2 * 4)
        assertFalse(
            BguSample.sampleLowFrame(
                plane8(), 16, 2, 0, 0, 64, 2, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
                out
            )
        )
        assertFalse(
            BguSample.sampleLowFrame(
                plane8(), 0, 2, 0, 0, 4, 2, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
                out
            )
        )
    }
}
