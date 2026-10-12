// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Vulkan viewfinder recovery: after a wedged queue or lost device,
 * [VfVulkan.reinitNative] must tear down and rebuild the device so the next
 * frame re-probes a fresh device instead of failing on a dead one forever.
 * Asserts through the real JNI (including symbol linkage), with no camera.
 */
@RunWith(AndroidJUnit4::class)
class VfVulkanInstrumentedTest {
    @Test fun reinitRebuildsDeviceAndReimports() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spv = try {
            context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        assumeTrue("vf superpixel SPIR-V missing", spv != null)
        val init = VfVulkan.initNative(spv!!)
        assumeTrue("vulkan unavailable (${VfVulkan.describe(init)})", init == VfVulkan.OK)
        // Same export-buffer contract as RawViewfinder.runVulkanSuperpixel.
        // The emulator gralloc rejects GPU_DATA_BUFFER on RGBA_8888, so fall
        // back to a reduced-flags twin there (same reinit/reimport round
        // trip, only the allocation flags differ); skip only when neither
        // allocates at all. The native import path is identical either way.
        fun tryExport(usage: Long): HardwareBuffer? = try {
            HardwareBuffer.create(680, 510, HardwareBuffer.RGBA_8888, 1, usage)
        } catch (_: Exception) {
            null
        }
        val prodUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_DATA_BUFFER or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        val liteUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        var export = tryExport(prodUsage)
        var exportKind = "prod"
        if (export == null) {
            export = tryExport(liteUsage)
            exportKind = "lite"
        }
        assumeTrue("gralloc rejects RGBA_8888 export (prod+lite)", export != null)
        Log.i(TAG, "REINIT export=$exportKind")
        try {
            assertEquals(
                VfVulkan.OK,
                VfVulkan.ensureOutputNative(export!!)
            )
            // Teardown + rebuild on the same device must succeed exactly when
            // bring-up did: identical init path, no wedged state retained.
            assertEquals(VfVulkan.OK, VfVulkan.reinitNative(spv))
            // Imports were dropped by the reinit and must rebuild on demand.
            assertEquals(VfVulkan.OK, VfVulkan.ensureOutputNative(export))
        } finally {
            try { export?.close() } catch (_: Exception) {}
            VfVulkan.resetNative()
        }
    }

