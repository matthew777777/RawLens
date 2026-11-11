// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Kotlin side of the LK-R MHC port parity check (tools/lkr-pipeline
 * lkr_mhc.cpp vs [MhcReference.demosaicCoherent]). Opt-in: set
 * LKR_MHC_DIR to a directory with in.u16 + meta.txt (lkr_mhc_check format);
 * writes kotlin.f32 there.
 */
class MhcLkrParityTest {
    @Test
    fun dumpReference() {
        val dir = System.getenv("LKR_MHC_DIR")
        assumeTrue("LKR_MHC_DIR not set", dir != null)
        val d = File(dir!!)
        val m = File(d, "meta.txt").readText().trim().split(Regex("\\s+"))
        val (sw, sh, pitch, left, top, w, h) = m.take(7).map { it.toInt() }
        val channels = IntArray(4) { m[7 + it].toInt() }
        val levels = FloatArray(4) { m[11 + it].toFloat() }
        val white = m[15].toFloat()
        val bytes = ByteBuffer.wrap(File(d, "in.u16").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val codes = IntArray(pitch * sh) { bytes.getShort(it * 2).toInt() and 0xFFFF }
        val rgb = MhcReference.demosaicCoherent(codes, sw, sh, pitch, left, top, w, h, channels, levels, white)
        val out = ByteBuffer.allocate(rgb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        rgb.forEach { out.putFloat(it) }
        File(d, "kotlin.f32").writeBytes(out.array())
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
private operator fun <T> List<T>.component7(): T = this[6]
