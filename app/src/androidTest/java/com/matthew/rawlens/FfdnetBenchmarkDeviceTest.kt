// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Half
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real 12 MP camera-linear input; production CPU stages + forced GPU/NPU inference. */
@RunWith(AndroidJUnit4::class)
class FfdnetBenchmarkDeviceTest {
    @Test fun twelveMegapixel() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(requireNotNull(args.getString("ffdnetDir")))
        val meta = JSONObject(File(dir, "meta.json").readText())
        val w = meta.getInt("width"); val h = meta.getInt("height")
        assertEquals("exactly 12 MP", 12_000_000, w * h)
        val cfa = IntArray(4) { meta.getJSONArray("cfa").getInt(it) }
        val wb = FloatArray(3) { meta.getJSONArray("wb").getDouble(it).toFloat() }
        val backendName = args.getString("ffdnetBackend") ?: "npu"
        val backend = when (backendName) { "gpu" -> 1; "npu" -> 2; else -> error("backend must be gpu or npu") }
        val fp16 = args.getString("ffdnetFp16") == "1"
        val repeats = (args.getString("ffdnetRepeats") ?: "3").toInt().coerceIn(1, 5)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawLens:FfdnetBenchmark")
        fun temperature(): Double = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0) ?: -1.0
        fun model(asset: String): String {
            val out = File(context.filesDir, "ffdnet-bench/" + asset.substringAfterLast('/'))
            out.parentFile!!.mkdirs()
            context.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
            return out.path
        }
        val modelStart = SystemClock.elapsedRealtime()
        val param = model("models/lkr_ffdnet.ncnn.param")
        val bin = model("models/lkr_ffdnet.ncnn.bin")
        val npu = model("models/lkr_ffdnet_512.tflite")
        val installMs = SystemClock.elapsedRealtime() - modelStart
        val input = HdrPlusVulkan.allocBuffer(w.toLong() * h * 8).order(ByteOrder.nativeOrder())
        val rows = JSONArray()
        val report = JSONObject().put("width", w).put("height", h).put("requested_backend", backendName)
            .put("fp16", fp16).put("source", meta).put("asset_install_ms", installMs)
            .put("device", android.os.Build.MODEL).put("soc", android.os.Build.SOC_MODEL)
            .put("runs", rows)
        val output = File(context.getExternalFilesDir(null), "ffdnet-${backendName}-${if (fp16) "fp16" else "fp32"}.json")
        val names = listOf("backend", "native_total_ms", "noise_ms", "model_load_ms", "npu_open_ms", "range_ms",
            "primary_ms", "chroma_ms", "colour_guard_ms", "blend_ms", "pack_ms", "inference_ms", "unpack_ms", "prior_calls", "fp16", "success")
        wake.acquire(20 * 60 * 1000L)
        try {
            repeat(repeats) { run ->
                input.clear()
                val loadStart = SystemClock.elapsedRealtime()
                RandomAccessFile(File(dir, "input.rgba16f"), "r").channel.use { ch ->
                    while (input.hasRemaining()) check(ch.read(input) > 0) { "short input" }
                }
                input.rewind()
                val stats = FloatArray(16)
                val startTemp = temperature(); val startThermal = power.currentThermalStatus
                Log.i(TAG, "START backend=$backendName fp16=$fp16 run=$run ${w}x$h temp=$startTemp thermal=$startThermal")
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                val started = SystemClock.elapsedRealtimeNanos()
                val ok = LkrBridge.nativeBenchmarkDenoise(input, w, h, cfa, wb, param, bin,
                    context.applicationInfo.nativeLibraryDir, npu, backend, fp16, stats)
                val wallMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                val row = JSONObject().put("run", run).put("wall_ms", wallMs).put("input_load_ms", loadMs)
                    .put("start_battery_C", startTemp).put("end_battery_C", temperature())
                    .put("start_thermal", startThermal).put("end_thermal", power.currentThermalStatus)
                    .put("native_heap_MB_after", Debug.getNativeHeapAllocatedSize() / 1048576.0)
                names.forEachIndexed { i, name -> row.put(name, stats[i].toDouble()) }
                rows.put(row)
                output.writeText(report.toString(2))
                Log.i(TAG, "RESULT $row")
                assertTrue("forced $backendName failed (fallback forbidden); see RawLensFfdnet log", ok)
                assertEquals("actual backend", if (backend == 1) 2 else 3, stats[0].toInt())
                assertEquals("12 MP tile calls", 159, stats[13].toInt())
                // Sparse output validity and alpha checks stay outside the timed section.
                for (i in 0 until w*h step 997) {
                    for (c in 0..2) assertTrue(Half.toFloat(input.getShort(i*8+c*2)).isFinite())
                    assertEquals(Half.toHalf(1f), input.getShort(i*8+6))
                }
                if (run == 0 && args.getString("ffdnetSaveOutput") == "1") {
                    val image = File(context.getExternalFilesDir(null), "ffdnet-$backendName-output.rgba16f")
                    image.outputStream().channel.use { ch -> input.rewind(); while (input.hasRemaining()) ch.write(input) }
                }
                if (run+1 < repeats) SystemClock.sleep(10_000)
            }
            Log.i(TAG, "REPORT ${output.path}")
        } finally {
            HdrPlusVulkan.freeBuffer(input)
            wake.release()
        }
    }
    companion object { const val TAG = "RawLensFfdnetBench" }
}
