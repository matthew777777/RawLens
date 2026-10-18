// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RawFormatPreferenceStoreTest {
    private fun harness(stored: String? = null): Triple<RawFormatPreferenceStore, SharedPreferences, SharedPreferences.Editor> {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(anyString(), any())).thenReturn(stored)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        return Triple(RawFormatPreferenceStore(context), prefs, editor)
    }

    @Test fun `get returns stored format`() {
        val (store, _, _) = harness("RAW12")
        assertEquals("RAW12", store.get("0"))
    }

    @Test fun `get rejects unknown names as auto`() {
        val (store, _, _) = harness("RAW14")
        assertNull(store.get("0"))
    }

    @Test fun `set persists known formats and clears on null`() {
        val (store, _, editor) = harness()
        store.set("0", "RAW10")
        verify(editor).putString(eq("format_0"), eq("RAW10"))
        store.set("0", null)
        verify(editor).remove(eq("format_0"))
    }

    @Test fun `set ignores unknown formats`() {
        val (store, _, editor) = harness()
        store.set("0", "YUV")
        verify(editor, never()).putString(anyString(), anyString())
    }
}
