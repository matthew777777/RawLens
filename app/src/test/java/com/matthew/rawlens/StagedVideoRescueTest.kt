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

    /** Minimal closed container: payload plus a valid type-0 footer. */
    private fun closedTake(dir: File, name: String, payloadBytes: Int = 64): File {
        val file = File(dir, name)
        val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(0x8A905612.toInt()).putInt(3).putLong(8)
        val payload = ByteArray(payloadBytes) { it.toByte() }
        file.writeBytes(payload + tail.array())
        return file
    }

    @Test fun selectsClosedTakesOldestFirst() {
        val dir = stageDir()
        val old = closedTake(dir, "IMG_20260926_010000_000_VID.mcraw")
        old.setLastModified(1000L)
        val new = closedTake(dir, "IMG_20260926_020000_000_VID.mcraw")
        new.setLastModified(2000L)
        assertEquals(listOf(old, new), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun excludesActiveRecordingByIdentity() {
        val dir = stageDir()
        val active = closedTake(dir, "IMG_20260926_010000_000_VID.mcraw")
        // A distinct File object for the same path still matches.
        val samePath = File(dir, active.name)
        assertTrue(RawVideoSaver.stagedTakes(dir, samePath).isEmpty())
        assertEquals(listOf(active), RawVideoSaver.stagedTakes(dir, null))
    }

    @Test fun ignoresNonTakesAndMissingDir() {
        val dir = stageDir()
        File(dir, "notes.txt").writeText("not a take")
        File(dir, "empty.mcraw").writeBytes(byteArrayOf())
        File(dir, "nested").mkdir()
        assertTrue(RawVideoSaver.stagedTakes(dir, null).isEmpty())
        assertTrue(RawVideoSaver.stagedTakes(File(dir, "absent"), null).isEmpty())
    }

    @Test fun rejectsUnfinalizedTakes() {
        val dir = stageDir()
        // Truncated: shorter than magic + footer.
        File(dir, "short.mcraw").writeBytes(ByteArray(16) { 1 })
        // Garbage tail: right size, wrong magic.
        File(dir, "garbage.mcraw").writeBytes(ByteArray(64) { 2 })
        // Bad footer: zero frame count.
        val zero = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(0x8A905612.toInt()).putInt(0).putLong(8)
        File(dir, "zero.mcraw").writeBytes(ByteArray(64) { 3 } + zero.array())
        // Bad footer: index offset outside the file.
        val badOff = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(0x8A905612.toInt()).putInt(3).putLong(9999)
        File(dir, "badoff.mcraw").writeBytes(ByteArray(64) { 4 } + badOff.array())
        assertTrue(RawVideoSaver.stagedTakes(dir, null).isEmpty())
    }
}
