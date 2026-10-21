// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host demosaic benchmark harness (docs/refactor/vf-native-study.md): runs
 * the CPU twins of the record shaders ([MhcReference], [RcdReference]) on
 * every `<name>_<W>x<H>.u16` RGGB mosaic in $RAWLENS_DEMOSAIC_BENCH and
 * writes `<name>.<algo>.f32` (interleaved RGB). Skipped unless set.
 */
class DemosaicBenchHarnessTest {
    @Test
    fun runBench() {
        val dir = System.getenv("RAWLENS_DEMOSAIC_BENCH")
        assumeTrue("set RAWLENS_DEMOSAIC_BENCH", dir != null)
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.u16")
        for (f in File(dir!!).listFiles()!!.filter { re.matches(it.name) }) {
            val (name, w, h) = re.matchEntire(f.name)!!.destructured
            val width = w.toInt(); val height = h.toInt()
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val codes = IntArray(width * height) { bb.short.toInt() and 0xffff }
            val ch = intArrayOf(0, 1, 2, 3)
            val lv = floatArrayOf(0f, 0f, 0f, 0f)
            for ((algo, run) in listOf(
                "mhc_rawlens" to { MhcReference.demosaic(codes, width, height, width, 0, 0, width, height, ch, lv, 65535f) },
                "rcd_rawlens" to { RcdReference.demosaic(codes, width, height, width, 0, 0, width, height, ch, lv, 65535f) },
            )) {
                val rgb = run()
                val out = ByteBuffer.allocate(rgb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                rgb.forEach { out.putFloat(it) }
                File(dir, "$name.$algo.f32").writeBytes(out.array())
            }
        }
    }
}
