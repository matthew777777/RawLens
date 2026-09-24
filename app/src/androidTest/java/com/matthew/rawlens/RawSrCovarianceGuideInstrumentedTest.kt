// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prompt 4B.1 GPU tests: stabilized guide path over packed frames. */
@RunWith(AndroidJUnit4::class)
class RawSrCovarianceGuideInstrumentedTest {
    private val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
    private val profile8 = ImmutableDoubleValues(
        doubleArrayOf(0.01, 0.001, 0.02, 0.001, 0.03, 0.001, 0.04, 0.001))

    private fun packed(
        layoutW: Int = 34,
        layoutH: Int = 26,
        originX: Int = 0,
        originY: Int = 0,
        codes: (sensorX: Int, sensorY: Int) -> Int,
        sensorPattern: BayerPattern = BayerPattern.RGGB,
        profile: ImmutableDoubleValues? = profile8
    ): RawSrPackedFrame {
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(originX + sx, originY + sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, originX, originY),
            RawCrop(0, 0, 32, 24), RawNormalization(sensorPattern, listOf(64f, 64f, 64f, 64f), 4000f),
            null, profile)
    }

    private fun content(sx: Int, sy: Int) = 1200 + (sx * 79 + sy * 43) % 500

    private fun runCovariance(frames: List<RawSrPackedFrame>): Map<Int, FloatArray> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = mutableMapOf<Int, FloatArray>()
        Gles31RawSrProcessor(context).use { processor ->
            processor.processPacked(frames, config, onCovariance =
                { index, id, width, height -> captured[index] = readRgba(id, width, height) }) { _ -> }
        }
        return captured
    }

    private fun expected(frame: RawSrPackedFrame): FloatArray {
        val tuning = RawSrTuning.fromReference(frame).tuning
        return RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frame).gray, tuning).values
    }

    @Test fun stabilizedGuideMatchesOracleAcrossOrigins() {
        for ((ox, oy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
            val reference = packed(originX = ox, originY = oy, codes = ::content)
            val moving = packed(originX = ox, originY = oy, codes = { sx, sy -> content(sx + 2, sy + 1) })
            val captured = runCovariance(listOf(reference, moving))
            assertEquals("($ox,$oy)", setOf(0, 1), captured.keys)
            assertSameField("($ox,$oy) ref", expected(reference), captured.getValue(0))
            assertSameField("($ox,$oy) moving", expected(moving), captured.getValue(1))
        }
    }

    @Test fun sixCoefficientGuideMatchesOracle() {
        val profile6 = ImmutableDoubleValues(doubleArrayOf(0.02, 0.002, 0.01, 0.001, 0.03, 0.003))
        for ((pattern, ox, oy) in listOf(
            Triple(BayerPattern.RGGB, 0, 0), Triple(BayerPattern.BGGR, 1, 1))) {
            val reference = packed(originX = ox, originY = oy, codes = ::content,
                sensorPattern = pattern, profile = profile6)
            val moving = packed(originX = ox, originY = oy, codes = { sx, sy -> content(sx + 2, sy + 1) },
                sensorPattern = pattern, profile = profile6)
            val captured = runCovariance(listOf(reference, moving))
            assertSameField("$pattern ref", expected(reference), captured.getValue(0))
            assertSameField("$pattern moving", expected(moving), captured.getValue(1))
        }
    }

    @Test fun missingProfileFallsBackToPlainGuideOnDevice() {
        val reference = packed(codes = ::content, profile = null)
        val moving = packed(codes = { sx, sy -> content(sx + 2, sy + 1) }, profile = null)
        assertEquals(RawSrCovarianceGuide.Status.UNSTABILIZED_MISSING_PROFILE,
            RawSrCovarianceGuide.guide(reference).status)
        val captured = runCovariance(listOf(reference, moving))
        assertSameField("missing ref", expected(reference), captured.getValue(0))
        assertSameField("missing moving", expected(moving), captured.getValue(1))
    }

    @Test fun zeroShotAffineGuideMatchesOracleOnDevice() {
        val affine = ImmutableDoubleValues(
            doubleArrayOf(0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0))
        val reference = packed(codes = ::content, profile = affine)
        val moving = packed(codes = { sx, sy -> content(sx + 2, sy + 1) }, profile = affine)
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED_ZERO_SHOT,
            RawSrCovarianceGuide.guide(reference).status)
        val captured = runCovariance(listOf(reference, moving))
        assertSameField("zero-shot ref", expected(reference), captured.getValue(0))
        assertSameField("zero-shot moving", expected(moving), captured.getValue(1))
    }

    @Test fun tinyStepDarkAnisotropicBrightIsotropicOnDevice() {
        fun stepped(base: Int) = packed(codes = { sx, _ -> if (sx < 16) base else base + 4 },
            profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.02 else 1.0 }))
        // Guide is 16 wide; the step straddles quad columns 7|8; sample quad (8,6).
        // Absolute ratios follow the reference-resolved tuning (dark resolves to
        // SNR 6, unlike the SNR-30 CPU analytic), so the test asserts strict
        // oracle agreement plus the GAT ordering on both sides.
        val deviceRatios = mutableMapOf<Int, Float>()
        val oracleRatios = mutableMapOf<Int, Float>()
        for (base in listOf(200, 3000)) {
            val reference = stepped(base)
            val tuning = RawSrTuning.fromReference(reference).tuning
            val oracle = RawSrKernelCovariance.precision(
                RawSrCovarianceGuide.guide(reference).gray, tuning)
            oracleRatios[base] = oracle.get(8, 6, 0) / oracle.get(8, 6, 3)
            val captured = runCovariance(listOf(reference, stepped(base + 100)))
            assertSameField("base $base", oracle.values, captured.getValue(0))
            val field = captured.getValue(0)
            deviceRatios[base] = field[(6 * 16 + 8) * 4] / field[(6 * 16 + 8) * 4 + 3]
        }
        assertTrue("oracle ${oracleRatios[200]} vs ${oracleRatios[3000]}",
            oracleRatios.getValue(200) > oracleRatios.getValue(3000) * 1.2f)
        assertTrue("device ${deviceRatios[200]} vs ${deviceRatios[3000]}",
            deviceRatios.getValue(200) > deviceRatios.getValue(3000))
    }

    private fun assertSameField(name: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$name size", expected.size, actual.size)
        var worst = 0f
        for (i in expected.indices) {
            val allowed = 2e-3f + 2e-3f * abs(expected[i])
            val error = abs(expected[i] - actual[i])
            worst = maxOf(worst, error)
            assertTrue("$name [$i] expected=${expected[i]} actual=${actual[i]}",
                error <= allowed && actual[i].isFinite())
        }
        android.util.Log.i("RawSrGuideGpu", "$name worstAbsoluteError=$worst")
    }

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
