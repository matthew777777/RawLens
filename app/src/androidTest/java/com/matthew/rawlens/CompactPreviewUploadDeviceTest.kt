// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.opengl.EGL14.*
import android.opengl.GLES30.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Vulkan -> AHB -> EGL readback; tests every byte, not a sparse mean. */
@RunWith(AndroidJUnit4::class)
class CompactPreviewUploadDeviceTest {
    @Test fun compactPlaneAndLockedAhbMatchScalarAtEveryOutputSite() {
        assumeTrue(VfVulkan.available && VfEglImport.available)
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val spv = assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        assertEquals(VfVulkan.OK, VfVulkan.reinitNative(spv))
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        assertTrue(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        assertTrue(eglChooseConfig(display, intArrayOf(EGL_RENDERABLE_TYPE, 0x40,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        val surface = eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        assertTrue(eglMakeCurrent(display, surface, surface, context))
        val width = 35
        val height = 27
        val pitch = 328 // padded rows; steps/crops leave most source codes unused
        val rawH = 256
        val bytes = ByteBuffer.allocateDirect(pitch * rawH * 2).order(ByteOrder.LITTLE_ENDIAN)
        val random = java.util.Random(73013)
        for (i in 0 until bytes.capacity() / 2) bytes.putShort(i * 2, random.nextInt(65536).toShort())
        val blob = VfEglImport.createBlobBayerInput(pitch, rawH)
        assertNotNull("BLOB input", blob)
        val output = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val names = IntArray(2)
        glGenTextures(1, names, 0)
        glGenFramebuffers(1, names, 1)
        var image = 0L
        try {
            assertEquals(0, VfEglImport.blitBytesToBlob(blob!!, bytes, bytes.capacity()))
            assertEquals(VfVulkan.OK, VfVulkan.ensureOutputNative(output))
            image = VfEglImport.createEGLImage(output)
            assertTrue(image != 0L)
            assertEquals(GL_NO_ERROR, VfEglImport.bindEGLImageToTexture2D(image, names[0]))
            glBindFramebuffer(GL_FRAMEBUFFER, names[1])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, names[0], 0)
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER))
            val levels = floatArrayOf(64f, 65.5f, 66f, 67.25f)
            val white = 16383f
            val (blackQ, denQ) = VfLevels.toFixedQ6(levels, white)
            val (bound, lut) = VfLevels.lutFor(blackQ, denQ)
            val actual = ByteBuffer.allocateDirect(width * height * 4)
            for (step in intArrayOf(2, 3, 4, 8)) for (left in 0..1) for (top in 0..1) {
                for (channels in arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(3, 2, 1, 0),
                    intArrayOf(1, 0, 3, 2), intArrayOf(2, 3, 0, 1))) {
                    val (ip, fp) = VfVulkan.packParams(channels, left, top, width, height, step, pitch, levels, white)
                    for (copy in listOf(false, true)) {
                        assertEquals(VfVulkan.OK, VfVulkan.uploadLutNative(bound, lut))
                        val rc = if (copy) VfVulkan.computeCopyNative(blob, ip, fp)
                            else VfVulkan.computePlaneNative(bytes, bytes.capacity(), ip, fp)
                        assertEquals("step=$step crop=$left,$top copy=$copy", VfVulkan.OK, rc)
                        actual.clear()
                        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, actual)
                        assertEquals(GL_NO_ERROR, glGetError())
                        for (y in 0 until height) for (x in 0 until width) for (ch in 0..3) {
                            val site = channels[ch]
                            val sx = left + x * step + (site and 1)
                            val sy = top + y * step + (site shr 1)
                            val raw = bytes.getShort((sy * pitch + sx) * 2).toInt() and 65535
                            val want = VfLevels.normalizeByte(raw, blackQ[site], denQ[site])
                            val got = actual.get((y * width + x) * 4 + ch).toInt() and 255
                            assertEquals("step=$step crop=$left,$top copy=$copy ($x,$y,$ch)", want, got)
                        }
                        // Finish this test's GL read before the next Vulkan write.
                        glFinish()
                    }
                }
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glDeleteFramebuffers(1, names, 1)
            glDeleteTextures(1, names, 0)
            if (image != 0L) VfEglImport.destroyEGLImage(image)
            output.close()
            blob?.close()
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            eglDestroySurface(display, surface)
            eglDestroyContext(display, context)
            eglTerminate(display)
        }
    }
}
