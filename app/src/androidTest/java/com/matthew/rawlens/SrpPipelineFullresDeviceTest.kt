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
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.NoiseLut
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
 * Full 8-frame FULL-RES burst through the app-integrated pipeline
 * ([VulkanPipeline.run] from the shared tools module, same config
 * recipe as the desktop CLI: full 3060x4080 frames, scale 1, LUT on).
 * Writes `merged-device-fullres.dng` to the app external dir; pull it
 * and compare against the desktop reference
 * (`/tmp/sr-crop/fullres-desktop/merged.dng`, 4080x3060, 30.2 s
 * desktop Vulkan) under the envelope gate. Test asserts rc==0s +
 * output exists + logs wall/heap; pixel identity is checked on the
 * host (no multi-MB blob in test output).
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
        val burstDir = File("/data/local/tmp/sr-burst")
        val listing = burstDir.list() ?: emptyArray()
        Log.i("SrpFullresDevice", "burst dir ${burstDir.absolutePath} lists ${listing.size}: " +
            listing.sorted().take(10).joinToString(","))
        val frames = listing.filter { it.lowercase().endsWith(".dng") }
            .map { File(burstDir, it) }.filter { it.isFile }.sortedBy { it.name }
        assumeTrue("push 8 burst DNGs to ${burstDir.absolutePath} first", frames.size == 8)
        val lutFile = File(burstDir, "IMG_20260927_134544_522_noise.npz")
        assumeTrue("push the noise LUT next to the burst first", lutFile.isFile)
        val outDir = context.getExternalFilesDir(null)
            ?: context.filesDir

        val t0 = System.nanoTime()
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            Log.i("SrpFullresDevice", "device: ${SrpBridge.nativeDeviceInfo(handle)}")
            val refPath = frames[0].absolutePath
            val refMeta = DngRead.readMeta(refPath)
            // The reference decodes through a temp (dead before the
            // pipeline's init window); the shared decode staging is
            // allocated lazily by the first comparison thunk, so it is
            // not live during init either. Every decode fully rewrites
            // the buffer, so reuse is aliasing-safe.
            var refBox: Array<FloatArray>? =
                DngRead.readNormalized(refPath, refMeta)
            var decodeBuf: java.nio.ByteBuffer? = null
            val compImgs = frames.drop(1).map { f ->
                {
                    val b = decodeBuf
                        ?: SrpBridge.directFloats(refMeta.width * refMeta.height).also { decodeBuf = it }
                    DngRead.readNormalizedInto(f.absolutePath, DngRead.readMeta(f.absolutePath), b)
                }
            }
            val h = refBox!!.size
            val w = refBox!![0].size
            val (alpha, beta) = DngRead.noiseAlphaBeta(refMeta.noise, refMeta.noiseCount)
            val curves = NoiseLut.load(lutFile.absolutePath, alpha, beta)
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
                    fft = fft, ica = assetBytes("ica")),
                VulkanPipeline.Spirvs(
                    gat = assetBytes("gat"), grad = assetBytes("grad"),
                    kernels = assetBytes("kernels"), divide = assetBytes("divide"),
                    robust = assetBytes("robustness"),
                    mergeSplat = assetBytes("merge_splat"),
                    fft = fft),
            )
            val outH = h
            val outW = w
            VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), outH * outW * 3)
            rgb.rewind()
            VulkanPipeline.flipRowsInPlace(rgb.asFloatBuffer(), outW, outH)
            val profile = SrpBridge.readDngColorProfile(refPath)
            val orientation = SrpBridge.readDngOrientation(refPath)
            val out = File(outDir, "merged-device-fullres.dng").absolutePath
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
