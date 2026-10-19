package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrSeqMergeTest {
    private val noise = CfaNoiseModel(FloatArray(4) { 2.5e-4f }, FloatArray(4) { 2.5e-6f })

    private fun frame(
        w: Int = 32, h: Int = 32,
        nanos: Long = 10_000_000L, iso: Int = 100,
        fill: (x: Int, y: Int) -> Float = { _, _ -> 0.2f }
    ): HdrMergeFrame {
        val values = FloatArray(w * h) { i -> fill(i % w, i / w) }
        val cfa = UnpackedRawCfa(w, h, BayerPattern.RGGB, values,
            RawCrop(0, 0, w, h))
        return HdrMergeFrame(cfa, nanos, iso, noiseModel = noise)
    }

    @Test fun seqPlanCapturesZeroMinusTwoZero() {
        val plan = HdrBracketConfig.seqZeroMinusTwoZero()
        assertArrayEquals(intArrayOf(-2, 0), plan.stops)
        assertEquals(3, plan.totalFrames)
        assertTrue(plan.allEvsCovered)
        assertEquals(listOf(0, -2, 0), plan.frames.map { it.evStops })
        assertEquals(HdrBracketConfig.shutterNanos(10_000_000L, 0),
            HdrBracketConfig.shutterNanos(10_000_000L, 0))
    }

    @Test fun seqPlanRejectsOverusedEv() {
        try {
            HdrBracketConfig.sequence(intArrayOf(-2, 0), intArrayOf(1, 2), intArrayOf(0, 0, 0))
            throw AssertionError("expected IllegalArgumentException")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.contains("overuses"))
        }
    }

    private fun hashNoise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xffff).toFloat() / 65535f * 2f - 1f) * 0.001f
    }

    @Test fun staticPairAverages() {
        val ref = frame(fill = { x, y -> 0.2f + (x + y) * 1e-5f })
        val alt = frame(fill = { x, y -> 0.2f + (x + y) * 1e-5f + hashNoise(x, y) })
        val out = HdrSeqMerge.mergeZeroPair(ref, alt)
        assertEquals(1.0, out.keptFraction, 1e-9)
        // Zero-mean noise averages away: output tracks the reference level.
        assertEquals(0.20028f, out.cfa.values[17 * 32 + 11], 5e-4f)
    }

    @Test fun motionRegionRetainsReference() {
        val ref = frame()
        val alt = frame(fill = { x, y ->
            if (x in 12..19 && y in 12..19) 0.7f else 0.2f
        })
        val out = HdrSeqMerge.mergeZeroPair(ref, alt)
        assertTrue(out.keptFraction in 0.5..0.99)
        assertEquals(0.2f, out.cfa.values[15 * 32 + 15], 1e-6f)
        assertEquals(0.2f, out.cfa.values[0], 1e-4f)
    }

    @Test fun trioRescuesClippedHighlights() {
        val clipBlock: (Int, Int) -> Float = { x, y ->
            if (x in 12..19 && y in 12..19) 1.0f else 0.2f
        }
        val z0 = frame(nanos = 10_000_000L, fill = clipBlock)
        val z1 = frame(nanos = 10_000_000L, fill = clipBlock)
        val short = frame(nanos = 2_500_000L, fill = { x, y ->
            if (x in 12..19 && y in 12..19) 0.2f else 0.05f
        })
        val frames = listOf(z0, short, z1)
        assertTrue(HdrSeqMerge.isTrio(frames))
        val out = HdrSeqMerge.mergeTrio(frames)
        assertEquals(0.8f, out.cfa.values[15 * 32 + 15], 1e-3f)
        assertEquals(1.0, out.rescuedFraction, 1e-9)
        assertTrue(out.timings.alignMs >= 0 && out.timings.mergeMs >= 0 && out.timings.rescueMs >= 0)
    }

    @Test fun trioPicksSharperZeroAsReference() {
        val flat = frame(fill = { _, _ -> 0.2f })
        val sharp = frame(fill = { x, y -> if ((x / 2 + y / 2) % 2 == 0) 0.25f else 0.15f })
        assertTrue(HdrSeqMerge.sharpness(sharp.cfa) > HdrSeqMerge.sharpness(flat.cfa))
        val short = frame(nanos = 2_500_000L, fill = { _, _ -> 0.05f })
        val out = HdrSeqMerge.mergeTrio(listOf(flat, short, sharp))
        assertEquals(2, out.refIndex)
    }

    @Test fun trioRejectsMismatchedIso() {
        val a = frame(iso = 100)
        val b = frame(iso = 200)
        val c = frame(nanos = 2_500_000L)
        assertFalse(HdrSeqMerge.isTrio(listOf(a, b, c)))
    }
}
