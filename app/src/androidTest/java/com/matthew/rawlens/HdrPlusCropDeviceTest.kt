// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.DngRead
import java.io.File
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HDR+ burst crop through [HdrPlusVulkan] on-device (Fast + HQ).
 *
 * Reads the 8-frame burst at /data/local/tmp/sr-burst, center-crops each
 * frame (default 1528,1018,1024,1024, even-aligned so the Bayer phase is
 * preserved), merges Fast then HQ, and logs wall + gpu + heaps per path.
 * Raw float outputs land in the app external dir for host pull + compare
 * against the desktop runner and the CPU reference.
 *
 * Opt-in: `-e rawlensHdrCrop true` (+ optional `-e rawlensHdrCropRect
 * x,y,w,h`, `-e rawlensHdrHq only|fast|both`).
 */
@RunWith(AndroidJUnit4::class)
class HdrPlusCropDeviceTest {
    @Test fun burstCropMergesOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensHdrCrop true to run the on-device HDR+ burst crop",
            args.getString("rawlensHdrCrop") == "true",
        )
        try {
            SrpBridge.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("libsrpipeline.so unavailable: ${e.message}", false)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val burstDir = File("/data/local/tmp/sr-burst")
        val listing = burstDir.list() ?: emptyArray()
        Log.i(TAG, "burst dir lists ${listing.size}: " + listing.sorted().take(10).joinToString(","))
        val frames = listing.filter { it.lowercase().endsWith(".dng") }
            .map { File(burstDir, it) }.filter { it.isFile }.sortedBy { it.name }
        assumeTrue("push 8 burst DNGs to ${burstDir.absolutePath} first", frames.size == 8)
        val rect = args.getString("rawlensHdrCropRect")
            ?.split(",")?.map { it.trim().toInt() }
            ?: listOf(1528, 1018, 1024, 1024)
        require(rect.size == 4 && rect[0] >= 0 && rect[1] >= 0 && rect[2] > 0 && rect[3] > 0 &&
            rect.all { it % 2 == 0 }) {
            "rawlensHdrCropRect must hold even x,y,w,h with non-negative x,y and positive w,h"
        }
        val (cx, cy, cw, ch) = rect
        val which = args.getString("rawlensHdrHq") ?: "both"
        require(which in setOf("fast", "only", "both")) { "rawlensHdrHq must be fast|only|both" }

        val metas = frames.map { DngRead.readMeta(it.absolutePath) }
        val m0 = metas[0]
        require(metas.all { it.width == m0.width && it.height == m0.height }) {
            "burst geometry mismatch"
        }
        require(cx + cw <= m0.width && cy + ch <= m0.height) {
            "crop $cx,$cy,$cw,$ch exceeds ${m0.width}x${m0.height}"
        }
        Log.i(TAG, "crop $cx,$cy,${cw}x$ch full ${m0.width}x${m0.height} " +
            "black=${m0.black.toList()} white=${m0.white}")

        val tInit0 = System.nanoTime()
        val handle = HdrPlusVulkan.init(context.assets, null)
        val initS = (System.nanoTime() - tInit0) / 1e9
        Log.i(TAG, "init %.1fs shaders=%d/%d".format(
            initS, HdrPlusVulkan.loadedShaderCount(handle), HdrPlusVulkan.expectedShaderCount()))
        try {
            assumeTrue("HDR+ shaders incomplete", HdrPlusVulkan.loadedShaderCount(handle) ==
                HdrPlusVulkan.expectedShaderCount())
            // Decode regions to ART buffers, then repack into malloc-backed
            // buffers (the app path: ART buffers would charge Java heap).
            val packed = metas.mapIndexed { i, meta ->
                val region = DngRead.decodeRegion(frames[i].absolutePath, meta, cx, cy, cw, ch)
                val malloc = HdrPlusVulkan.allocBuffer(cw.toLong() * ch * 2)
                    .order(ByteOrder.nativeOrder())
                region.rewind()
                malloc.put(region)
                malloc.rewind()
                malloc
            }
            try {
                val blacks = FloatArray(metas.size * 4)
                val whites = FloatArray(metas.size)
                metas.forEachIndexed { i, meta ->
                    for (k in 0..3) blacks[i * 4 + k] = meta.black[k].toFloat()
                    whites[i] = meta.white.toFloat()
                }
                val refIndex = metas.size / 2
                // App-writable external dir (/data/local/tmp is not
                // writable from the app sandbox on HyperOS: EACCES).
                // Pull the outputs right after the run, before any
                // reinstall wipes this dir.
                val outDir = context.getExternalFilesDir(null) ?: context.filesDir
                fun runPath(hq: Boolean, tag: String) {
                    val output = HdrPlusVulkan.allocBuffer(cw.toLong() * ch * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                    try {
                        val rt = Runtime.getRuntime()
                        val t0 = System.nanoTime()
                        val gpuMs = HdrPlusVulkan.merge(
                            handle, packed.toTypedArray(), blacks, whites, refIndex,
                            IntArray(0), 13f, null, null, 32, 64, hq, true, cw, ch, output,
                        )
                        val wallS = (System.nanoTime() - t0) / 1e9
                        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
                        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1048576.0
                        val lastGpu = HdrPlusVulkan.lastGpuMs(handle)
                        Log.i(TAG, "$tag ${cw}x$ch N=${packed.size} ref=$refIndex " +
                            "wall=%.1fs gpu=%.1fms lastGpu=%.1fms javaHeap=%.0fMB nativeHeap=%.0fMB".format(
                                wallS, gpuMs, lastGpu, javaMb, nativeMb))
                        output.rewind()
                        val values = FloatArray(cw * ch)
                        output.asFloatBuffer().get(values)
                        var nonFinite = 0
                        for (v in values) if (!v.isFinite()) nonFinite++
                        assertTrue("$tag output has $nonFinite non-finite samples", nonFinite == 0)
                        val out = File(outDir, "hdrplus-crop-$tag-f32.bin")
                        out.outputStream().buffered().use { os ->
                            val bb = java.nio.ByteBuffer.allocate(1 shl 20)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val fb = bb.asFloatBuffer()
                            var pos = 0
                            while (pos < values.size) {
                                fb.clear()
                                val n = minOf(fb.capacity(), values.size - pos)
                                fb.put(values, pos, n)
                                os.write(bb.array(), 0, n * 4)
                                pos += n
                            }
                        }
                        Log.i(TAG, "$tag wrote ${out.absolutePath} (${out.length()} bytes)")
                    } finally {
                        runCatching { HdrPlusVulkan.freeBuffer(output) }
                    }
                }
                if (which == "both" || which == "fast") runPath(false, "fast")
                if (which == "both" || which == "only") runPath(true, "hq")
            } finally {
                packed.forEach { runCatching { HdrPlusVulkan.freeBuffer(it) } }
            }
        } finally {
            HdrPlusVulkan.release(handle)
        }
    }

    companion object {
        private const val TAG = "HdrPlusCropDevice"
    }
}
