// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BurstPublishedTest {
    private fun cursorFor(names: List<String>, sizes: List<Long>, datesSecs: List<Long>): Cursor {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.getColumnIndex(MediaStore.Video.Media._ID)).thenReturn(0)
        `when`(cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)).thenReturn(1)
        `when`(cursor.getColumnIndex(MediaStore.Video.Media.SIZE)).thenReturn(2)
        `when`(cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)).thenReturn(3)
        val moves = List(names.size + 1) { it < names.size }
        `when`(cursor.moveToNext()).thenReturn(moves[0], *moves.drop(1).toTypedArray())
        `when`(cursor.getString(1)).thenReturn(names[0], *names.drop(1).toTypedArray())
        `when`(cursor.getLong(0)).thenReturn(101L, *List(names.size - 1) { 102L + it }.toTypedArray())
        `when`(cursor.getLong(2)).thenReturn(sizes[0], *sizes.drop(1).toTypedArray())
        `when`(cursor.getLong(3)).thenReturn(datesSecs[0], *datesSecs.drop(1).toTypedArray())
        return cursor
    }

    private fun resolverWith(cursor: Cursor?): ContentResolver {
        val resolver = mock(ContentResolver::class.java)
        `when`(
            resolver.query(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
            )
        ).thenReturn(cursor)
        return resolver
    }

    @Test fun publishedListsBurstTakes() {
        val cursor = cursorFor(
            listOf("IMG_20260926_010000_000_BURST.crs", "IMG_20260926_020000_000_BURST.crs"),
            listOf(1000L, 2000L),
            listOf(100L, 200L)
        )
        val rows = BurstMergeTakes.loadPublished(resolverWith(cursor)) { mock(Uri::class.java) }
        assertEquals(2, rows.size)
        assertEquals("IMG_20260926_010000_000_BURST.crs", rows[0].displayName)
        assertEquals(1000L, rows[0].sizeBytes)
        assertEquals(100_000L, rows[0].lastModified)
        assertEquals(2000L, rows[1].sizeBytes)
    }

    @Test fun publishedSkipsNonBurstNames() {
        val cursor = cursorFor(
            listOf("notes.txt", "IMG_20260926_010000_000_BURST.crs"),
            listOf(10L, 1000L),
            listOf(1L, 100L)
        )
        val rows = BurstMergeTakes.loadPublished(resolverWith(cursor)) { mock(Uri::class.java) }
        assertEquals(1, rows.size)
        assertEquals("IMG_20260926_010000_000_BURST.crs", rows[0].displayName)
    }

    @Test fun publishedEmptyWhenQueryFails() {
        assertTrue(BurstMergeTakes.loadPublished(resolverWith(null)).isEmpty())
    }

    @Test fun restageCopiesToStage() {
        val payload = closedBytes(frames = 4)
        val uri = mock(Uri::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(payload))
        val stage = Files.createTempDirectory("rawlens-restage").toFile()
        val published = PublishedTake("IMG_20260926_010000_000_BURST.crs", payload.size.toLong(), 0L, uri)
        val staged = BurstMergeTakes.restage(resolver, published, stage)
        assertEquals(File(stage, published.displayName).absolutePath, staged.absolutePath)
        assertTrue(staged.isFile)
        assertEquals(payload.size.toLong(), staged.length())
        // Restaged file is a valid staged take.
        assertEquals(listOf(staged), BurstCrsStore.stagedBursts(stage))
    }

    @Test fun restageRejectsNonBurstName() {
        val resolver = mock(ContentResolver::class.java)
        val stage = Files.createTempDirectory("rawlens-restage-bad").toFile()
        try {
            BurstMergeTakes.restage(
                resolver, PublishedTake("notes.txt", 10L, 0L, mock(Uri::class.java)), stage)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    private fun closedBytes(frames: Int): ByteArray {
        val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(16).putInt(0x43525346.toInt()).putInt(frames).putLong(8)
        return ByteArray(64) { it.toByte() } + tail.array()
    }
}
