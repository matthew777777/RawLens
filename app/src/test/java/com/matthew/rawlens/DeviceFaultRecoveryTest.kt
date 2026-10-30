package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class DeviceFaultRecoveryTest {
    @Test fun disconnectedAuxRetriesThenFallsBackWithoutCycling() {
        val recovery = DeviceFaultRecovery()
        assertEquals("6", recovery.next("6", listOf("0", "2")))
        assertEquals("0", recovery.next("6", listOf("0", "2")))
        assertEquals("0", recovery.next("0", listOf("0", "2")))
        assertEquals("2", recovery.next("0", listOf("0", "2")))
        assertEquals("2", recovery.next("2", listOf("0", "2")))
        assertNull(recovery.next("2", listOf("0", "2")))
    }

    @Test fun unprovenRoutesAreNotTriedAndNewFramesRestoreBudget() {
        val recovery = DeviceFaultRecovery()
        assertEquals("7", recovery.next("7", emptyList()))
        assertNull(recovery.next("7", emptyList()))
        recovery.reset()
        assertEquals("7", recovery.next("7", emptyList()))
    }
}
