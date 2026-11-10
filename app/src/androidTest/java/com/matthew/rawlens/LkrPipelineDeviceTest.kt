// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rawlens.srpipeline.SrpBridge
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end timing of LK-Reloaded through the same JNI path the
 * Merges screen uses ([LkrBridge.nativeMerge]).
 *
 * Push a burst first (raw uint16 frames f0..fN.u16 + meta.json, see
 * tools/lkr-pipeline/README.md), default /data/local/tmp/lkr/night:
 *   adb shell am instrument -w -e class com.matthew.rawlens.LkrPipelineDeviceTest \
 *     [-e lkrDir /data/local/tmp/lkr/night] [-e lkrCases crop1,crop2x,full] ...
 * Results: logcat tag RawLensLkrTest, DNGs in the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class LkrPipelineDeviceTest {
    private data class Case(val name: String, val scale: Int, val crop: IntArray?, val outW: Int = 0, val outH: Int = 0)

    @Test
    fun endToEnd() {
        val args = InstrumentationRegistry.getArguments()
        val dir = File(args.getString("lkrDir") ?: "/data/local/tmp/lkr/night")
        assumeTrue("burst not pushed: $dir", File(dir, "meta.json").isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val meta = JSONObject(File(dir, "meta.json").readText())
        val w = meta.getInt("w")
        val h = meta.getInt("h")
        val n = meta.getInt("frames")
        val cfa = IntArray(4) { meta.getJSONArray("cfa").getInt(it) }
        val black = FloatArray(4) { meta.getJSONArray("black").getDouble(it).toFloat() }
        val white = meta.getDouble("white").toFloat()
        val wb = FloatArray(3) { meta.getJSONArray("wb").getDouble(it).toFloat() }
        val wanted = (args.getString("lkrCases") ?: "crop1,crop2x,full").split(',')
        val cases = listOf(
            Case("crop1", 1, intArrayOf(1425, 1224, 1024, 1024)),
            Case("crop2x", 2, intArrayOf(1425, 1224, 512, 512)),
            Case("full", 1, null),
            // LK-SR zoom exactly as LkrMerge computes it: centre crop at x2, output = sensor size.
            Case("zoom2", 2, LkrZoom.X2.crop(w, h), w, h),
            Case("zoom15", 2, LkrZoom.X1_5.crop(w, h), w, h)
        ).filter { it.name in wanted }

        val bufs = ArrayList<ByteBuffer>()
        try {
            val tLoad = SystemClock.elapsedRealtime()
            repeat(n) { k ->
                val b = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                RandomAccessFile(File(dir, "f$k.u16"), "r").channel.use { ch ->
                    while (b.hasRemaining() && ch.read(b) >= 0) Unit
                }
                b.rewind()
                bufs += b
            }
            Log.i(TAG, "loaded $n frames ${w}x$h in ${SystemClock.elapsedRealtime() - tLoad} ms")
            val dct = (args.getString("lkrDct") ?: "0") == "1"
            for (c in cases) {
                val stats = FloatArray(LkrBridge.STATS_SIZE)
                val t0 = SystemClock.elapsedRealtime()
                val rgb = LkrBridge.nativeMerge(bufs.toTypedArray(), w, h, w, cfa, black, white, wb,
                    c.scale, 512, 32, c.crop, dct, stats, null, c.outW, c.outH)
                val ms = SystemClock.elapsedRealtime() - t0
                assertNotNull("merge ${c.name} failed", rgb)
                if (c.outW > 0) org.junit.Assert.assertEquals("${c.name} output size", c.outW.toLong() * c.outH * 12, rgb!!.capacity().toLong())
                val ow = stats[LkrBridge.STAT_OUT_W].toInt()
                val oh = stats[LkrBridge.STAT_OUT_H].toInt()
                Log.i(TAG, "[dct=$dct] %s: %dx%d s=%d tiles %d | total %d ms (native %.0f: noise %.0f coarse %.0f tiles %.0f = gpu %.0f + prior %.0f [%d calls, backend %d]) sigma %.5f".format(
                    c.name, ow, oh, c.scale, stats[LkrBridge.STAT_TILES].toInt(), ms,
                    stats[LkrBridge.STAT_MS_TOTAL], stats[LkrBridge.STAT_MS_NOISE], stats[LkrBridge.STAT_MS_COARSE],
                    stats[LkrBridge.STAT_MS_TILES], stats[LkrBridge.STAT_MS_GPU], stats[LkrBridge.STAT_MS_PRIOR],
                    stats[LkrBridge.STAT_PRIOR_CALLS].toInt(), stats[LkrBridge.STAT_BACKEND].toInt(),
                    stats[LkrBridge.STAT_SIGMA]))
                // Raw float dump for desktop comparison (crop cases only: small).
                if (c.crop != null && c.outW == 0) {
                    val out = File(context.getExternalFilesDir(null), "lkr_${c.name}.f32")
                    out.outputStream().channel.use { ch -> rgb!!.rewind(); ch.write(rgb) }
                    Log.i(TAG, "wrote ${out.absolutePath}")
                }
                SrpBridge.nativeFreeDirect(rgb!!)
            }
        } finally {
            bufs.forEach { runCatching { HdrPlusVulkan.freeBuffer(it) } }
        }
    }

    private companion object {
        const val TAG = "RawLensLkrTest"
    }
}
