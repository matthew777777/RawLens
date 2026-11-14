// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The shared capture/offline tail must remove multi-pixel false color while retaining detail. */
class ChromaCleanupDeviceTest {
    private val width = 32
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val off = ChromaCleanup(false, 0f)
    private val on = ChromaCleanup(true, 0f)
    private fun rgb(x: Int, y: Int): FloatArray {
        val green = if ((x + y) % 2 == 0) .2f else .25f
        val purple = (x in 15..17 && y in 15..17) || (x in 3..10 && y in 3..10)
        return floatArrayOf(green + if (purple) .1f else 0f, green,
            green + if (purple) .1f else 0f)
    }
    private fun read(texture: Int): FloatArray {
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val bytes = ByteBuffer.allocateDirect(width * width * 16).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, width, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(width * width * 4).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fb, 0)
        }
    }
    private fun checkClean(before: FloatArray, after: FloatArray) {
        val center = (16 * width + 16) * 4
        assertTrue(before[center] - before[center + 1] > .09f)
        assertEquals(after[center + 1], after[center], .001f)
        assertEquals(after[center + 1], after[center + 2], .001f)
        for (p in 0 until width * width) assertEquals("green detail $p", before[p * 4 + 1], after[p * 4 + 1], .001f)
        val flat = (6 * width + 6) * 4
        assertEquals("flat purple stays colored", .1f, after[flat] - after[flat + 1], .001f)
    }
    @Test fun sharedCaptureAndOfflineRgbTailRemovesColorIslands() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = Gles31AmazeProcessor(context)
        val input = ByteBuffer.allocateDirect(width * width * 12).order(ByteOrder.nativeOrder())
        for (y in 0 until width) for (x in 0 until width) rgb(x, y).forEach { input.putFloat(it) }
        fun render(cleanup: ChromaCleanup): FloatArray {
            processor.chromaCleanup = cleanup
            input.rewind()
            return processor.processMfsrRgb(input, width, width, identity, floatArrayOf(1f, 1f, 1f), true) { read(it.textureId) }
        }
        try { checkClean(render(off), render(on)) } finally { processor.close() }
    }
    @Test fun mergedTextureTailUsesTheSameCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val textures = IntArray(2)
            GLES31.glGenTextures(2, textures, 0)
            val input = ByteBuffer.allocateDirect(width * width * 16).order(ByteOrder.nativeOrder())
            for (y in 0 until width) for (x in 0 until width) { rgb(x, y).forEach { input.putFloat(it) }; input.putFloat(1f) }
            input.rewind()
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[0])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, width, width)
            GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, width, width, GLES31.GL_RGBA, GLES31.GL_FLOAT, input)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textures[1])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_R32F, width / 2, width / 2)
            val metadata = OfflineBurstMetadata.synthesize(
                """{"sensorArrangment":"rggb","blackLevel":[0,0,0,0],"whiteLevel":65535,"forwardMatrix1":[1,0,0,0,1,0,0,0,1],"referenceIlluminant1":21}""",
                """{"width":32,"height":32,"timestamp":"0","asShotNeutral":[1,1,1]}""", 0)
            val processor = Gles31MergedDevelopProcessor(context)
            fun render(cleanup: ChromaCleanup) = processor.develop(
                MergedTextureJpegInput(textures[0], width, width, textures[1], 1, metadata),
                cameraLinear = true, chromaCleanup = cleanup) { read(it.textureId) }
            try { checkClean(render(off), render(on)) } finally {
                processor.close()
                GLES31.glDeleteTextures(2, textures, 0)
            }
        }
    }
    @Test fun offlineQualitySettingsProduceActualJpegAndJxl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("offline-quality-regression", 0)
        prefs.edit().clear().putInt(MainActivity.KEY_JPEG_QUALITY, 100)
            .putInt(MainActivity.KEY_JXL_QUALITY, 100).commit()
        assertEquals(100, MergeScreenSettings.jpeg(prefs).jpegQuality)
        assertEquals(100, MergeScreenSettings.jxl(prefs).quality)
        val jpeg = File(context.cacheDir, "offline-quality.jpg")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff4080c0.toInt())
        try {
            ParcelFileDescriptor.open(jpeg, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                NativeJpegEncoder.encode(bitmap, it, MergeScreenSettings.jpeg(prefs))
            }
            val bytes = jpeg.readBytes()
            assertEquals(0xff, bytes[0].toInt() and 255); assertEquals(0xd8, bytes[1].toInt() and 255)
            val decoded = requireNotNull(BitmapFactory.decodeFile(jpeg.path))
            assertNotNull(decoded); assertEquals(32, decoded.width); decoded.recycle()
            assertTrue(jpeg.length() < 32 * 32 * 6)
        } finally { bitmap.recycle(); jpeg.delete() }
        assertTrue("JPEG XL must be bundled", NativeJxlEncoder.isAvailable())
        val jxl = File(context.cacheDir, "offline-quality.jxl")
        try {
            val pixels = ByteBuffer.allocateDirect(32 * 32 * 6).order(ByteOrder.nativeOrder())
            repeat(32 * 32 * 3) { pixels.putShort(32767.toShort()) }; pixels.rewind()
            ParcelFileDescriptor.open(jxl, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                NativeJxlEncoder.encode(pixels, 32, 32, it, MergeScreenSettings.jxl(prefs).copy(
                    bitDepth = JxlBitDepth.BIT_16, pixelType = JxlPixelType.UINT))
            }
            val bytes = jxl.readBytes()
            val raw = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0x0a.toByte()
            val boxed = bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "JXL "
            assertTrue("Real JPEG XL signature", raw || boxed)
            assertTrue(jxl.length() < 32 * 32 * 6)
            prefs.edit().putInt(MainActivity.KEY_JPEG_QUALITY, 87).putInt(MainActivity.KEY_JXL_QUALITY, 92).commit()
            assertEquals(87, MergeScreenSettings.jpeg(prefs).jpegQuality)
            assertEquals(92, MergeScreenSettings.jxl(prefs).quality)
        } finally { jxl.delete(); prefs.edit().clear().commit() }
    }
}
