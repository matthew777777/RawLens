// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstCrawStoreTest {
    private fun stageDir(): File =
        Files.createTempDirectory("rawlens-bursts").toFile()

    private fun closedFile(dir: File, name: String, frameCount: Int = 3): File {
        val file = File(dir, name)
        file.writeBytes(CrawFormat.closedStub(frameCount, ByteArray(64) { it.toByte() }))
        return file
    }

    @Test fun burstFileNameHasBurstSuffix() {
        val ts = 1_786_269_212_527L
        val name = BurstCrawStore.burstFileName(ts)
        assertEquals(CaptureFileNames.burstCraw(ts), name)
        assertTrue(name, name.endsWith("_BURST.craw"))
    }

    @Test fun recognizesOnlyBurstCraw() {
        val dir = stageDir()
        val burst = closedFile(dir, "IMG_20260926_010000_000_BURST.craw")
        val video = closedFile(dir, "IMG_20260926_010000_000_VID.craw")
        assertTrue(BurstCrawStore.isBurstFile(burst))
        assertFalse(BurstCrawStore.isBurstFile(video))
        assertFalse(BurstCrawStore.isBurstFile(File(dir, "notes.txt").apply { writeText("x") }))
    }

    @Test fun stagedBurstsExcludesVideoAndOrdersOldestFirst() {
        val dir = stageDir()
        closedFile(dir, "IMG_20260926_010000_000_VID.craw").setLastModified(500L)
        val old = closedFile(dir, "IMG_20260926_010000_001_BURST.craw")
        old.setLastModified(1000L)
        val new = closedFile(dir, "IMG_20260926_020000_000_BURST.craw")
        new.setLastModified(2000L)
        assertEquals(listOf(old, new), BurstCrawStore.stagedBursts(dir))
    }

    @Test fun stagedBurstsRejectsFooterless() {
        val dir = stageDir()
        File(dir, "IMG_20260926_010000_000_BURST.craw").writeBytes(ByteArray(16) { 1 })
        assertTrue(BurstCrawStore.stagedBursts(dir).isEmpty())
    }

    @Test fun videoStagedTakesExcludesBursts() {
        val dir = stageDir()
        val video = closedFile(dir, "IMG_20260926_010000_000_VID.craw")
        closedFile(dir, "IMG_20260926_010000_001_BURST.craw")
        assertEquals(listOf(video), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun listTakesReportsFooterFrameCount() {
        val dir = stageDir()
        closedFile(dir, "IMG_20260926_010000_000_BURST.craw", frameCount = 7)
        val takes = BurstCrawStore.listTakes(dir)
        assertEquals(1, takes.size)
        assertEquals(7, takes[0].frameCount)
        assertTrue(takes[0].fileBytes > 0)
    }
}
