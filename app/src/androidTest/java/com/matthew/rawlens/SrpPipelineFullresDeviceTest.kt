// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.NoiseLut
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-res burst through the app-integrated pipeline
 * ([VulkanPipeline.run] from the shared tools module, same config
 * recipe as the desktop CLI: full 3060x4080 frames, scale 1, LUT on).
 * Frame count via `-e rawlensSrpCount N` (default: all pushed DNGs,
 * cycling the burst in sorted order when N exceeds it); writes
 * `merged-device-fullres[N].dng` to the
 * app external dir. Pull it and compare against the desktop reference
 * run over the same cycled sequence under the envelope gate. Test
 * asserts rc==0s + output exists + logs wall/heap; pixel identity is
 * checked on the host (no multi-MB blob in test output).
 *
 * Burst + LUT must be pushed beforehand to /data/local/tmp/sr-burst
 * (world-readable, survives app reinstalls).
 *
 * Opt-in: `-e rawlensSrpFullres true`.
 */
@RunWith(AndroidJUnit4::class)
class SrpPipelineFullresDeviceTest {
    private fun assetBytes(name: String): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.assets.open("sr_spv/$name.spv").use { it.readBytes() }
    }

    @Test fun burstFullresMergesOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensSrpFullres true to run the on-device full-res burst",
            args.getString("rawlensSrpFullres") == "true",
        )
        try {
            SrpBridge.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("libsrpipeline.so unavailable: ${e.message}", false)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Phase heap + stage clock: the live-set curve is load-bearing
        // under the 512 MB ceiling (set before first VulkanPipeline use).
        System.setProperty("srpipeline.profile", "true")
        // Burst dir + frame count are runner args so proofs are not tied
        // to one staging dir (`-e rawlensSrpDir /data/local/tmp/...`,
        // `-e rawlensSrpCount N`); the LUT is the single `*.npz` in the
        // dir, validated against the burst like the desktop CLI does.
        val burstDir = File(args.getString("rawlensSrpDir") ?: "/data/local/tmp/sr-burst")
        val listing = burstDir.list() ?: emptyArray()
        Log.i("SrpFullresDevice", "burst dir ${burstDir.absolutePath} lists ${listing.size}: " +
            listing.sorted().take(10).joinToString(","))
        val frames = listing.filter { it.lowercase().endsWith(".dng") }
            .map { File(burstDir, it) }.filter { it.isFile }.sortedBy { it.name }
        // Frame count (default: all pushed DNGs): longer proofs cycle
        // the burst in sorted order (frame[i] = frames[i % size]), so
        // the sequence matches a desktop dir with the same cycling.
        // Memory is per-frame steady, so count costs wall time, not heap.
        val count = args.getString("rawlensSrpCount")?.toIntOrNull() ?: frames.size
        require(count in 2..64) { "rawlensSrpCount must hold 2..64 frames" }
        assumeTrue("push burst DNGs to ${burstDir.absolutePath} first", frames.isNotEmpty())
        val seq = (0 until count).map { frames[it % frames.size] }
        Log.i("SrpFullresDevice", "frame count $count (cycled over ${frames.size})")
        val lutFiles = listing.filter { it.lowercase().endsWith(".npz") }
            .map { File(burstDir, it) }.filter { it.isFile }
        assumeTrue("push exactly one noise LUT (*.npz) next to the burst first",
            lutFiles.size == 1)
        val lutFile = lutFiles[0]
        val outDir = context.getExternalFilesDir(null)
            ?: context.filesDir

        val t0 = System.nanoTime()
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            Log.i("SrpFullresDevice", "device: ${SrpBridge.nativeDeviceInfo(handle)}")
            val refPath = seq[0].absolutePath
            val refMeta = DngRead.readMeta(refPath)
            // The reference decodes through a temp (dead before the
            // pipeline's init window); the shared decode staging is
            // allocated lazily by the first comparison thunk, so it is
            // not live during init either. Every decode fully rewrites
            // the buffer, so reuse is aliasing-safe.
            var refBox: Array<FloatArray>? =
                DngRead.readNormalized(refPath, refMeta)
            // Frames decode into the pipeline-lent staging (same bytes
            // as a private buffer): no 50 MB decode staging lives
            // alongside the loop state.
            val compImgs = seq.drop(1).map { f ->
                { stage: java.nio.ByteBuffer ->
                    DngRead.readNormalizedInto(f.absolutePath, DngRead.readMeta(f.absolutePath), stage)
                }
            }
            val h = refBox!!.size
            val w = refBox!![0].size
            val (alpha, beta) = DngRead.noiseAlphaBeta(refMeta.noise, refMeta.noiseCount)
            // -e rawlensSrpLut gen: the in-app generator (cached per
            // profile, as MfsrMerge) instead of the pushed Python LUT.
            val curves = if (args.getString("rawlensSrpLut") == "gen") {
                val t0 = android.os.SystemClock.elapsedRealtime()
                NoiseLutGen.cached(java.io.File(context.cacheDir, "noise_lut"), alpha, beta).also {
                    Log.i("SrpFullresDevice", "noise LUT gen ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                }
            } else NoiseLut.load(lutFile.absolutePath, alpha, beta)
            val snr = BurstConfig.estimateSnr(refBox!!, alpha, beta)
            val fin = BurstConfig.finalize(snr)
            val factors = listOf(1, 2, 4, 4)
            BurstConfig.sanitize(1f, fin.tileSize, factors, fin.tileSizes, h, w)
            val config = PipelineConfig(
                scale = 1f, bayer = true, factors = factors, tileSizes = fin.tileSizes,
                kernelParams = KernelParams(
                    kDetail = fin.kDetail.toFloat(), kDenoise = fin.kDenoise.toFloat(),
                    dTh = fin.dTh.toFloat(), dTr = fin.dTr.toFloat()),
                kernelPreprocessing = "gat",
                kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
                kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(noiseCorrection = true),
                robustnessEnabled = true,
                sigmaSqCurve = curves.sigmaSq, dSqCurve = curves.dSq,
                icaIterations = 3,
                // Collect after every frame: GC timing must not decide
                // survival at the 512 MB ceiling (values-identical).
                memoryTight = true,
                // -e rawlensSrpKernel kernelnet|wronski (default wronski);
                // the merge input IS the full frame here.
                kernelNet = if (args.getString("rawlensSrpKernel") != "kernelnet") null else {
                    val (fx, fy) = KernelNet.rggbFlips(refMeta.cfa)
                    KernelNetConfig(
                        weights = KernelNet.bundledWeights(),
                        sigma = KernelNet.sigma(
                            FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                        flipX = fx, flipY = fy)
                },
            )
            Log.i("SrpFullresDevice", "fullres ${w}x$h snr=%.2f tile=%d".format(snr, fin.tileSize))
            val fft = assetBytes("fft")
            // The thunk hands the decoded reference to the pipeline and
            // releases it: the 50 MB frame dies with the ref phase.
            val refThunk = {
                val r = refBox!!
                refBox = null
                r
            }
            val rgb = VulkanPipeline.run(
                refThunk, compImgs, config, handle,
                AlignGpu.Spirvs(
                    align = assetBytes("align"), corr = assetBytes("corr"),
                    fft = fft, ica = assetBytes("ica"), corrDirect = assetBytes("corr_direct"), icaWg = assetBytes("ica_wg")),
                VulkanPipeline.Spirvs(
                    gat = assetBytes("gat"), grad = assetBytes("grad"),
                    kernels = assetBytes("kernels"), divide = assetBytes("divide"),
                    robust = assetBytes("robustness"),
                    mergeSplat = assetBytes("merge_splat"),
                    fft = fft, corr = assetBytes("corr"), greyFft = assetBytes("grey_fft"),
                    kernelNet = if (args.getString("rawlensSrpKernel") == "kernelnet") assetBytes("kernelnet") else null),
            )
            val outH = h
            val outW = w
            VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), outH * outW * 3)
            rgb.rewind()
            VulkanPipeline.flipRowsInPlace(rgb.asFloatBuffer(), outW, outH)
            val profile = SrpBridge.readDngColorProfile(refPath)
            val orientation = SrpBridge.readDngOrientation(refPath)
            val outName = if (count == 8) "merged-device-fullres.dng"
                else "merged-device-fullres$count.dng"
            val out = File(outDir, outName).absolutePath
            val rc = SrpBridge.writeDng(out, outW, outH, rgb, profile.make, profile.model,
                profile.colorMatrix, profile.neutral, profile.illuminant, orientation)
            assertEquals("writeDng status", 0, rc)
            val wall = (System.nanoTime() - t0) / 1e9
            val rt = Runtime.getRuntime()
            val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
            Log.i("SrpFullresDevice",
                "wrote $out (${outW}x$outH) in %.1fs javaHeap=%.0fMB nativeHeap=%.0fMB".format(
                    wall, javaMb, Debug.getNativeHeapAllocatedSize() / 1048576.0))
            assertTrue("output missing", File(out).length() > 0)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }
}
