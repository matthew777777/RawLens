// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstCrsStoreTest {
    private fun stageDir(): File =
        Files.createTempDirectory("rawlens-bursts").toFile()

    private fun closedFile(dir: File, name: String, frameCount: Int = 3): File {
        val file = File(dir, name)
        val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(CrsFormat.FOOTER_MAGIC).putInt(frameCount).putLong(8)
        file.writeBytes(ByteArray(64) { it.toByte() } + tail.array())
        return file
    }

    @Test fun burstFileNameHasBurstSuffix() {
        val ts = 1_786_269_212_527L
        val name = BurstCrsStore.burstFileName(ts)
        assertEquals(CaptureFileNames.burstCrs(ts), name)
        assertTrue(name, name.endsWith("_BURST.crs"))
    }

    @Test fun recognizesOnlyBurstCrs() {
        val dir = stageDir()
        val burst = closedFile(dir, "IMG_20260926_010000_000_BURST.crs")
        val video = closedFile(dir, "IMG_20260926_010000_000_VID.crs")
        assertTrue(BurstCrsStore.isBurstFile(burst))
        assertFalse(BurstCrsStore.isBurstFile(video))
        assertFalse(BurstCrsStore.isBurstFile(File(dir, "notes.txt").apply { writeText("x") }))
    }

    @Test fun stagedBurstsExcludesVideoAndOrdersOldestFirst() {
        val dir = stageDir()
        closedFile(dir, "IMG_20260926_010000_000_VID.crs").setLastModified(500L)
        val old = closedFile(dir, "IMG_20260926_010000_001_BURST.crs")
        old.setLastModified(1000L)
        val new = closedFile(dir, "IMG_20260926_020000_000_BURST.crs")
        new.setLastModified(2000L)
        assertEquals(listOf(old, new), BurstCrsStore.stagedBursts(dir))
    }

    @Test fun stagedBurstsRejectsFooterless() {
        val dir = stageDir()
        File(dir, "IMG_20260926_010000_000_BURST.crs").writeBytes(ByteArray(16) { 1 })
        assertTrue(BurstCrsStore.stagedBursts(dir).isEmpty())
    }

    @Test fun videoStagedTakesExcludesBursts() {
        val dir = stageDir()
        val video = closedFile(dir, "IMG_20260926_010000_000_VID.crs")
        closedFile(dir, "IMG_20260926_010000_001_BURST.crs")
        assertEquals(listOf(video), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun listTakesReportsFooterFrameCount() {
        val dir = stageDir()
        closedFile(dir, "IMG_20260926_010000_000_BURST.crs", frameCount = 7)
        val takes = BurstCrsStore.listTakes(dir)
        assertEquals(1, takes.size)
        assertEquals(7, takes[0].frameCount)
        assertTrue(takes[0].fileBytes > 0)
    }
}
