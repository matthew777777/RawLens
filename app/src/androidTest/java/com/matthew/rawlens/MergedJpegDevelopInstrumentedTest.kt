// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ColorSpace
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Prompt 5A headless-GPU tests: direct merged-camera-RGB development without a
 * second demosaic. Synthetic RGBA32F merged textures plus R32F Rc textures are
 * uploaded on a private EGL context; device output is asserted against the
 * CPU mirror ([MergedDevelopWeights]) within fp16 readback tolerance.
 */
@RunWith(AndroidJUnit4::class)
class MergedJpegDevelopInstrumentedTest {
    @Test fun uniformFieldMatchesCpuForBothWhiteBalanceSources() {
        withGl {
            // Unity neutral (UNITY_FALLBACK path is not hit: gains null + neutral
            // identity) and a skewed AsShotNeutral: the pipeline must consume the
            // REFERENCE metadata white balance in both cases.
            for (neutral in listOf(
                doubleArrayOf(1.0, 1.0, 1.0),
                doubleArrayOf(0.85, 1.0, 1.15)
            )) {
                val metadata = metadata(neutral = neutral)
                val field = FloatArray(8 * 8 * 4) { i ->
                    doubleArrayOf(0.2, 0.3, 0.4, 1.0)[i % 4].toFloat()
                }
                for (denoise in listOf(false, true)) {
                    val out = develop(
                        field, 8, 8, rcValue = 0f, accepted = 4, metadata = metadata,
                        denoise = RawDevelopmentSettings(
                            denoise = DenoiseSettings(enabled = denoise, strength = 0.2f)
                        )
                    )
                    val expected = cpuField(field, 8, 8, rcValue = 0f, 4, metadata,
                        denoise, 0.2f, crop = RawCrop(0, 0, 8, 8))
                    assertArraysNear("$neutral denoise=$denoise", expected, out)
                    if (denoise) {
                        // Uniform field: denoise must be exactly no-op even when on.
                        val plain = develop(
                            field, 8, 8, rcValue = 0f, accepted = 4, metadata = metadata,
                            denoise = RawDevelopmentSettings()
                        )
                        assertArraysNear("$neutral denoise==off", plain, out)
                    }
                }
            }
        }
    }

    @Test fun cropSelectsSubregionAtOffset() {
        withGl {
            val metadata = metadata()
            val field = gradientField(8, 8)
            val crop = RawCrop(2, 1, 4, 3)
            val out = develop(
                field, 8, 8, rcValue = 2f, accepted = 4, metadata = metadata,
                denoise = RawDevelopmentSettings(
                    denoise = DenoiseSettings(enabled = true, strength = 1f)
                ),
                crop = crop
            )
            assertEquals(4 * 3 * 4, out.size)
            val expected = cpuField(field, 8, 8, rcValue = 2f, 4, metadata, true, 1f, crop)
            assertArraysNear("crop", expected, out)
        }
    }

    @Test fun impulseProvesNoSecondDemosaic() {
        withGl {
            // Denoise off: the path is per-pixel independent. Any demosaic
            // kernel (AMaZE's narrowest spans >= 3 px) would leak impulse
            // energy into neighbors; assert it does not.
            val metadata = metadata()
            val field = FloatArray(8 * 8 * 4)
            field[(4 * 8 + 4) * 4] = 0.5f
            val out = develop(
                field, 8, 8, rcValue = 0f, accepted = 1, metadata = metadata,
                denoise = RawDevelopmentSettings()
            )
            val expected = cpuField(field, 8, 8, rcValue = 0f, 1, metadata, false, 0f,
                RawCrop(0, 0, 8, 8))
            assertArraysNear("impulse", expected, out)
            var neighborWorst = 0f
            for (y in 0 until 8) for (x in 0 until 8) {
                if (x == 4 && y == 4) continue
                val i = (y * 8 + x) * 4
                neighborWorst = max(neighborWorst, abs(out[i]) + abs(out[i + 1]) + abs(out[i + 2]))
            }
            assertTrue("demosaic leak into neighbors: $neighborWorst", neighborWorst < 1e-3f)
            assertTrue("impulse lost at center", out[(4 * 8 + 4) * 4] > 0.4f)
        }
    }

