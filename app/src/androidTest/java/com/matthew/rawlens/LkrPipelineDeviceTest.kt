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
    @Test
    fun nativeSharpnessMatchesSelectionScore() {
        val random = java.util.Random(82)
        for ((w, h) in listOf(64 to 48, 67 to 51, 128 to 96, 4096 to 3072)) {
            val stride = w + 8
            val raw = ByteBuffer.allocateDirect(stride * h * 2).order(ByteOrder.nativeOrder())
            val shorts = raw.asShortBuffer()
            repeat(stride * h) { shorts.put(it, random.nextInt(65536).toShort()) }
            val black = floatArrayOf(63.25f, 64.5f, 65.75f, 66f)
            for (cfa in listOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1),
                intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))) {
                val start = SystemClock.elapsedRealtimeNanos()
                val expected = BurstSharpness.measure(w, h,
                    { x, y -> cfa[(y and 1) * 2 + (x and 1)] == 1 }) { x, y ->
                    (shorts.get(y * stride + x).toInt() and 0xffff) - black[(y and 1) * 2 + (x and 1)]
                }
                val kotlinNs = SystemClock.elapsedRealtimeNanos() - start
                val nativeStart = SystemClock.elapsedRealtimeNanos()
                val actual = LkrBridge.nativeSharpness(raw, w, h, stride, cfa, black)
                val nativeNs = SystemClock.elapsedRealtimeNanos() - nativeStart
                org.junit.Assert.assertEquals(expected, actual, 0.0)
                if (w == 4096) Log.i(TAG, "sharpness ${w}x$h Kotlin=${kotlinNs / 1e6}ms native=${nativeNs / 1e6}ms exact=true")
            }
            shorts.clear()
            repeat(stride * h) { shorts.put(0.toShort()) }
            org.junit.Assert.assertEquals(0.0, LkrBridge.nativeSharpness(raw, w, h, stride,
                intArrayOf(0, 1, 1, 2), black), 0.0)
        }
    }

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
            Case("full2x", 2, null),
            // LK-R zoom exactly as LkrMerge computes it: centre crop at x2, output = sensor size.
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
            LkrBridge.nativeSetCacheDir(context.codeCacheDir.absolutePath)
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
