// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentValues
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 spike for RAW Video: measures the four numbers that decide the
 * CPU-pack vs Vulkan-zero-copy verdict —
 *  1. HAL stream limits (RAW min frame duration / stall per size),
 *  2. CPU RAW16->RAW10 pack throughput at candidate sizes,
 *  3. MediaCinemaRAW type-7 encode throughput, Path A (RAW16 direct) vs
 *     Path B (CPU pack + RAW10 encode), across Open Gate / 16:9 / 2.39 / 2.00 crops,
 *  4. sequential storage bandwidth (cache file + MediaStore.Video probe).
 *
 * Spike, not a gate: these tests log tables and never assert on performance.
 * Correctness spot-checks (pack round-trip, encode determinism) do assert.
 * Pull logcat with `adb logcat -s CinemaRawSpike` or read the test output.
 */
@RunWith(AndroidJUnit4::class)
class CinemaRawSpikeTest {
    private fun direct(bytes: Long): ByteBuffer {
        require(bytes <= Int.MAX_VALUE) { "buffer too large: $bytes" }
        return ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
    }

    private fun median(values: List<Double>): Double = values.sorted().let {
        if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
    }

    /** Deterministic 10-bit gradient+noise: compressible like real sensor data. */
    private fun fillRaw16(buf: ByteBuffer, w: Int, h: Int, stride: Int) {
        var state = 0x12345678L
        for (y in 0 until h) {
            var idx = y * stride
            for (x in 0 until w) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val noise = ((state ushr 33) and 63).toInt()
                val v = ((x * 3 + y * 5 + noise) and 1023).toShort()
                buf.putShort(idx, v)
                idx += 2
            }
        }
    }

    /** Centered even-row crop for [w]; height derived from [ratio], %4==0. */
    private fun cropFor(w: Int, h: Int, ratio: Double): Pair<Int, Int> {
        var ch = (w / ratio).toInt() / 4 * 4
        if (ch > h) ch = h / 4 * 4
        if (ch <= 0) ch = h / 4 * 4
        var top = (h - ch) / 2
        top -= top % 2
        return top to ch
    }

    @Test fun streamCombos() {        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXT"
            }
            val ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val array = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            Log.i(TAG, "camera id=$id facing=$facing pixelArray=$array timestampSource=$ts")
            val map: StreamConfigurationMap =
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            for (fmt in intArrayOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12)) {
                val fmtName = when (fmt) {
                    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                    ImageFormat.RAW10 -> "RAW10"
                    else -> "RAW12"
                }
                val fmtSizes = map.getOutputSizes(fmt)
                if (fmtSizes == null) {
                    Log.i(TAG, "fmt id=$id $fmtName unsupported")
                    continue
                }
                for (s in fmtSizes.sortedByDescending { it.width * it.height }) {
                    val minNs = map.getOutputMinFrameDuration(fmt, s)
                    val stallNs = map.getOutputStallDuration(fmt, s)
                    Log.i(
                        TAG, "FMT id=$id $fmtName ${s.width}x${s.height} " +
                            "min=${"%.2f".format(minNs / 1e6)}ms stall=${"%.2f".format(stallNs / 1e6)}ms"
                    )
                }
            }
            val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: continue
            for (s in sizes.sortedByDescending { it.width * it.height }) {
                val minNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
                val stallNs = map.getOutputStallDuration(ImageFormat.RAW_SENSOR, s)
                val maxFps = if (minNs > 0) 1e9 / minNs else Double.NaN
                val mp = s.width * s.height / 1e6
                Log.i(
                    TAG, "RAW id=$id ${s.width}x${s.height} ${"%.1f".format(mp)}MP " +
                        "min=${"%.2f".format(minNs / 1e6)}ms stall=${"%.2f".format(stallNs / 1e6)}ms " +
                        "maxFps~${"%.1f".format(maxFps)} " +
                        "raw16MB=${"%.1f".format(s.width * s.height * 2 / 1e6)} " +
                        "raw10MB=${"%.1f".format(s.width / 4 * 5 * s.height / 1e6)}"
                )
            }
        }
    }

    @Test fun packCorrectness() {
        assumeTrue("cinemaraw native library unavailable", CinemaRawSpike.available)
        val w = 8
        val h = 2
        val src = direct((w * h * 2).toLong())
        val codes = intArrayOf(0, 1, 1023, 512, 64, 65, 1000, 999, 7, 8, 9, 10, 511, 510, 2, 3)
        codes.forEachIndexed { i, v -> src.putShort(i * 2, v.toShort()) }
        val dst = direct((w / 4 * 5 * h).toLong())
        CinemaRawSpike.packRaw10(src, w * 2, w, h, dst)
        dst.flip()
        // Group 0: lows 0,1,255,0 highs 0,0,3,2 -> 0|0<<2|3<<4|2<<6 = 0xB0
        assertEquals(0, dst.get().toInt() and 255)
        assertEquals(1, dst.get().toInt() and 255)
        assertEquals(255, dst.get().toInt() and 255)
        assertEquals(0, dst.get().toInt() and 255)
        assertEquals(0xB0, dst.get().toInt() and 255)
        // Scalar reference over the whole buffer
        dst.rewind()
        var p = 0
        for (y in 0 until h) for (gx in 0 until w / 4) {
            for (k in 0 until 4) {
                assertEquals("low y=$y gx=$gx k=$k", codes[p + k] and 255, dst.get().toInt() and 255)
            }
            var hi = 0
            for (k in 0 until 4) hi = hi or (((codes[p + k] shr 8) and 3) shl (2 * k))
            assertEquals("hi y=$y gx=$gx", hi, dst.get().toInt() and 255)
            p += 4
        }
    }

    @Test fun packBench() {
        assumeTrue("cinemaraw native library unavailable", CinemaRawSpike.available)
        val sizes = listOf(1920 to 1080, 1920 to 1440, 3264 to 2448, 4000 to 3000)
        for ((w, h) in sizes) {
            val stride = w * 2
            val src = direct((h - 1).toLong() * stride + w * 2)
            fillRaw16(src, w, h, stride)
            val dst = direct(CinemaRawSpike.packedStride(w).toLong() * h)
            repeat(2) { // warmup
                src.rewind()
                dst.rewind()
                CinemaRawSpike.packRaw10(src, stride, w, h, dst)
            }
            val times = mutableListOf<Double>()
            repeat(10) {
                src.rewind()
                dst.rewind()
                val t0 = System.nanoTime()
                CinemaRawSpike.packRaw10(src, stride, w, h, dst)
                times.add((System.nanoTime() - t0) / 1e6)
            }
            val med = median(times)
            val inMb = w * h * 2 / 1e6
            Log.i(
                TAG, "SPIKE pack ${w}x$h med=${"%.2f".format(med)}ms " +
                    "in=${"%.1f".format(inMb)}MB gbs=${"%.2f".format(inMb / med * 1000 / 1000)} " +
                    "packFps~${"%.0f".format(1000 / med)} " +
                    "budget24=${"%.0f".format(41.67 / med * 100)}% budget30=${"%.0f".format(33.33 / med * 100)}%"
            )
        }
    }

    @Test fun encodeBench() {
        assumeTrue("cinemaraw native library unavailable", CinemaRawSpike.available)
        val sizes = listOf(1920 to 1080, 3264 to 2448, 4000 to 3000)
        val crops = listOf("open" to null, "16:9" to 16.0 / 9, "2.39" to 2.39, "2.00" to 2.00)
        for ((w, h) in sizes) {
            val stride = w * 2
            val planeBytes = ((h - 1).toLong() * stride + w * 2).toInt()
            val src = direct(planeBytes.toLong())
            fillRaw16(src, w, h, stride)
            // Worst-case output bound: padded-width raw + metadata slack.
            val ew = (w + 63) / 64 * 64
            val dst = direct((ew * h * 2 + ew * h / 8 + 4096).toLong())
            for ((name, ratio) in crops) {
                val (top, ch) = if (ratio == null) 0 to (h / 4 * 4) else cropFor(w, h, ratio)
                if (ch < 4) continue
                src.rewind()
                dst.rewind()
                val first = CinemaRawSpike.encode(src, planeBytes, w, h, stride, false, top, ch, false, dst)
                assertTrue("empty payload ${w}x$h $name", first > 0)
                val times = mutableListOf<Double>()
                var bytes = first
                repeat(4) {
                    src.rewind()
                    dst.rewind()
                    val t0 = System.nanoTime()
                    bytes = CinemaRawSpike.encode(src, planeBytes, w, h, stride, false, top, ch, false, dst)
                    times.add((System.nanoTime() - t0) / 1e6)
                }
                val med = median(times)
                Log.i(
                    TAG, "SPIKE encA ${w}x$h crop=$name(${w}x$ch) med=${"%.1f".format(med)}ms " +
                        "out=${"%.2f".format(bytes / 1e6)}MB ratio=${"%.2f".format(bytes / (w * ch * 2.0))} " +
                        "fps~${"%.0f".format(1000 / med)}"
                )
            }
            // Path B (pack + RAW10 encode) on open gate for the small + large size.
            if (w == 1920 || w == 4000) {
                val packed = direct(CinemaRawSpike.packedStride(w).toLong() * h)
                src.rewind()
                packed.rewind()
                CinemaRawSpike.packRaw10(src, stride, w, h, packed)
                val pStride = CinemaRawSpike.packedStride(w)
                val pBytes = pStride * h
                src.rewind()
                packed.rewind()
                dst.rewind()
                val firstB = CinemaRawSpike.encode(packed, pBytes, w, h, pStride, true, 0, h / 4 * 4, false, dst)
                assertTrue("empty RAW10 payload ${w}x$h", firstB > 0)
                // Determinism: same input -> same byte count (encoder is deterministic).
                packed.rewind()
                dst.rewind()
                val secondB = CinemaRawSpike.encode(packed, pBytes, w, h, pStride, true, 0, h / 4 * 4, false, dst)
                assertEquals("encoder not deterministic", firstB, secondB)
                val timesB = mutableListOf<Double>()
                var bytesB = firstB
                repeat(4) {
                    packed.rewind()
                    dst.rewind()
                    val t0 = System.nanoTime()
                    bytesB = CinemaRawSpike.encode(packed, pBytes, w, h, pStride, true, 0, h / 4 * 4, false, dst)
                    timesB.add((System.nanoTime() - t0) / 1e6)
                }
                val medB = median(timesB)
                Log.i(
                    TAG, "SPIKE encB ${w}x$h open med=${"%.1f".format(medB)}ms " +
                        "out=${"%.2f".format(bytesB / 1e6)}MB ratioVsRaw10=${"%.2f".format(bytesB / pBytes.toDouble())} " +
                        "fps~${"%.0f".format(1000 / medB)}"
                )
            }
            // In-encoder 4x same-colour downscale (bin) on the large size.
            if (w == 4000) {
                src.rewind()
                dst.rewind()
                val t0 = System.nanoTime()
                val bytesBin = CinemaRawSpike.encode(src, planeBytes, w, h, stride, false, 0, h / 4 * 4, true, dst)
                val msBin = (System.nanoTime() - t0) / 1e6
                Log.i(
                    TAG, "SPIKE encBin ${w}x$h -> ${w / 2}x${h / 2} ${"%.1f".format(msBin)}ms " +
                        "out=${"%.2f".format(bytesBin / 1e6)}MB"
                )
            }
        }
    }

    @Test fun storageBench() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chunk = direct(1024L * 1024)
        var s = 0L
        val bb = chunk.duplicate()
        while (bb.hasRemaining()) {
            s = s * 6364136223846793005L + 1442695040888963407L
            bb.put((s ushr 33).toByte())
        }
        val totalMb = 256
        val file = File(context.cacheDir, "cinemaraw_spike.bin")
        val t0 = System.nanoTime()
        file.outputStream().buffered(1024 * 1024).use { out ->
            val bytes = ByteArray(1024 * 1024)
            repeat(totalMb) {
                chunk.rewind()
                chunk.get(bytes)
                out.write(bytes)
            }
        }
        val msWrite = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        file.outputStream().use { it.fd.sync() }
        val msSync = (System.nanoTime() - t1) / 1e6
        Log.i(
            TAG, "SPIKE storage cache write ${totalMb}MB ${"%.0f".format(msWrite)}ms " +
                "bw=${"%.0f".format(totalMb / msWrite * 1000)}MB/s fsyncExtra=${"%.0f".format(msSync)}ms"
        )
        file.delete()
        // MediaStore.Video probe: insert + write + unpend + delete, log-only.
        // NOTE: ".mcraw" has no registered MIME; the probe uses video/mp4 to
        // measure the MediaStore pipe. Final MIME/collection choice is a P3 item
        // (options: MediaStore.Video+video/mp4, or app-private file + share).
        try {
            val resolver = context.contentResolver
            val name = "RawLensSpike_probe.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RawLensSpike")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val t2 = System.nanoTime()
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            var probeBytes = 0L
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    val bytes = ByteArray(1024 * 1024)
                    repeat(32) {
                        chunk.rewind()
                        chunk.get(bytes)
                        out.write(bytes)
                        probeBytes += bytes.size
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                val msProbe = (System.nanoTime() - t2) / 1e6
                Log.i(
                    TAG, "SPIKE storage mediastore 32MB ${"%.0f".format(msProbe)}ms " +
                        "bw=${"%.0f".format(probeBytes / 1e6 / msProbe * 1000)}MB/s uri=$uri"
                )
                resolver.delete(uri, null, null)
            } else {
                Log.i(TAG, "SPIKE storage mediastore insert returned null")
            }
        } catch (e: Exception) {
            Log.i(TAG, "SPIKE storage mediastore probe skipped: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CinemaRawSpike"
    }
}
