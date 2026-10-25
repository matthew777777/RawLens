// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstMergesTest {
    @Test fun evenSampleKeepsAllWhenFitting() {
        assertEquals((0 until 8).toList(), BurstMergeTakes.evenSampleIndices(8, 8))
        assertEquals((0 until 5).toList(), BurstMergeTakes.evenSampleIndices(5, 15))
    }

    @Test fun evenSampleSpreadsEndpoints() {
        assertEquals(listOf(0, 2, 4, 6, 8), BurstMergeTakes.evenSampleIndices(9, 5))
        assertEquals(listOf(0, 58), BurstMergeTakes.evenSampleIndices(59, 2))
    }

    @Test fun evenSampleMiddleForSingle() {
        assertEquals(listOf(29), BurstMergeTakes.evenSampleIndices(59, 1))
    }

    @Test fun evenSampleEmpty() {
        assertTrue(BurstMergeTakes.evenSampleIndices(0, 8).isEmpty())
        assertTrue(BurstMergeTakes.evenSampleIndices(0, 0).isEmpty())
    }

    @Test fun etaUnknownBeforeFirstUnit() {
        assertNull(BurstMergeRunner.etaRemaining(1000L, 0, 8))
        assertEquals(0L, BurstMergeRunner.etaRemaining(1000L, 8, 8))
    }

    @Test fun etaScalesLinearly() {
        // 2 of 8 frames in 1000ms -> 3000ms left.
        assertEquals(3000L, BurstMergeRunner.etaRemaining(1000L, 2, 8))
        assertEquals(0L, BurstMergeRunner.etaRemaining(0L, 4, 8))
    }

    @Test fun estimateCountsSelectedOutputs() {
        val dngOnly = BurstMergeRunner.estimateOutBytes(
            100, 100, CaptureFormat.DNG_ONLY, BurstMergeRunner.Method.MFSR)
        val both = BurstMergeRunner.estimateOutBytes(
            100, 100, CaptureFormat.JPEG_DNG, BurstMergeRunner.Method.MFSR)
        val jpegOnly = BurstMergeRunner.estimateOutBytes(
            100, 100, CaptureFormat.JPEG, BurstMergeRunner.Method.HDRPLUS)
        assertEquals(100L * 100 * 6, dngOnly)
        assertEquals(dngOnly + 100L * 100, both)
        assertEquals(100L * 100, jpegOnly)
    }

    @Test fun loadStageListsWithoutNativeLib() {
        // Host JVM has no native library: rows fall back to footer stats.
        val dir = Files.createTempDirectory("rawlens-merges").toFile()
        stagedBurst(dir, "IMG_20260926_010000_000_BURST.crs", frames = 5)
            .setLastModified(1000L)
        stagedBurst(dir, "IMG_20260926_020000_000_BURST.crs", frames = 9)
            .setLastModified(2000L)
        val rows = BurstMergeTakes.loadStage(dir)
        assertEquals(2, rows.size)
        assertEquals(5, rows[0].frameCount)
        assertEquals(9, rows[1].frameCount)
        assertTrue(rows.all { it.width == null && it.height == null })
        assertTrue(rows.all { it.fileBytes > 0 })
    }

    @Test fun cfaNamesMapToPatterns() {
        assertEquals(BayerPattern.RGGB, OfflineBurstMetadata.cfaFrom("rggb"))
        assertEquals(BayerPattern.GRBG, OfflineBurstMetadata.cfaFrom("grbg"))
        assertEquals(BayerPattern.GBRG, OfflineBurstMetadata.cfaFrom("gbrg"))
        assertEquals(BayerPattern.BGGR, OfflineBurstMetadata.cfaFrom("bggr"))
    }

    private fun stagedBurst(dir: File, name: String, frames: Int): File {
        val file = File(dir, name)
        val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(CrsFormat.FOOTER_MAGIC).putInt(frames).putLong(8)
        file.writeBytes(ByteArray(64) { it.toByte() } + tail.array())
        return file
    }
}
