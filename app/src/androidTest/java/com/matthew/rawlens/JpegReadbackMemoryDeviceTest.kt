// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real readback: full-size buffers must never remain cached or charged to ART. */
@RunWith(AndroidJUnit4::class)
class JpegReadbackMemoryDeviceTest {
    @Test fun repeatedReadbackReleasesNativeStaging() = checkReadback(512, 384)

    @Test fun full50MpReadbackWithLiveCfaHeap() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jpeg50MpMemory") == "true")
        checkReadback(8160, 6120)
    }

    private fun checkReadback(width: Int, height: Int) {
        // Models the live B&W source CFA (one float per sensor site).
        val retainedCfa = FloatArray(width * height) { .25f }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use { egl ->
            egl.makeCurrent()
            val texture = IntArray(1)
            GLES30.glGenTextures(1, texture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            val processor = Gles31JpegOutputProcessor(context)
            try {
                val outstanding = MemoryLeakDiagnostics.nativeBufferOutstandingBytes()
                repeat(2) {
                    val result = processor.processEncoded(AmazeGpuOutput(texture[0], width, height,
                        AmazeTextureFormat.RGBA8), JpegOutputSettings(ultraHdr = false))
                    try {
                        assertEquals(width, result.bitmap.width)
                        assertEquals(height, result.bitmap.height)
                        assertEquals(outstanding, MemoryLeakDiagnostics.nativeBufferOutstandingBytes())
                    } finally { result.bitmap.recycle() }
                }
            } finally {
                processor.close()
                GLES30.glDeleteTextures(1, texture, 0)
            }
        }
        assertEquals(.25f, retainedCfa.last())
    }
}
