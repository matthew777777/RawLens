package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure host tests for [VkRawSrProcessor]: the SR guard estimate and fit
 * policy, plus the GPU-flow readback mapping behind the shared support
 * gate. No GL involved.
 */
class VkRawSrProcessorTest {
    @Test fun estimateScalesWithOutputArea() {
        val oneX = VkRawSrProcessor.estimateTransientBytes(4080, 3060, RawSrLinearScale.X1)
        val sr = VkRawSrProcessor.estimateTransientBytes(4080, 3060, RawSrLinearScale.SR)
        // SR doubles the output area (5768x4326 shared grid, pinned
        // literally: if the grid ever moves, this budget must move with
        // it); sources stay put. The estimate is the max of the finalize
        // peak (five RGBA32F + two R32F output planes, resident Rc, last
        // flow) and the moving peak (merge accumulators + resident
        // reference planes + one full alignment workspace: FFT scratch,
        // padded pyramid pair, flow ping-pong, sensor-space remap doubling
        // the finest flow (+815360B at 12MP), staging). Pinned literally:
        // 1x is moving-dominated, SR is finalize-dominated.
        assertEquals(1367860184L, oneX)
        assertEquals(2209108544L, sr)
    }

    @Test fun upscaleFitsGuardsSrOnThinDevices() {
        // ~2.1GB estimate at 12MP SR: fits with 4GB free, refused at 2GB.
        assertTrue(VkRawSrProcessor.upscaleFits(4080, 3060, RawSrLinearScale.SR, 4L shl 30))
        assertFalse(VkRawSrProcessor.upscaleFits(4080, 3060, RawSrLinearScale.SR, 2L shl 30))
        // 1x fits anywhere reasonable.
        assertTrue(VkRawSrProcessor.upscaleFits(4080, 3060, RawSrLinearScale.X1, 2L shl 30))
        // Boundary: ceil(4/3 of the estimate) fits, one byte less refuses.
        val est = VkRawSrProcessor.estimateTransientBytes(4080, 3060, RawSrLinearScale.SR)
        val edge = (est * 4 + 2) / 3
        assertTrue(VkRawSrProcessor.upscaleFits(4080, 3060, RawSrLinearScale.SR, edge))
        assertFalse(VkRawSrProcessor.upscaleFits(4080, 3060, RawSrLinearScale.SR, edge - 1))
    }

    @Test fun flowFieldFromReadbackKeepsRawUnitsVerbatim() {
        // 2x1 grid, 16 raw-px tiles on a 64x32 image. The 1:1 lattice
        // carries RAW-pixel flows on RAW-pixel tiles (reference
        // convention, like the CPU chain), so the mapping applies
        // verbatim — no x2 (retired: the legacy quad lattice scaled
        // quad-unit vectors to raw here and in the merge).
        val rgba = floatArrayOf(
            1.5f, -0.5f, 0.01f, 1f,
            0.0f, 2.0f, 0.02f, 1f
        )
        val field = VkRawSrProcessor.flowFieldFromReadback(rgba, 2, 1, 64, 32, 16)
        assertEquals(16, field.tileSize)
        assertEquals(2, field.columns)
        assertEquals(1, field.rows)
        assertEquals(1.5f, field.tiles[0].dx, 0f)
        assertEquals(-0.5f, field.tiles[0].dy, 0f)
        assertEquals(0.0f, field.tiles[1].dx, 0f)
        assertEquals(2.0f, field.tiles[1].dy, 0f)
        assertEquals(0.01f, field.tiles[0].residual, 0f)
        assertEquals(0.02f, field.tiles[1].residual, 0f)
        // Tile centers in raw pixels.
        assertEquals(8.0f, field.tiles[0].centerX, 0f)
        assertEquals(24.0f, field.tiles[1].centerX, 0f)
        assertEquals(8.0f, field.tiles[0].centerY, 0f)
        assertTrue(field.tiles.all { it.reliable })
    }

    @Test fun flowFieldFromReadbackMapsReliability() {
        // w <= 0 rejects; non-finite components reject even with w set.
        val rgba = floatArrayOf(
            0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f,
            Float.NaN, 0f, 0f, 1f,
            0f, Float.POSITIVE_INFINITY, 0f, 1f
        )
        val field = VkRawSrProcessor.flowFieldFromReadback(rgba, 2, 2, 64, 64, 16)
        assertEquals(listOf(true, false, false, false), field.tiles.map { it.reliable })
        assertEquals(0.25, RawSrFrameRejection.reliableFraction(field), 0.0)
    }

    @Test fun flowFieldFromReadbackJudgesThroughSharedGate() {
        // A healthy readback (all reliable, small span, strong r) keeps;
        // a zero-r readback rejects with low-mean-r — the same verdicts
        // the CPU chain reaches for identical stats.
        val columns = 4
        val rows = 4
        val rgba = FloatArray(columns * rows * 4) { i ->
            when (i % 4) {
                0 -> 0.1f
                1 -> -0.1f
                2 -> 0.01f
                else -> 1f
            }
        }
        val field = VkRawSrProcessor.flowFieldFromReadback(rgba, columns, rows, 128, 128, 16)
        val strong = RawSrRobustness.FrameRobustness(8, 8, FloatArray(64) { 0.9f }, IntArray(64))
        assertTrue(RawSrFrameRejection.judge(field, strong).keep)
        val empty = RawSrRobustness.FrameRobustness(8, 8, FloatArray(64), IntArray(64))
        val verdict = RawSrFrameRejection.judge(field, empty)
        assertFalse(verdict.keep)
        assertEquals("low-mean-r", verdict.rejectReason)
    }
}
