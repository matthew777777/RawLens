// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VendorKeysTest {
    private fun entry(
        name: String = "vivo.control.forceSensorMode",
        type: VendorKeyType = VendorKeyType.INT,
        scope: VendorKeyScope = VendorKeyScope.SESSION,
        value: String = "1",
        enabled: Boolean = true
    ) = VendorKeyEntry(name, type, scope, value, enabled)

    @Test fun `round trip keeps session and request entries per lens`() {
        val profile = VendorKeyProfile(
            listOf(
                entry(),
                VendorKeyEntry("android.sensor.blackLevelPattern", VendorKeyType.INT_ARRAY, VendorKeyScope.REQUEST, "64, 64, 64, 64")
            )
        )
        val loaded = VendorKeyProfile.fromJson(profile.toJson())
        assertEquals(profile, loaded)
        loaded.validate()
    }

    @Test fun `scope split separates early session keys from per-request keys`() {
        val profile = VendorKeyProfile(listOf(entry(), entry("x.oppo.tag", VendorKeyType.FLOAT, VendorKeyScope.REQUEST, "0.5")))
        assertEquals(listOf("vivo.control.forceSensorMode"), profile.sessionEntries().map { it.name })
        assertEquals(listOf("x.oppo.tag"), profile.requestEntries().map { it.name })
        assertTrue(VendorKeyProfile().sessionEntries().isEmpty())
    }

    @Test fun `disabled keys stay stored but leave the active set`() {
        val profile = VendorKeyProfile(listOf(entry(enabled = false)))
        assertFalse(profile.isEmpty())
        assertTrue(profile.activeEntries().isEmpty())
        assertEquals(profile, VendorKeyProfile.fromJson(profile.toJson()))
    }

    @Test fun `presets carry only the session DCG switch, disabled until the user opts in`() {
        val presets = VendorKeyProfile.presets()
        presets.validate()
        assertTrue(presets.activeEntries().isEmpty())
        assertEquals(1, presets.entries.size)
        val session = presets.entries.single()
        assertEquals("vivo.control.forceSensorMode", session.name)
        assertEquals(VendorKeyScope.SESSION, session.scope)
        assertEquals(1, session.parsedValue())
    }

    @Test fun `black level array parses comma and space forms`() {
        val parsed = VendorKeyEntry("a.b", VendorKeyType.INT_ARRAY, VendorKeyScope.REQUEST, "64,64, 64 64").parsedValue() as IntArray
        assertArrayEquals(intArrayOf(64, 64, 64, 64), parsed)
    }

    @Test fun `validation rejects nameless dotless and bad values`() {
        assertFails { entry(name = "   ").validate() }
        assertFails { entry(name = "nodomain").validate() }
        assertFails { entry(name = "a b.c").validate() }
        assertFails { entry(value = "not-a-number").validate() }
        assertFails { entry(type = VendorKeyType.BYTE, scope = VendorKeyScope.REQUEST, name = "a.b", value = "999").validate() }
        assertFails { entry(type = VendorKeyType.INT_ARRAY, scope = VendorKeyScope.REQUEST, name = "a.b", value = "").validate() }
    }

    @Test fun `validation rejects duplicate names and oversized profiles`() {
        assertFails { VendorKeyProfile(listOf(entry(), entry())).validate() }
        val many = List(VendorKeyProfile.MAX_ENTRIES + 1) { entry(name = "vendor.key$it") }
        assertFails { VendorKeyProfile(many).validate() }
    }

    @Test fun `load drops single bad rows but keeps valid keys`() {
        val good = entry().toJson().toString()
        val bad = """{"n":"broken","t":"Nope","s":"request","v":"0","e":true}"""
        val json = """{"v":1,"entries":[$good,$bad]}"""
        val loaded = VendorKeyProfile.fromJson(json)
        assertEquals(listOf("vivo.control.forceSensorMode"), loaded.entries.map { it.name })
    }

    @Test fun `blank store loads empty without throwing`() {
        assertTrue(VendorKeyProfile.fromJson(null).isEmpty())
        assertTrue(VendorKeyProfile.fromJson("  ").isEmpty())
        assertFails { VendorKeyProfile.fromJson("{not json").entries }
    }

    @Test fun `summary labels rows for the settings editor`() {
        assertEquals(
            "vivo.control.forceSensorMode • Int32 • session • 1",
            entry().summary()
        )
        assertEquals(
            "x.oppo.tag • Float • request • 0.5 • OFF",
            entry("x.oppo.tag", VendorKeyType.FLOAT, VendorKeyScope.REQUEST, "0.5", enabled = false).summary()
        )
    }

    @Test fun `scalar types parse their boxed values`() {
        assertEquals(7, VendorKeyEntry("a.b", VendorKeyType.INT, VendorKeyScope.REQUEST, "7").parsedValue())
        assertEquals(9L, VendorKeyEntry("a.b", VendorKeyType.LONG, VendorKeyScope.REQUEST, "9").parsedValue())
        assertEquals(0.5f, VendorKeyEntry("a.b", VendorKeyType.FLOAT, VendorKeyScope.REQUEST, "0.5").parsedValue())
        assertEquals(true, VendorKeyEntry("a.b", VendorKeyType.BOOLEAN, VendorKeyScope.REQUEST, "true").parsedValue())
        assertEquals(1.toByte(), VendorKeyEntry("a.b", VendorKeyType.BYTE, VendorKeyScope.REQUEST, "1").parsedValue())
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (e: Exception) {
            throw AssertionError("Expected IllegalArgumentException, got $e")
        }
        throw AssertionError("Expected IllegalArgumentException, block passed")
    }
}
