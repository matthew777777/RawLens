// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measured merge footprint (MFSR sharp merge / HDR+) on a real burst take,
 * for the low-memory redesign: peak RSS, PSS and graphics above the
 * pre-merge baseline, sampled every 50 ms, plus a SHA-256 and a dump of the
 * merged output for host-side parity.
 *
 * Opt-in: `-e mergeMem mfsr|hdrplus -e mergeTake /data/local/tmp/merge-mem/X_BURST.craw`
 * (`-e mergeFrames N` limits the pool, `-e mergeDump false` skips the dump).
 */
@RunWith(AndroidJUnit4::class)
class MergeMemoryDeviceTest {
    private data class Peak(var rssKb: Long = 0, var pssKb: Long = 0, var gfxKb: Long = 0, var nativeKb: Long = 0)

    private fun rssKb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.get(1)?.toLong() ?: -1

    private fun sample(peak: Peak, full: Boolean) {
        peak.rssKb = maxOf(peak.rssKb, rssKb())
        peak.nativeKb = maxOf(peak.nativeKb, Debug.getNativeHeapAllocatedSize() / 1024)
        if (full) {
            val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            peak.pssKb = maxOf(peak.pssKb, info.totalPss.toLong())
            val gfx = info.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0
            peak.gfxKb = maxOf(peak.gfxKb, gfx)
        }
    }

    private fun <T> measured(label: String, block: () -> T): T {
        System.gc(); Thread.sleep(300)
        val base = Peak().also { sample(it, true) }
        val peak = base.copy()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        var tick = 0
        val sampler = Thread {
            while (running.get()) {
                // Debug.getMemoryInfo is ~30-80 ms; RSS every tick, full every 4th.
                sample(peak, tick++ % 4 == 0)
                Thread.sleep(50)
            }
        }.apply { start() }
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            running.set(false)
            sampler.join()
            sample(peak, true)
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "MERGEMEM[$label] wall=${ms}ms " +
                "rss ${base.rssKb / 1024}->${peak.rssKb / 1024}MB (+${(peak.rssKb - base.rssKb) / 1024}) " +
                "pss ${base.pssKb / 1024}->${peak.pssKb / 1024}MB (+${(peak.pssKb - base.pssKb) / 1024}) " +
                "gfx ${base.gfxKb / 1024}->${peak.gfxKb / 1024}MB (+${(peak.gfxKb - base.gfxKb) / 1024}) " +
                "native ${base.nativeKb / 1024}->${peak.nativeKb / 1024}MB (+${(peak.nativeKb - base.nativeKb) / 1024})")
        }
    }

    private fun digest(bytes: ByteBuffer): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dup = bytes.duplicate().apply { rewind() }
        val chunk = ByteArray(1 shl 20)
        while (dup.hasRemaining()) {
            val n = minOf(chunk.size, dup.remaining())
            dup.get(chunk, 0, n); md.update(chunk, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun dump(name: String, bytes: ByteBuffer, header: IntArray) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), name)
        out.outputStream().channel.use { ch ->
            val h = ByteBuffer.allocate(4 * header.size).order(ByteOrder.LITTLE_ENDIAN)
            header.forEach { h.putInt(it) }; h.flip(); ch.write(h)
            ch.write(bytes.duplicate().apply { rewind() })
        }
        Log.i(TAG, "dumped ${out.absolutePath}")
    }

    @Test fun mergeFootprint() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("mergeMem")
        assumeTrue("pass -e mergeMem mfsr|hdrplus", mode == "mfsr" || mode == "hdrplus")
        val take = File(args.getString("mergeTake") ?: "")
        assumeTrue("pass -e mergeTake <readable _BURST.craw>", take.canRead())
        val maxFrames = args.getString("mergeFrames")?.toIntOrNull() ?: 16
        val doDump = args.getString("mergeDump") != "false"
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val handle = CrawReader.open(take.absolutePath)
        val nativeBufs = ArrayList<ByteBuffer>()
        try {
            val timestamps = checkNotNull(CrawReader.frameTimestamps(handle)).take(maxFrames)
            val containerJson = checkNotNull(CrawReader.containerMetadata(handle))
            val frames = timestamps.mapIndexed { i, ts ->
                val json = org.json.JSONObject(checkNotNull(CrawReader.frameMetadata(handle, ts)))
                val w = json.getInt("width"); val h = json.getInt("height")
                val buf = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                nativeBufs += buf
                val off = buf.position()
                val written = CrawReader.decodeFrame(handle, ts, buf, off, buf.remaining())
                check(written >= 0) { "decode failed $written" }
                BurstMergeRunner.prepareDecodedPixels(buf, off, written, w.toLong() * h * 2)
                val metadata = OfflineBurstMetadata.synthesize(containerJson, json.toString(), i)
                OfflineBurstFrame(buf, w, h, w * 2, ts, metadata.exposureTimeNanos ?: 0L,
                    metadata.sensitivityIso ?: 0, metadata, OfflineBurstMetadata.normalizationFor(metadata))
            }
            Log.i(TAG, "take ${take.name}: ${frames.size} frames ${frames[0].width}x${frames[0].height}")
            when (mode) {
                "mfsr" -> {
                    val ordered = checkNotNull(MfsrMerge.prepareDecoded(frames))
                    val merged = measured("mfsr ${frames[0].width}x${frames[0].height} N=${ordered.size}") {
                        MfsrMerge.mergeDecoded(context, ordered, MfsrSettings())
                    }
                    checkNotNull(merged) { "MFSR merge returned null" }
                    try {
                        Log.i(TAG, "MERGEMEM[mfsr] sha=${digest(merged.rgb)} ${merged.width}x${merged.height}")
                        if (doDump) dump("mergemem-mfsr.f32", merged.rgb, intArrayOf(merged.width, merged.height, 3))
                    } finally { merged.release() }
                }
                "hdrplus" -> {
                    val processor = HdrPlusMerge(context)
                    val inputs = frames.map { frame ->
                        HdrPlusMerge.FrameInput(
                            image = null,
                            layout = RawPlaneLayout(frame.width, frame.height, frame.rowStride, 2, 0, 0),
                            crop = RawCrop(0, 0, frame.width, frame.height),
                            normalization = frame.normalization,
                            timestampNanos = frame.timestampNanos,
                            gyroRadiansPerSecond = Float.NaN,
                            sensitivityIso = frame.sensitivityIso,
                            pixels = frame.pixels
                        )
                    }
                    val ref = inputs.size / 2
                    val merged = measured("hdrplus ${frames[0].width}x${frames[0].height} N=${inputs.size}") {
                        processor.merge(inputs, ref, HdrPlusSettings(),
                            noiseSource = RawNoiseModel.Source.of(frames[ref].metadata))
                    }
                    checkNotNull(merged) { "HDR+ merge returned null" }
                    val bb = ByteBuffer.allocateDirect(merged.values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(merged.values)
                    Log.i(TAG, "MERGEMEM[hdrplus] sha=${digest(bb)} ${merged.width}x${merged.height} " +
                        "decision=${processor.lastDecision?.logLine(inputs.size)}")
                    if (doDump) dump("mergemem-hdrplus.f32", bb, intArrayOf(merged.width, merged.height, 1))
                }
            }
        } finally {
            nativeBufs.forEach { runCatching { HdrPlusVulkan.freeBuffer(it) } }
            runCatching { CrawReader.closeReader(handle) }
        }
    }

    private companion object { const val TAG = "MergeMemDevice" }
}
