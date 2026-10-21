// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.opengl.GLES30
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo demosaic on known mosaics (docs/refactor/vf-native-study.md):
 * runs the still pipeline's AMaZE ([Gles31AmazeProcessor.process]) and RCD
 * ([Gles31AmazeProcessor.processRcdCfa]) on every RGGB `<name>_<W>x<H>.u16`
 * in /data/local/tmp/rawlens_kodak and writes `<name>.<amaze|rcd>.f32`
 * (RGBA float, texture row order) to files/photo-demosaic/ for host
 * scoring against ground truth. No camera. Opt-in: `-e rawlensPhotoDemosaic true`.
 */
class PhotoDemosaicKodakDeviceTest {
    @Test
    fun demosaicKnownMosaics() {
        assumeTrue("Pass -e rawlensPhotoDemosaic true",
            InstrumentationRegistry.getArguments().getString("rawlensPhotoDemosaic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "photo-demosaic").apply { deleteRecursively(); mkdirs() }
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.u16")
        val files = File("/data/local/tmp/rawlens_kodak").listFiles()?.filter { re.matches(it.name) }.orEmpty()
        assumeTrue("push mosaics to /data/local/tmp/rawlens_kodak", files.isNotEmpty())
        val processor = Gles31AmazeProcessor(context)
        try {
            for (f in files.sortedBy { it.name }) {
                val (name, w, h) = re.matchEntire(f.name)!!.destructured
                val width = w.toInt(); val height = h.toInt()
                val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val values = FloatArray(width * height) { (bb.short.toInt() and 0xffff) / 65535f }
                val cfa = UnpackedRawCfa(width, height, BayerPattern.RGGB, values, RawCrop(0, 0, width, height))
                for ((algo, run) in listOf<Pair<String, ((AmazeGpuOutput) -> FloatArray) -> FloatArray>>(
                    "amaze" to { consume -> processor.process(cfa, cameraLinear = true, consume = consume) },
                    "rcd" to { consume -> processor.processRcdCfa(cfa, cameraLinear = true, consume = consume) },
                )) {
                    // -e rawlensAmazeProfile true: per-pass GPU timing (glFinish per pass).
                    AmazeProfiler.enabled =
                        InstrumentationRegistry.getArguments().getString("rawlensAmazeProfile") == "true"
                    AmazeProfiler.reset()
                    val t0 = System.nanoTime()
                    val px = run { o -> read(o) }
                    Log.i(TAG, "PHOTO $name $algo ${(System.nanoTime() - t0) / 1_000_000} ms")
                    if (AmazeProfiler.enabled) Log.i(TAG, "PROFILE $name $algo ${AmazeProfiler.report()}")
                    val bytes = ByteBuffer.allocate(px.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    px.forEach { bytes.putFloat(it) }
                    File(out, "$name.$algo.f32").writeBytes(bytes.array())
                    // B5 regression check: graphics memory after each photo-sized develop.
                    MemoryLeakDiagnostics.sample("photo-demosaic-$name-$algo")
                }
            }
        } finally {
            processor.close()
        }
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
        const val TAG = "RawLensPhotoDemosaic"
    }
}