    /**
     * Direct-Log gate: grade init allocates the 3 recorder twin sets
     * (superpixel compute + grade). Regression: the compute pool held 3
     * STORAGE_BUFFER descriptors but each set needs 2, so set 1 failed and
     * every take aborted with "grade init failed" before the first frame
     * (the record preview never initialized either: it sits behind the
     * same gate). Ungated (no camera): Vulkan device + bundled SPIR-V only.
     */
    @Test fun gradeInitAllocatesRecorderTwins() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(name: String): ByteArray? = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        val spv = asset("shaders/vf/vf_superpixel.spv")
        val f16spv = asset("shaders/vf/vf_superpixel_f16.spv")
        val gradespv = asset("shaders/vf/vf_loggrade.spv")
        val yuvspv = asset("shaders/vf/vf_gradeyuv.spv")
        val pvspv = asset("shaders/vf/vf_p010preview.spv")
        assumeTrue(
            "record SPIR-V missing",
            spv != null && f16spv != null && gradespv != null && yuvspv != null && pvspv != null
        )
        val init = VfVulkan.initNative(spv!!)
        assumeTrue("vulkan unavailable (${VfVulkan.describe(init)})", init == VfVulkan.OK)
        try {
            assertEquals(VfVulkan.OK, VfVulkan.initF16Native(f16spv!!))
            val grade = VfLogGrade.initGradeNative(gradespv!!)
            assertEquals("grade init failed (${VfLogGrade.describe(grade)})", VfVulkan.OK, grade)
            assertEquals(VfVulkan.OK, VfLogGrade.initGradeYuvNative(yuvspv!!))
            assertEquals(VfVulkan.OK, VfRecordPreview.initPreviewNative(pvspv!!))
        } finally {
            VfVulkan.resetNative()
        }
    }

    /**
     * Synthetic end-to-end take: the Direct-Log start sequence from
     * [DirectLogRecorder.start] (superpixel + f16 + grade + grade-YUV +
     * record-preview inits, neutral LUT pre-warm), then 4 frames of
     * synthetic Bayer through the recorder's own submits —
     * [VfVulkan.computeSubmitNative] (GPU-copy fallback when the gralloc
     * input is unimportable, mirroring production) ->
     * [VfLogGrade.gradeYuvSubmitNative] -> [VfRecordPreview.previewSubmitNative]
     * — on the real Vulkan engine, with no camera. The fd twins run first
     * (production path); on drivers without semaphore-fd export they
     * return -1 side-effect-free and the blocking twins (identical pixels,
     * fence wait) run instead. Each frame's P010
     * center words, preview sample, and full FP16 export dump append to
     * a file that must grow every frame; the first frame proves the
     * record output exists, the preview count proves the monitor runs.
     * Content oracles are the GPU-visible outputs (preview sample, FP16
     * dump — hard mid-scale gates); the P010 CPU readback gates only when
     * the driver keeps that mapping coherent (software Vulkan does not,
     * while its GPU consumers read real data).
     */
    @Test fun syntheticRecordFirstFrameGrowsFile() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        assumeTrue("egl import unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(name: String): ByteArray? = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        val spv = asset("shaders/vf/vf_superpixel.spv")
        val f16spv = asset("shaders/vf/vf_superpixel_f16.spv")
        val gradespv = asset("shaders/vf/vf_loggrade.spv")
        val yuvspv = asset("shaders/vf/vf_gradeyuv.spv")
        val pvspv = asset("shaders/vf/vf_p010preview.spv")
        assumeTrue(
            "record SPIR-V missing",
            spv != null && f16spv != null && gradespv != null && yuvspv != null && pvspv != null
        )
        val init = VfVulkan.initNative(spv!!)
        assumeTrue("vulkan unavailable (${VfVulkan.describe(init)})", init == VfVulkan.OK)
        try {
            // Direct-log start (same order as the recorder).
            assertEquals(VfVulkan.OK, VfVulkan.initF16Native(f16spv!!))
            val grade = VfLogGrade.initGradeNative(gradespv!!)
            assertEquals("grade init failed (${VfLogGrade.describe(grade)})", VfVulkan.OK, grade)
            assertEquals(VfVulkan.OK, VfLogGrade.initGradeYuvNative(yuvspv!!))
            assertEquals(VfVulkan.OK, VfRecordPreview.initPreviewNative(pvspv!!))
            val lut = VfLogGrade.gradeLutUploadNative(VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST))
            assertEquals("lut upload failed (${VfVulkan.describe(lut)})", VfVulkan.OK, lut)

            // Synthetic sensor input, fresh buffer per frame like camera
            // frames (post-import CPU refills read stale on software
            // Vulkan: no cache maintenance for imported AHB memory, while
            // production never refills — each frame is a new HAL buffer).
            // R16 mosaic first, GPU BLOB twin when the gralloc has no R16,
            // RGBA_8888-backed mosaic last. The emulator rejects
            // GPU_DATA_BUFFER outright AND its Vulkan driver answers
            // INVALID_EXTERNAL_HANDLE for BLOB props, so the last-resort
            // input is an RGBA_8888 buffer (proven importable) filled with
            // the mosaic: fillBayerPattern(buf, 128, 192, f) writes
            // R16-view rows at byte y*S*2 (S = RGBA stride) while the
            // shader reads U16 rows at byte y*S*4 with pitch 2S, so shader
            // row y lands exactly on fill row 2y (even mosaic rows covering
            // the full frame, animated per frame) for any stride S >= 128.
            // The buffer import is format-agnostic (explicit pitch
            // addressing), so the bytes are addressed identically to a true
            // R16 mosaic.
            val rawW = 128
            val rawH = 96
            fun allocInput(frame: Int): Triple<HardwareBuffer?, Int, String> {
                var raw = VfEglImport.createBayerInput(rawW, rawH)
                var pitch = if (raw != null) VfEglImport.fillBayerPattern(raw, rawW, rawH, frame) else -1
                if (raw != null && pitch > 0) return Triple(raw, pitch, "r16")
                try { raw?.close() } catch (_: Exception) {}
                raw = VfEglImport.createBlobBayerInput(rawW, rawH)
                pitch = if (raw != null) VfEglImport.fillBlobBayer(raw, rawW, rawH) else -1
                if (raw != null && pitch > 0) return Triple(raw, pitch, "blob")
                try { raw?.close() } catch (_: Exception) {}
                raw = try {
                    HardwareBuffer.create(
                        rawW, rawH, HardwareBuffer.RGBA_8888, 1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                            HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                            HardwareBuffer.USAGE_CPU_READ_OFTEN
                    )
                } catch (_: Exception) {
                    null
                }
                val s = if (raw != null) VfEglImport.fillBayerPattern(raw, rawW, rawH * 2, frame) else -1
                pitch = if (s > 0) s * 2 else -1
                return Triple(raw, pitch, "rgba8")
            }
            val (probeRaw, probePitch, inputKind) = allocInput(0)
            try { probeRaw?.close() } catch (_: Exception) {}
            assumeTrue("no synthetic Bayer input allocates (r16+blob+rgba8)", probeRaw != null && probePitch > 0)
            Log.i(TAG, "SYNTH input=$inputKind ${rawW}x$rawH pitch=$probePitch")

            // Quarter-res FP16 export (superpixel step 2): production GPU
            // set first (CPU-readable for the full-dump proof when the
            // gralloc allows it), lite-flags twins for grallocs that reject
            // GPU_DATA_BUFFER (the reinit test proves such buffers still
            // import as Vulkan images on the emulator).
            val expW = rawW / 2
            val expH = rawH / 2
            fun tryExp(usage: Long): HardwareBuffer? = try {
                HardwareBuffer.create(expW, expH, HardwareBuffer.RGBA_FP16, 1, usage)
            } catch (_: Exception) {
                null
            }
            val gpuSet = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            val liteSet = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            var exp = tryExp(gpuSet or HardwareBuffer.USAGE_CPU_READ_OFTEN)
            var expKind = "gpu+cpu"
            if (exp == null) {
                exp = tryExp(gpuSet)
                expKind = "gpu"
            }
            if (exp == null) {
                exp = tryExp(liteSet or HardwareBuffer.USAGE_CPU_READ_OFTEN)
                expKind = "lite+cpu"
            }
            if (exp == null) {
                exp = tryExp(liteSet)
                expKind = "lite"
            }
            assumeTrue("RGBA_FP16 export unallocatable (all flag sets)", exp != null)
            val expBuf = exp!!
            val expCpu = expKind.endsWith("+cpu")
            // P010 staging: native GPU allocator first (production path),
            // then Java twins for grallocs without GPU_DATA_BUFFER. Note the
            // Vulkan driver only resolves AHBs carrying GPU usage (CPU-only
            // buffers answer INVALID_EXTERNAL_HANDLE), so the twins keep
            // GPU sampled/color usage and drop DATA_BUFFER only; every
            // submit validates strides, never flags.
            var p010 = VfEglImport.createP010(expW, expH)
            var p010Kind = "native"
            fun tryP010(usage: Long): HardwareBuffer? = try {
                HardwareBuffer.create(expW, expH, HardwareBuffer.YCBCR_P010, 1, usage)
            } catch (_: Exception) {
                null
            }
            if (p010 == null) {
                p010 = tryP010(
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                        HardwareBuffer.USAGE_CPU_READ_OFTEN or
                        HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                )
                p010Kind = "javalite"
            }
            if (p010 == null) {
                p010 = tryP010(
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_CPU_READ_OFTEN or
                        HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                )
                p010Kind = "javasampled"
            }
            assumeTrue("P010 staging allocate failed (native+javalite+javasampled)", p010 != null)
            val p010Buf = p010!!
            val layout = VfEglImport.describeP010(p010Buf)
            assumeTrue("P010 describe failed", layout != null)
            val lay = layout!!
            val pvDims = VfRecordPreview.previewDims(expW, expH)
            val box = VfRecordPreview.previewBox(expW, expH, pvDims[0], pvDims[1])
            fun tryPv(usage: Long): HardwareBuffer? = try {
                HardwareBuffer.create(pvDims[0], pvDims[1], HardwareBuffer.RGBA_8888, 1, usage)
            } catch (_: Exception) {
                null
            }
            val fullPv = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            val litePv = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            var preview = tryPv(fullPv)
            var pvKind = "full"
            if (preview == null) {
                preview = tryPv(litePv)
                pvKind = "lite"
            }
            assumeTrue("preview RGBA8 unallocatable (full+lite)", preview != null)
            val pvBuf = preview!!
            Log.i(TAG, "SYNTH exp=${expW}x$expH kind=$expKind " +
                "p010=$p010Kind yStride=${lay[0]} uvStride=${lay[1]} " +
                "preview=${pvDims[0]}x${pvDims[1]} kind=$pvKind box=$box")

            val outFile = File(context.cacheDir, "synth_record.bin")
            if (outFile.exists()) outFile.delete()
            val (_, gfparams) = VfLogGrade.packGrade(
                intArrayOf(expW, expH),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            var previewFrames = 0
            var computePath = "submit"
            var yuvPath = "fd"
            var pvPath = "fd"
            val sizes = mutableListOf<Long>()
            try {
                for (f in 0 until 4) {
                    val (frameRaw, framePitch, frameKind) = allocInput(f)
                    check(frameRaw != null && framePitch > 0) { "frame input alloc failed" }
                    check(frameKind == inputKind) { "input kind drifted: $frameKind" }
                    try {
                        val (iparams, fparams) = VfVulkan.packParams(
                            intArrayOf(0, 1, 2, 3), 0, 0, expW, expH, 2, framePitch,
                            floatArrayOf(0f, 0f, 0f, 0f), 1023f
                        )
                        val slot = f % 3
                        var cs = VfVulkan.computeSubmitNative(frameRaw, expBuf, iparams, fparams, slot, null, null)
                        if (cs != VfVulkan.OK) {
                            // Unimportable gralloc input (emulator): the
                            // production GPU-copy twin lock+memcpys instead.
                            computePath = "copy"
                            cs = VfVulkan.computeCopyNative(frameRaw, iparams, fparams)
                        }
                        check(cs == VfVulkan.OK) { "compute failed: ${VfVulkan.describe(cs)}" }
                        val yfd = VfLogGrade.gradeYuvSubmitNative(
                            expBuf, p010Buf,
                            intArrayOf(expW, expH), intArrayOf(expW, expH), gfparams,
                            intArrayOf(lay[0], lay[1], expW, expH), slot, 0
                        )
                        if (yfd >= 0) {
                            check(awaitFd(yfd)) { "grade-yuv completion timeout" }
                            VfEglImport.closeSyncFd(yfd)
                        } else {
                            check(yfd == -1) { "grade-yuv submit failed: $yfd" }
                            yuvPath = "blocking"
                            val yrc = VfLogGrade.gradeYuvBlockingNative(
                                expBuf, p010Buf,
                                intArrayOf(expW, expH), intArrayOf(expW, expH), gfparams,
                                intArrayOf(lay[0], lay[1], expW, expH), slot, 0
                            )
                            check(yrc == VfVulkan.OK) {
                                "grade-yuv blocking failed: ${VfVulkan.describe(yrc)} ($yrc)"
                            }
                        }
                        val words = VfEglImport.sampleP010Center(
                            p010Buf, lay[0], lay[1], expW, expH, expW / 2, expH / 2
                        )
                        check(words != null && words.size == 3) { "P010 readback failed" }
                        val pfd = VfRecordPreview.previewSubmitNative(
                            p010Buf, pvBuf,
                            intArrayOf(pvDims[0], pvDims[1]), intArrayOf(expW, expH),
                            intArrayOf(lay[0], lay[1], expW, expH), box, slot
                        )
                        if (pfd >= 0) {
                            check(awaitFd(pfd)) { "preview completion timeout" }
                            VfEglImport.closeSyncFd(pfd)
                        } else {
                            check(pfd == -1) { "preview submit failed: $pfd" }
                            pvPath = "blocking"
                            val prc = VfRecordPreview.previewBlockingNative(
                                p010Buf, pvBuf,
                                intArrayOf(pvDims[0], pvDims[1]), intArrayOf(expW, expH),
                                intArrayOf(lay[0], lay[1], expW, expH), box, slot
                            )
                            check(prc == VfVulkan.OK) {
                                "preview blocking failed: ${VfVulkan.describe(prc)} ($prc)"
                            }
                        }
                        previewFrames++
                        val rgba = VfEglImport.sampleRgba(pvBuf, pvDims[0], pvDims[1])
                        // Content sanity: the mosaic center is mid-gray, so
                        // the monitor sample must read mid-scale (hard gate:
                        // a black/broken grade fails here on every driver).
                        // The P010 CPU readback gates only when the driver
                        // shows it GPU writes (software Vulkan leaves the
                        // CPU mapping stale-zero while GPU consumers read
                        // real data — the preview below is then the oracle).
                        check(rgba != null) { "preview readback failed" }
                        check(rgba[0] in 0.05f..0.95f && rgba[1] in 0.05f..0.95f &&
                            rgba[2] in 0.05f..0.95f) {
                            "preview sample implausible: ${rgba.toList()}"
                        }
                        if (words[0] != 0 || words[1] != 0 || words[2] != 0) {
                            check(words[0] in 100..950 && words[1] in 64..960 && words[2] in 64..960) {
                                "P010 center implausible: ${words.toList()}"
                            }
                        } else {
                            Log.i(TAG, "SYNTH p010-cpu-stale (preview proves GPU-side content)")
                        }
                        var dumpMean = Float.NaN
                        var dumpMax = Float.NaN
                        FileOutputStream(outFile, true).use { fos ->
                            fos.write(
                                ("f$f yuv=${words[0]},${words[1]},${words[2]} " +
                                    "rgba=${rgba?.joinToString(",") ?: "null"}\n").toByteArray()
                            )
                            if (expCpu) {
                                val dump = VfEglImport.dumpFp16(expBuf, expW, expH)
                                if (dump != null) {
                                    var sum = 0.0
                                    var mx = 0f
                                    dump.forEach { v ->
                                        sum += v
                                        if (v > mx) mx = v
                                    }
                                    dumpMean = (sum / dump.size).toFloat()
                                    dumpMax = mx
                                    val bb = ByteBuffer.allocate(dump.size * 4)
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                    dump.forEach { bb.putFloat(it) }
                                    fos.write(bb.array())
                                }
                            }
                        }
                        if (expCpu) {
                            check(!dumpMean.isNaN()) { "FP16 dump failed" }
                            check(dumpMean in 0.05f..0.95f && dumpMax > 0.5f) {
                                "FP16 export implausible: mean=$dumpMean max=$dumpMax"
                            }
                        }
                        sizes.add(outFile.length())
                        Log.i(TAG, "SYNTH frame=$f path=$computePath/$yuvPath/$pvPath " +
                            "yuv=${words.toList()} rgba=${rgba?.toList()} " +
                            "dumpMean=$dumpMean dumpMax=$dumpMax bytes=${outFile.length()}")
                    } finally {
                        try { frameRaw?.close() } catch (_: Exception) {}
                    }
                }
            } finally {
                try { exp?.close() } catch (_: Exception) {}
                try { p010?.close() } catch (_: Exception) {}
                try { preview?.close() } catch (_: Exception) {}
            }
            assertEquals("no preview frames completed", 4, previewFrames)
            assertTrue("output file empty", outFile.length() > 0)
            for (i in 1 until sizes.size) {
                assertTrue("file did not grow at frame $i: $sizes", sizes[i] > sizes[i - 1])
            }
            Log.i(TAG, "SYNTH done path=$computePath/$yuvPath/$pvPath " +
                "bytes=${outFile.length()} sizes=$sizes")
        } finally {
            VfVulkan.resetNative()
        }
    }

    /** Poll a Vulkan-exported sync fd to completion (5s valve, probe only). */
    private fun awaitFd(fd: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (VfEglImport.pollSyncFd(fd)) return true
            Thread.sleep(2)
        }
        return false
    }

    @Test fun reinitRejectsMalformedSpirv() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        assertEquals(
            VfVulkan.BAD_ARGUMENT,
            VfVulkan.reinitNative(ByteArray(7))
        )
    }

    companion object {
        private const val TAG = "VfVulkanTest"
    }
}
