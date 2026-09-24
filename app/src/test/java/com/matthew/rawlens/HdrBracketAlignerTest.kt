package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrBracketAlignerTest {
    @Test fun recoversEvenTranslation() {
        val size = 32
        // Smooth gradient with texture so SAD has a clear minimum.
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB,
            FloatArray(size * size) { i ->
                val x = i % size
                val y = i / size
                ((x * 0.02f + y * 0.013f + ((x * y) % 7) * 0.01f)).coerceIn(0.05f, 0.9f)
            }, RawCrop(0, 0, size, size))
        val dx = 4
        val dy = -6
        val movValues = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val sx = (x - dx).coerceIn(0, size - 1)
            val sy = (y - dy).coerceIn(0, size - 1)
            ref.values[sy * size + sx]
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val shift = HdrBracketAligner.estimateShift(
            HdrMergeFrame(ref, 10_000_000L, 100),
            HdrMergeFrame(mov, 10_000_000L, 100))
        assertEquals(dx.toFloat(), shift.dx, 1.5f)
        assertEquals(dy.toFloat(), shift.dy, 1.5f)
    }

    @Test fun zeroShiftForIdenticalFrames() {
        val size = 32
        val values = FloatArray(size * size) { i ->
            ((i % size) * 0.02f + (i / size) * 0.011f).coerceIn(0.05f, 0.9f)
        }
        val a = UnpackedRawCfa(size, size, BayerPattern.RGGB, values, RawCrop(0, 0, size, size))
        val shift = HdrBracketAligner.estimateShift(
            HdrMergeFrame(a, 10_000_000L, 100),
            HdrMergeFrame(a.copy(), 10_000_000L, 100))
        assertTrue(kotlin.math.abs(shift.dx) < 1.5f && kotlin.math.abs(shift.dy) < 1.5f)
    }

    @Test fun chainedFlowSumsDisplacements() {
        val f = HdrBracketAligner.chain(
            HdrFlowField { _, _ -> 2f to 0f },
            HdrFlowField { _, _ -> 0f to 3f })
        val (dx, dy) = f!!.displacement(0, 0)
        assertEquals(2f, dx, 0f)
        assertEquals(3f, dy, 0f)
    }

    @Test fun snapEvenRoundsToCompleteCells() {
        val s = HdrBracketAligner.snapEven(HdrBracketAligner.Shift(3.2f, -5.7f))
        assertEquals(4f, s.dx, 0f)
        assertEquals(-6f, s.dy, 0f)
    }

    @Test fun rejectedDenseFlowKeepsFractionalTranslation() {
        val shift = HdrBracketAligner.Shift(3.2f, -5.7f)
        val flow = HdrBracketAligner.refinedFlow(shift, null)!!
        assertEquals(shift.dx, flow.displacement(10, 10).first, 0f)
        assertEquals(shift.dy, flow.displacement(10, 10).second, 0f)
        val refined = HdrBracketAligner.refinedFlow(shift, TranslationFlow(-0.8f, 0.3f))!!
        assertEquals(shift.dx, refined.displacement(10, 10).first, 1e-6f)
        assertEquals(shift.dy, refined.displacement(10, 10).second, 1e-6f)
    }

    @Test fun evenPreShiftPreservesCfaAtBorders() {
        val size = 16
        for (pattern in BayerPattern.entries) {
            val input = UnpackedRawCfa(size, size, pattern,
                FloatArray(size * size) { ((it / size and 1) * 2 + (it % size and 1)) / 4f },
                RawCrop(0, 0, size, size))
            for (shift in listOf(-20f, -2f, 2f, 20f)) {
                val out = HdrBracketAligner.warpShiftedEven(input, HdrBracketAligner.Shift(shift, -shift))
                for (i in input.values.indices) assertEquals(input.values[i], out.values[i], 0f)
            }
        }
    }

    @Test fun evenPreShiftIsLossless() {
        val size = 16
        val values = FloatArray(size * size) { it.toFloat() / (size * size) }
        val input = UnpackedRawCfa(size, size, BayerPattern.RGGB, values, RawCrop(0, 0, size, size))
        val out = HdrBracketAligner.warpShiftedEven(input, HdrBracketAligner.Shift(4f, -6f))
        // Interior is a pure reindex: every interior pixel equals its source exactly.
        for (y in 6..9) for (x in 4..11) {
            assertEquals(values[(y - 6) * size + (x + 4)], out.values[y * size + x], 0f)
        }
        assertEquals(BayerPattern.RGGB, out.pattern)
    }
}
