// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Integer values are the API 37 CameraMetadata constants (javap -constants). */
class Camera2NamesTest {
    @Test
    fun aeModesIncludingApi28And35() {
        val expected = listOf("OFF", "ON", "AUTO_FLASH", "ALWAYS_FLASH", "AUTO_REDEYE",
            "EXTERNAL_FLASH", "LOW_LIGHT_BOOST")
        expected.forEachIndexed { v, name -> assertEquals(name, Camera2Names.aeModeName(v)) }
        assertEquals("UNKNOWN(7)", Camera2Names.aeModeName(7))
    }

    @Test
    fun afModesAndStates() {
        listOf("OFF", "AUTO", "MACRO", "CONT_VIDEO", "CONT_PICTURE", "EDOF")
            .forEachIndexed { v, name -> assertEquals(name, Camera2Names.afModeName(v)) }
        listOf("INACTIVE", "PASSIVE_SCAN", "PASSIVE_FOCUSED", "ACTIVE_SCAN",
            "FOCUSED_LOCKED", "NOT_FOCUSED_LOCKED", "PASSIVE_UNFOCUSED")
            .forEachIndexed { v, name -> assertEquals(name, Camera2Names.afStateName(v)) }
    }

    @Test
    fun aeStatesTriggersAndOis() {
        listOf("INACTIVE", "SEARCHING", "CONVERGED", "LOCKED", "FLASH_REQUIRED", "PRECAPTURE")
            .forEachIndexed { v, name -> assertEquals(name, Camera2Names.aeStateName(v)) }
        listOf("IDLE", "START", "CANCEL").forEachIndexed { v, name ->
            assertEquals(name, Camera2Names.afTriggerName(v))
            assertEquals(name, Camera2Names.aeTriggerName(v))
        }
        assertEquals("IDLE", Camera2Names.afTriggerName(null))
        assertEquals("OFF", Camera2Names.oisModeName(0))
        assertEquals("ON", Camera2Names.oisModeName(1))
        assertEquals("UNAVAILABLE", Camera2Names.oisModeName(null))
    }
}
