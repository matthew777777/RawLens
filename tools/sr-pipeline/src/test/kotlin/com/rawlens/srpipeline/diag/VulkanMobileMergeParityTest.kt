package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.GreenNormalTile
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10

/**
 * App recipe through the staged path and MobileMerge (known-truth burst of
 * [Sr2xTruthTest]): the two outputs must agree above 50 dB and score alike.
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*VulkanMobileMergeParityTest*'
 *      -PsrpDiag=1 -PsrpLib=$PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib -i
 */
class VulkanMobileMergeParityTest {
    private val spvDir = System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host"
    private fun spv(name: String) = File(spvDir, "$name.spv").readBytes()

    private fun run(handle: Long, frames: List<Array<FloatArray>>, c: PipelineConfig, mobile: Boolean, fit: Boolean): FloatArray {
        val h = frames[0].size; val w = frames[0][0].size
        val align = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"), alignWg = spv("align_wg"),
            argminWg = spv("corr_argmin_wg"), l2Fused = spv("corr_l2_fused"))
        val stages = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = spv("fft"),
            corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"), pyrDown = spv("pyr_down"),
            noiseAcc = spv("noise_acc"), noiseEven = spv("noise_even"), caWarp = spv("ca_warp"),
            mobile = if (mobile) VulkanPipeline.MobileSpirvs.load(::spv) else null)
        val green = if (fit) GreenNormalTile.createResident(handle, spv("green_residual"), spv("green_normal_resident"),
            w, h, c.tileSizes.first(), frames.size) else null
        try {
            val buf = VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
                frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } },
                c, handle, align, stages, greenFit = green)
            return FloatArray((h * c.scale).toInt() * (w * c.scale).toInt() * 3).also { out ->
                buf.rewind(); buf.asFloatBuffer().get(out)
                for (i in out.indices) if (out[i].isNaN()) out[i] = 0f
            }
        } finally { green?.close() }
    }

    @Test
    fun stagedAndMobileAgree() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue("needs -PsrpLib", lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        check(handle != 0L) { "no Vulkan device" }
        val bad = ArrayList<String>()
        try {
            val t = Sr2xTruthTest(); val truth = t.truth2x()
            for ((label, ab) in listOf("iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
                val (a0, b0) = ab
                val rng = java.util.Random(5)
                val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
                val base = shifts.mapIndexed { k, (sx, sy) -> t.frame(sx, sy, a0, b0, 100L + k) }
                val al = DoubleArray(4) { a0 }; val be = DoubleArray(4) { b0 }
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(base[0], al, be))
                for (scale in listOf(1f, 2f)) for (tile in listOf(16, 32, 64)) {
                    // Larger tiles need a larger frame for the coarsest level: repeat the scene.
                    val rep = tile / 16
                    val frames = if (tile == 16) base else base.map { f -> Array(rep * f.size) { y -> FloatArray(rep * f[0].size) { x -> f[y % f.size][x % f[0].size] } } }
                    val c = PipelineConfig(scale = scale, bayer = true, greyMethod = "FIR7", factors = listOf(1, 2, 4, 4),
                        tileSizes = listOf(tile, tile, tile, tile / 2),
                        kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                        kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { a0.toFloat() }, kernelBeta = FloatArray(4) { b0.toFloat() },
                        robustnessParams = RobustnessParams(noiseCorrection = false, s3 = true), robustnessEnabled = true,
                        icaIterations = 3, colourDiff = floatArrayOf(1f, 1f), chromaScale = if (scale > 1f) 2f else 1f,
                        continuousMotion = true, detailReconstruction = true, noiseEvening = true, lateralCa = true,
                        ghostGrow = true, blurGamma = 1f, blackPedestal = true)
                    val a = run(handle, frames, c, false, scale == 1f)
                    val b = run(handle, frames, c, true, scale == 1f)
                    var se = 0.0; var mx = 0.0; var same = 0
                    for (i in a.indices) { val d = abs(a[i] - b[i]).toDouble(); se += d * d; mx = maxOf(mx, d); if (d == 0.0) same++ }
                    val psnr = if (se == 0.0) 999.0 else 10 * log10(a.size / se)
                    val score = if (scale == 2f && tile == 16) {
                        val w = truth[0].size
                        fun img(v: FloatArray) = Array(v.size / 3 / w) { y -> Array(w) { x -> FloatArray(3) { ch -> v[(y * w + x) * 3 + ch] } } }
                        val (pa, da) = t.score(img(a), truth); val (pb, db) = t.score(img(b), truth)
                        " truth staged %.3f/%.3f mobile %.3f/%.3f dB".format(pa, da, pb, db)
                    } else ""
                    println("PARITY [$label] ${frames[0][0].size}x${frames[0].size} x$scale tile $tile: %.1f dB max %.5f equal %.1f%%%s".format(psnr, mx, 100.0 * same / a.size, score))
                    if (psnr < 50) bad.add("$label x$scale tile $tile: $psnr dB")
                }
            }
        } finally { SrpBridge.nativeDestroy(handle) }
        assertTrue(bad.toString(), bad.isEmpty())
    }
}
