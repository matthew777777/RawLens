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

    @Test fun `capture stop marker names the reason and the truncation`() {
        // A truncated session must be self-describing: without this marker
        // a dead logcat child (file ends mid-session, app alive) reads
        // exactly like a dead process.
        assertEquals(
            "----- logcat capture stopped (respawn budget spent); " +
                "session truncated, app continues -----",
            LogcatFileWriter.captureStoppedMarker("respawn budget spent")
        )
    }

    @Test fun `rotation budget constants stay bounded`() {
        assertEquals(8L * 1024L * 1024L, LogcatFileWriter.MAX_BYTES)
        assertEquals(5, LogcatFileWriter.KEEP_SESSIONS)
    }

    @Test fun `spawn failure marker names the cause and the headers-only session`() {
        // A spawn that throws used to leave an orphaned headers-only session
        // that exported as a mysteriously empty log (vivo field report: a
        // 2-line file with no diagnosis at all).
        assertEquals(
            "----- logcat capture failed to start (IOException: Permission denied); " +
                "session has headers only, app continues -----",
            LogcatFileWriter.captureSpawnFailedMarker("IOException: Permission denied")
        )
    }

    @Test fun `silence marker names the pid and the wait`() {
        // A live-but-silent logcat must be a diagnosed device restriction,
        // never an incomplete recording.
        assertEquals(
            "----- logcat produced no lines for pid 1234 after 15s " +
                "(device logd may restrict app logs); session has headers only, app continues -----",
            LogcatFileWriter.captureSilentMarker(1234, 15)
        )
    }

    @Test fun `silence timeout is decisive but not hasty`() {
        // logcat dumps the pid buffer within milliseconds on a working
        // device; the watchdog must wait long enough to rule out slow
        // startup, yet fire well before the first scheduled mirror.
        assertTrue(LogcatFileWriter.SILENCE_TIMEOUT_MS >= 10_000L)
        assertTrue(LogcatFileWriter.SILENCE_TIMEOUT_MS < LogcatFileWriter.MIRROR_INTERVAL_MS)
    }
}
