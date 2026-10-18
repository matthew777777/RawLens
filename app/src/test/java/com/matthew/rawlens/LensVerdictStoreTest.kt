// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LensVerdictStoreTest {
    private fun harness(
        openable: MutableSet<String>? = null,
        dead: MutableSet<String>? = null,
        verified: MutableSet<String>? = null
    ): Triple<LensVerdictStore, SharedPreferences, SharedPreferences.Editor> {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getStringSet(eq("openable_ids"), any())).thenReturn(openable)
        `when`(prefs.getStringSet(eq("dead_ids"), any())).thenReturn(dead)
        `when`(prefs.getStringSet(eq("verified_ids"), any())).thenReturn(verified)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putStringSet(anyString(), any())).thenReturn(editor)
        return Triple(LensVerdictStore(context), prefs, editor)
    }

    @Test fun `snapshot reads all sets`() {
        val (store, _, _) = harness(
            openable = mutableSetOf("0"),
            dead = mutableSetOf("5"),
            verified = mutableSetOf("3/2")
        )
        assertEquals(LensVerdicts(setOf("0"), setOf("5"), setOf("3/2")), store.snapshot())
    }

    @Test fun `markVerified persists identity and clears dead mark`() {
        val (store, _, editor) = harness(dead = mutableSetOf("3/2"))
        store.markVerified("3/2")
        verify(editor).putStringSet(eq("verified_ids"), eq(setOf("3/2")))
        verify(editor).putStringSet(eq("dead_ids"), eq(emptySet()))
        verify(editor).apply()
    }

    @Test fun `markOpenable persists id and clears dead mark`() {
        val (store, _, editor) = harness(dead = mutableSetOf("3"))
        store.markOpenable("3")
        verify(editor).putStringSet(eq("openable_ids"), eq(setOf("3")))
        verify(editor).putStringSet(eq("dead_ids"), eq(emptySet()))
        verify(editor).apply()
    }

    @Test fun `markDead remembers unknown ids`() {
        val (store, _, editor) = harness()
        store.markDead("5")
        verify(editor).putStringSet(eq("dead_ids"), eq(setOf("5")))
        verify(editor).apply()
    }

    @Test fun `markDead never pins an id that opened before`() {
        val (store, _, editor) = harness(openable = mutableSetOf("3"))
        store.markDead("3")
        verify(editor, never()).putStringSet(anyString(), any())
    }

    @Test fun `blank ids are ignored`() {
        val (store, _, editor) = harness()
        store.markOpenable("  ")
        store.markDead("")
        verify(editor, never()).putStringSet(anyString(), any())
    }
}
