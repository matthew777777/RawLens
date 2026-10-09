package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BguFenceTest {

    @Test
    fun takeEmptySlotYieldsMinusOne() {
        val fds = intArrayOf(-1, -1, -1)
        assertEquals(-1, takeSlotFence(fds, 1))
        assertArrayEquals(intArrayOf(-1, -1, -1), fds)
    }

    @Test
    fun takeClaimsAndClears() {
        val fds = intArrayOf(7, -1, 9)
        assertEquals(7, takeSlotFence(fds, 0))
        assertArrayEquals(intArrayOf(-1, -1, 9), fds)
        assertEquals(9, takeSlotFence(fds, 2))
        assertArrayEquals(intArrayOf(-1, -1, -1), fds)
    }

    @Test
    fun storePublishesAndReturnsStale() {
        val fds = intArrayOf(-1, 5, -1)
        assertEquals(-1, storeSlotFence(fds, 0, 11))
        assertEquals(5, storeSlotFence(fds, 1, 12))
        assertArrayEquals(intArrayOf(11, 12, -1), fds)
    }

    @Test
    fun storeTakeRoundTripAcrossSlots() {
        val fds = IntArray(3) { -1 }
        for (slot in 0..2) storeSlotFence(fds, slot, 100 + slot)
        // Render overwrites slot 1 before offer takes: stale 101 returned.
        assertEquals(101, storeSlotFence(fds, 1, 201))
        assertEquals(100, takeSlotFence(fds, 0))
        assertEquals(201, takeSlotFence(fds, 1))
        assertEquals(102, takeSlotFence(fds, 2))
        assertArrayEquals(intArrayOf(-1, -1, -1), fds)
    }
}
