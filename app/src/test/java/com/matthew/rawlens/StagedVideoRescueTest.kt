// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedVideoRescueTest {
    private fun stageDir(): File =
        Files.createTempDirectory("rawlens-rescue").toFile()

    /** Minimal closed .CRAW take: header, payload, INDX and trailer. */
    private fun closedTake(dir: File, name: String, payloadBytes: Int = 64): File {
        val file = File(dir, name)
        file.writeBytes(CrawFormat.closedStub(3, ByteArray(payloadBytes) { it.toByte() }))
        return file
    }

    @Test fun selectsClosedTakesOldestFirst() {
        val dir = stageDir()
        val old = closedTake(dir, "IMG_20260926_010000_000_VID.craw")
        old.setLastModified(1000L)
        val new = closedTake(dir, "IMG_20260926_020000_000_VID.craw")
        new.setLastModified(2000L)
        assertEquals(listOf(old, new), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun excludesActiveRecordingByIdentity() {
        val dir = stageDir()
        val active = closedTake(dir, "IMG_20260926_010000_000_VID.craw")
        // A distinct File object for the same path still matches.
        val samePath = File(dir, active.name)
        assertTrue(RawVideoSaver.stagedTakes(dir, samePath).isEmpty())
        assertEquals(listOf(active), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun ignoresNonTakesAndMissingDir() {
        val dir = stageDir()
        File(dir, "notes.txt").writeText("not a take")
        File(dir, "empty.craw").writeBytes(byteArrayOf())
        File(dir, "nested").mkdir()
        assertTrue(RawVideoSaver.stagedTakes(dir, null).isEmpty())
        assertTrue(RawVideoSaver.stagedTakes(File(dir, "absent"), null).isEmpty())
    }

    @Test fun rejectsUnfinalizedTakes() {
        val dir = stageDir()
        // Truncated: shorter than magic + footer.
        File(dir, "short.craw").writeBytes(ByteArray(16) { 1 })
        // Garbage tail: right size, wrong magic.
        File(dir, "garbage.craw").writeBytes(ByteArray(64) { 2 })
        // Closed but empty: no frames indexed.
        File(dir, "zero.craw").writeBytes(CrawFormat.closedStub(0, ByteArray(64) { 3 }))
        // Bad trailer: index offset outside the file.
        val badOff = CrawFormat.closedStub(3, ByteArray(64) { 4 })
        ByteBuffer.wrap(badOff).order(ByteOrder.LITTLE_ENDIAN).putLong(badOff.size - 16, 9999L)
        File(dir, "badoff.craw").writeBytes(badOff)
        // Crashed take: valid header, no trailer.
        File(dir, "crashed.craw").writeBytes(CrawFormat.closedStub(3).copyOf(40))
        assertTrue(RawVideoSaver.stagedTakes(dir, null).isEmpty())
    }
}
