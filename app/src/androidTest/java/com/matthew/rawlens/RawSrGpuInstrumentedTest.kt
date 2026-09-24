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
import org.json.JSONObject
import org.json.JSONArray
import android.os.SystemClock
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class RawSrGpuInstrumentedTest {
    @Test fun clippedHighlightsUseReferenceNeutralAcrossEveryBayerPhase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val neutral = doubleArrayOf(0.749634, 1.0, 0.368611)
        Gles31RawSrProcessor(context).use { processor ->
            for (pattern in BayerPattern.entries) for (left in 0..1) for (top in 0..1) {
                val w = 64; val h = 48
                val plane = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
                for (y in 0 until h) for (x in 0 until w) {
                    val code = when (pattern.colorAt(left + x, top + y)) {
                        CfaColor.RED -> 7400
                        CfaColor.GREEN -> 10000
                        CfaColor.BLUE -> 3000
                    }
                    plane.putShort(code.toShort())
                }
                plane.flip()
                val frame = RawSrPackedFrame(plane, RawPlaneLayout(w, h, w * 2, 2, left, top),
                    RawCrop(0, 0, w, h), RawNormalization(pattern, List(4) { 0f }, 10000f),
                    null, neutralColorPoint = ImmutableDoubleValues(neutral))
                processor.processPacked(listOf(frame), referenceOnly = true) { output ->
                    val rgb = readMerged(output)
                    for (p in 0 until w * h) for (c in 0..2) {
                        assertEquals("$pattern origin=$left,$top pixel=$p ch=$c",
                            neutral[c], rgb[p * 4 + c].toDouble(), 1e-5)
                    }
                }
            }
        }
    }

    @Test fun packedDefaultAlignmentUsesResolvedSnrTileUnits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31RawSrProcessor(context).use { processor ->
            for ((snr, tileQuads) in listOf(10.0 to 32, 18.0 to 16, 26.0 to 8)) {
                val plane = ByteBuffer.allocateDirect(64 * 48 * 2).order(ByteOrder.nativeOrder())
                repeat(64 * 48) { plane.putShort(500.toShort()) }; plane.flip()
                val variance = (0.5 / snr) * (0.5 / snr)
                val frame = RawSrPackedFrame(plane, RawPlaneLayout(64, 48, 128, 2),
                    RawCrop(0, 0, 64, 48), RawNormalization(BayerPattern.RGGB, List(4) { 0f }, 1000f),
                    null, ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.0 else variance }))
                var callbacks = 0
                processor.processPacked(listOf(frame, frame), onFlow = { _, _, columns, rows ->
                    assertEquals((32 + tileQuads - 1) / tileQuads, columns)
                    assertEquals((24 + tileQuads - 1) / tileQuads, rows)
                    callbacks++
                }) { assertEquals(1, callbacks) }
            }
        }
    }

    @Test fun realSeaFixtureMetadataAndChecksums() {
        val fixture = RawSrFixture.load(InstrumentationRegistry.getInstrumentation().context.assets)
        assertEquals(30, fixture.frames.size)
        assertEquals(15, fixture.referenceIndex)
        val metadata = fixture.manifest.getJSONArray("frames")
        for ((index, frame) in fixture.frames.withIndex()) {
            val input = frame.uploadInput()
            assertEquals(BayerPattern.GRBG, input.pattern) // GBRG at DNG origin, crop at odd/odd
            assertEquals(801, input.sensorCropLeft); assertEquals(1201, input.sensorCropTop)
            assertEquals(512, frame.width); assertEquals(384, frame.height)
            val cpu = RawSensorUnpacker.unpackNormalized(input.buffer, input.layout, input.normalization, input.crop)
            val plane = input.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            for ((x, y) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 511 to 383)) {
                val code = plane.getShort((input.crop.top + y) * input.layout.rowStride + (input.crop.left + x) * 2).toInt() and 65535
                val black = input.normalization.blackAt(input.sensorCropLeft + x, input.sensorCropTop + y)
                assertEquals((code - black) / (input.normalization.whiteLevel - black), cpu.values[y * cpu.width + x], 0f)
                assertEquals(input.normalization.sensorPattern.colorAt(input.sensorCropLeft + x, input.sensorCropTop + y),
                    cpu.pattern.colorAt(x, y))
            }
            val info = metadata.getJSONObject(index)
            assertEquals(50, info.getInt("iso"))
            assertEquals(metadata.getJSONObject(0).getDouble("exposureSeconds"), info.getDouble("exposureSeconds"), 0.0)
            assertTrue(info.getJSONObject("timestamp").isNull("sensorNanos")) // unknown, never fabricated
            assertEquals(6, frame.noiseProfile!!.size)
            assertTrue(input.lensShading != null && !input.lensShading.alreadyApplied)
        }
    }

    @Test fun realSeaFixtureRejectsCorruptPayloadOrManifest() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("rawsr/sea/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RawSrFixture.decode(json) { byteArrayOf(0, 1, 2) }
        }
        json.put("referenceIndex", 30)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RawSrFixture.decode(json) { throw AssertionError("must reject metadata before reading planes") }
        }
    }

    @Test fun realSeaFixtureAlignmentMatchesCpuAndExportsMetrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        val fixture = RawSrFixture.load(instrumentation.context.assets)
        val config = RawSrAlignmentConfig(levels = 4, tileSize = 16, searchRadius = 4)
        fun normalized(frame: RawSrPackedFrame): UnpackedRawCfa {
            val input = frame.uploadInput()
            val cpu = RawSensorUnpacker.unpackNormalized(input.buffer, input.layout, input.normalization, input.crop)
            val lens = requireNotNull(input.lensShading)
            for (y in 0 until cpu.height) for (x in 0 until cpu.width)
                cpu.values[y * cpu.width + x] *= lens.gainAt(cpu.sensorCropLeft + x, cpu.sensorCropTop + y, cpu.pattern.colorAt(x, y))
            RawSrHotPixel.inpaintNormalized(cpu.values, RawSrHotPixel.detectPacked(frame),
                cpu.width, cpu.height, cpu.pattern)
            return cpu
        }
        val order = fixture.referenceFirst
        val frames = order.map { fixture.frames[it] }
        val gray = frames.map { RawSrAlignment.bayerQuadGray(normalized(it)) }
        val records = JSONArray()
        var pairedTotal = 0
        val report = JSONObject().put("fixture", "sea").put("frameCount", frames.size)
            .put("referenceIndex", fixture.referenceIndex).put("adreno", "UNTESTED")
            .put("coveragePolicy", "Real-scene coverage and rejection are reported, not a ground-truth motion claim; synthetic 80% gate unchanged")
            .put("frames", records)
        Gles31RawSrProcessor(instrumentation.targetContext).use { processor ->
            // Reference-only phase/lens check independently of alignment rejection.
            val refTuning = RawSrTuning.fromReference(frames.first()).tuning
            processor.processPacked(listOf(frames.first()), config, referenceOnly = true) { output ->
                val expected = packedOracle(listOf(frames.first()), config, refTuning, referenceOnly = true)
                val error = assertBayerMerge(output, expected, "sea-reference")
                report.put("referenceRgbMaxError", error.toDouble())
                assertTrue("Sea reference phase/lens error $error", error <= RGB_TOLERANCE)
                assertEquals(1, output.acceptedFrames)
                assertTrue(output.flowTextureIds.isEmpty())
            }
            processor.processPacked(frames, config, onFlow = { index, id, columns, rows ->
                val frameStarted = SystemClock.elapsedRealtime()
                val cpu = RawSrAlignment.align(gray[0], gray[index], config)
                val flow = readTexture(id, columns, rows, GLES30.GL_RGBA)
                val identity = gpuIdentity()
                report.put("vendor", GLES20.glGetString(GLES20.GL_VENDOR)).put("renderer", GLES20.glGetString(GLES20.GL_RENDERER))
                exportFlow(instrumentation.targetContext.cacheDir, identity, "sea_frame_${order[index]}", flow, columns, rows)
                var eligible = 0; var paired = 0; var gpuValid = 0; var rejected = 0; var masks = 0
                var biasX = 0.0; var biasY = 0.0; var maeX = 0.0; var maeY = 0.0; var maximum = 0f
                for (p in cpu.tiles.indices) {
                    val tile = cpu.tiles[p]; val valid = flow[p * 4 + 3] > 0.5f
                    if (!valid) rejected++
                    if (valid != tile.reliable) masks++
                    if (p % columns !in 1 until columns - 1 || p / columns !in 1 until rows - 1) continue
                    eligible++
                    if (valid) gpuValid++
                    if (!valid || !tile.reliable) continue
                    paired++
                    val dx = flow[p * 4] - tile.dx; val dy = flow[p * 4 + 1] - tile.dy
                    biasX += dx; biasY += dy; maeX += abs(dx); maeY += abs(dy)
                    maximum = max(maximum, max(abs(dx), abs(dy)))
                }
                val entry = JSONObject().put("sourceFrameIndex", order[index]).put("eligibleInterior", eligible)
                    .put("pairedValidTiles", paired).put("gpuValidInterior", gpuValid)
                    .put("coverage", gpuValid.toDouble() / eligible).put("pairedCoverage", paired.toDouble() / eligible)
                    .put("rejectionRate", rejected.toDouble() / cpu.tiles.size).put("maskDisagreements", masks)
                    .put("biasX", if (paired > 0) biasX / paired else JSONObject.NULL)
                    .put("biasY", if (paired > 0) biasY / paired else JSONObject.NULL)
                    .put("maeX", if (paired > 0) maeX / paired else JSONObject.NULL)
                    .put("maeY", if (paired > 0) maeY / paired else JSONObject.NULL)
                    .put("maximumError", if (paired > 0) maximum.toDouble() else JSONObject.NULL)
                    .put("cpuComparisonElapsedMs", SystemClock.elapsedRealtime() - frameStarted)
                records.put(entry)
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                val destination = File(instrumentation.targetContext.cacheDir, "rawsr-debug/sea_metrics.json")
                destination.parentFile!!.mkdirs(); destination.writeText(report.toString(2))
                Log.i(TAG, "$identity Sea $entry")
                assertTrue("Sea finite diagnostics", flow.all(Float::isFinite))
                assertTrue("Sea explicit confidence", cpu.tiles.indices.all { flow[it * 4 + 3] == 0f || flow[it * 4 + 3] == 1f })
                if (paired > 0) assertTrue("Sea CPU/GPU flow error $entry", maeX / paired < FLOW_MAE_LIMIT && maeY / paired < FLOW_MAE_LIMIT)
                pairedTotal += paired
            }) { output ->
                report.put("peakTextureBytes", output.peakTextureBytes).put("pairedTotal", pairedTotal)
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                File(instrumentation.targetContext.cacheDir, "rawsr-debug/sea_metrics.json").writeText(report.toString(2))
                assertEquals(29, records.length())
                assertTrue("Real comparison must not pass vacuously", pairedTotal > 0)
            }
        }
    }

    @Test fun packedRawMatchesCpuAcrossOffsetsAndLensShadingStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Gles31RawSrProcessor(context).use { processor ->
            for (pattern in BayerPattern.entries) for (origin in 0..1) for (cropOffset in 0..1)
                for (alreadyApplied in listOf(false, true)) {
                    val layout = RawPlaneLayout(68, 52, 144, 2, origin, 1 - origin)
                    val crop = RawCrop(cropOffset, 1 - cropOffset, 64, 48)
                    val plane = ByteBuffer.allocateDirect(8 + layout.rowStride * layout.height).order(ByteOrder.nativeOrder())
                    plane.position(8)
                    for (y in 0 until layout.height) for (x in 0 until layout.width) {
                        val code = 1000 + (x * 79 + y * 43) % 1500
                        plane.putShort(8 + y * layout.rowStride + x * 2, code.toShort())
                    }
                    val normalization = RawNormalization(pattern, listOf(64f, 70f, 80f, 90f), 4095f)
                    val lens = LensShadingModel(2, 2, FloatArray(16) { 1f + it * 0.03f },
                        IntRectSnapshot(0, 0, 72, 56), alreadyApplied)
                    val frame = RawSrPackedFrame(plane, layout, crop, normalization, lens)
                    val tuning = RawSrTuning.fromReference(frame).tuning
                    val refConfig = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
                    processor.processPacked(listOf(frame), refConfig, referenceOnly = true) { output ->
                        val expected = packedOracle(listOf(frame), refConfig, tuning, referenceOnly = true)
                        val label = "packed phase=$pattern origin=$origin crop=$cropOffset applied=$alreadyApplied"
                        val error = assertBayerMerge(output, expected, label)
                        assertTrue("$label error=$error", error <= RGB_TOLERANCE)
                    }
                }
        }
    }

    @Test fun packedWorkingMemoryIsIndependentOfBurstLengthAndRecoversAfterCallbackFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = syntheticRaw(64, 48, 0f, 0f)
        val plane = ByteBuffer.allocateDirect(64 * 48 * 2).order(ByteOrder.nativeOrder())
        raw.values.forEach { plane.putShort((it * 60000f).toInt().toShort()) }; plane.flip()
        val frame = RawSrPackedFrame(plane, RawPlaneLayout(64, 48, 128, 2), RawCrop(0, 0, 64, 48),
            RawNormalization(raw.pattern, List(4) { 0f }, 60000f), null)
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val tuning = RawSrTuning.fromReference(frame).tuning
        Gles31RawSrProcessor(context).use { processor ->
            var peak = 0L
            for (count in listOf(2, 8, 15, 30)) {
                var flows = 0
                processor.processPacked(List(count) { frame }, config, onFlow = { _, id, w, h ->
                    assertTrue(readTexture(id, w, h, GLES30.GL_RGBA).all(Float::isFinite)); flows++
                }) { output ->
                    if (peak == 0L) peak = output.peakTextureBytes
                    assertEquals("count=$count", peak, output.peakTextureBytes)
                    assertEquals(count - 1, flows)
                    val expected = packedOracle(List(count) { frame }, config, tuning)
                    assertTrue("count=$count",
                        assertBayerMerge(output, expected, "burst-length $count") <= RGB_TOLERANCE)
                    Log.i(TAG, "${gpuIdentity()} packed frames=$count peakTextureBytes=$peak")
                }
            }
            try {
                processor.processPacked(listOf(frame, frame), config,
                    onFlow = { _, _, _, _ -> throw IllegalStateException("diagnostic cancellation") }) { }
                throw AssertionError("callback must throw")
            } catch (expectedFailure: IllegalStateException) {
                assertEquals("diagnostic cancellation", expectedFailure.message)
            }
            processor.processPacked(listOf(frame, frame), config) { assertEquals(peak, it.peakTextureBytes) }
        }
    }

    @Test fun flowMatrixMatchesCpuOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = listOf(
            FlowCase("static", 0f, 0f, true), FlowCase("integer_positive", 4f, 2f),
            FlowCase("integer_negative", -4f, -2f), FlowCase("subpixel_mixed", 1.2f, -0.6f),
            FlowCase("large_supported", 20f, -12f, width = 384, height = 288)
        )
        Gles31RawSrProcessor(context).use { processor ->
            cases.forEach { case ->
                val reference = syntheticRaw(case.width, case.height, 0f, 0f)
                val moving = syntheticRaw(case.width, case.height, case.rawShiftX, case.rawShiftY)
                val config = RawSrAlignmentConfig(levels = if (case.width > 128) 4 else 3, tileSize = 8, searchRadius = 3)
                val cpu = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(reference),
                    RawSrAlignment.bayerQuadGray(moving), config)
                var first: FloatArray? = null
                processor.process(listOf(reference, moving), config) { output ->
                    val identity = gpuIdentity()
                    val flow = readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA)
                    exportFlow(context.cacheDir, identity, case.name, flow, cpu.columns, cpu.rows)
                    first = flow
                    assertTileContract(flow, cpu)
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
                processor.process(listOf(reference, moving), config) { output ->
                    org.junit.Assert.assertArrayEquals("deterministic ${case.name}", first,
                        readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA), 0f)
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

    @Test fun degenerateAndPartialCornerTilesMatchCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 130; val height = 98 // partial right/bottom tiles, including a one-quad corner
        val cases = listOf(
            "flat_partial" to flatRaw(width, height),
            "rank_one" to UnpackedRawCfa(width, height, BayerPattern.RGGB,
                FloatArray(width * height) { 0.3f + (it % width) * 0.002f }, RawCrop(0, 0, width, height)),
            "nan_input" to flatRaw(width, height).let { it.copy(values = it.values.copyOf().apply { this[20] = Float.NaN }) },
            "textured_partial" to syntheticRaw(width, height, 0f, 0f)
        )
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 3)
        Gles31RawSrProcessor(context).use { processor ->
            for ((name, raw) in cases) {
                val cpu = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(raw), RawSrAlignment.bayerQuadGray(raw), config)
                processor.process(listOf(raw, raw), config) { output ->
                    val flow = readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA)
                    exportFlow(context.cacheDir, gpuIdentity(), name, flow, cpu.columns, cpu.rows)
                    assertTileContract(flow, cpu)
                    if (name != "textured_partial") assertTrue(cpu.tiles.none { it.reliable })
                    assertEquals(0f, flow[flow.size - 1], 0f)
                }
            }
        }
    }

    @Test fun packedOddCropAlignmentAllCfaPhases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 3)
        Gles31RawSrProcessor(context).use { processor ->
            for (pattern in BayerPattern.entries) {
                fun packed(dx: Float, dy: Float): RawSrPackedFrame {
                    val raw = syntheticRaw(132, 100, dx, dy, pattern.shifted(1, 0))
                    val plane = ByteBuffer.allocateDirect(272 * 100 + 6).order(ByteOrder.nativeOrder())
                    plane.position(6)
                    for (y in 0 until 100) for (x in 0 until 132)
                        plane.putShort(6 + y * 272 + x * 2, (raw.values[y * 132 + x] * 60000).toInt().toShort())
                    return RawSrPackedFrame(plane, RawPlaneLayout(132, 100, 272, 2, 1, 0),
                        RawCrop(1, 1, 128, 96), RawNormalization(pattern, List(4) { 0f }, 60000f), null)
                }
                val frames = listOf(packed(0f, 0f), packed(-1.2f, 0.6f))
                val cpuRaw = frames.map { frame ->
                    frame.uploadInput().let { RawSensorUnpacker.unpackNormalized(it.buffer, it.layout, it.normalization, it.crop) }
                }
                val cpu = RawSrAlignment.align(RawSrAlignment.bayerQuadGray(cpuRaw[0]), RawSrAlignment.bayerQuadGray(cpuRaw[1]), config)
                processor.processPacked(frames, config) { output ->
                    val flow = readTexture(output.flowTextureIds.single(), cpu.columns, cpu.rows, GLES30.GL_RGBA)
                    exportFlow(context.cacheDir, gpuIdentity(), "odd_crop_$pattern", flow, cpu.columns, cpu.rows)
                    assertTileContract(flow, cpu)
                    val metrics = compareFlows(flow, cpu, -0.6f, 0.3f)
                    assertTrue("$pattern $metrics", metrics.coverage >= MIN_RELIABLE_COVERAGE &&
                        metrics.maeX < FLOW_MAE_LIMIT && metrics.maeY < FLOW_MAE_LIMIT &&
                        metrics.truthMaeX < FLOW_MAE_LIMIT && metrics.truthMaeY < FLOW_MAE_LIMIT)
                }
            }
        }
    }

    private fun assertTileContract(flow: FloatArray, cpu: RawSrAlignmentField) {
        assertTrue("finite including rejected tiles", flow.all(Float::isFinite))
        for (p in cpu.tiles.indices) {
            val tile = cpu.tiles[p]
            assertEquals("tile $p confidence", if (tile.reliable) 1f else 0f, flow[p * 4 + 3], 0f)
            if (tile.reliable) {
                assertTrue("tile $p X", abs(tile.dx - flow[p * 4]) < FLOW_MAE_LIMIT)
                assertTrue("tile $p Y", abs(tile.dy - flow[p * 4 + 1]) < FLOW_MAE_LIMIT)
                assertEquals("tile $p residual", tile.residual, flow[p * 4 + 2], 1e-4f)
            }
        }
    }

    @Test fun referenceOnlyMatchesBayerMergeOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        BayerPattern.entries.forEach { pattern ->
            val reference = syntheticRaw(64, 48, 0f, 0f, pattern)
            val tuning = explicitTuning(listOf(reference))
            val expected = adapterOracle(reference, emptyList(), config, tuning, referenceOnly = true)
            Gles31RawSrProcessor(context).use { processor ->
                processor.process(listOf(reference), config, tuning, referenceOnly = true) { output ->
                    assertTrue(output.flowTextureIds.isEmpty())
                    assertEquals(1, output.acceptedFrames)
                    assertTrue(assertBayerMerge(output, expected, "reference-only $pattern") <= RGB_TOLERANCE)
                }
            }
        }
    }

    @Test fun twoFrameBayerMergeMatchesCpuOracleAndPreservesChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = listOf(syntheticRaw(64, 48, 0f, 0f), syntheticRaw(64, 48, 0f, 0f))
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "two-frame", checkRc = false)
                Log.i(TAG, "${gpuIdentity()} two_frame mergedRgbMaxError=$maxError")
                    assertTrue("merged RGB max error=$maxError", maxError <= RGB_TOLERANCE)
                val p = (output.height / 2 * output.width + output.width / 2) * 3
                assertTrue(expected.rgb[p] > expected.rgb[p + 1])
                assertTrue(expected.rgb[p + 1] > expected.rgb[p + 2])
            }
        }
    }

    @Test fun mergeChromaDeweightMatchesCpuOracle() {
        // L1 GPU mirror: textured burst with subpixel shifts and a tight
        // green noise model, so the R/B gate is active. The oracle carries
        // the same ChromaParams on moving frames; the GPU takes it via
        // process(chroma = ...). Agreement uses the standard RGB_TOLERANCE
        // family (float32 precision interpolation vs float64 oracle).
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        val shifts = listOf(0f to 0f, 0.4f to -0.2f, -0.3f to 0.35f, 0.2f to 0.1f)
        val frames = shifts.mapIndexed { i, (dx, dy) -> noisyTexturedRaw(w, h, dx, dy, seed = 2000 + i) }
        val tuning = explicitTuning(frames)
        val chroma = RawSrBayerMerge.ChromaParams(0.0, 1e-6)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning, chroma = chroma)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning, chroma = chroma) { output ->
                val maxError = assertBayerMerge(output, expected, "chroma-gated", checkRc = false)
                assertTrue("chroma-gated max error=$maxError", maxError <= RGB_TOLERANCE)
                Log.i(TAG, "${gpuIdentity()} chroma_gated maxError=$maxError")
            }
        }
    }

    // Prompt 4D merge-mirror suite: CPU scenes from RawSrBayerMergeTest replayed
    // headless (no Activity) through the GPU adapter path. The adapter merges
    // with unit robustness (Rc stays zero by bypass design), so Rc gates are
    // logged, not asserted. Hard gates assert CPU/GPU agreement (2e-3 + 2e-3
    // family, bit-exact fallback/OOB sets); contract docs/raw-sr-merge.md §12
    // metrics are asserted where the adapter path supports them and logged
    // everywhere for the record.

    @Test fun mergeStaticColourBurstMatchesCpuAndPreservesColour() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val shifts = listOf(0f to 0f, 0.5f to 0f, 0.13f to -0.29f, 1f to -1f)
        val frames = shifts.map { (dx, dy) -> syntheticRaw(64, 48, dx, dy) }
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            val refOnly = processor.process(listOf(frames[0]), config, tuning, referenceOnly = true) {
                readMerged(it)
            }
            processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "static-colour", checkRc = false)
                assertTrue("static-colour max error=$maxError", maxError <= RGB_TOLERANCE)
                val merged = readMerged(output)
                for (c in 0..2) {
                    val mean = channelMeanRgba(merged, output.width, output.height, c)
                    val refMean = channelMeanRgba(refOnly, output.width, output.height, c)
                    assertEquals("channel $c $mean vs $refMean", refMean, mean, abs(refMean) * 0.01)
                }
                val mid = (output.height / 2 * output.width + output.width / 2) * 4
                assertTrue("R>G>B ordering", merged[mid] > merged[mid + 1] && merged[mid + 1] > merged[mid + 2])
                Log.i(TAG, "${gpuIdentity()} static_colour maxError=$maxError " +
                    "close01=${closeFractionRgba(merged, refOnly, output.width, output.height, 0.01f)}")
            }
        }
    }

    @Test fun mergeNoisyBurstMatchesCpuAndReducesVariance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        val shifts = listOf(0f to 0f, 0.4f to -0.2f, -0.3f to 0.35f, 0.2f to 0.1f,
            -0.4f to -0.1f, 0.1f to -0.4f, 0.3f to 0.3f, -0.2f to 0.2f)
        val frames = shifts.mapIndexed { i, (dx, dy) -> noisyTexturedRaw(w, h, dx, dy, seed = 1000 + i) }
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "noisy-burst", checkRc = false)
                assertTrue("noisy-burst max error=$maxError", maxError <= RGB_TOLERANCE)
                val merged = readMerged(output)
                assertTrue("finite", merged.all(Float::isFinite))
                var mergedVar = 0.0; var singleVar = 0.0; var pixels = 0
                for (y in 4 until h - 4) for (x in 4 until w - 4) {
                    val signal = 0.4 + 0.2 * valueNoise(x.toFloat(), y.toFloat(), 7f)
                    for (c in 0..2) {
                        val m = merged[(y * w + x) * 4 + c] - signal
                        mergedVar += m * m
                    }
                    val s = frames[0].values[y * w + x] - signal.toFloat()
                    singleVar += s * s
                    pixels++
                }
                mergedVar /= (pixels * 3); singleVar /= pixels
                // Contract §12 noise: interior variance <= 0.25x single-frame.
                // Rc >= 6 is N/A here: the adapter merges with unit weight (Rc zero
                // by bypass design); the CPU unit suite gates Rc with real weights.
                assertTrue("variance ratio=${mergedVar / singleVar}", mergedVar <= 0.25 * singleVar)
                Log.i(TAG, "${gpuIdentity()} noisy_burst maxError=$maxError " +
                    "varianceRatio=${mergedVar / singleVar} rcMean=0.0(adapter-bypass)")
            }
        }
    }

    @Test fun mergeStepCornerCheckerMatchCpuAndPreserveContrast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        fun step(sx: Float, sy: Float) = if (sx < w / 2) 0.2f else 0.8f
        fun corner(sx: Float, sy: Float) = if ((sx < w / 2) == (sy < h / 2)) 0.7f else 0.2f
        fun checker(sx: Float, sy: Float) =
            if (((sx / 2).toInt() + (sy / 2).toInt()) % 2 == 0) 0.65f else 0.25f
        Gles31RawSrProcessor(context).use { processor ->
            for ((name, scene) in listOf("step" to ::step, "corner" to ::corner, "checker" to ::checker)) {
                val frames = List(3) { graySceneRaw(w, h, 0f, 0f, scene) }
                val tuning = explicitTuning(frames)
                val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
                val refOnly = processor.process(listOf(frames[0]), config, tuning, referenceOnly = true) {
                    readMerged(it)
                }
                processor.process(frames, config, tuning) { output ->
                    val maxError = assertBayerMerge(output, expected, "edge-$name", checkRc = false)
                    assertTrue("edge-$name max error=$maxError", maxError <= RGB_TOLERANCE)
                    val merged = readMerged(output)
                    val close = closeFractionRgba(merged, refOnly, w, h, 0.01f)
                    // The 2px checker is alignment-ambiguous by construction, so its
                    // static 99% close fraction is recorded, not gated.
                    if (name != "checker") assertTrue("$name close01=$close", close >= 0.99)
                    Log.i(TAG, "${gpuIdentity()} edge_$name maxError=$maxError close01=$close")
                }
            }
            // Slanted-subpixel step keeps >= 90% of the reference-only contrast.
            val slanted = listOf(0f to 0f, 0.1f to 0f, 0f to 0.1f, 0.1f to 0.1f)
                .map { (dx, dy) -> graySceneRaw(w, h, dx, dy, ::step) }
            val tuning = explicitTuning(slanted)
            val expected = adapterOracle(slanted[0], slanted.drop(1), config, tuning)
            val refOnly = processor.process(listOf(slanted[0]), config, tuning, referenceOnly = true) {
                readMerged(it)
            }
            processor.process(slanted, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "edge-slanted", checkRc = false)
                assertTrue("edge-slanted max error=$maxError", maxError <= RGB_TOLERANCE)
                val merged = readMerged(output)
                fun contrast(rgba: FloatArray): Double {
                    var left = 0.0; var n = 0; var right = 0.0; var m = 0
                    for (y in 4 until h - 4) {
                        for (x in 4 until w / 2 - 2) { left += rgba[(y * w + x) * 4 + 1]; n++ }
                        for (x in w / 2 + 2 until w - 4) { right += rgba[(y * w + x) * 4 + 1]; m++ }
                    }
                    return right / m - left / n
                }
                val ratio = contrast(merged) / contrast(refOnly)
                assertTrue("contrast ratio=$ratio", ratio >= 0.9)
                Log.i(TAG, "${gpuIdentity()} edge_slanted maxError=$maxError contrastRatio=$ratio")
            }
        }
    }

    @Test fun mergeGhostAndSaturatedScenesMatchCpuOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        val ref = syntheticRaw(w, h, 0f, 0f)
        val tuning = explicitTuning(listOf(ref))
        Gles31RawSrProcessor(context).use { processor ->
            val refOnly = processor.process(listOf(ref), config, tuning, referenceOnly = true) {
                readMerged(it)
            }
            // Foreground patch: rejection needs real robustness weights, so under
            // adapter unit weights the ghost close fraction is recorded, not gated
            // (the CPU unit suite gates rejection with r == 0).
            val ghostValues = ref.values.copyOf()
            for (y in 8..15) for (x in 12..21) ghostValues[y * w + x] += 0.5f
            val ghost = UnpackedRawCfa(w, h, BayerPattern.RGGB, ghostValues, RawCrop(0, 0, w, h))
            val ghostExpected = adapterOracle(ref, listOf(ghost), config, tuning)
            processor.process(listOf(ref, ghost), config, tuning) { output ->
                val maxError = assertBayerMerge(output, ghostExpected, "ghost", checkRc = false)
                assertTrue("ghost max error=$maxError", maxError <= RGB_TOLERANCE)
                val merged = readMerged(output)
                assertTrue("finite", merged.all(Float::isFinite))
                Log.i(TAG, "${gpuIdentity()} ghost maxError=$maxError " +
                    "close02=${closeFractionRgba(merged, refOnly, w, h, 0.02f)}(record-only,unit-weights)")
            }
            val saturated = UnpackedRawCfa(w, h, BayerPattern.RGGB, FloatArray(w * h) { 5.0f },
                RawCrop(0, 0, w, h))
            val saturatedExpected = adapterOracle(ref, listOf(saturated), config, tuning)
            processor.process(listOf(ref, saturated), config, tuning) { output ->
                val maxError = assertBayerMerge(output, saturatedExpected, "saturated", checkRc = false)
                assertTrue("saturated max error=$maxError", maxError <= RGB_TOLERANCE)
                assertTrue("finite", readMerged(output).all(Float::isFinite))
                Log.i(TAG, "${gpuIdentity()} saturated maxError=$maxError")
            }
        }
    }

    @Test fun mergeLargeShiftMatchesCpuOracle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 4, tileSize = 8, searchRadius = 3)
        val frames = listOf(syntheticRaw(128, 96, 0f, 0f), syntheticRaw(128, 96, 20f, -12f))
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "large-shift", checkRc = false)
                assertTrue("large-shift max error=$maxError", maxError <= RGB_TOLERANCE)
                Log.i(TAG, "${gpuIdentity()} large_shift maxError=$maxError")
            }
        }
    }

    @Test fun mergePoisonedInputsStayFiniteAndMatchCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        val refValues = FloatArray(w * h) { 0.4f }
        refValues[10] = Float.NaN; refValues[20] = Float.POSITIVE_INFINITY
        val ref = UnpackedRawCfa(w, h, BayerPattern.RGGB, refValues, RawCrop(0, 0, w, h))
        val movingValues = syntheticRaw(w, h, 0.5f, -0.25f).values.copyOf()
        movingValues[30] = Float.NaN; movingValues[40] = Float.NEGATIVE_INFINITY
        val moving = UnpackedRawCfa(w, h, BayerPattern.RGGB, movingValues, RawCrop(0, 0, w, h))
        val tuning = explicitTuning(listOf(ref))
        val expected = adapterOracle(ref, listOf(moving), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(listOf(ref, moving), config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "poisoned", checkRc = false)
                assertTrue("poisoned max error=$maxError", maxError <= RGB_TOLERANCE)
                assertTrue("finite", readMerged(output).all(Float::isFinite))
                Log.i(TAG, "${gpuIdentity()} poisoned maxError=$maxError " +
                    "fallbackPixels=${expected.fallback.count { it }}")
            }
        }
    }

    @Test fun mergeIsDeterministicAcrossGlesRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val frames = listOf(syntheticRaw(64, 48, 0f, 0f), syntheticRaw(64, 48, 0.3f, -0.2f),
            syntheticRaw(64, 48, -0.4f, 0.35f))
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            val first = processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "deterministic", checkRc = false)
                assertTrue("deterministic max error=$maxError", maxError <= RGB_TOLERANCE)
                Triple(readMerged(output),
                    readTexture(output.numeratorTextureId, output.width, output.height, GLES30.GL_RGBA),
                    readTexture(output.denominatorTextureId, output.width, output.height, GLES30.GL_RGBA))
            }
            processor.process(frames, config, tuning) { output ->
                val second = readMerged(output)
                org.junit.Assert.assertArrayEquals("merged bit-identical", first.first, second, 0f)
                org.junit.Assert.assertArrayEquals("numerator bit-identical", first.second,
                    readTexture(output.numeratorTextureId, output.width, output.height, GLES30.GL_RGBA), 0f)
                org.junit.Assert.assertArrayEquals("denominator bit-identical", first.third,
                    readTexture(output.denominatorTextureId, output.width, output.height, GLES30.GL_RGBA), 0f)
                Log.i(TAG, "${gpuIdentity()} deterministic bit-identical=true")
            }
        }
    }

    @Test fun mergeUnclampedNegativesMatchCpuAndPreserveMeans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        fun constRaw(): UnpackedRawCfa {
            val values = FloatArray(64 * 48)
            for (y in 0 until 48) for (x in 0 until 64) {
                values[y * 64 + x] = when (BayerPattern.RGGB.colorAt(x, y)) {
                    CfaColor.RED -> -0.05f; CfaColor.GREEN -> 0.0f; CfaColor.BLUE -> -0.2f
                }
            }
            return UnpackedRawCfa(64, 48, BayerPattern.RGGB, values, RawCrop(0, 0, 64, 48))
        }
        // Constant scenes are flow-invariant, so any estimated flow preserves them.
        val frames = List(2) { constRaw() }
        val tuning = explicitTuning(frames)
        val expected = adapterOracle(frames[0], frames.drop(1), config, tuning)
        Gles31RawSrProcessor(context).use { processor ->
            processor.process(frames, config, tuning) { output ->
                val maxError = assertBayerMerge(output, expected, "negatives", checkRc = false)
                assertTrue("negatives max error=$maxError", maxError <= RGB_TOLERANCE)
                val merged = readMerged(output)
                assertEquals(-0.05, channelMeanRgba(merged, 64, 48, 0), 1e-3)
                assertEquals(0.0, channelMeanRgba(merged, 64, 48, 1), 1e-3)
                assertEquals(-0.2, channelMeanRgba(merged, 64, 48, 2), 1e-3)
                Log.i(TAG, "${gpuIdentity()} negatives maxError=$maxError")
            }
        }
    }

    @Test fun mergeOddOriginShiftEquivalenceMatchesCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
        val w = 64; val h = 48
        fun textured(ox: Int, pattern: BayerPattern): UnpackedRawCfa {
            val values = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = ox + x
                val color = BayerPattern.RGGB.colorAt(sx, y)
                val cell = ((sx * 73856093) xor (y * 19349663) xor (color.ordinal * 83492791)) and 0x7fffffff
                values[y * w + x] = 0.1f + (cell % 1000) / 1000f * 0.7f
            }
            return UnpackedRawCfa(w, h, pattern, values, RawCrop(0, 0, w, h))
        }
        val a = textured(0, BayerPattern.RGGB)
        val b = textured(1, BayerPattern.RGGB.shifted(1, 0))
        Gles31RawSrProcessor(context).use { processor ->
            val tuning = explicitTuning(listOf(a))
            val outA = processor.process(listOf(a), config, tuning, referenceOnly = true) {
                assertBayerMerge(it, adapterOracle(a, emptyList(), config, tuning, referenceOnly = true),
                    "odd-origin-a", checkRc = false)
                readMerged(it)
            }
            processor.process(listOf(b), config, tuning, referenceOnly = true) {
                assertBayerMerge(it, adapterOracle(b, emptyList(), config, tuning, referenceOnly = true),
                    "odd-origin-b", checkRc = false)
                val outB = readMerged(it)
                for (y in 4 until h - 4) for (x in 5 until w - 4) for (c in 0..2) {
                    // Two distinct GPU evaluations of shifted inputs: 1-ulp
                    // summation/exp differences are inherent, so the declared
                    // merge agreement tolerance binds here, not bitwise equality.
                    val a = outA[(y * w + x) * 4 + c]
                    assertEquals("($x,$y,$c)", a, outB[(y * w + (x - 1)) * 4 + c],
                        MERGE_TOL_ABS + MERGE_TOL_REL * abs(a))
                }
                Log.i(TAG, "${gpuIdentity()} odd_origin bit-exact-shift=true")
            }
        }
    }

    private fun readMerged(output: RawSrGpuOutput): FloatArray =
        readTexture(output.mergedTextureId, output.width, output.height, GLES30.GL_RGBA)

    private fun channelMeanRgba(rgba: FloatArray, width: Int, height: Int, channel: Int, border: Int = 4): Double {
        var sum = 0.0; var n = 0
        for (y in border until height - border) for (x in border until width - border) {
            sum += rgba[(y * width + x) * 4 + channel]; n++
        }
        return sum / n
    }

    private fun closeFractionRgba(a: FloatArray, b: FloatArray, width: Int, height: Int,
                                  tol: Float, border: Int = 4): Double {
        var close = 0; var total = 0
        for (y in border until height - border) for (x in border until width - border) for (c in 0..2) {
            total++
            if (abs(a[(y * width + x) * 4 + c] - b[(y * width + x) * 4 + c]) <= tol) close++
        }
        return close.toDouble() / total
    }

    private fun noisyTexturedRaw(w: Int, h: Int, dx: Float, dy: Float, seed: Int): UnpackedRawCfa {
        val random = Random(seed)
        val noise = FloatArray(w * h) { (random.nextGaussianLocal() * 0.02).toFloat() }
        val values = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w)
            values[y * w + x] = 0.4f + 0.2f * valueNoise(x - dx, y - dy, 7f) + noise[y * w + x]
        return UnpackedRawCfa(w, h, BayerPattern.RGGB, values, RawCrop(0, 0, w, h))
    }

    private fun graySceneRaw(w: Int, h: Int, dx: Float, dy: Float, scene: (Float, Float) -> Float): UnpackedRawCfa {
        val values = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) values[y * w + x] = scene(x - dx, y - dy)
        return UnpackedRawCfa(w, h, BayerPattern.RGGB, values, RawCrop(0, 0, w, h))
    }

    private fun Random.nextGaussianLocal(): Double {
        var u = 0.0
        while (u == 0.0) u = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * kotlin.math.PI * nextDouble())
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

    /** Prompt 4D CPU/GPU agreement: merged RGB, numerators, and independent per-channel
     * denominators within 2e-3 + 2e-3*|cpu|; fallback and OOB pixel sets bit-exact.
     * Rc is checked only when the GPU actually accumulated robustness (packed path).
     */
    private fun assertBayerMerge(output: RawSrGpuOutput, expected: RawSrBayerMerge.MergeResult,
                                 label: String, checkRc: Boolean = true): Float {
        val merged = readTexture(output.mergedTextureId, output.width, output.height, GLES30.GL_RGBA)
        val numerator = readTexture(output.numeratorTextureId, output.width, output.height, GLES30.GL_RGBA)
        val denominator = readTexture(output.denominatorTextureId, output.width, output.height, GLES30.GL_RGBA)
        val refNumerator = readTexture(output.refNumeratorTextureId, output.width, output.height, GLES30.GL_RGBA)
        val refDenominator = readTexture(output.refDenominatorTextureId, output.width, output.height, GLES30.GL_RGBA)
        val fallback = readTexture(output.fallbackTextureId, output.width, output.height, GLES30.GL_RED)
        val oob = readTexture(output.oobTextureId, output.width, output.height, GLES30.GL_RED)
        var maxError = 0f
        for (y in 4 until output.height - 4) for (x in 4 until output.width - 4) {
            val p = y * output.width + x
            for (channel in 0..2) {
                // The GPU keeps the moving-frame and reference-last pairs split; the
                // oracle reports their totals, so agreement compares the sums.
                val pairs = listOf(merged[p * 4 + channel] to expected.rgb[p * 3 + channel],
                    numerator[p * 4 + channel] + refNumerator[p * 4 + channel] to expected.numerator[p * 3 + channel],
                    denominator[p * 4 + channel] + refDenominator[p * 4 + channel] to expected.denominator[p * 3 + channel])
                for ((gpu, cpu) in pairs) {
                    assertTrue("$label finite", gpu.isFinite())
                    maxError = max(maxError, abs(cpu - gpu))
                    assertTrue("$label pixel ($x,$y) channel $channel gpu=$gpu cpu=$cpu",
                        abs(cpu - gpu) <= MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu))
                }
            }
            assertEquals("$label fallback ($x,$y)", if (expected.fallback[p]) 1f else 0f, fallback[p], 0f)
            assertEquals("$label oob ($x,$y)", expected.oobCount[p].toFloat(), oob[p], 0f)
        }
        if (checkRc) {
            val rc = readTexture(output.rcTextureId, output.width / 2, output.height / 2, GLES30.GL_RED)
            assertEquals(expected.rc.values.size, rc.size)
            for (i in expected.rc.values.indices) {
                val gpu = rc[i]; val cpu = expected.rc.values[i]
                assertTrue("$label rc finite", gpu.isFinite())
                assertTrue("$label rc $i gpu=$gpu cpu=$cpu",
                    abs(cpu - gpu) <= MERGE_TOL_ABS + MERGE_TOL_REL * abs(cpu))
            }
        }
        return maxError
    }

    /** Adapter-path oracle: unpacked linear samples, kernel precision from quad gray,
     * CPU alignment flow, unit robustness (the adapter merges with weight 1, Rc zero).
     */
    private fun adapterOracle(reference: UnpackedRawCfa, moving: List<UnpackedRawCfa>,
                              config: RawSrAlignmentConfig, tuning: RawSrTuning,
                              referenceOnly: Boolean = false,
                              chroma: RawSrBayerMerge.ChromaParams? = null): RawSrBayerMerge.MergeResult {
        fun frame(raw: UnpackedRawCfa, flow: RawSrAlignmentField?,
                  robust: RawSrRobustness.FrameRobustness?, gated: Boolean) =
            RawSrBayerMerge.MergeFrame(raw.width, raw.height, raw.values, raw.pattern,
                raw.sensorCropLeft, raw.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrAlignment.bayerQuadGray(raw), tuning),
                flow, robust, chroma = if (gated) chroma else null)
        val refGray = RawSrAlignment.bayerQuadGray(reference)
        val flows = moving.map { RawSrAlignment.align(refGray, RawSrAlignment.bayerQuadGray(it), config) }
        return RawSrBayerMerge.merge(frame(reference, null, null, false),
            moving.mapIndexed { i, raw ->
                frame(raw, flows[i], RawSrRobustness.FrameRobustness(raw.width / 2, raw.height / 2,
                    FloatArray(raw.width / 2 * raw.height / 2) { 1f },
                    IntArray(raw.width / 2 * raw.height / 2)), true)
            }, referenceOnly)
    }

    /** Packed-path oracle: shaded normalized samples with full-sensor CFA routing,
     * variance-stabilized guide precision, CPU alignment flow, evaluated robustness.
     */
    private fun packedOracle(frames: List<RawSrPackedFrame>, config: RawSrAlignmentConfig,
                             tuning: RawSrTuning,
                             referenceOnly: Boolean = false): RawSrBayerMerge.MergeResult {
        fun shaded(frame: RawSrPackedFrame): Pair<UnpackedRawCfa, BayerPattern> {
            val input = frame.uploadInput()
            val cpu = RawSensorUnpacker.unpackNormalized(input.buffer, input.layout, input.normalization, input.crop)
            val lens = input.lensShading
            if (lens != null) for (y in 0 until cpu.height) for (x in 0 until cpu.width)
                cpu.values[y * cpu.width + x] *= lens.gainAt(cpu.sensorCropLeft + x, cpu.sensorCropTop + y,
                    cpu.pattern.colorAt(x, y))
            RawSrHotPixel.inpaintNormalized(cpu.values, RawSrHotPixel.detectPacked(frame),
                cpu.width, cpu.height, cpu.pattern)
            // MergeFrame routes crop-relative taps. The unpacker already shifted
            // the CFA for the sensor/crop origin; do not pass the unshifted sensor CFA.
            return cpu to cpu.pattern
        }
        val shadedFrames = frames.map(::shaded)
        val grays = shadedFrames.map { RawSrAlignment.bayerQuadGray(it.first) }
        val moving = if (referenceOnly) emptyList() else frames.drop(1).mapIndexed { i, frame ->
            val flow = RawSrAlignment.align(grays[0], grays[i + 1], config)
            val robust = RawSrRobustness.evaluate(RawSrRobustness.linearGuide(frames[0]),
                RawSrRobustness.linearGuide(frame), flow, tuning, config)
            val (cpu, sensorPattern) = shadedFrames[i + 1]
            RawSrBayerMerge.MergeFrame(cpu.width, cpu.height, cpu.values, sensorPattern,
                cpu.sensorCropLeft, cpu.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frame).gray, tuning),
                flow, robust)
        }
        val (refCpu, refPattern) = shadedFrames[0]
        return RawSrBayerMerge.merge(
            RawSrBayerMerge.MergeFrame(refCpu.width, refCpu.height, refCpu.values, refPattern,
                refCpu.sensorCropLeft, refCpu.sensorCropTop,
                RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frames[0]).gray, tuning),
                null, null),
            moving, referenceOnly)
    }

    private fun explicitTuning(frames: List<UnpackedRawCfa>): RawSrTuning =
        RawSrTuning.estimate(if (frames.first().values.isEmpty()) 0.0
            else frames.first().values.average(), null).tuning

    private fun exportFlow(cache: File, identity: String, case: String, flow: FloatArray,
                           columns: Int, rows: Int) {
        val stem = "${identity}_${case}".safeName()
        RawSrDebugExporter.writeFlowPng(flow, columns, rows, File(cache, "rawsr-debug/$stem.png"))
        RawSrDebugExporter.writeFlowCsv(flow, columns, rows, File(cache, "rawsr-debug/$stem.csv"))
        RawSrDebugExporter.writeScalarPng(flow, columns, rows, File(cache, "rawsr-debug/${stem}_residual.png"), false)
        RawSrDebugExporter.writeScalarPng(flow, columns, rows, File(cache, "rawsr-debug/${stem}_confidence.png"), true)
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
                                val static: Boolean = false, val width: Int = 128, val height: Int = 96)
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
        /** Prompt 4D CPU/GPU agreement family (merge contract §12). */
        const val MERGE_TOL_ABS = 2e-3f
        const val MERGE_TOL_REL = 2e-3f
    }
}
