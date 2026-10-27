// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/** Host-testable half of Step 2: level-tag guard and advertised-key partition. */
class VendorKeyApplyTest {
    private fun entry(
        name: String,
        scope: VendorKeyScope = VendorKeyScope.REQUEST
    ) = VendorKeyEntry(name, VendorKeyType.INT, scope, "1")

    @Test fun `standard level tags are recognised, vendor lookalikes are not`() {
        assertTrue(VendorKeyApply.isLevelTag("android.sensor.blackLevelPattern"))
        assertTrue(VendorKeyApply.isLevelTag("android.sensor.info.whiteLevel"))
        assertFalse(VendorKeyApply.isLevelTag("vivo.control.forceSensorMode"))
        assertFalse(VendorKeyApply.isLevelTag("xiaomi.sensor.blackLevel"))
    }

    @Test fun `fingerprint ignores order but flips on any change`() {
        val one = entry("b.two")
        val two = entry("a.one")
        assertEquals(
            VendorKeyApply.fingerprint(listOf(one, two)),
            VendorKeyApply.fingerprint(listOf(two, one))
        )
        assertEquals("", VendorKeyApply.fingerprint(emptyList()))
        assertEquals(
            VendorKeyApply.fingerprint(listOf(one)),
            VendorKeyApply.fingerprint(listOf(entry("b.two")))
        )
        assertNotEquals(
            VendorKeyApply.fingerprint(listOf(entry("b.two"))),
            VendorKeyApply.fingerprint(
                listOf(VendorKeyEntry("b.two", VendorKeyType.INT, VendorKeyScope.SESSION, "2", enabled = false))
            )
        )
    }

    @Test fun `fingerprint cannot collide through delimiters in string values`() {
        val one = VendorKeyEntry("a.one", VendorKeyType.STRING, VendorKeyScope.SESSION,
            "first~true|b.two~String~session~last")
        val two = listOf(
            VendorKeyEntry("a.one", VendorKeyType.STRING, VendorKeyScope.SESSION, "first"),
            VendorKeyEntry("b.two", VendorKeyType.STRING, VendorKeyScope.SESSION, "last")
        )
        assertNotEquals(VendorKeyApply.fingerprint(listOf(one)), VendorKeyApply.fingerprint(two))
    }

    @Test fun `session and request entries split by scope`() {
        val profile = VendorKeyProfile(
            listOf(
                entry("vivo.control.forceSensorMode", VendorKeyScope.SESSION),
                entry("x.oppo.tag", VendorKeyScope.REQUEST)
            )
        )
        assertEquals(listOf("vivo.control.forceSensorMode"), profile.sessionEntries().map { it.name })
        assertEquals(listOf("x.oppo.tag"), profile.requestEntries().map { it.name })
    }
}
