// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production packFrame parity + timing: -e rawlensHdrPacking true. */
@RunWith(AndroidJUnit4::class)
class HdrPlusPackingDeviceTest {
    private val normalization = RawNormalization(BayerPattern.BGGR, List(4) { 64f }, 1023f)

    // HEAD's row-array packing, retained as the byte-level reference.
    private fun headPack(frame: HdrPlusMerge.FrameInput): ByteBuffer {
        val input = frame.pixels!!.duplicate().order(ByteOrder.nativeOrder())
        val origin = input.position()
        val layout = frame.layout
        val crop = frame.crop
        val out = HdrPlusVulkan.allocBuffer(crop.width.toLong() * crop.height * 2)
            .order(ByteOrder.nativeOrder())
        if (layout.pixelStride == 2) {
            val row = ByteArray(crop.width * 2)
            for (y in 0 until crop.height) {
                input.position(origin + (crop.top + y) * layout.rowStride + crop.left * 2)
                input.get(row)
                out.put(row)
            }
        } else {
            for (y in 0 until crop.height) {
                val start = origin + (crop.top + y) * layout.rowStride + crop.left * layout.pixelStride
                for (x in 0 until crop.width) out.putShort(input.getShort(start + x * layout.pixelStride))
            }
        }
        out.rewind()
        return out
    }

    @Test fun productionPackingMatchesHeadAndMeasuresFullResolution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensHdrPacking true", InstrumentationRegistry.getArguments()
            .getString("rawlensHdrPacking") == "true")
        val merge = HdrPlusMerge(instrumentation.targetContext)
        val method = HdrPlusMerge::class.java.getDeclaredMethod(
            "packFrame", HdrPlusMerge.FrameInput::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun pack(frame: HdrPlusMerge.FrameInput) = method.invoke(merge, frame, 0) as ByteBuffer
        fun verify(frame: HdrPlusMerge.FrameInput) {
            val source = frame.pixels!!
            val position = source.position()
            val limit = source.limit()
            val expected = headPack(frame)
            try {
                val actual = pack(frame)
                try {
                    assertEquals("packed bytes", expected, actual)
                    assertEquals("input position", position, source.position())
                    assertEquals("input limit", limit, source.limit())
                    assertEquals("output position", 0, actual.position())
                } finally { HdrPlusVulkan.freeBuffer(actual) }
            } finally { HdrPlusVulkan.freeBuffer(expected) }
        }
        // Tight/padded rows, cropped origins, a nonzero source position,
        // final-row mapping ending at the last pixel, read-only/heap buffers,
        // and the unusual-pixel-stride fallback.
        for (direct in listOf(false, true)) for (stride in listOf(2, 4)) {
            val rowStride = 18 * stride + 10
            val bytes = 11 + 13 * rowStride + 17 * stride + 2
            val data = (if (direct) ByteBuffer.allocateDirect(bytes) else ByteBuffer.allocate(bytes))
                .order(ByteOrder.nativeOrder())
            for (i in 0 until bytes) data.put(i, ((i * 71 + i / 7) and 255).toByte())
            data.position(11)
            val frame = HdrPlusMerge.FrameInput(null, RawPlaneLayout(18, 14, rowStride, stride),
                RawCrop(1, 2, 16, 12), normalization, pixels = data.asReadOnlyBuffer())
            verify(frame)
        }
        for (padding in listOf(0, 32)) {
            val w = 4208
            val h = 3120
            val rowStride = w * 2 + padding
            val source = HdrPlusVulkan.allocBuffer(rowStride.toLong() * (h - 1) + w * 2)
                .order(ByteOrder.nativeOrder())
            try {
                for (i in 0 until source.capacity()) source.put(i, ((i * 37 + i / 17) and 255).toByte())
                val frame = HdrPlusMerge.FrameInput(null, RawPlaneLayout(w, h, rowStride, 2),
                    RawCrop(0, 0, w, h), normalization, pixels = source.asReadOnlyBuffer())
                verify(frame)
                fun timeBurst(head: Boolean): Double {
                    val start = System.nanoTime()
                    repeat(8) {
                        val out = if (head) headPack(frame) else pack(frame)
                        HdrPlusVulkan.freeBuffer(out)
                    }
                    return (System.nanoTime() - start) / 1e6
                }
                repeat(3) { timeBurst(true); timeBurst(false) }
                val before = ArrayList<Double>()
                val after = ArrayList<Double>()
                repeat(8) { i ->
                    if (i % 2 == 0) { before += timeBurst(true); after += timeBurst(false) }
                    else { after += timeBurst(false); before += timeBurst(true) }
                }
                fun median(values: List<Double>): Double = values.sorted().let { (it[3] + it[4]) / 2 }
                Log.i("HdrPlusPacking", "model=${Build.MODEL} ${w}x$h N=8 padding=$padding " +
                    "HEAD_ms=${median(before)} direct_ms=${median(after)} " +
                    "HEAD_samples=$before direct_samples=$after parity=PASS")
            } finally { HdrPlusVulkan.freeBuffer(source) }
        }
    }
}
