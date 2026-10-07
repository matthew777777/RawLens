// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class SidecarTreeAccessTest {
    @Test fun `rawLens tree receives the stem directly`() {
        assertEquals(
            listOf("IMG_123"),
            SidecarTreeAccess.relativeSegments("DCIM/RawLens", "IMG_123")
        )
    }

    @Test fun `dcim tree nests under RawLens`() {
        assertEquals(
            listOf("RawLens", "IMG_123"),
            SidecarTreeAccess.relativeSegments("DCIM", "IMG_123")
        )
    }

    @Test fun `other folders receive the stem`() {
        assertEquals(
            listOf("IMG_123"),
            SidecarTreeAccess.relativeSegments("Download/RawLens", "IMG_123")
        )
    }

    @Test fun `auto prompt fires exactly once and never with a grant`() {
        assertTrue(SidecarTreeAccess.shouldAutoPrompt(hasGrant = false, alreadyPrompted = false))
        assertFalse(SidecarTreeAccess.shouldAutoPrompt(hasGrant = true, alreadyPrompted = false))
        assertFalse(SidecarTreeAccess.shouldAutoPrompt(hasGrant = false, alreadyPrompted = true))
        assertFalse(SidecarTreeAccess.shouldAutoPrompt(hasGrant = true, alreadyPrompted = true))
    }

    @Test fun `fresh install without a grant needs the prompt`() {
        assertTrue(SidecarTreeAccess.needsFirstInstallPrompt(prefsContext(mutableMapOf())))
    }

    @Test fun `marking prompted suppresses the prompt`() {
        val store = mutableMapOf<String, Any?>()
        val context = prefsContext(store)
        SidecarTreeAccess.markFirstInstallPrompted(context)
        assertEquals(true, store[SidecarTreeAccess.KEY_FIRST_INSTALL_PROMPTED])
        assertFalse(SidecarTreeAccess.needsFirstInstallPrompt(context))
    }

    /** Context whose shared prefs are backed by [store] (no grant persisted). */
    private fun prefsContext(store: MutableMap<String, Any?>): Context {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { inv ->
            store[inv.arguments[0] as String] as String?
        }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            (store[inv.arguments[0] as String] as Boolean?) ?: inv.arguments[1] as Boolean
        }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            store[inv.arguments[0] as String] = inv.arguments[1] as Boolean
            editor
        }
        `when`(prefs.edit()).thenReturn(editor)
        return context
    }
}
