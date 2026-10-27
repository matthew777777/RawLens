// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.cos
import kotlin.math.sin

/**
 * .CRAW end to end through the app's JNI libraries, no camera needed:
 * recorder-shaped encode (N parallel workers, in-order commit with audio and
 * gyro), then decode every frame back through [CrawReader] bit-exact.
 *
 * Also the on-device performance gate: sustained encode throughput with the
 * recorder's worker model must reach 4080x3060 @ 30 fps (374 MPix/s) at
 * 12-bit. Results go to `files/craw-roundtrip.json` for the bench harness.
 * Options: `-e crawFrames 60`, `-e crawWorkers 4`, `-e crawMinMpix 374`.
 */
class CrawRoundTripDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun syntheticFrame(width: Int, height: Int, bits: Int, seed: Int): ByteBuffer {
        val rng = java.util.Random(seed.toLong())
        val max = (1 shl bits) - 1
        val buf = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val sb = buf.asShortBuffer()
        for (y in 0 until height) for (x in 0 until width) {
            val scene = 0.25 + 0.2 * sin((x + seed * 40) * 0.004) * cos(y * 0.006) + (if ((x and 1) == (y and 1)) 0.05 else 0.0)
            val v = (scene * max + rng.nextGaussian() * 4.0).toInt().coerceIn(0, max)
            sb.put(y * width + x, v.toShort())
        }
        return buf
    }

    @Test fun encodeCommitDecodeIsBitExactAndFastEnough() {
        assumeTrue("craw writer library unavailable", CrawWriter.available)
        assumeTrue("craw reader library unavailable", CrawReader.available)
        val width = 4080
        val height = 3060
        val bits = 12
        val frames = args.getString("crawFrames")?.toInt() ?: 60
        val workers = args.getString("crawWorkers")?.toInt() ?: 4
        val minMpix = args.getString("crawMinMpix")?.toDouble() ?: 374.0
        val sources = List(4) { syntheticFrame(width, height, bits, it) }
        val file = File(context.cacheDir, "craw-roundtrip.craw").apply { delete() }

        val handle = CrawWriter.open(file.absolutePath, "{\"sensor\":{\"width\":$width,\"height\":$height,\"bit_depth\":$bits,\"cfa\":\"RGGB\"}}")
        val pool = Executors.newFixedThreadPool(workers)
        val bound = CrawFormat.payloadBound(width, height)
        // Pooled payload buffers, as in RawVideoRecorder (no per-frame allocation).
        val buffers = java.util.concurrent.ArrayBlockingQueue<ByteBuffer>(workers * 2 + 1).apply {
            repeat(workers * 2 + 1) { add(ByteBuffer.allocateDirect(bound)) }
        }
        var payloadBytes = 0L
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            // Keep `workers` frames in flight; commit strictly in order, like RawVideoRecorder.
            val inflight = ArrayDeque<Pair<Int, Future<ByteBuffer>>>()
            fun submit(i: Int) {
                inflight.addLast(i to pool.submit<ByteBuffer> {
                    val src = sources[i % sources.size].duplicate()
                    val dst = buffers.take().apply { clear() }
                    val n = CrawWriter.encodeFrame(src, src.capacity(), width, height, width * 2,
                        0, height, dst, false, 0, bits)
                    dst.flip()
                    check(dst.remaining() == n)
                    dst
                })
            }
            var next = 0
            while (next < minOf(workers * 2, frames)) submit(next++)
            val gyroTs = LongArray(8)
            val gyroXyz = FloatArray(24) { it * 0.01f }
            // 32-bit float stereo, as RawVideoRecorder records it.
            val audio = ByteBuffer.allocateDirect(1600 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
            while (inflight.isNotEmpty()) {
                val (i, future) = inflight.removeFirst()
                val payload = future.get()
                payloadBytes += payload.remaining()
                val ts = (i + 1) * 33_333_333L
                CrawWriter.writeFrame(handle, payload, payload.remaining(), ts, "{\"iso\":100,\"frame\":$i}")
                for (k in 0 until 8) gyroTs[k] = ts + k * 4_000_000L
                CrawWriter.writeGyro(handle, gyroTs, gyroXyz, 8)
                audio.clear()
                CrawWriter.writeAudio(handle, audio, 1600, 2, ts, float = true)
                buffers.add(payload)
                if (next < frames) submit(next++)
            }
        } finally {
            pool.shutdown()
            CrawWriter.close(handle)
        }
        val seconds = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        val mpixPerS = width.toDouble() * height * frames / 1e6 / seconds
        val bpp = payloadBytes * 8.0 / (width.toDouble() * height * frames)
        assertEquals(frames, CrawFormat.closedFrameCount(file))

        // Decode every frame back and compare with its source.
        val reader = CrawReader.open(file.absolutePath)
        var decodeNs = 0L
        try {
            val timestamps = CrawReader.frameTimestamps(reader)!!
            assertEquals(frames, timestamps.size)
            val dst = ByteBuffer.allocateDirect(width * height * 2)
            for ((i, ts) in timestamps.withIndex()) {
                dst.clear()
                val d0 = SystemClock.elapsedRealtimeNanos()
                val (w, h) = CrawReader.decodeFrame(reader, ts, dst)
                decodeNs += SystemClock.elapsedRealtimeNanos() - d0
                assertEquals(width to height, w to h)
                if (i < sources.size || i == frames - 1) {
                    val expect = ByteArray(width * height * 2).also { sources[i % sources.size].duplicate().get(it) }
                    val got = ByteArray(width * height * 2).also { dst.flip(); dst.get(it) }
                    assertArrayEquals("frame $i differs", expect, got)
                }
            }
            assertTrue(CrawReader.containerMetadata(reader)!!.contains("\"bit_depth\":$bits"))
        } finally {
            CrawReader.closeReader(reader)
        }
        val decodeMpix = width.toDouble() * height * frames / 1e6 / (decodeNs / 1e9)
        val json = "{\"device\":\"${android.os.Build.MODEL}\",\"soc\":\"${android.os.Build.HARDWARE}\"," +
            "\"width\":$width,\"height\":$height,\"bits\":$bits,\"frames\":$frames,\"workers\":$workers," +
            "\"encode_mpix_per_s\":${"%.1f".format(mpixPerS)},\"fps\":${"%.2f".format(frames / seconds)}," +
            "\"decode_mpix_per_s\":${"%.1f".format(decodeMpix)},\"bpp\":${"%.3f".format(bpp)}," +
            "\"file_mb\":${"%.1f".format(file.length() / 1e6)}}"
        File(context.filesDir, "craw-roundtrip.json").writeText(json + "\n")
        android.util.Log.i("CrawRoundTrip", json)
        file.delete()
        assertTrue("encode $mpixPerS MPix/s < required $minMpix", mpixPerS >= minMpix)
    }

    /**
     * Independent reference of the RGB binning (craw_writer_jni.cpp binRgb),
     * returned as the cell frame: (0,0) R, (0,1) G, (1,0) B, (1,1) 0.
     */
    private fun binReference(src: ShortArray, w: Int, h: Int, cfa: Int): IntArray {
        val ow = w / 2; val oh = h / 2
        val greenEven = cfa == 1 || cfa == 2
        val rPos = arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1))[cfa]
        val bPos = arrayOf(intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(0, 0))[cfa]
        fun m(i: Int, n: Int) = if (i < 0) -i else if (i >= n) 2 * (n - 1) - i else i
        fun at(x: Int, y: Int) = src[m(y, h) * w + m(x, w)].toInt() and 0xffff
        fun diff(x: Int, y: Int): Int {   // 4 x (C - edge-directed green)
            val c = at(x, y); val l = at(x - 1, y); val r = at(x + 1, y); val u = at(x, y - 1); val d = at(x, y + 1)
            val dh = 2 * kotlin.math.abs(l - r) + kotlin.math.abs(2 * c - at(x - 2, y) - at(x + 2, y))
            val dv = 2 * kotlin.math.abs(u - d) + kotlin.math.abs(2 * c - at(x, y - 2) - at(x, y + 2))
            val g2 = if (dh < dv) 2 * (l + r) else if (dv < dh) 2 * (u + d) else l + r + u + d
            return 4 * c - g2
        }
        val white = 1023; val clip = white - white / 20
        val out = IntArray(w * h)
        for (j in 0 until oh) for (i in 0 until ow) {
            val sx = 2 * i; val sy = 2 * j
            val gin = if (greenEven) at(sx, sy) + at(sx + 1, sy + 1) else at(sx + 1, sy) + at(sx, sy + 1)
            val ring = if (greenEven) at(sx + 1, sy - 1) + at(sx - 1, sy + 1) + at(sx + 2, sy) + at(sx, sy + 2)
                else at(sx - 1, sy) + at(sx, sy - 1) + at(sx + 2, sy + 1) + at(sx + 1, sy + 2)
            val g16 = 6 * gin + ring
            var near = false
            for (bj in maxOf(j - 1, 0)..minOf(j + 1, oh - 1)) for (bi in maxOf(i - 1, 0)..minOf(i + 1, ow - 1))
                for (k in 0 until 4) if (at(2 * bi + (k and 1), 2 * bj + (k shr 1)) >= clip) near = true
            fun channel(p: IntArray): Int {
                val x = sx + p[0]; val y = sy + p[1]
                // Same-colour neighbours toward the block centre, mirrored on the site grid.
                val tx = 2 * m(i + (if (p[0] == 0) 1 else -1), ow) + p[0]
                val ty = 2 * m(j + (if (p[1] == 0) 1 else -1), oh) + p[1]
                val v = if (near) (9 * at(x, y) + 3 * at(tx, y) + 3 * at(x, ty) + at(tx, ty) + 8) shr 4
                    else (4 * g16 + 9 * diff(x, y) + 3 * diff(tx, y) + 3 * diff(x, ty) + diff(tx, ty) + 32) shr 6
                return v.coerceIn(0, white)
            }
            out[sy * w + sx] = channel(rPos)
            out[sy * w + sx + 1] = (g16 + 8) shr 4
            out[(sy + 1) * w + sx] = channel(bPos)
        }
        return out
    }

    /** Independent reference of the cheap centre-aligned CFA binning (craw_writer_jni.cpp). */
    private fun binCentredReference(src: ShortArray, w: Int, h: Int, cfa: Int): IntArray {
        val ow = w / 2; val oh = h / 2
        val greenEven = cfa == 1 || cfa == 2
        fun m(i: Int, n: Int) = if (i < 0) -i else if (i >= n) 2 * (n - 1) - i else i
        fun at(x: Int, y: Int) = src[m(y, h) * w + m(x, w)].toInt() and 0xffff
        val kw = intArrayOf(1, 5, 3)
        return IntArray(ow * oh) { idx ->
            val ox = idx % ow; val oy = idx / ow; val sx = 2 * ox; val sy = 2 * oy
            val green = (((ox + oy) and 1) == 0) == greenEven
            if (!green) {
                val cx = sx + (ox and 1); val cy = sy + (oy and 1)
                val dx = if ((ox and 1) == 1) -2 else 2; val dy = if ((oy and 1) == 1) -2 else 2
                var sum = 0
                for (j in 0 until 3) for (i in 0 until 3) sum += kw[i] * kw[j] * at(cx + (i - 1) * dx, cy + (j - 1) * dy)
                (sum + 40) / 81
            } else if (!greenEven) {
                (2 * (at(sx + 1, sy) + at(sx, sy + 1)) + at(sx - 1, sy) + at(sx, sy - 1) + at(sx + 2, sy + 1) + at(sx + 1, sy + 2) + 4) shr 3
            } else {
                (2 * (at(sx, sy) + at(sx + 1, sy + 1)) + at(sx + 1, sy - 1) + at(sx - 1, sy + 1) + at(sx + 2, sy) + at(sx, sy + 2) + 4) shr 3
            }
        }
    }

    private fun encodeDecodeBinned(src: ByteBuffer, width: Int, height: Int, cfa: Int, rgb: Boolean = true): IntArray {
        val file = File(context.cacheDir, "craw-binned.craw").apply { delete() }
        val handle = CrawWriter.open(file.absolutePath, "{}")
        val dst = ByteBuffer.allocateDirect(CrawFormat.payloadBound(width, height))
        val n = CrawWriter.encodeFrame(src, src.capacity(), width, height, width * 2, 0, height, dst, true, cfa, 10, CrawWriter.CODEC_LIVE, rgb)
        dst.flip()
        CrawWriter.writeFrame(handle, dst, n, 1L, "{}")
        CrawWriter.close(handle)
        val reader = CrawReader.open(file.absolutePath)
        try {
            val ts = CrawReader.frameTimestamps(reader)!!.single()
            val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
            val (ow, oh) = if (rgb) width to height else width / 2 to height / 2  // RGB: cell frame
            assertEquals(ow to oh, CrawReader.decodeFrame(reader, ts, out))
            return IntArray(ow * oh) { out.getShort(it * 2).toInt() and 0xffff }
        } finally {
            CrawReader.closeReader(reader)
            file.delete()
        }
    }

    @Test fun cheapBinnedFrameIsCentreAlignedForEveryCfa() {
        assumeTrue("craw libraries unavailable", CrawWriter.available && CrawReader.available)
        val width = 64
        val height = 32
        val src = syntheticFrame(width, height, 10, 7)
        val shorts = ShortArray(width * height).also { src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        for (cfa in 0..3) {
            assertArrayEquals("cfa $cfa", binCentredReference(shorts, width, height, cfa),
                encodeDecodeBinned(src, width, height, cfa, rgb = false))
        }
    }

    @Test fun binnedFrameIsRgbForEveryCfa() {
        assumeTrue("craw libraries unavailable", CrawWriter.available && CrawReader.available)
        val width = 64
        val height = 32
        val src = syntheticFrame(width, height, 10, 7)
        val shorts = ShortArray(width * height).also { src.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        for (cfa in 0..3) {
            assertArrayEquals("cfa $cfa", binReference(shorts, width, height, cfa), encodeDecodeBinned(src, width, height, cfa))
        }
        // Flat field passes exactly: R = G = B = 417, unused cell 0.
        val flat = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { flat.putShort(it * 2, 417.toShort()) }
        val cells = encodeDecodeBinned(flat, width, height, 0)
        assertTrue(cells.withIndex().all { (k, v) -> v == if ((k / width) % 2 == 1 && k % 2 == 1) 0 else 417 })
    }

    @Test fun lensShadingChunkExpandsIntoFrameJson() {
        assumeTrue("craw libraries unavailable", CrawWriter.available && CrawReader.available)
        val width = 32
        val height = 16
        val src = syntheticFrame(width, height, 10, 3)
        val file = File(context.cacheDir, "craw-shading.craw").apply { delete() }
        val grid = CrawLensShading.Grid(3, 2, FloatArray(24) { 1f + it * 0.125f })
        val handle = CrawWriter.open(file.absolutePath, "{}")
        val dst = ByteBuffer.allocateDirect(CrawFormat.payloadBound(width, height))
        val n = CrawWriter.encodeFrame(src, src.capacity(), width, height, width * 2, 0, height, dst, false, 0, 10)
        dst.flip()
        CrawWriter.writeShading(handle, 1L, 1, grid)
        CrawWriter.writeFrame(handle, dst, n, 1L, "{\"iso\":50,\"lshd_ref\":1}")
        CrawWriter.close(handle)
        val reader = CrawReader.open(file.absolutePath)
        try {
            val ts = CrawReader.frameTimestamps(reader)!!.single()
            val json = org.json.JSONObject(CrawReader.frameMetadata(reader, ts)!!)
            assertEquals(3, json.getInt("lensShadingMapWidth"))
            assertEquals(2, json.getInt("lensShadingMapHeight"))
            val planes = json.getJSONArray("lensShadingMap")
            // Plane ch holds gains[i * 4 + ch], i.e. colour planes R, Gr, Gb, B.
            for (ch in 0 until 4) for (i in 0 until 6)
                assertEquals(grid.gains[i * 4 + ch].toDouble(), planes.getJSONArray(ch).getDouble(i), 1e-6)
            assertEquals(width, json.getInt("width"))
        } finally {
            CrawReader.closeReader(reader)
            file.delete()
        }
    }
}
