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
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
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
 * Full 8-frame crop burst through the app-integrated pipeline
 * ([VulkanPipeline.run] from the shared tools module, same config
 * recipe as the desktop CLI: crop 1528,1018,1024,1024, scale 1, LUT
 * on). Writes `merged-device.dng` next to the pushed burst; pull it
 * and `cmp` against the desktop reference
 * (`/tmp/sr-crop/after/merged.dng`) — the on-device parity gate.
 * Test asserts rc==0s + output exists + logs wall/heap; pixel identity
 * is checked on the host (no 6 MB blob in test output).
 *
 * Burst + LUT must be pushed beforehand to the app's external files
 * dir (no permissions needed there):
 * `adb push IMG_..._F0N.dng .../files/sr-burst/`.
 *
 * Opt-in: `-e rawlensSrpCrop true`.
 */
@RunWith(AndroidJUnit4::class)
class SrpPipelineCropDeviceTest {
    private fun readFrame(path: String, cx: Int, cy: Int, cw: Int, ch: Int): Array<FloatArray> {
        val meta = DngRead.readMeta(path)
        require(cx + cw <= meta.width && cy + ch <= meta.height) {
            "crop $cx,$cy,$cw,$ch exceeds ${meta.width}x${meta.height}"
        }
        val img = DngRead.readNormalized(path, meta)
        return Array(ch) { y -> img[cy + y].copyOfRange(cx, cx + cw) }
    }

    private fun assetBytes(name: String): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.assets.open("sr_spv/$name.spv").use { it.readBytes() }
    }

    @Test fun burstCropMergesOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensSrpCrop true to run the on-device burst crop",
            args.getString("rawlensSrpCrop") == "true",
        )
        try {
            SrpBridge.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("libsrpipeline.so unavailable: ${e.message}", false)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Stage clock + phase heap (perf-slice diagnosis; set before
        // first VulkanPipeline use).
        System.setProperty("srpipeline.profile", "true")
        // Inputs live in /data/local/tmp (world-readable, survives app
        // reinstalls which wipe the external files dir); output goes to
        // the app's own external dir (app-writable, pulled via adb).
        val burstDir = File("/data/local/tmp/sr-burst")
        val listing = burstDir.list() ?: emptyArray()
        Log.i("SrpCropDevice", "burst dir ${burstDir.absolutePath} lists ${listing.size}: " +
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
            Log.i("SrpCropDevice", "device: ${SrpBridge.nativeDeviceInfo(handle)}")
            // Crop rect via `-e rawlensSrpCropRect x,y,w,h` (default the
            // 1024 gate); must keep every pyramid level tileable
            // (768 is the smallest legal here; 512 bottoms out).
            val rect = args.getString("rawlensSrpCropRect")
                ?.split(",")?.map { it.trim().toInt() }
                ?: listOf(1528, 1018, 1024, 1024)
            require(rect.size == 4 && rect.all { it > 0 }) {
                "rawlensSrpCropRect must hold x,y,w,h positive integers"
            }
            val cx = rect[0]
            val cy = rect[1]
            val cw = rect[2]
            val ch = rect[3]
            val refPath = frames[0].absolutePath
            val refImg = readFrame(refPath, cx, cy, cw, ch)
            val compImgs = frames.drop(1).map { f ->
                { readFrame(f.absolutePath, cx, cy, cw, ch) }
            }
            val h = refImg.size
            val w = refImg[0].size
            val refMeta = DngRead.readMeta(refPath)
            val (alpha, beta) = DngRead.noiseAlphaBeta(refMeta.noise, refMeta.noiseCount)
            val curves = NoiseLut.load(lutFile.absolutePath, alpha, beta)
            // Full-frame SNR, like the CLI and the paper (S.2).
            val fullRef = DngRead.readNormalized(refPath, refMeta)
            val snr = BurstConfig.estimateSnr(fullRef, alpha, beta)
            // -e rawlensSrpKernel kernelnet|wronski (default wronski).
            val kernel = args.getString("rawlensSrpKernel") ?: "wronski"
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
                kernelNet = if (kernel != "kernelnet") null else {
                    val (fx, fy) = KernelNet.rggbFlips(refMeta.cfa)
                    KernelNetConfig(
                        weights = KernelNet.bundledWeights(),
                        sigma = KernelNet.sigma(
                            FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                        fullReference = fullRef, cropX = cx, cropY = cy, flipX = fx, flipY = fy)
                },
            )
            Log.i("SrpCropDevice", "crop ${w}x$h snr=%.2f tile=%d kernel=%s".format(snr, fin.tileSize, kernel))
            val fft = assetBytes("fft")
            val rgb = VulkanPipeline.run(
                { refImg }, compImgs.map { t -> { _: java.nio.ByteBuffer -> t() } },
                config, handle,
                AlignGpu.Spirvs(
                    align = assetBytes("align"), corr = assetBytes("corr"),
                    fft = fft, ica = assetBytes("ica")),
                VulkanPipeline.Spirvs(
                    gat = assetBytes("gat"), grad = assetBytes("grad"),
                    kernels = assetBytes("kernels"), divide = assetBytes("divide"),
                    robust = assetBytes("robustness"),
                    mergeSplat = assetBytes("merge_splat"),
                    fft = fft, corr = assetBytes("corr"),
                    kernelNet = if (kernel == "kernelnet") assetBytes("kernelnet") else null),
            )
            val outH = h
            val outW = w
            VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), outH * outW * 3)
            rgb.rewind()
            VulkanPipeline.flipRowsInPlace(rgb.asFloatBuffer(), outW, outH)
            val profile = SrpBridge.readDngColorProfile(refPath)
            val orientation = SrpBridge.readDngOrientation(refPath)
            val outName = "merged-device-${kernel}-${cw}x${ch}.dng"
            val out = File(outDir, outName).absolutePath
            val rc = SrpBridge.writeDng(out, outW, outH, rgb, profile.make, profile.model,
                profile.colorMatrix, profile.neutral, profile.illuminant, orientation)
            assertEquals("writeDng status", 0, rc)
            val wall = (System.nanoTime() - t0) / 1e9
            val rt = Runtime.getRuntime()
            val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
            Log.i("SrpCropDevice",
                "wrote $out (${outW}x$outH) in %.1fs javaHeap=%.0fMB nativeHeap=%.0fMB".format(
                    wall, javaMb, Debug.getNativeHeapAllocatedSize() / 1048576.0))
            assertTrue("output missing", File(out).length() > 0)
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }
}