    @Test fun denoiseScalesWithEffectiveCount() {
        withGl {
            val metadata = metadata()
            // Left half unsupported (reference fallback), right half fully supported.
            val field = FloatArray(8 * 8 * 4) { 0.2f }
            field[(4 * 8 + 2) * 4] = 0.5f
            field[(4 * 8 + 6) * 4] = 0.5f
            val rc = FloatArray(4 * 4) { i -> if ((i % 4) < 2) 0f else 4f }
            val settings = RawDevelopmentSettings(
                denoise = DenoiseSettings(enabled = true, strength = 1f)
            )
            val out = developRc(field, 8, 8, rc, accepted = 4, metadata, settings)
            val expected = cpuFieldRc(field, 8, 8, rc, 4, metadata, true, 1f, RawCrop(0, 0, 8, 8))
            assertArraysNear("rc-weighted", expected, out)
            // Movement is measured against the no-denoise developed value, so
            // the color matrix cannot masquerade as denoise motion.
            val crop = RawCrop(0, 0, 8, 8)
            val stillLow = cpuPixel(field, 8, 8, rc, 4, metadata, false, 1f, crop, 2, 4)[0].toFloat()
            val stillHigh = cpuPixel(field, 8, 8, rc, 4, metadata, false, 1f, crop, 6, 4)[0].toFloat()
            val lowMove = abs(out[(4 * 8 + 2) * 4] - stillLow)
            val highMove = abs(out[(4 * 8 + 6) * 4] - stillHigh)
            assertTrue("low-support pixel must move more ($lowMove vs $highMove)", lowMove > highMove + 1e-4f)
            assertTrue("full-support pixel must hold", highMove < 1e-3f)
        }
    }

