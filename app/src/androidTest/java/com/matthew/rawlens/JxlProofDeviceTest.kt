// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * libjxl proof inputs, encoded on device by the shipping encoders
 * ([NativeJxlEncoder] for stills, [TinyDngImageWriter.writeCompressed] for
 * DNG 1.7 JXL tiles). Writes each deterministic input next to its encoded
 * file plus a timing/size report; tools/jxl_proof/verify.py decodes them on
 * the host with libjxl's djxl/jxlinfo and checks lossless pixels,
 * orientation, PQ transfer and intensity target. Opt-in:
 * `-e rawlensJxlProof <label>` (output: files/jxl-proof-<label>/).
 */
class JxlProofDeviceTest {
    @Test
    fun encodeProofSet() {
        val label = InstrumentationRegistry.getArguments().getString("rawlensJxlProof")
        assumeTrue("Pass -e rawlensJxlProof <label>", label != null)
        assumeTrue("libjxl not bundled in this build", NativeJxlEncoder.isAvailable())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "jxl-proof-$label").apply {
            deleteRecursively(); mkdirs()
        }
        val report = StringBuilder()

        // Stills: deterministic 16-bit RGB, lossless (default settings).
        val w = STILL_W
        val h = STILL_H
        val rgb = ByteBuffer.allocateDirect(w * h * 3 * 2).order(ByteOrder.nativeOrder())
        var seed = 0x2545F491
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            seed = seed * 1103515245 + 12345
            val ramp = ((x * 65535L / (w - 1)) * (c + 1) / 3).toInt()
            rgb.putShort(((ramp + (seed ushr 20)) and 0xffff).toShort())
        }
        rgb.flip()
        File(dir, "still_input_rgb16_native.bin").writeBytes(ByteArray(rgb.remaining()).also { rgb.duplicate().get(it) })
        fun still(name: String, settings: JxlOutputSettings, orientation: Int) {
            val out = File(dir, name)
            val t0 = SystemClock.elapsedRealtimeNanos()
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { pfd ->
                NativeJxlEncoder.encode(rgb.duplicate().order(ByteOrder.nativeOrder()), w, h, pfd, settings,
                    orientation = orientation)
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            report.append("$name bytes=${out.length()} ms=${"%.1f".format(ms)} orientation=$orientation " +
                "transfer=${settings.hdrTransfer}\n")
            assertTrue("$name empty", out.length() > 0)
        }
        still("still_sdr_lossless_o6.jxl", JxlOutputSettings(), orientation = 6)
        still("still_pq_lossless_o1.jxl", JxlOutputSettings(hdrTransfer = JxlHdrTransfer.PQ), orientation = 1)
        report.append("pq_peak_nits=${JxlPixelPack.peakNits(rgb.duplicate(), JxlOutputSettings(hdrTransfer = JxlHdrTransfer.PQ))}\n")

        // DNG 1.7 JXL: deterministic 12-bit RGGB mosaic, small and full 12 MP.
        fun dng(name: String, mw: Int, mh: Int, keepInput: Boolean) {
            val mosaic = ByteArray(mw * mh * 2)
            var s = 0x1234567
            for (i in 0 until mw * mh) {
                s = s * 1103515245 + 12345
                val v = (((i % mw) * 3000 / mw) + (s ushr 24) + 64).coerceAtMost(4095)
                mosaic[2 * i] = v.toByte()
                mosaic[2 * i + 1] = (v shr 8).toByte()
            }
            if (keepInput) File(dir, name.removeSuffix(".dng") + "_mosaic_u16le.bin").writeBytes(mosaic)
            val fields = TiffFields().apply {
                bytes(50706, byteArrayOf(1, 7, 0, 0))            // DNGVersion
                bytes(50707, byteArrayOf(1, 7, 0, 0))            // DNGBackwardVersion
                ascii(271, "RawLens")                            // Make
                ascii(272, "JXL proof")                          // Model
                ascii(50708, "RawLens JXL proof")                // UniqueCameraModel
                shorts(33421, 2, 2)                              // CFARepeatPatternDim
                bytes(33422, byteArrayOf(0, 1, 1, 2))            // CFAPattern RGGB
                shorts(50714, 64)                                // BlackLevel
                longs(50717, 4095)                               // WhiteLevel
            }
            val out = File(dir, name)
            val t0 = SystemClock.elapsedRealtimeNanos()
            FileOutputStream(out).use { TinyDngImageWriter.writeCompressed(it, mw, mh, mosaic, fields, null, null) }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            report.append("$name bytes=${out.length()} raw_bytes=${mosaic.size} ratio=" +
                "%.3f".format(mosaic.size.toDouble() / out.length()) + " ms=${"%.1f".format(ms)}\n")
        }
        dng("dng_jxl_small.dng", 1024, 768, keepInput = true)
        dng("dng_jxl_12mp.dng", 4080, 3060, keepInput = false)

        File(dir, "report.txt").writeText(report.toString())
        Log.i(TAG, "wrote ${dir.absolutePath}\n$report")
    }

    private companion object {
        const val TAG = "RawLensJxlProof"
        const val STILL_W = 257
        const val STILL_H = 193
    }
}
