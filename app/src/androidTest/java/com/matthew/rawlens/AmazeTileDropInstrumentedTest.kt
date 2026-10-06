// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression test for the cold-pool black first tile: tile scratch used to allocate lazily
 * mid-pass, and allocation binds the new texture to the active sampler unit — so on a cold
 * texture pool every pass of tile (0,0) sampled uninitialized zeros and the developed frame
 * carried an exactly 1024x1024 pure-black square. A fresh processor reproduces the cold pool;
 * without the fix the first frame below renders tile (0,0) as exact zeros.
 */
@RunWith(AndroidJUnit4::class)
class AmazeTileDropInstrumentedTest {
    @Test fun coldAndWarmFramesHaveNoBlackTiles() {
        val processor = Gles31AmazeProcessor(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val w = 2048; val h = 1536
            val values = FloatArray(w * h) { i ->
                0.2f + 0.4f * ((i % w) + (i / w)) / (w + h)
            }
            val cfa = UnpackedRawCfa(w, h, BayerPattern.RGGB, values, RawCrop(0, 0, w, h))
            repeat(2) { frame ->
                processor.process(cfa) { out ->
                    assertEquals(AmazeTextureFormat.RGBA16F, out.internalFormat)
                    val pixels = read(out)
                    for (tileY in 0 until h / TILE) for (tileX in 0 until w / TILE) {
                        var sum = 0.0; var count = 0
                        for (y in tileY * TILE until (tileY + 1) * TILE) {
                            for (x in tileX * TILE until (tileX + 1) * TILE) {
                                val base = (y * w + x) * 4
                                sum += pixels[base] + pixels[base + 1] + pixels[base + 2]
                                count += 3
                            }
                        }
                        assertTrue(
                            "frame=$frame tile=($tileX,$tileY) mean=${sum / count}",
                            sum / count > 0.05
                        )
                    }
                }
            }
        } finally { processor.close() }
    }

    private fun read(out: AmazeGpuOutput): FloatArray {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, out.textureId, 0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val buffer = ByteBuffer.allocateDirect(out.width * out.height * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            GLES30.glReadPixels(0, 0, out.width, out.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(out.width * out.height * 4).also { buffer.position(0); buffer.get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
    }

    private companion object {
        const val TILE = 1024
    }
}
