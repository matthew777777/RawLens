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

    @Test fun `preset names trim and reject blanks and odd characters`() {
        assertEquals("14-bit MediaTek", VendorKeyStore.cleanPresetName("  14-bit MediaTek "))
        assertFails { VendorKeyStore.cleanPresetName("   ") }
        assertFails { VendorKeyStore.cleanPresetName("a/b") }
        assertFails { VendorKeyStore.cleanPresetName("x".repeat(VendorKeyStore.MAX_PRESET_NAME_CHARS + 1)) }
    }

    @Test fun `preset merge adds only missing keys and never deletes`() {
        val lens = VendorKeyProfile(
            listOf(
                entry("vivo.control.forceSensorMode", VendorKeyType.INT, VendorKeyScope.SESSION, "1"),
                entry("lens.only", VendorKeyType.INT, VendorKeyScope.REQUEST, "2")
            )
        )
        val preset = VendorKeyProfile(
            listOf(
                entry("vivo.control.forceSensorMode", VendorKeyType.INT, VendorKeyScope.SESSION, "9"),
                entry("preset.only", VendorKeyType.FLOAT, VendorKeyScope.REQUEST, "0.5")
            )
        )
        val merged = lens.mergedWith(preset)
        merged.validate()
        // Lens value wins on the shared name; preset-only key is appended.
        assertEquals("1", merged.entries.first { it.name == "vivo.control.forceSensorMode" }.value)
        assertEquals(
            listOf("lens.only", "preset.only", "vivo.control.forceSensorMode"),
            merged.entries.map { it.name }
        )
        // Merging an empty preset keeps every key (canonically sorted).
        assertEquals(
            lens.entries.map { it.name }.sorted(),
            lens.mergedWith(VendorKeyProfile()).entries.map { it.name }
        )
    }

    @Test fun `numeric tags validate and round trip like names`() {
        assertTrue(VendorKeyEntry.isNumericTag("0x80020000"))
        assertTrue(VendorKeyEntry.isNumericTag("0X1a"))
        assertFalse(VendorKeyEntry.isNumericTag("vivo.control.forceSensorMode"))
        assertFalse(VendorKeyEntry.isNumericTag("80020000"))
        assertFalse(VendorKeyEntry.isNumericTag("0x"))
        assertFalse(VendorKeyEntry.isNumericTag("0x123456789"))
        assertEquals(0x80020000.toInt(), VendorKeyEntry.numericTagValue("0x80020000"))
        assertEquals(null, VendorKeyEntry.numericTagValue("vivo.control.forceSensorMode"))
        val numeric = VendorKeyEntry("0x80020000", VendorKeyType.INT, VendorKeyScope.SESSION, "1")
        numeric.validate()
        assertEquals(numeric, VendorKeyProfile.fromJson(VendorKeyProfile(listOf(numeric)).toJson()).entries.single())
        assertTrue(VendorKeyEntry.isPlausibleName("0x80020000"))
        assertFails { entry(name = "0xZZ").validate() }
    }

    @Test fun `operating mode round trips and validates`() {
        assertEquals(0, VendorKeyProfile().operatingMode)
        assertEquals(37379, VendorKeyProfile.parseOperatingMode(" 37379 "))
        assertFails { VendorKeyProfile.parseOperatingMode("") }
        assertFails { VendorKeyProfile.parseOperatingMode("fast") }
        assertFails { VendorKeyProfile.parseOperatingMode("-1") }
        val profile = VendorKeyProfile(listOf(entry()), operatingMode = 37379)
        profile.validate()
        assertEquals(profile, VendorKeyProfile.fromJson(profile.toJson()))
        assertFails { VendorKeyProfile(listOf(entry()), operatingMode = -1).validate() }
        // Old backups without the field still load.
        assertEquals(0, VendorKeyProfile.fromJson("""{"v":1,"entries":[]}""").operatingMode)
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
