// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawVideoSaverTest {
    @Test fun publishesExactBytesAndRemovesPrivateSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = File(context.filesDir, "rawlens_export_test_${System.nanoTime()}.mcraw")
        val bytes = ByteArray(1024 * 1024 + 137) { (it * 31).toByte() }
        source.writeBytes(bytes)
        var saved: RawVideoSaver.Saved? = null
        try {
            saved = RawVideoSaver.save(resolver, source)
            assertTrue(saved.sourceRemoved)
            assertFalse(source.exists())
            resolver.openInputStream(saved.uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            resolver.query(saved.uri, arrayOf(
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.IS_PENDING
            ), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(source.name, cursor.getString(0))
                assertEquals("DCIM/RawLens", cursor.getString(1).trimEnd('/'))
                assertEquals(0, cursor.getInt(2))
            }
        } finally {
            saved?.let { resolver.delete(it.uri, null, null) }
            source.delete()
        }
    }

    @Test fun emptySourceIsRetained() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "rawlens_empty_test_${System.nanoTime()}.mcraw")
        source.writeBytes(byteArrayOf())
        try {
            try {
                RawVideoSaver.save(context.contentResolver, source)
                fail("Empty recording must not be published")
            } catch (_: IllegalArgumentException) {
                assertTrue(source.exists())
            }
        } finally { source.delete() }
    }
}
