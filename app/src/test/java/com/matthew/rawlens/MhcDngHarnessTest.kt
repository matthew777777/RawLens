// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [MhcReference] (the CPU twin of the Direct-Log fused shader) on a
 * dumped mosaic: `<name>_<W>x<H>.u16` (LE codes) in $RAWLENS_MHC_DIR,
 * writing `<name>.mhc.f32` (RGB, normalized, pre-WB) for comparison with
 * reference demosaics. Opt-in via RAWLENS_MHC_DIR; env RAWLENS_MHC_CHANNELS
 * is the quad-offset map (default GBRG 2,0,3,1), RAWLENS_MHC_BW black,white.
 */
class MhcDngHarnessTest {
    @Test
    fun demosaicDumpedFrames() {
        val dir = System.getenv("RAWLENS_MHC_DIR")
        assumeTrue("set RAWLENS_MHC_DIR", dir != null)
        val channels = (System.getenv("RAWLENS_MHC_CHANNELS") ?: "2,0,3,1").split(",").map { it.trim().toInt() }
        val (black, white) = (System.getenv("RAWLENS_MHC_BW") ?: "64,1023").split(",").map { it.trim().toFloat() }
        val re = Regex("(.+)_(\\d+)x(\\d+)\\.u16")
        for (f in File(dir!!).listFiles().orEmpty().filter { re.matches(it.name) }) {
            val (name, w, h) = re.matchEntire(f.name)!!.destructured
            val width = w.toInt(); val height = h.toInt()
            val src = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val codes = IntArray(width * height) { src.get(it).toInt() and 0xffff }
            val rgb = MhcReference.demosaic(
                codes, width, height, width, 0, 0, width, height,
                channels.toIntArray(), FloatArray(4) { black }, white
            )
            val out = ByteBuffer.allocate(rgb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            out.asFloatBuffer().put(rgb)
            File(dir, "$name.mhc.f32").writeBytes(out.array())
        }
    }
}
