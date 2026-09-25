// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vulkan backend proofs, smallest first: host round-trips, then a real SR
 * pass (clear_reference) through the manifest-driven host layer. Each test
 * opens its own context; failures name the layer (staging, descriptor,
 * dispatch) by construction.
 */
class VkRoundTripTest {
    @Test
    fun imageWriteReadRoundTrips() {
        SrVulkan.open().use { vk ->
            VkArena(vk).use { arena ->
                val r32 = arena.texture(64, 48, GLES30.GL_R32F)
                val pattern = FloatArray(64 * 48) { it * 0.5f - 100f }
                r32.uploadR32f(pattern, UploadBuffers())
                assertArrayEquals(pattern, r32.downloadR32f(), 0f)

                val rgba = arena.texture(33, 17, GLES30.GL_RGBA32F)
                val colors = FloatArray(33 * 17 * 4) { (it * 7919 % 1048573) / 1048573f }
                rgba.uploadRgba32f(colors, UploadBuffers())
                assertArrayEquals(colors, rgba.downloadRgba32f(), 0f)

                val ui = arena.texture(64, 64, GLES30.GL_R32UI)
                val uints = IntArray(64 * 64) { it * 2654435761u.toInt() }
                val direct = java.nio.ByteBuffer.allocateDirect(uints.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                direct.asIntBuffer().put(uints)
                vk.writeImage(ui.nativeHandleForTest(), direct)
                assertArrayEquals(uints, ui.downloadR32ui())
            }
        }
    }

    @Test
    fun clearReferenceDispatchClears() {
        SrVulkan.open().use { vk ->
            val assets = android.content.Context(java.io.File("build/resources/main")).assets
            VkProgramCache(vk, assets).use { programs ->
                VkArena(vk).use { arena ->
                    val uploads = UploadBuffers()
                    val a = arena.texture(64, 64, GLES30.GL_RGBA32F)
                    val b = arena.texture(64, 64, GLES30.GL_RGBA32F)
                    a.uploadRgba32f(FloatArray(64 * 64 * 4) { it.toFloat() }, uploads)
                    b.uploadRgba32f(FloatArray(64 * 64 * 4) { -it.toFloat() }, uploads)
                    VkSession(vk, programs, uploads).pass("rawsr/clear_reference.glsl") {
                        ivec2("u_size", 64, 64)
                        image(0, a, GLES30.GL_RGBA32F)
                        image(1, b, GLES30.GL_RGBA32F)
                        dispatch(64, 64, 8, 8)
                    }
                    assertTrue(a.downloadRgba32f().all { it == 0f })
                    assertTrue(b.downloadRgba32f().all { it == 0f })
                }
            }
        }
    }

    @Test
    fun regionDownloadMatchesWholeDownloadSlice() {
        SrVulkan.open().use { vk ->
            VkArena(vk).use { arena ->
                val w = 64
                val h = 48
                val rgba = arena.texture(w, h, GLES30.GL_RGBA32F)
                val colors = FloatArray(w * h * 4) { (it * 7919 % 1048573) / 1048573f }
                rgba.uploadRgba32f(colors, UploadBuffers())
                // Ragged bands incl. edges; each must equal the whole slice.
                var y = 0
                var step = 0
                while (y < h) {
                    val rows = minOf(if (step % 2 == 0) 5 else 9, h - y)
                    val band = rgba.downloadRgba32fRegion(0, y, w, rows)
                    assertArrayEquals(
                        colors.copyOfRange(y * w * 4, (y + rows) * w * 4), band, 0f
                    )
                    y += rows
                    step++
                }
                // Interior 2D window (not just full-width bands).
                val win = rgba.downloadRgba32fRegion(7, 5, 11, 13)
                val expect = FloatArray(11 * 13 * 4)
                for (r in 0 until 13) {
                    colors.copyInto(
                        expect, r * 11 * 4, (5 + r) * w * 4 + 7 * 4, (5 + r) * w * 4 + 18 * 4
                    )
                }
                assertArrayEquals(expect, win, 0f)

                val r32 = arena.texture(w, h, GLES30.GL_R32F)
                val pattern = FloatArray(w * h) { it * 0.5f - 100f }
                r32.uploadR32f(pattern, UploadBuffers())
                val rband = r32.downloadR32fRegion(0, 40, w, 8)
                assertArrayEquals(pattern.copyOfRange(40 * w, 48 * w), rband, 0f)
            }
        }
    }
}

internal fun VkImage.nativeHandleForTest(): Long = handle
