package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class MonoModeTest {
    @Test fun cleanIsDefaultAndMigratesAuto() {
        assertEquals(MonoMode.CLEAN, MonoSettings().mode)
        for (value in listOf(null, "AUTO", "DIRECT", "unknown")) {
            assertEquals(MonoMode.CLEAN, MonoMode.fromPreference(value))
        }
        assertEquals(MonoMode.OFF, MonoMode.fromPreference("OFF"))
        assertEquals(MonoMode.DETAIL, MonoMode.fromPreference("DETAIL"))
    }
    @Test fun cycleContainsOnlyExplicitModes() {
        assertEquals(listOf(MonoMode.OFF, MonoMode.DETAIL, MonoMode.CLEAN), MonoMode.entries.toList())
        assertEquals(MonoMode.OFF, MonoMode.CLEAN.next())
    }
}
