// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host HL parity harness (docs/refactor/jpeg-pipeline-study.md): runs the
 * production [HlReconstructionStage] CPU path on every `<name>_<W>x<H>.f16`
 * (RGBA half, normalized tap) in $RAWLENS_HL_BENCH and writes
 * `<name>.rawlens.f32` (RGB) for comparison with the RawTherapee oracle
 * built from references/RawTherapee/hilite_recon.cc. Skipped unless set.
 */
class HlOracleHarnessTest {
    @Test
    fun runHarness() {
        val dir = System.getenv("RAWLENS_HL_BENCH")
        assumeTrue("set RAWLENS_HL_BENCH", dir != null)
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.f16")
        for (f in File(dir!!).listFiles()!!.filter { re.matches(it.name) }) {
            val (name, w, h) = re.matchEntire(f.name)!!.destructured
            val width = w.toInt(); val height = h.toInt()
            val camera = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
            camera.put(ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).let { src ->
                ByteBuffer.allocate(src.remaining()).order(ByteOrder.nativeOrder()).also { dst ->
                    while (src.hasRemaining()) dst.putShort(src.short)
                    dst.flip()
                }
            })
            camera.flip()
            val out = HlReconstructionStage.applyIfEnabled(
                camera, width, height, true,
                HlReconstructionStage.Inputs(1023f, floatArrayOf(64f, 64f, 64f, 64f), floatArrayOf(0.2126f, 0.7152f, 0.0722f)),
                gpuProvider = null,
                // RAWLENS_HL_WORKERS=n: same pool shape as the develop (bit-identical by design).
                workers = System.getenv("RAWLENS_HL_WORKERS")?.toInt()?.let {
                    java.util.concurrent.Executors.newFixedThreadPool(it)
                }
            )
            val shorts = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val rgb = ByteBuffer.allocate(width * height * 12).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until width * height) {
                for (c in 0..2) rgb.putFloat(HlReconstructionStage.unpackHalf(shorts.get(i * 4 + c).toInt() and 0xffff))
            }
            File(dir, "$name.rawlens.f32").writeBytes(rgb.array())
        }
    }
}
