// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RawSrPackedFrameTest {
    @Test fun metadataAndPlaneWindowAreFrozenWithoutCopyingPixels() {
        val plane = ByteBuffer.allocateDirect(128).apply { position(4) }
        val black = mutableListOf(1f, 2f, 3f, 4f)
        val gains = floatArrayOf(2f, 3f, 4f, 5f)
        val frame = RawSrPackedFrame(plane, RawPlaneLayout(6, 6, 16, 2, 1, 0),
            RawCrop(1, 1, 4, 4), RawNormalization(BayerPattern.RGGB, black, 100f),
            LensShadingModel(1, 1, gains, IntRectSnapshot(0, 0, 8, 8)),
            ImmutableDoubleValues(doubleArrayOf(0.1, 0.01)))
        black[0] = 99f; gains[0] = 99f; plane.position(0); plane.limit(1)
        val input = frame.uploadInput()
        assertEquals(4, input.buffer.position())
        assertEquals(128, input.buffer.limit())
        assertTrue(input.buffer.isReadOnly)
        assertEquals(1f, input.normalization.blackLevels[0])
        assertEquals(2f, input.lensShading!!.gains[0])
        input.lensShading.gains[0] = 88f
        assertEquals(2f, frame.uploadInput().lensShading!!.gains[0])
        assertEquals(BayerPattern.GBRG, frame.pattern)
        assertEquals(0.1, frame.noiseProfile!![0], 0.0)
    }

    @Test fun allSensorAndCropParityCombinationsPreservePhaseAndBlackLevels() {
        for (pattern in BayerPattern.entries) for (sx in 0..1) for (sy in 0..1)
            for (cx in 0..1) for (cy in 0..1) {
                val normalization = RawNormalization(pattern, listOf(1f, 2f, 3f, 4f), 100f)
                val frame = RawSrPackedFrame(ByteBuffer.allocate(72), RawPlaneLayout(6, 6, 12, 2, sx, sy),
                    RawCrop(cx, cy, 4, 4), normalization, null)
                val input = frame.uploadInput()
                for (y in 0..1) for (x in 0..1) {
                    assertEquals(pattern.colorAt(sx + cx + x, sy + cy + y), frame.pattern.colorAt(x, y))
                    assertEquals(normalization.blackAt(sx + cx + x, sy + cy + y),
                        input.normalization.blackAt(input.sensorCropLeft + x, input.sensorCropTop + y))
                }
            }
    }

    @Test fun rejectsUnsupportedStridesAndTruncatedPlanes() {
        fun rejected(layout: RawPlaneLayout, bytes: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                RawSrPackedFrame(ByteBuffer.allocate(bytes), layout, RawCrop(0, 0, 4, 4),
                    RawNormalization(BayerPattern.RGGB, List(4) { 0f }, 100f), null)
            }
        }
        rejected(RawPlaneLayout(4, 4, 16, 4), 64)
        rejected(RawPlaneLayout(4, 4, 9, 2), 36)
        rejected(RawPlaneLayout(4, 4, 8, 2), 31)
    }

    @Test fun textureLedgerTracksPeakAndBalancesSequentialWorkspaces() {
        fun run(frames: Int): Long {
            val ledger = RawSrTextureMemory()
            val persistent = 40L * 4000 * 3000
            val workspace = 20L * 4000 * 3000
            ledger.allocate(persistent)
            repeat(frames) {
                ledger.allocate(workspace)
                ledger.release(workspace)
                assertEquals(persistent, ledger.liveBytes)
            }
            ledger.release(persistent)
            assertEquals(0L, ledger.liveBytes)
            return ledger.peakBytes
        }
        for (count in listOf(2, 8, 15, 30)) assertEquals(run(2), run(count))
        assertThrows(IllegalArgumentException::class.java) { RawSrTextureMemory().release(1) }
    }
}
