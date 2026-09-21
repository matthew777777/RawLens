// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatFileWriterTest {
    @Test fun `session names carry a sortable timestamp stem`() {
        val name = LogcatFileWriter.sessionFileName(1780276800000L)
        assertTrue(name.startsWith("logcat-"))
        assertTrue(name.endsWith(".txt"))
        // Fixed instant must format deterministically (modulo device time zone,
        // which only shifts the stem, never its shape).
        // "logcat-" (7) + yyyyMMdd_HHmmss_SSS stem (19) + ".txt" (4).
        assertEquals(30, name.length)
    }

    @Test fun `prune keeps the newest sessions and drops the rest oldest-first`() {
        val names = listOf(
            "logcat-20260921_100000_000.txt",
            "notes.txt",
            "logcat-20260921_120000_000.txt",
            "logcat-20260921_110000_000.txt"
        )
        assertEquals(
            listOf("logcat-20260921_100000_000.txt"),
            LogcatFileWriter.sessionsToDelete(names, 2)
        )
    }

    @Test fun `prune ignores non-session files and short lists`() {
        assertTrue(LogcatFileWriter.sessionsToDelete(listOf("notes.txt"), 5).isEmpty())
        val names = listOf("logcat-20260921_100000_000.txt")
        assertTrue(LogcatFileWriter.sessionsToDelete(names, 5).isEmpty())
    }

    @Test fun `session names accept the writer format and reject traversal`() {
        assertTrue(LogcatFileWriter.isSessionName("logcat-20260921_143005_042.txt"))
        assertTrue(!LogcatFileWriter.isSessionName("notes.txt"))
        assertTrue(!LogcatFileWriter.isSessionName("logcat-20260921_143005_042.log"))
        assertTrue(!LogcatFileWriter.isSessionName("../logcat-20260921_143005_042.txt"))
        assertTrue(!LogcatFileWriter.isSessionName("sub/logcat-20260921_143005_042.txt"))
    }

    @Test fun `mirror is due after the interval and overdue from zero`() {
        assertTrue(LogcatFileWriter.mirrorDue(0L, LogcatFileWriter.MIRROR_INTERVAL_MS))
        assertTrue(LogcatFileWriter.mirrorDue(1000L, 1000L + LogcatFileWriter.MIRROR_INTERVAL_MS))
        assertTrue(!LogcatFileWriter.mirrorDue(1000L, 1000L + LogcatFileWriter.MIRROR_INTERVAL_MS - 1))
    }

    @Test fun `rotation budget constants stay bounded`() {
        assertEquals(8L * 1024L * 1024L, LogcatFileWriter.MAX_BYTES)
        assertEquals(5, LogcatFileWriter.KEEP_SESSIONS)
    }
}
