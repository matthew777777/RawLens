// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Color
import android.opengl.GLES30
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HdrStillOutputDeviceTest {
    @Test
    fun allToneMappersExtendHighlightsInPqAndUltraHdr() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val levels = floatArrayOf(0.18f, 0.85f, 1.5f, 8f)
        val width = levels.size * 4
        val height = 4
        val pixels = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
        repeat(height) {
            for (level in levels) {
                val half = android.util.Half.toHalf(level)
                repeat(4) {
                    repeat(3) { pixels.putShort(half) }
                    pixels.putShort(0x3c00)
                }
            }
        }
        pixels.rewind()
        Gles31AmazeProcessor.EglComputeContext().use {
            val texture = IntArray(1)
            GLES30.glGenTextures(1, texture, 0)
            val jxl = Gles31JxlOutputProcessor(context)
            val jpeg = Gles31JpegOutputProcessor(context)
            try {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, width, height)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, pixels
                )
                val scene = AmazeGpuOutput(texture[0], width, height)
                for (mapper in PhotoToneMapper.entries) {
                    val look = JpegOutputSettings(toneMapper = mapper, ultraHdr = true)
                    val pq = jxl.process(scene, look, JxlOutputSettings(hdrTransfer = JxlHdrTransfer.PQ))
                    val nits = levels.indices.map { index ->
                        val code = JxlPixelPack.halfBitsToFloat(pq.halfRgba.getShort(index * 32).toInt() and 0xffff)
                        JxlPixelPack.pqEotfNits(code)
                    }
                    assertTrue("$mapper PQ levels: $nits", nits.zipWithNext().all { (a, b) -> a < b })
                    assertTrue("$mapper PQ headroom: $nits", nits[1] > 203f && nits.last() < 820f)
                    val ultraHdr = jpeg.process(scene, look)
                    val gain = checkNotNull(ultraHdr.gainmap)
                    try {
                        val codes = levels.indices.map { Color.red(gain.getPixel(it, 0)) }
                        assertTrue("$mapper Ultra HDR gain: $codes", codes.first() <= 1 && codes[1] > 0)
                        assertTrue("$mapper Ultra HDR peak gain: $codes", codes.last() > codes[1])
                    } finally {
                        gain.recycle()
                        ultraHdr.bitmap.recycle()
                    }
                }
            } finally {
                jpeg.close()
                jxl.close()
                GLES30.glDeleteTextures(1, texture, 0)
            }
        }
    }
}
