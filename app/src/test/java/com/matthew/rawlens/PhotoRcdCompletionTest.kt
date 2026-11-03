// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class PhotoRcdCompletionTest {
    @Test fun duplicateFailureRetainsOriginalUntilCompletion() {
        val owner = PhotoRcdCompletion()
        try { owner.submitted(7) { throw java.io.IOException("EMFILE") }; fail() }
        catch (_: java.io.IOException) { }
        assertTrue(owner.pending)
        val closed = mutableListOf<Int>()
        assertFalse(owner.complete(30, { fd, timeout -> assertEquals(7, fd); assertEquals(30, timeout); false }, closed::add))
        assertTrue(closed.isEmpty())
        try { owner.submitted(8) { 9 }; fail() } catch (_: IllegalStateException) { }
        assertTrue(owner.complete(0, { fd, _ -> fd == 7 }, closed::add))
        assertEquals(listOf(7), closed)
        assertFalse(owner.pending)
    }

    @Test fun deliveryAndOriginalHaveSeparateOwnersAcrossReuse() {
        val owner = PhotoRcdCompletion(); val closed = mutableListOf<Int>()
        assertEquals(11, owner.submitted(10) { 11 })
        assertTrue(owner.complete(100, { _, _ -> true }, closed::add))
        assertTrue(owner.complete(0, { _, _ -> error("no pending work") }, closed::add))
        assertEquals(listOf(10), closed)
        assertEquals(21, owner.submitted(20) { 21 })
        assertTrue(owner.complete(0, { _, _ -> true }, closed::add))
        assertEquals(listOf(10, 20), closed)
    }

    @Test fun waitExceptionDoesNotAuthorizeReuse() {
        val owner = PhotoRcdCompletion(); owner.submitted(3) { 4 }
        try { owner.complete(1, { _, _ -> throw IllegalStateException("driver error") }, { fail() }); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(owner.pending)
    }
}