    @Test fun orientationNeverRotatesPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val coordinator = RawDevelopmentCoordinator(context)
            try {
                val pixels = mutableListOf<Int>()
                for (orientation in listOf(1, 3, 6, 8)) {
                    val metadata = metadata().copy(exifOrientation = orientation)
                    val field = gradientField(8, 8)
                    val developed = coordinator.developMergedTextureJpeg(
                        textureInput(field, 8, 8, rcValue = 4f, accepted = 4, metadata),
                        RawDevelopmentSettings(),
                        JpegOutputSettings()
                    )
                    try {
                        assertEquals(8, developed.bitmap.width)
                        assertEquals(8, developed.bitmap.height)
                        pixels.add(developed.bitmap.getPixel(0, 0))
                    } finally {
                        developed.bitmap.recycle()
                    }
                }
                assertEquals(1, pixels.distinct().size)
            } finally {
                coordinator.close()
            }
        }
    }

    @Test fun sdrP3AndUltraHdrBitmapOutputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31AmazeProcessor.EglComputeContext().use {
            val coordinator = RawDevelopmentCoordinator(context)
            try {
                val metadata = metadata()
                val field = gradientField(16, 16)
                val sdr = coordinator.developMergedTextureJpeg(
                    textureInput(field, 16, 16, rcValue = 4f, accepted = 4, metadata),
                    RawDevelopmentSettings(), JpegOutputSettings()
                )
                try {
                    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), sdr.bitmap.colorSpace)
                    assertEquals(JpegOutputSettings(), sdr.settings)
                } finally {
                    sdr.bitmap.recycle()
                }
                val p3 = coordinator.developMergedTextureJpeg(
                    textureInput(field, 16, 16, rcValue = 4f, accepted = 4, metadata),
                    RawDevelopmentSettings(), JpegOutputSettings(displayP3 = true)
                )
                try {
                    assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), p3.bitmap.colorSpace)
                } finally {
                    p3.bitmap.recycle()
                }
                val uhdr = coordinator.developMergedTextureJpeg(
                    textureInput(field, 16, 16, rcValue = 4f, accepted = 4, metadata),
                    RawDevelopmentSettings(), JpegOutputSettings(ultraHdr = true)
                )
                try {
                    val resolved = JpegOutputSettings(ultraHdr = true).resolvedForPlatform()
                    assertEquals(resolved, uhdr.settings)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        assertEquals(resolved.ultraHdr, uhdr.bitmap.hasGainmap())
                    }
                } finally {
                    if (uhdr.settings.ultraHdr &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ) {
                        uhdr.bitmap.setGainmap(null)
                    }
                    uhdr.bitmap.recycle()
                }
            } finally {
                coordinator.close()
            }
        }
    }

    @Test fun rejectsMismatchedTextures() {
        withGl {
            val metadata = metadata()
            val rgba8 = IntArray(1)
            GLES31.glGenTextures(1, rgba8, 0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rgba8[0])
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, 8, 8)
            try {
                val rc = uploadR32f(4, 4, FloatArray(16))
                try {
                    val processor = Gles31MergedDevelopProcessor(
                        InstrumentationRegistry.getInstrumentation().targetContext
                    )
                    try {
                        assertThrows(IllegalStateException::class.java) {
                            processor.develop(
                                MergedTextureJpegInput(
                                    mergedTextureId = rgba8[0], width = 8, height = 8,
                                    effectiveCountTextureId = rc, acceptedFrames = 4,
                                    referenceMetadata = metadata
                                )
                            ) { fail("must not develop from RGBA8") }
                        }
                    } finally {
                        processor.close()
                    }
                } finally {
                    GLES31.glDeleteTextures(1, intArrayOf(rc), 0)
                }
            } finally {
                GLES31.glDeleteTextures(1, rgba8, 0)
            }
        }
    }

    // ---- harness ----

    private fun withGl(block: () -> Unit) {
        Gles31AmazeProcessor.EglComputeContext().use { block() }
    }

    private fun metadata(neutral: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)) = RawFrameMetadata(
        cameraId = "0", timestampNanos = 100, frameNumber = 1, imageWidth = 16, imageHeight = 16,
        imageCrop = IntRectSnapshot(0, 0, 16, 16), rawPlaneCount = 1, rawPlaneRowStride = 32,
        rawPlanePixelStride = 2, exifOrientation = 1, sensorOrientationDegrees = 0,
        sensitivityIso = 100, exposureTimeNanos = 10_000_000, frameDurationNanos = null,
        rollingShutterSkewNanos = 0, cfaPattern = BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason = null,
        blackLevels = ImmutableFloatValues(floatArrayOf(0f, 0f, 0f, 0f)),
        blackLevelSource = BlackLevelSource.STATIC, whiteLevel = 1023f,
        whiteLevelSource = WhiteLevelSource.STATIC, pixelArraySize = 16 to 16,
        activeArray = IntRectSnapshot(0, 0, 16, 16), preCorrectionActiveArray = null,
        rawCropRegion = null, bufferGeometry = RawBufferGeometry.Supported(0, 0, RawCrop(0, 0, 16, 16), "test"),
        lensShadingAlreadyApplied = true, lensShadingMap = null, hotPixels = emptyList(), wbGains = null,
        neutralColorPoint = ImmutableDoubleValues(neutral), colorCorrectionTransform = null,
        colorMatrix1 = null, colorMatrix2 = null, cameraCalibration1 = null, cameraCalibration2 = null,
        forwardMatrix1 = camera2Frozen(IDENTITY), forwardMatrix2 = null,
        referenceIlluminant1 = 21, referenceIlluminant2 = null, noiseProfile = null, sensorPixelMode = null,
        rawBinningFactorUsed = false, activePhysicalCameraId = "wide", afState = 2, aeState = 2, lensState = 0
    )

    private fun gradientField(w: Int, h: Int) = FloatArray(w * h * 4) { i ->
        val p = i / 4
        val x = (p % w).toFloat() / w
        val y = (p / w).toFloat() / h
        when (i % 4) {
            0 -> (0.1f + 0.4f * x)
            1 -> (0.1f + 0.4f * y)
            2 -> (0.1f + 0.4f * (x + y) / 2f)
            else -> 1f
        }
    }

    private fun uploadRgba32f(w: Int, h: Int, data: FloatArray): Int {
        val id = IntArray(1)
        GLES31.glGenTextures(1, id, 0)
        assertTrue(id[0] != 0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id[0])
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).rewind()
        // Immutable storage: the develop shader binds inputs as images, which
        // rejects mutable-format textures with INVALID_OPERATION.
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, w, h)
        GLES31.glTexSubImage2D(
            GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer
        )
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        return id[0]
    }

    private fun uploadR32f(w: Int, h: Int, data: FloatArray): Int {
        val id = IntArray(1)
        GLES31.glGenTextures(1, id, 0)
        assertTrue(id[0] != 0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id[0])
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).rewind()
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_R32F, w, h)
        GLES31.glTexSubImage2D(
            GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RED, GLES30.GL_FLOAT, buffer
        )
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        return id[0]
    }

    private fun readRgba16f(texture: Int, w: Int, h: Int): FloatArray {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        assumeTrue("half-float FBO readback unavailable", "GL_EXT_color_buffer_half_float" in extensions)
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0
            )
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val bytes = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(w * h * 4).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fb, 0)
        }
    }

    private fun textureInput(
        field: FloatArray, w: Int, h: Int, rcValue: Float, accepted: Int, metadata: RawFrameMetadata,
        crop: RawCrop = RawCrop(0, 0, w, h)
    ): MergedTextureJpegInput {
        // Textures are intentionally leaked to the test EGL context lifetime:
        // the coordinator consumes them synchronously before returning.
        val merged = uploadRgba32f(w, h, field)
        val rc = uploadR32f(w / 2, h / 2, FloatArray(w * h / 4) { rcValue })
        return MergedTextureJpegInput(
            mergedTextureId = merged, width = w, height = h,
            effectiveCountTextureId = rc, acceptedFrames = accepted,
            referenceMetadata = metadata, crop = crop
        )
    }

    private fun develop(
        field: FloatArray, w: Int, h: Int, rcValue: Float, accepted: Int,
        metadata: RawFrameMetadata, denoise: RawDevelopmentSettings,
        crop: RawCrop = RawCrop(0, 0, w, h)
    ): FloatArray {
        val processor = Gles31MergedDevelopProcessor(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        try {
            val merged = uploadRgba32f(w, h, field)
            val rc = uploadR32f(w / 2, h / 2, FloatArray(w * h / 4) { rcValue })
            try {
                return processor.develop(
                    MergedTextureJpegInput(
                        mergedTextureId = merged, width = w, height = h,
                        effectiveCountTextureId = rc, acceptedFrames = accepted,
                        referenceMetadata = metadata, crop = crop
                    ),
                    denoise
                ) { scene ->
                    assertEquals(AmazeTextureFormat.RGBA16F, scene.internalFormat)
                    assertEquals(crop.width, scene.width)
                    assertEquals(crop.height, scene.height)
                    readRgba16f(scene.textureId, scene.width, scene.height)
                }
            } finally {
                GLES31.glDeleteTextures(1, intArrayOf(merged), 0)
                GLES31.glDeleteTextures(1, intArrayOf(rc), 0)
            }
        } finally {
            processor.close()
        }
    }

    private fun developRc(
        field: FloatArray, w: Int, h: Int, rc: FloatArray, accepted: Int,
        metadata: RawFrameMetadata, denoise: RawDevelopmentSettings
    ): FloatArray {
        val processor = Gles31MergedDevelopProcessor(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        try {
            val merged = uploadRgba32f(w, h, field)
            val rcTex = uploadR32f(w / 2, h / 2, rc)
            try {
                return processor.develop(
                    MergedTextureJpegInput(
                        mergedTextureId = merged, width = w, height = h,
                        effectiveCountTextureId = rcTex, acceptedFrames = accepted,
                        referenceMetadata = metadata
                    ),
                    denoise
                ) { scene -> readRgba16f(scene.textureId, scene.width, scene.height) }
            } finally {
                GLES31.glDeleteTextures(1, intArrayOf(merged), 0)
                GLES31.glDeleteTextures(1, intArrayOf(rcTex), 0)
            }
        } finally {
            processor.close()
        }
    }

    private fun cpuField(
        field: FloatArray, w: Int, h: Int, rcValue: Float, accepted: Int,
        metadata: RawFrameMetadata, denoiseOn: Boolean, strength: Float, crop: RawCrop
    ): FloatArray = cpuFieldRc(
        field, w, h, FloatArray(w * h / 4) { rcValue }, accepted, metadata, denoiseOn, strength, crop
    )

    private fun cpuFieldRc(
        field: FloatArray, w: Int, h: Int, rc: FloatArray, accepted: Int,
        metadata: RawFrameMetadata, denoiseOn: Boolean, strength: Float, crop: RawCrop
    ): FloatArray {
        val out = FloatArray(crop.width * crop.height * 4)
        for (oy in 0 until crop.height) for (ox in 0 until crop.width) {
            val px = cpuPixel(field, w, h, rc, accepted, metadata, denoiseOn, strength, crop, ox, oy)
            val o = (oy * crop.width + ox) * 4
            out[o] = px[0].toFloat(); out[o + 1] = px[1].toFloat(); out[o + 2] = px[2].toFloat()
            out[o + 3] = 1f
        }
        return out
    }

    private fun cpuPixel(
        field: FloatArray, w: Int, h: Int, rc: FloatArray, accepted: Int,
        metadata: RawFrameMetadata, denoiseOn: Boolean, strength: Float,
        crop: RawCrop, ox: Int, oy: Int
    ): DoubleArray {
        val transform = SceneLinearColorProcessor.resolve(SceneLinearColorMetadata.from(metadata), 0.0)
        val matrix = transform.cameraToAcescg.toDoubleArray()
        val white = transform.cameraWhiteNormalized.toDoubleArray()
        fun sample(qx: Int, qy: Int, c: Int): Double {
            // Full-frame clamp AFTER the crop offset — the shader samples true
            // neighbors across the crop boundary (no seam), then clamps.
            val cx = qx.coerceIn(0, w - 1)
            val cy = qy.coerceIn(0, h - 1)
            return field[(cy * w + cx) * 4 + c].toDouble()
        }
        val block = DoubleArray(27)
        for (dy in -1..1) for (dx in -1..1) {
            val i = ((dy + 1) * 3 + (dx + 1)) * 3
            block[i] = sample(ox + crop.left + dx, oy + crop.top + dy, 0)
            block[i + 1] = sample(ox + crop.left + dx, oy + crop.top + dy, 1)
            block[i + 2] = sample(ox + crop.left + dx, oy + crop.top + dy, 2)
        }
        val qx = ox + crop.left; val qy = oy + crop.top
        return MergedDevelopWeights.developPixel(
            block, rc[(qy / 2) * (w / 2) + (qx / 2)], accepted, denoiseOn, strength, matrix, white
        )
    }

    private fun assertArraysNear(label: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$label size", expected.size, actual.size)
        var worst = 0f
        for (i in expected.indices) {
            val tol = 2e-3f + 2e-3f * abs(expected[i])
            worst = max(worst, abs(actual[i] - expected[i]) - tol)
            assertTrue("$label [$i]: expected=${expected[i]} actual=${actual[i]}", abs(actual[i] - expected[i]) <= tol)
        }
        assertTrue("$label worst slack $worst", worst <= 0f)
    }

    private fun camera2Frozen(rowMajor: DoubleArray) = ImmutableDoubleValues(DoubleArray(9) { index ->
        rowMajor[(index % 3) * 3 + (index / 3)]
    })

    private companion object {
        val IDENTITY = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}
