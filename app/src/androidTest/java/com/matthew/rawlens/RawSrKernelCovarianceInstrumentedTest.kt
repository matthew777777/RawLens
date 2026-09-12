// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prompt 4B GPU comparisons: packed precision textures against the CPU oracle. */
@RunWith(AndroidJUnit4::class)
class RawSrKernelCovarianceInstrumentedTest {
    private val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
    private val tuning = RawSrTuning.forSnr(30.0)

    @Test fun gpuMatchesCpuOracleForReferenceAndMoving() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reference = flatRaw(64, 48, 0.4f)
        val moving = quadLineRaw(64, 48)
        val captured = mutableMapOf<Int, FloatArray>()
        val sizes = mutableMapOf<Int, Pair<Int, Int>>()
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(listOf(reference, moving), config, tuning, onCovariance =
                { index, id, width, height ->
                    captured[index] = readRgba(id, width, height)
                    sizes[index] = width to height
                }) { _ -> }
        }
        assertEquals(setOf(0, 1), captured.keys)
        assertEquals(32 to 24, sizes[0])
        assertEquals(32 to 24, sizes[1])
        for ((index, frame) in listOf(reference, moving).withIndex()) {
            val expected = RawSrKernelCovariance.precision(RawSrAlignment.bayerQuadGray(frame), tuning)
            assertSameField("frame $index", expected.values, captured.getValue(index))
        }
    }

    @Test fun flatFieldMatchesIsotropicDenoiseOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frame = flatRaw(64, 48, 0.4f)
        var actual = floatArrayOf()
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(listOf(frame, frame), config, tuning, onCovariance =
                { index, id, width, height ->
                    if (index == 0) actual = readRgba(id, width, height)
                }) { _ -> }
        }
        // kDenoise at SNR 30 is 3.0, kDetail is 0.25: precision is 1/0.75^2 everywhere.
        assertTrue(actual.isNotEmpty())
        for (p in 0 until actual.size / 4) {
            assertEquals(1.7777778f, actual[p * 4], 1e-3f)
            assertEquals(0f, actual[p * 4 + 1], 1e-4f)
            assertEquals(0f, actual[p * 4 + 2], 1e-4f)
            assertEquals(1.7777778f, actual[p * 4 + 3], 1e-3f)
        }
    }

    @Test fun gpuResultsAreDeterministic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = listOf(texturedRaw(64, 48, 7), texturedRaw(64, 48, 99))
        fun run(): List<FloatArray> {
            val out = mutableListOf<FloatArray>()
            Gles31RawSrProcessor(context).use { processor ->
                processor.process(frames, config, tuning, onCovariance =
                    { _, id, width, height -> out += readRgba(id, width, height) }) { _ -> }
            }
            return out
        }
        val first = run()
        val second = run()
        assertEquals(first.size, second.size)
        for (i in first.indices) org.junit.Assert.assertArrayEquals(first[i], second[i], 0f)
    }

    @Test fun texturedPairMatchesOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = listOf(texturedRaw(64, 48, 7), texturedRaw(64, 48, 99))
        val captured = mutableMapOf<Int, FloatArray>()
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning, onCovariance =
                { index, id, width, height -> captured[index] = readRgba(id, width, height) }) { _ -> }
        }
        for ((index, frame) in frames.withIndex()) {
            val expected = RawSrKernelCovariance.precision(RawSrAlignment.bayerQuadGray(frame), tuning)
            assertSameField("textured frame $index", expected.values, captured.getValue(index))
        }
    }

    private fun assertSameField(name: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$name size", expected.size, actual.size)
        var worst = 0f
        for (i in expected.indices) {
            val allowed = 2e-3f + 2e-3f * kotlin.math.abs(expected[i])
            val error = kotlin.math.abs(expected[i] - actual[i])
            worst = maxOf(worst, error)
            assertTrue("$name [$i] expected=${expected[i]} actual=${actual[i]}",
                error <= allowed && actual[i].isFinite())
        }
        android.util.Log.i("RawSrKernelCovariance", "$name worstAbsoluteError=$worst")
    }

    private fun flatRaw(width: Int, height: Int, value: Float) = UnpackedRawCfa(width, height,
        BayerPattern.RGGB, FloatArray(width * height) { value }, RawCrop(0, 0, width, height))

    /** Full-contrast line covering raw columns 32-33, i.e. one complete quad column. */
    private fun quadLineRaw(width: Int, height: Int) = UnpackedRawCfa(width, height,
        BayerPattern.RGGB,
        FloatArray(width * height) { i -> if ((i % width) / 2 == 16) 1f else 0f },
        RawCrop(0, 0, width, height))

    private fun texturedRaw(width: Int, height: Int, seed: Int) = UnpackedRawCfa(width, height,
        BayerPattern.RGGB,
        FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            0.2f + 0.6f * ((x * 79 + y * 43 + seed * 131) % 101) / 101f
        },
        RawCrop(0, 0, width, height))

    private fun readRgba(texture: Int, width: Int, height: Int): FloatArray {
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val bytes = ByteBuffer.allocateDirect(width * height * 4 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(width * height * 4).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }
}
