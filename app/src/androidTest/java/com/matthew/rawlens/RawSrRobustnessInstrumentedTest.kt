// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prompt 4C GPU tests. Fixed scenes (declared below); CPU expectations use the
 * device's own reconstructed flow, so agreement holds regardless of what the
 * alignment estimates. Criteria follow docs/raw-sr-robustness.md §10.
 */
@RunWith(AndroidJUnit4::class)
class RawSrRobustnessInstrumentedTest {
    private val config = RawSrAlignmentConfig(levels = 3, tileSize = 8, searchRadius = 2)
    private val baseProfile = ImmutableDoubleValues(
        doubleArrayOf(0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0))

    private fun packed(codes: (sensorX: Int, sensorY: Int) -> Int,
                       profile: ImmutableDoubleValues? = baseProfile): RawSrPackedFrame {
        val layoutW = 66
        val layoutH = 50
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(sx, sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, 0, 0),
            RawCrop(1, 1, 64, 48), RawNormalization(BayerPattern.RGGB,
                listOf(64f, 64f, 64f, 64f), 4000f), null, profile)
    }

    private fun textured(seed: Int): (Int, Int) -> Int {
        val sigma = kotlin.math.sqrt(0.02 * 1500 + 1.0)
        return { sx, sy ->
            val hash = (((sx * 73856093) xor (sy * 19349663) xor seed) and 0x7fffffff) % 1001 / 1000.0 * 2.0 - 1.0
            1500 + (hash * 1.73 * sigma).toInt() +
                ((sx * 79 + sy * 43 + seed * 131) % 101)
        }
    }

    private data class Captured(val r: MutableMap<Int, FloatArray> = mutableMapOf(),
                                val flags: MutableMap<Int, IntArray> = mutableMapOf(),
                                val flows: MutableMap<Int, FloatArray> = mutableMapOf(),
                                val flowGrid: MutableMap<Int, Pair<Int, Int>> = mutableMapOf(),
                                var rc: FloatArray = floatArrayOf(),
                                var rcSize: Pair<Int, Int> = 0 to 0)

    private fun run(frames: List<RawSrPackedFrame>, referenceOnly: Boolean = false): Captured {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = Captured()
        VkRawSrProcessor(context).use { processor ->
            processor.processPacked(frames, config, referenceOnly = referenceOnly,
                onFlow = { index, id, columns, rows ->
                    captured.flows[index] = readRgba(id, columns, rows)
                    captured.flowGrid[index] = columns to rows
                },
                onCovariance = { _, _, _, _ -> },
                onRobustness = { index, rId, flagsId, width, height ->
                    captured.r[index] = readR32f(rId, width, height)
                    captured.flags[index] = readR32ui(flagsId, width, height)
                }) { output ->
                captured.rc = readR32f(output.rcTextureId, 32, 24)
                captured.rcSize = 32 to 24
            }
        }
        return captured
    }

    private fun cpuFlow(flow: FloatArray, grid: Pair<Int, Int>, width: Int, height: Int,
                       tag: String): RawSrAlignmentField {
        val (columns, rows) = grid
        val tiles = List(columns * rows) { i ->
            val tx = i % columns
            val ty = i / columns
            RawSrTileFlow((tx * 8 + 4).toFloat(), (ty * 8 + 4).toFloat(),
                flow[i * 4], flow[i * 4 + 1], flow[i * 4 + 2], flow[i * 4 + 3] > 0.5f)
        }
        val summary = tiles.joinToString(";") {
            "(${it.dx},${it.dy},r=${it.residual},ok=${it.reliable})" }
        android.util.Log.i("RawSrRobustFlow", "$tag tiles=$summary")
        return RawSrAlignmentField(width, height, 8, columns, rows, tiles)
    }

    private fun expected(reference: RawSrPackedFrame, moving: RawSrPackedFrame,
                         captured: Captured, index: Int): RawSrRobustness.FrameRobustness {
        val tuning = RawSrTuning.fromReference(reference).tuning
        val flow = cpuFlow(captured.flows.getValue(index), captured.flowGrid.getValue(index), 32, 24, "frame$index")
        return RawSrRobustness.evaluate(
            RawSrRobustness.linearGuide(reference), RawSrRobustness.linearGuide(moving),
            flow, tuning, config)
    }

    private fun assertAgreement(name: String, frame: RawSrRobustness.FrameRobustness,
                                actualR: FloatArray, actualFlags: IntArray) {
        assertEquals("$name size", frame.r.size, actualR.size)
        assertEquals("$name flags size", frame.flags.size, actualFlags.size)
        var rDiffs = 0
        var rWorst = ""
        var flagDiffs = 0
        var flagFirst = ""
        for (i in frame.r.indices) {
            val allowed = 2e-3f + 2e-3f * abs(frame.r[i])
            if (!(abs(frame.r[i] - actualR[i]) <= allowed && actualR[i].isFinite())) {
                if (rDiffs < 5) rWorst += "[$i] ${frame.r[i]} vs ${actualR[i]}; "
                rDiffs++
            }
            if (frame.flags[i] != actualFlags[i]) {
                if (flagDiffs < 5) flagFirst += "[$i] ${frame.flags[i]} vs ${actualFlags[i]}; "
                flagDiffs++
            }
        }
        android.util.Log.i("RawSrRobustAgree",
            "$name rDiffs=$rDiffs $rWorst flagDiffs=$flagDiffs $flagFirst")
        assertTrue("$name rDiffs=$rDiffs $rWorst", rDiffs == 0)
        assertTrue("$name flagDiffs=$flagDiffs $flagFirst", flagDiffs == 0)
    }

    private fun acceptance(r: FloatArray, width: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var accepted = 0
        var total = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            total++
            if (r[y * width + x] > 0f) accepted++
        }
        return accepted.toDouble() / total
    }

    @Test fun staticSceneAgreesAndRetains() {
        val reference = packed(textured(7))
        val movingA = packed(textured(99))
        val movingB = packed(textured(1001))
        val captured = run(listOf(reference, movingA, movingB))
        assertEquals(setOf(1, 2), captured.r.keys)
        var rc = RawSrRobustness.accumulate(null, expected(reference, movingA, captured, 1).let {
            assertAgreement("static A", it, captured.r.getValue(1), captured.flags.getValue(1))
            it
        })
        rc = RawSrRobustness.accumulate(rc, expected(reference, movingB, captured, 2).let {
            assertAgreement("static B", it, captured.r.getValue(2), captured.flags.getValue(2))
            it
        })
        for (i in rc.values.indices)
            assertEquals("Rc [$i]", rc.values[i], captured.rc[i], 2e-3f + 2e-3f * abs(rc.values[i]))
        val rate = acceptance(captured.r.getValue(1), 32, 4, 28, 4, 20)
        assertTrue("static retention=$rate", rate >= 0.95)
    }

    @Test fun occlusionSceneRejectsForeground() {
        val reference = packed(textured(7))
        val moving = packed({ sx, sy -> if (sx < 33) 2500 else textured(7)(sx, sy) })
        val captured = run(listOf(reference, moving))
        val frame = expected(reference, moving, captured, 1)
        assertAgreement("occlusion", frame, captured.r.getValue(1), captured.flags.getValue(1))
        // Foreground covers sensor x < 33, i.e. quads x < 16; judge clear of x=16.
        val rejected = 1.0 - acceptance(captured.r.getValue(1), 32, 2, 12, 4, 20)
        val kept = acceptance(captured.r.getValue(1), 32, 20, 30, 4, 20)
        assertTrue("foreground rejection=$rejected", rejected >= 0.90)
        assertTrue("background retention=$kept", kept >= 0.95)
        for (i in frame.flags.indices)
            if (frame.r[i] == 0f) assertTrue("[$i] zero weight must be flagged", frame.flags[i] != 0)
    }

    @Test fun exposureMismatchRejectedOnDevice() {
        val reference = packed(textured(7))
        val moving = packed({ sx, sy -> (textured(7)(sx, sy) * 1.5).toInt() })
        val captured = run(listOf(reference, moving))
        val frame = expected(reference, moving, captured, 1)
        assertAgreement("exposure", frame, captured.r.getValue(1), captured.flags.getValue(1))
        val rejected = 1.0 - acceptance(captured.r.getValue(1), 32, 4, 28, 4, 20)
        assertTrue("exposure rejection=$rejected", rejected >= 0.70)
    }

    @Test fun saturatedBlockRejectedOnDevice() {
        val reference = packed(textured(7))
        val moving = packed({ sx, sy -> if (sx in 17 until 49 && sy in 13 until 37) 3995 else textured(7)(sx, sy) })
        val captured = run(listOf(reference, moving))
        val frame = expected(reference, moving, captured, 1)
        assertAgreement("saturated", frame, captured.r.getValue(1), captured.flags.getValue(1))
        // Saturated sensor block maps to quads x in 8..23, y in 6..17; judge interior.
        for (y in 8..15) for (x in 10..21) {
            val o = y * 32 + x
            assertTrue("($x,$y) flags=${captured.flags.getValue(1)[o]}",
                captured.flags.getValue(1)[o] and RawSrRobustness.FLAG_SATURATED != 0)
            assertEquals(0f, captured.r.getValue(1)[o], 0f)
        }
    }

    @Test fun referenceOnlyLeavesRcZero() {
        val reference = packed(textured(7))
        val captured = run(listOf(reference), referenceOnly = true)
        assertTrue(captured.r.isEmpty())
        assertTrue(captured.flags.isEmpty())
        assertTrue(captured.rc.all { it == 0f })
    }

    @Test fun deviceResultsAreDeterministic() {
        val frames = listOf(packed(textured(7)), packed(textured(99)))
        val first = run(frames)
        val second = run(frames)
        val flowA = first.flows.getValue(1)
        val flowB = second.flows.getValue(1)
        var flowDiffs = 0
        var flowFirst = ""
        for (i in flowA.indices) if (flowA[i] != flowB[i]) {
            if (flowDiffs == 0) flowFirst = "flow[$i] ${flowA[i]} vs ${flowB[i]}"
            flowDiffs++
        }
        val flagsA = first.flags.getValue(1)
        val flagsB = second.flags.getValue(1)
        var flagDiffs = 0
        var flagFirst = ""
        for (i in flagsA.indices) if (flagsA[i] != flagsB[i]) {
            if (flagDiffs == 0) flagFirst = "flags[$i] ${flagsA[i]} vs ${flagsB[i]}"
            flagDiffs++
        }
        android.util.Log.i("RawSrRobustDeterminism",
            "flowDiffs=$flowDiffs $flowFirst flagDiffs=$flagDiffs $flagFirst")
        org.junit.Assert.assertArrayEquals(first.r.getValue(1), second.r.getValue(1), 0f)
        assertTrue("flagDiffs=$flagDiffs $flagFirst | flowDiffs=$flowDiffs $flowFirst",
            flagsA.contentEquals(flagsB))
        org.junit.Assert.assertArrayEquals(first.rc, second.rc, 0f)
    }

    @Test fun exportsAreWritten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reference = packed(textured(7))
        val moving = packed(textured(99))
        val captured = run(listOf(reference, moving))
        val dir = File(context.cacheDir, "rawsr-debug/robustness_static")
        val r = captured.r.getValue(1)
        val flags = captured.flags.getValue(1)
        RawSrDebugExporter.writeFieldCsv(r, 32, 24, "r", File(dir, "r.csv"))
        RawSrDebugExporter.writeFieldPng(r, 32, 24, 1f, File(dir, "r.png"))
        RawSrDebugExporter.writeFlagsCsv(flags, 32, 24, File(dir, "flags.csv"))
        RawSrDebugExporter.writeFlagsPng(flags, 32, 24, File(dir, "flags.png"))
        RawSrDebugExporter.writeFieldCsv(captured.rc, 32, 24, "rc", File(dir, "rc.csv"))
        for (name in listOf("r.csv", "r.png", "flags.csv", "flags.png", "rc.csv"))
            assertTrue("$name missing or empty", File(dir, name).length() > 0)
    }

    private fun readRgba(image: Int, width: Int, height: Int): FloatArray =
        vkDownloadRgba32f(image)

    private fun readR32f(image: Int, width: Int, height: Int): FloatArray =
        vkDownloadR32f(image)

    private fun readR32ui(image: Int, width: Int, height: Int): IntArray =
        vkDownloadR32ui(image)
}
