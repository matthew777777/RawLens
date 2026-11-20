package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class MonoModeTest {
    @Test fun offIsDefaultAndOldNamesMigrateToClean() {
        assertEquals(MonoMode.OFF, MonoSettings().mode)
        for (value in listOf(null, "unknown", "OFF")) {
            assertEquals(MonoMode.OFF, MonoMode.fromPreference(value))
        }
        for (value in listOf("AUTO", "DIRECT", "CLEAN")) {
            assertEquals(MonoMode.CLEAN, MonoMode.fromPreference(value))
        }
        assertEquals(MonoMode.DETAIL, MonoMode.fromPreference("DETAIL"))
    }
    @Test fun cycleContainsOnlyExplicitModes() {
        assertEquals(listOf(MonoMode.OFF, MonoMode.DETAIL, MonoMode.CLEAN), MonoMode.entries.toList())
        assertEquals(MonoMode.OFF, MonoMode.CLEAN.next())
    }
}
