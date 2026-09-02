// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCaptureJobTest {
    @Test fun `success releases every item exactly once`() {
        val releases = IntArray(3)
        val owner = CloseOnceOwner(listOf(0, 1, 2)) { releases[it]++ }
        var calls = 0
        val job = OwnedCaptureJob(owner) { calls++ }

        job.run()
        job.run()
        owner.close()

        assertEquals(1, calls)
        assertTrue(releases.all { it == 1 })
        assertEquals(OwnedCaptureJob.RELEASED, job.stateForTest())
    }

    @Test fun `exception releases every item exactly once`() {
        val releases = IntArray(2)
        val job = OwnedCaptureJob(CloseOnceOwner(listOf(0, 1)) { releases[it]++ }) {
            error("diagnostic failure")
        }

        runCatching(job::run)
        job.cancelBeforeRun()

        assertTrue(releases.all { it == 1 })
    }

    @Test fun `queue rejection or cancellation releases without running`() {
        var releases = 0
        var calls = 0
        val job = OwnedCaptureJob(CloseOnceOwner(listOf("frame")) { releases++ }) { calls++ }

        assertTrue(job.cancelBeforeRun())
        assertFalse(job.cancelBeforeRun())
        job.run()

        assertEquals(0, calls)
        assertEquals(1, releases)
    }

    @Test fun `owner defensively snapshots burst membership`() {
        val source = mutableListOf("reference", "moving")
        val owner = CloseOnceOwner(source) {}
        source.clear()

        assertEquals(listOf("reference", "moving"), owner.items)
    }
}
