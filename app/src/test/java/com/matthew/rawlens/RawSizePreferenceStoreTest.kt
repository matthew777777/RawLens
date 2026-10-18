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

class RawSizePreferenceStoreTest {
    private fun harness(stored: String? = null): Triple<RawSizePreferenceStore, SharedPreferences, SharedPreferences.Editor> {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(anyString(), any())).thenReturn(stored)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        return Triple(RawSizePreferenceStore(context), prefs, editor)
    }

    @Test fun `get returns stored size`() {
        val (store, _, _) = harness("4080x3072")
        assertEquals("4080x3072", store.get("0"))
    }

    @Test fun `get rejects malformed sizes as auto`() {
        val (store, _, _) = harness("huge")
        assertNull(store.get("0"))
    }

    @Test fun `set persists valid sizes and clears on null`() {
        val (store, _, editor) = harness()
        store.set("0", "4080x3072")
        verify(editor).putString(eq("size_0"), eq("4080x3072"))
        store.set("0", null)
        verify(editor).remove(eq("size_0"))
    }

    @Test fun `set ignores malformed sizes`() {
        val (store, _, editor) = harness()
        store.set("0", "0x3072")
        verify(editor, never()).putString(anyString(), anyString())
    }

    @Test fun `size plausibility rules`() {
        assertEquals(true, RawSizePreferenceStore.isPlausibleSize("4080x3072"))
        assertEquals(false, RawSizePreferenceStore.isPlausibleSize("4080"))
        assertEquals(false, RawSizePreferenceStore.isPlausibleSize("0x3072"))
        assertEquals(false, RawSizePreferenceStore.isPlausibleSize("4080x-1"))
    }
}
