// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val main = "rawlens_settings"
    private val dng = "rawlens_dng_metadata"
    private val ae = "rawlens_program_ae_v2"

    @Test fun knownStoresCoverAllThreePrefsFiles() {
        assertEquals(setOf(main, dng, ae), SettingsBackup.KNOWN_STORES)
    }

    @Test fun roundTripsAllValueTypes() {
        val stores = mapOf(
            main to mapOf(
                "bool_true" to true,
                "bool_false" to false,
                "int" to 42,
                "int_min" to Int.MIN_VALUE,
                "long" to 9_223_372_036_854_775_000L,
                "float" to 1.5f,
                "string" to "hello",
                "empty_string" to "",
                "set" to setOf("b", "a", "c"),
                "empty_set" to emptySet<String>()
            ),
            dng to mapOf("profile_0" to "{\"whiteLevel\":1023.0}"),
            ae to emptyMap()
        )
        val decoded = SettingsBackup.fromJson(SettingsBackup.toJson(stores))
        assertEquals(stores.keys, decoded.keys)
        assertEquals(stores.getValue(main), decoded.getValue(main))
        assertEquals(stores.getValue(dng), decoded.getValue(dng))
        assertTrue(decoded.getValue(ae).isEmpty())
        // Types survive, not just values.
        val round = decoded.getValue(main)
        assertTrue(round["bool_true"] is Boolean)
        assertTrue(round["int"] is Int)
        assertTrue(round["long"] is Long)
        assertTrue(round["float"] is Float)
        assertTrue(round["set"] is Set<*>)
    }

    @Test fun floatRoundTripIsBitExact() {
        val floats = listOf(
            0.1f, 1f / 3f, -2.5f, 0f, -0f, 1e30f, 1e-30f,
            Float.MAX_VALUE, Float.MIN_VALUE, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        val stores = mapOf(main to floats.mapIndexed { i, f -> "f$i" to f }.toMap())
        val decoded = SettingsBackup.fromJson(SettingsBackup.toJson(stores)).getValue(main)
        floats.forEachIndexed { i, f ->
            assertEquals(
                "f$i ($f)",
                java.lang.Float.floatToRawIntBits(f),
                java.lang.Float.floatToRawIntBits(decoded["f$i"] as Float)
            )
        }
    }

    @Test fun outputIsDeterministicWithSortedKeys() {
        val stores = mapOf(
            main to mapOf("zebra" to 1, "apple" to 2, "mango" to setOf("z", "a")),
            ae to mapOf("only" to true)
        )
        val first = SettingsBackup.toJson(stores)
        val second = SettingsBackup.toJson(stores)
        assertEquals(first, second)
        assertTrue(first.indexOf("apple") < first.indexOf("mango"))
        assertTrue(first.indexOf("mango") < first.indexOf("zebra"))
        assertTrue(first.indexOf("\"a\",\"z\"") >= 0)
        assertTrue(first.indexOf(ae) < first.indexOf(main))
    }

    @Test fun escapesAndUnicodeRoundTrip() {
        val tricky = "quote\" back\\slash\nnewline\ttab\rreturn\u0001 smiley żółć \uD83D\uDE00"
        val stores = mapOf(main to mapOf("k\"ey\\" to tricky))
        val decoded = SettingsBackup.fromJson(SettingsBackup.toJson(stores)).getValue(main)
        assertEquals(tricky, decoded["k\"ey\\"])
    }

    @Test fun unknownStoresAreSkippedButUnknownKeysAreKept() {
        val json = "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{" +
            "\"future_store\":{\"x\":{\"t\":\"int\",\"v\":1}}," +
            "\"$main\":{\"future_key\":{\"t\":\"string\",\"v\":\"kept\"}}}}"
        val decoded = SettingsBackup.fromJson(json)
        assertEquals(setOf(main), decoded.keys)
        assertEquals("kept", decoded.getValue(main)["future_key"])
    }

    @Test fun rejectsUnsupportedValueTypeOnExport() {
        for (bad in listOf<Any>(1.5, byteArrayOf(1), listOf("a"))) {
            try {
                SettingsBackup.toJson(mapOf(main to mapOf("bad" to bad)))
                throw AssertionError("exported $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
        try {
            SettingsBackup.toJson(mapOf(main to mapOf("bad" to null)))
            throw AssertionError("exported null")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun rejectsMalformedBackups() {
        val bad = listOf(
            "",
            "not json",
            "[1,2]",
            "{\"format\":\"other\",\"formatVersion\":1,\"stores\":{}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":2,\"stores\":{}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":\"1\",\"stores\":{}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":[]}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":[]}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":7}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"nope\",\"v\":1}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"int\",\"v\":\"1\"}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"int\",\"v\":1.5}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"int\",\"v\":9999999999}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"float\",\"v\":1.5}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"stringSet\",\"v\":[1]}}}}",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{\"$main\":{\"k\":{\"t\":\"bool\",\"v\":1}}}}",
            // Trailing garbage and unterminated input.
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{}} trailing",
            "{\"format\":\"rawlens-settings\",\"formatVersion\":1,\"stores\":{"
        )
        for (input in bad) {
            try {
                SettingsBackup.fromJson(input)
                throw AssertionError("accepted: $input")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }
}
