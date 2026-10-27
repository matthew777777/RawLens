// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VendorKeyStoreTest {
    private class Store {
        val data = ConcurrentHashMap<String, String>()
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val context = mock(Context::class.java)
        val store: VendorKeyStore
        init {
            `when`(context.getSharedPreferences(VendorKeyStore.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
            doAnswer { data[it.getArgument<String>(0)] }.`when`(prefs).getString(anyString(), isNull())
            `when`(prefs.edit()).thenReturn(editor)
            doAnswer { data[it.getArgument(0)] = it.getArgument(1); editor }.`when`(editor).putString(anyString(), anyString())
            store = VendorKeyStore(context)
        }
    }

    @Test fun `corrupt persisted JSON cannot crash reads or overwrite the original`() {
        val h = Store()
        h.data["profile_0"] = "{broken"
        assertTrue(h.store.get("0").isEmpty())
        assertEquals("{broken", h.data["profile_0"])
        verify(h.prefs, never()).edit()
    }

    @Test fun `concurrent edits read the latest profile under the storage lock`() {
        val h = Store()
        h.store.save("0", VendorKeyProfile(operatingMode = 32768))
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val errors = ConcurrentHashMap.newKeySet<Throwable>()
        repeat(4) {
            thread {
                try {
                    start.await()
                    repeat(25) { h.store.update("0") { current -> current.copy(operatingMode = current.operatingMode + 1) } }
                } catch (failure: Throwable) { errors += failure }
                finally { done.countDown() }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(32868, h.store.get("0").operatingMode)
    }
}
