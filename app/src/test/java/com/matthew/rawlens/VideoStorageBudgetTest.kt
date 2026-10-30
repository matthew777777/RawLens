// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VideoStorageBudgetTest {
    @Test fun completedTakeSyncsClosesThenPublishesWithoutAnotherFile() {
        val events = mutableListOf<String>()
        assertTrue(RecordingCompletion.finish(true, 20_000_000_000L,
            { events += "sync" }, { events += "close" }, { events += "publish" }, { fail("must keep take") }))
        assertEquals(listOf("sync", "close", "publish"), events)
    }
    @Test fun flushFailureClosesAndRetainsPartialTakeInsteadOfClaimingSuccess() {
        val events = mutableListOf<String>()
        try {
            RecordingCompletion.finish(true, 1234,
                { throw IOException("USB disconnected") }, { events += "close" },
                { events += "publish" }, { fail("must not delete nonempty take") })
            fail("must report finalization failure")
        } catch (e: IOException) { assertEquals("USB disconnected", e.cause?.message) }
        assertEquals(listOf("close", "publish"), events)
    }
    @Test fun onlyKnownEmptyFilesAreRemoved() {
        var deleted = 0
        assertFalse(RecordingCompletion.finish(false, 0,
            { fail("not valid") }, {}, { fail("empty take must not publish") }, { deleted++ }))
        assertEquals(1, deleted)
        assertFalse(RecordingCompletion.finish(false, -1,
            { fail("not valid") }, {}, {}, { fail("unknown size may mean unplugged drive") }))
    }
    @Test fun invalidNonemptyTakeStaysAtItsDestination() {
        var published = false
        assertFalse(RecordingCompletion.finish(false, 100,
            { fail("not valid") }, {}, { published = true }, { fail("preserve partial take") }))
        assertTrue(published)
    }
}
