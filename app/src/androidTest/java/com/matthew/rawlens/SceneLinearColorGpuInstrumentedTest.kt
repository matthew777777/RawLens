// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.opengl.GLES30
import android.opengl.GLES31
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneLinearColorGpuInstrumentedTest {
    @Test
    fun fusedAmazeOutputAttachesUltraHdrGainmap() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = UnpackedRawCfa(
            IDENTITY_WIDTH,
            IDENTITY_HEIGHT,
            BayerPattern.RGGB,
            FloatArray(IDENTITY_WIDTH * IDENTITY_HEIGHT) { index ->
                0.05f + (index % IDENTITY_WIDTH) / IDENTITY_WIDTH.toFloat() * 4f
            },
            RawCrop(0, 0, IDENTITY_WIDTH, IDENTITY_HEIGHT)
        )
        val settings = JpegOutputSettings(ultraHdr = true)
        val output = Gles31JpegOutputProcessor(context)
        val bitmap = Gles31AmazeProcessor(context).process(
            input,
            fusedOutputSettings = settings
        ) { output.processEncoded(it, settings).bitmap }
        try {
            assertTrue("fused output did not attach its Ultra HDR gain map", bitmap.hasGainmap())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fusedAmazeDisplayOutputMatchesSeparateReferencePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = UnpackedRawCfa(
            IDENTITY_WIDTH,
            IDENTITY_HEIGHT,
            BayerPattern.RGGB,
            FloatArray(IDENTITY_WIDTH * IDENTITY_HEIGHT) { index ->
                0.02f + ((index * 37) % 900) / 700f
            },
            RawCrop(0, 0, IDENTITY_WIDTH, IDENTITY_HEIGHT)
        )
        val settings = JpegOutputSettings(
            agxPurityBoost = 1.2f,
            agxContrast = 1.08f,
            agxSaturation = 0.9f,
            agxHuePreservation = 0.2f,
            agxGamutCompression = 0.35f
        )
        val amaze = Gles31AmazeProcessor(context)
        val output = Gles31JpegOutputProcessor(context)
        val reference = amaze.process(input) { output.process(it, settings).bitmap }
        val fused = amaze.process(input, fusedOutputSettings = settings) {
            output.processEncoded(it, settings).bitmap
        }
        try {
            var maximumError = 0
            for (y in 0 until IDENTITY_HEIGHT) for (x in 0 until IDENTITY_WIDTH) {
                val a = reference.getPixel(x, y)
                val b = fused.getPixel(x, y)
                maximumError = maxOf(
                    maximumError,
                    kotlin.math.abs(Color.red(a) - Color.red(b)),
                    kotlin.math.abs(Color.green(a) - Color.green(b)),
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
                )
            }
            assertTrue("fused output maximum 8-bit error $maximumError", maximumError <= 3)
        } finally {
            reference.recycle()
            fused.recycle()
        }
    }

    @Test
    fun rebuiltDenoiseShadersCompileAndProduceFiniteOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = UnpackedRawCfa(
            64, 64, BayerPattern.RGGB,
            FloatArray(64 * 64) { i -> .08f + .002f * ((i * 37) % 19) },
            RawCrop(0, 0, 64, 64)
        )
        Gles31AmazeProcessor(context).process(
            input, denoise = DenoiseSettings(enabled = true)
        ) { output ->
            val values = readTexture(output)
            assertTrue(values.all(Float::isFinite))
            assertTrue(values.indices.filter { it % 4 == 3 }.all { values[it] == 1f })
        }
    }

    @Test
    fun directPackedRawPreprocessingMatchesCpuReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 64
        val height = 64
        val rowStride = width * 2 + 8
        val source = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            source.putShort(y * rowStride + x * 2, (48 + (x * 9 + y * 13) % 900).toShort())
        }
        source.position(0)
        val layout = RawPlaneLayout(width, height, rowStride, 2, sensorOriginX = 3, sensorOriginY = 5)
        val crop = RawCrop(0, 0, width, height)
        val normalization = RawNormalization(
            BayerPattern.GBRG, listOf(64f, 65f, 66f, 67f), 1023f
        )
        val lens = LensShadingModel(
            2, 2,
            floatArrayOf(
                1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f,
                1.8f, 1.9f, 2f, 2.1f, 2.2f, 2.3f, 2.4f, 2.5f
            ),
            IntRectSnapshot(3, 5, 67, 69)
        )
        val cpuInput = LensShadingCorrector.applyOwnedInPlace(
            RawSensorUnpacker.unpackNormalized(source, layout, normalization, crop), lens
        ).cfa
        val amaze = Gles31AmazeProcessor(context)
        val cpu = amaze.process(cpuInput) { readTexture(it) }
        val gpu = amaze.processRaw(
            GpuRawAmazeInput(source, layout, crop, normalization, lens)
        ) { readTexture(it) }

        var maximumError = 0f
        for (index in cpu.indices) {
            maximumError = maxOf(maximumError, kotlin.math.abs(cpu[index] - gpu[index]))
        }
        assertTrue("direct RAW preprocessing maximum error $maximumError", maximumError <= 0.015f)
    }

    @Test
    fun amazePreservesRedAndBlueCfaChannelIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val amaze = Gles31AmazeProcessor(context)
        fun demosaic(pattern: BayerPattern, primary: CfaColor): FloatArray {
            val input = UnpackedRawCfa(
                IDENTITY_WIDTH,
                IDENTITY_HEIGHT,
                pattern,
                FloatArray(IDENTITY_WIDTH * IDENTITY_HEIGHT) { index ->
                    val x = index % IDENTITY_WIDTH
                    val y = index / IDENTITY_WIDTH
                    if (pattern.colorAt(x, y) == primary) 0.75f else 0f
                },
                RawCrop(0, 0, IDENTITY_WIDTH, IDENTITY_HEIGHT)
            )
            return amaze.process(input) { output -> readTexture(output) }
        }

        val offset = ((IDENTITY_HEIGHT / 2) * IDENTITY_WIDTH + (IDENTITY_WIDTH / 2)) * 4
        BayerPattern.entries.forEach { pattern ->
            val red = demosaic(pattern, CfaColor.RED)
            assertTrue("$pattern red CFA became blue", red[offset] > red[offset + 2])
            assertTrue("$pattern red CFA lost red dominance", red[offset] > red[offset + 1])

            val blue = demosaic(pattern, CfaColor.BLUE)
            assertTrue("$pattern blue CFA became red", blue[offset + 2] > blue[offset])
            assertTrue("$pattern blue CFA lost blue dominance", blue[offset + 2] > blue[offset + 1])
        }
    }

    @Test
    fun rgba16fShaderMatchesCpuReferenceAndPreservesUnboundedRange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = UnpackedRawCfa(
            WIDTH,
            HEIGHT,
            BayerPattern.RGGB,
            FloatArray(WIDTH * HEIGHT) { index -> when (index % 4) {
                0 -> -0.125f
                1 -> 0.5f
                2 -> 1.25f
                else -> 2.5f
            } },
            RawCrop(0, 0, WIDTH, HEIGHT)
        )
        val transform = SceneLinearColorProcessor.resolve(
            SceneLinearColorMetadata(
                asShotNeutral = ImmutableDoubleValues(doubleArrayOf(0.7, 1.0, 0.6)),
                wbGains = null,
                cameraCalibration1 = frozen(IDENTITY),
                cameraCalibration2 = null,
                forwardMatrix1 = frozen(doubleArrayOf(
                    0.70, 0.20, 0.06422,
                    0.10, 0.80, 0.10,
                    0.02, 0.10, 0.70521
                )),
                forwardMatrix2 = null,
                colorMatrix1 = null,
                colorMatrix2 = null,
                referenceIlluminant1 = 21,
                referenceIlluminant2 = null
            ),
            exposureEv = 0.5
        )

        val amaze = Gles31AmazeProcessor(context)
        val cameraRgb = amaze.process(input, clipPoint = 3f) { output -> readTexture(output) }
        amaze.process(
            input,
            clipPoint = 3f,
            cameraToAcescgColumnMajor = transform.glslColumnMajorMatrix()
        ) { output ->
            val expected = SceneLinearColorProcessor.processRgba(cameraRgb, transform)
            GLES31.glFinish()
            val actual = readTexture(output)

            assertEquals(expected.size, actual.size)
            var maximumError = 0f
            for (index in expected.indices) {
                maximumError = maxOf(maximumError, kotlin.math.abs(expected[index] - actual[index]))
                assertTrue("GPU produced NaN/Inf at $index", actual[index].isFinite())
            }
            assertTrue("CPU/GPU maximum error $maximumError", maximumError <= GPU_TOLERANCE)
            assertTrue("negative scene values were clipped", actual.any { it < 0f })
            assertTrue("highlight scene values were clipped", actual.any { it > 1f })
            assertTrue(actual.indices.filter { it % 4 == 3 }.all { actual[it] == 1f })

            val bitmap = Gles31JpegOutputProcessor(context).process(output).bitmap
            try {
                for (y in 0 until HEIGHT) {
                    for (x in 0 until WIDTH) {
                        val offset = (y * WIDTH + x) * 4
                        val outputLinear = AgxDisplayTransform.acescgToOutputLinearSrgb(
                            floatArrayOf(actual[offset], actual[offset + 1], actual[offset + 2])
                        )
                        val expected8 = AgxDisplayTransform.quantizeSrgb8(outputLinear, x, y)
                        val pixel = bitmap.getPixel(x, y)
                        assertChannel(expected8[0], Color.red(pixel), x, y, "red")
                        assertChannel(expected8[1], Color.green(pixel), x, y, "green")
                        assertChannel(expected8[2], Color.blue(pixel), x, y, "blue")
                        assertEquals(255, Color.alpha(pixel))
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun assertChannel(expected: Byte, actual: Int, x: Int, y: Int, name: String) {
        val expectedUnsigned = expected.toInt() and 0xff
        assertTrue(
            "$name mismatch at ($x,$y): CPU=$expectedUnsigned GPU=$actual",
            kotlin.math.abs(expectedUnsigned - actual) <= OUTPUT_8_BIT_TOLERANCE
        )
    }

    private fun readTexture(output: AmazeGpuOutput): FloatArray {
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                output.textureId,
                0
            )
            assertEquals(
                GLES30.GL_FRAMEBUFFER_COMPLETE,
                GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            )
            val bytes = ByteBuffer.allocateDirect(output.width * output.height * 4 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(
                0, 0, output.width, output.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes
            )
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(output.width * output.height * 4).also {
                bytes.asFloatBuffer().get(it)
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    private fun frozen(rowMajor: DoubleArray) = ImmutableDoubleValues(DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        rowMajor[column * 3 + row]
    })

    private companion object {
        const val WIDTH = 8
        const val HEIGHT = 8
        const val IDENTITY_WIDTH = 64
        const val IDENTITY_HEIGHT = 64
        const val GPU_TOLERANCE = 0.01f
        // Fused RGBA16F storage plus mobile GPU polynomial evaluation can differ from the
        // Float CPU reference by a few encoded code values near the AgX shoulder.
        const val OUTPUT_8_BIT_TOLERANCE = 3
        val IDENTITY = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}
