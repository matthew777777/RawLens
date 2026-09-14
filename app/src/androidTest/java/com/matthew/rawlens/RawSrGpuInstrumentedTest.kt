// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class RawSrGpuInstrumentedTest {
    @Test fun flowMatrixMatchesCpuOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = listOf(
            FlowCase("static", 0f, 0f, true), FlowCase("integer_positive", 4f, 2f),
            FlowCase("integer_negative", -4f, -2f), FlowCase("subpixel_mixed", 1.2f, -0.6f)
        )
        Gles31RawSrProcessor(context).use { processor ->
            cases.forEach { case ->
                val reference = syntheticRaw(128, 96, 0f, 0f)
                val moving = syntheticRaw(128, 96, case.rawShiftX, case.rawShiftY)
                val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 3)
                val cpu = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(reference),
                    RawSrAlignment.bayerQuadGray(moving), config)
                processor.process(listOf(reference, moving), config) { output ->
                    val identity = gpuIdentity()
                    val flow = readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA)
                    exportFlow(context.cacheDir, identity, case.name, flow, cpu.columns, cpu.rows)
                    val metrics = compareFlows(flow, cpu, case.rawShiftX * 0.5f, case.rawShiftY * 0.5f)
                    Log.i(TAG, "$identity ${case.name} $metrics")
                    assertTrue("$identity ${case.name} non-finite flow", flow.all(Float::isFinite))
                    assertTrue("$identity ${case.name} coverage=${metrics.coverage}",
                        metrics.coverage >= MIN_RELIABLE_COVERAGE)
                    if (case.static) assertTrue("$identity static max flow=${metrics.maxMagnitude}",
                        metrics.maxMagnitude <= STATIC_FLOW_LIMIT)
                    assertTrue("$identity ${case.name} $metrics",
                        metrics.maeX < FLOW_MAE_LIMIT && metrics.maeY < FLOW_MAE_LIMIT &&
                            metrics.truthMaeX < FLOW_MAE_LIMIT && metrics.truthMaeY < FLOW_MAE_LIMIT)
                }
            }
        }
    }

    @Test fun flatFieldProducesDeterministicRejectionMask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flat = flatRaw(64, 48)
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val cpu = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(flat),
            RawSrAlignment.bayerQuadGray(flat), config)
        assertTrue(cpu.tiles.none(RawSrTileFlow::reliable))
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(listOf(flat, flat), config) { output ->
                val flow = readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA)
                assertTrue(flow.all(Float::isFinite))
                assertTrue("flat tiles must be rejected", cpu.tiles.indices.all { flow[it * 4 + 3] == 0f })
            }
        }
    }

    @Test fun referenceOnlyAccumulationMatchesPhaseSafeCpuDemosaic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BayerPattern.entries.forEach { pattern ->
            val reference = syntheticRaw(64, 48, 0f, 0f, pattern)
            val expected = RawSrMergePrototype.demosaic(reference)
            Gles31RawSrProcessor(context).use { processor ->
                processor.process(listOf(reference), referenceOnly = true) { output ->
                    assertTrue(output.flowTextureIds.isEmpty())
                    assertEquals(1, output.acceptedFrames)
                    assertAccumulation(output, expected, expectedDenominator = 1f)
                }
            }
        }
    }

    @Test fun twoFrameAccumulatorMatchesCpuOracleAndPreservesChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = listOf(syntheticRaw(64, 48, 0f, 0f), syntheticRaw(64, 48, 0f, 0f))
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val cpu = RawSrMergePrototype.merge(frames, config)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config) { output ->
                val maxError = assertAccumulation(output, cpu.image, minimumDenominator = 1f)
                Log.i(TAG, "${gpuIdentity()} two_frame normalizedRgbMaxError=$maxError")
                assertTrue("normalized RGB max error=$maxError", maxError <= RGB_TOLERANCE)
                val p = (output.height / 2 * output.width + output.width / 2) * 3
                assertTrue(cpu.image.values[p] > cpu.image.values[p + 1])
                assertTrue(cpu.image.values[p + 1] > cpu.image.values[p + 2])
            }
        }
    }

    private fun compareFlows(gpu: FloatArray, cpu: RawSrAlignmentField,
                             expectedDx: Float, expectedDy: Float): FlowMetrics {
        var valid = 0; var possible = 0; var sumX = 0.0; var sumY = 0.0
        var biasX = 0.0; var biasY = 0.0; var truthX = 0.0; var truthY = 0.0
        var maxError = 0f; var maxMagnitude = 0f
        for (p in cpu.tiles.indices) {
            val tx = p % cpu.columns; val ty = p / cpu.columns
            if (tx !in 1 until cpu.columns - 1 || ty !in 1 until cpu.rows - 1) continue
            possible++
            val tile = cpu.tiles[p]
            if (!tile.reliable || gpu[p * 4 + 3] <= 0.5f) continue
            valid++
            val dx = gpu[p * 4] - tile.dx; val dy = gpu[p * 4 + 1] - tile.dy
            sumX += abs(dx); sumY += abs(dy); biasX += dx; biasY += dy
            truthX += abs(gpu[p * 4] - expectedDx); truthY += abs(gpu[p * 4 + 1] - expectedDy)
            maxError = max(maxError, max(abs(dx), abs(dy)))
            maxMagnitude = max(maxMagnitude, max(abs(gpu[p * 4]), abs(gpu[p * 4 + 1])))
        }
        require(valid > 0)
        return FlowMetrics(valid, possible, valid.toFloat() / possible, biasX / valid, biasY / valid,
            sumX / valid, sumY / valid, truthX / valid, truthY / valid, maxError, maxMagnitude)
    }

    private fun assertAccumulation(output: RawSrGpuOutput, expected: RawSrRgbImage,
                                   expectedDenominator: Float? = null,
                                   minimumDenominator: Float = 0f): Float {
        val numerator = readTexture(output.numeratorTextureId, output.width, output.height, GLES30.GL_RGBA)
        val denominator = readTexture(output.denominatorTextureId, output.width, output.height, GLES30.GL_RED)
        var maxError = 0f
        for (y in 4 until output.height - 4) for (x in 4 until output.width - 4) {
            val p = y * output.width + x
            expectedDenominator?.let { assertEquals(it, denominator[p], 0f) }
            assertTrue(denominator[p] >= minimumDenominator)
            for (channel in 0..2) {
                val gpu = numerator[p * 4 + channel] / denominator[p]
                assertTrue(gpu.isFinite())
                maxError = max(maxError, abs(expected.values[p * 3 + channel] - gpu))
            }
        }
        return maxError
    }

    private fun exportFlow(cache: File, identity: String, case: String, flow: FloatArray,
                           columns: Int, rows: Int) {
        val stem = "${identity}_${case}".safeName()
        RawSrDebugExporter.writeFlowPng(flow, columns, rows, File(cache, "rawsr-debug/$stem.png"))
        RawSrDebugExporter.writeFlowCsv(flow, columns, rows, File(cache, "rawsr-debug/$stem.csv"))
    }

    private fun gpuIdentity(): String {
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
        assertTrue("Missing GLES identity", !vendor.isNullOrBlank() && !renderer.isNullOrBlank())
        return "$vendor/$renderer"
    }

    private fun flatRaw(width: Int, height: Int) = UnpackedRawCfa(width, height, BayerPattern.RGGB,
        FloatArray(width * height) { 0.4f }, RawCrop(0, 0, width, height))

    private fun syntheticRaw(width: Int, height: Int, shiftX: Float, shiftY: Float,
                             pattern: BayerPattern = BayerPattern.RGGB): UnpackedRawCfa {
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shiftX; val sy = y - shiftY
            val texture = 0.20f * valueNoise(sx, sy, 7f) +
                0.12f * valueNoise(sx + 31f, sy - 17f, 19f) +
                0.04f * (sx / width + sy / height - 1f)
            values[y * width + x] = when (pattern.colorAt(x, y)) {
                CfaColor.RED -> 0.68f; CfaColor.GREEN -> 0.43f; CfaColor.BLUE -> 0.24f
            } + texture
        }
        return UnpackedRawCfa(width, height, pattern, values, RawCrop(0, 0, width, height))
    }

    private fun valueNoise(x: Float, y: Float, scale: Float): Float {
        val gx = x / scale; val gy = y / scale
        val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
        val fx = gx - x0; val fy = gy - y0
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val top = hash(x0, y0) * (1f - sx) + hash(x0 + 1, y0) * sx
        val bottom = hash(x0, y0 + 1) * (1f - sx) + hash(x0 + 1, y0 + 1) * sx
        return top * (1f - sy) + bottom * sy
    }

    private fun hash(x: Int, y: Int): Float {
        var bits = x * 0x1f123bb5 + y * 0x5f356495
        bits = (bits xor (bits ushr 15)) * 0x2c1b3c6d
        bits = bits xor (bits ushr 12)
        return ((bits ushr 8) and 0xffff) / 32767.5f - 1f
    }

    private fun readTexture(texture: Int, width: Int, height: Int, format: Int): FloatArray {
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
            val channels = if (format == GLES30.GL_RED) 1 else 4
            val bytes = ByteBuffer.allocateDirect(width * height * channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, format, GLES30.GL_FLOAT, bytes)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            return FloatArray(width * height * channels).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    private data class FlowCase(val name: String, val rawShiftX: Float, val rawShiftY: Float,
                                val static: Boolean = false)
    private data class FlowMetrics(val valid: Int, val possible: Int, val coverage: Float,
                                   val biasX: Double, val biasY: Double, val maeX: Double,
                                   val maeY: Double, val truthMaeX: Double, val truthMaeY: Double,
                                   val maxError: Float, val maxMagnitude: Float)

    private fun String.safeName() = replace(Regex("[^A-Za-z0-9._-]+"), "_")

    private companion object {
        const val TAG = "RawLensRawSrGpuTest"
        const val STATIC_FLOW_LIMIT = 0.05f
        const val FLOW_MAE_LIMIT = 0.45
        const val MIN_RELIABLE_COVERAGE = 0.8f
        const val RGB_TOLERANCE = 0.015f
    }
}
