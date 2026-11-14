package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * Quality gate for the mobile GPU path through the REAL VulkanPipeline
 * (MoltenVK on the Mac): known-truth burst of [Sr2xTruthTest] at three noise
 * levels, 2x merge scored against ground truth (PSNR, detailSNR), for
 *   parity  = FFT grey + legacy shaders (the reference-parity path)
 *   mobile  = FIR7 grey + align_wg + corr_argmin_wg (shipping app path)
 *   +fused  = mobile + corr_l2_fused (reordered L2 sums)
 * plus mobile vs +fused on the real C9 night crop (-PsrpBurstDir with burst.f32).
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*VulkanMobileGateTest*' -PsrpDiag=1 -PsrpHeap=8g
 *      -PsrpLib=$PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib -Dsrpipeline.spvdir=... -i
 */
class VulkanMobileGateTest {
    private val spvDir = System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host"
    private fun spv(name: String) = File(spvDir, "$name.spv").readBytes()

    private enum class Path { PARITY, MOBILE, FUSED, RESIDENT }

    private fun alignSpv(p: Path) = AlignGpu.Spirvs(
        align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
        corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"),
        alignWg = if (p == Path.PARITY) null else spv("align_wg"),
        argminWg = if (p == Path.PARITY) null else spv("corr_argmin_wg"),
        l2Fused = if (p == Path.FUSED || p == Path.RESIDENT) spv("corr_l2_fused") else null,
    )

    private fun stageSpv(p: Path) =
        VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"),
            fft = spv("fft"), corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"),
            pyrDown = if (p == Path.RESIDENT) spv("pyr_down") else null)

    private fun cfg(p: Path, fin: BurstConfig.Final, a: DoubleArray, b: DoubleArray, scale: Float) = PipelineConfig(
        scale = scale, bayer = true, greyMethod = if (p == Path.PARITY) "FFT" else "FIR7",
        factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
        kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
        kernelPreprocessing = "gat",
        kernelAlpha = FloatArray(4) { a[it].toFloat() }, kernelBeta = FloatArray(4) { b[it].toFloat() },
        robustnessParams = RobustnessParams(noiseCorrection = false, s3 = false), robustnessEnabled = true,
        icaIterations = 3,
    )

    private fun run(handle: Long, frames: List<Array<FloatArray>>, c: PipelineConfig, p: Path): Array<Array<FloatArray>> {
        val buf = VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
            frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } },
            c, handle, alignSpv(p), stageSpv(p))
        val h = (frames[0].size * c.scale).toInt(); val w = (frames[0][0].size * c.scale).toInt()
        val fb = buf.asFloatBuffer()
        return Array(h) { y -> Array(w) { x -> FloatArray(3) { ch -> fb.get((y * w + x) * 3 + ch).let { if (it.isNaN()) 0f else it } } } }
    }

    private fun diff(a: Array<Array<FloatArray>>, b: Array<Array<FloatArray>>, m: Int): String {
        var se = 0.0; var c = 0; var big = 0; var mx = 0.0
        for (y in m until a.size - m) for (x in m until a[0].size - m) for (ch in 0..2) {
            val d = (a[y][x][ch] - b[y][x][ch]).toDouble(); se += d * d; c++
            if (kotlin.math.abs(d) > 1.0 / 255) big++; mx = maxOf(mx, kotlin.math.abs(d))
        }
        return if (se == 0.0) "identical" else "%.1f dB, max %.4f, px>1/255 %.4f%%".format(10 * log10(1 / (se / c)), mx, 100.0 * big / c)
    }

    @Test
    fun knownTruthAndRealCrop() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue("needs -PsrpLib", lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        check(handle != 0L) { "no Vulkan device" }
        try {
            val t = Sr2xTruthTest(); val truth = t.truth2x()
            for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
                val (a0, b0) = ab
                val rng = java.util.Random(5)
                val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
                val frames = shifts.mapIndexed { k, (sx, sy) -> t.frame(sx, sy, a0, b0, 100L + k) }
                val al = DoubleArray(4) { maxOf(a0, 1e-6) }; val be = DoubleArray(4) { maxOf(b0, 1e-8) }
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
                val outs = Path.values().associateWith { p -> run(handle, frames, cfg(p, fin, al, be, 2f), p) }
                for ((p, img) in outs) {
                    val (ps, d) = t.score(img, truth)
                    println("GATE [%-6s] %-7s 2x PSNR %.2f dB detail %.2f dB".format(label, p, ps, d))
                }
                println("GATE [%-6s] mobile vs fused: %s".format(label, diff(outs[Path.MOBILE]!!, outs[Path.FUSED]!!, 24)))
                println("GATE [%-6s] fused vs resident: %s".format(label, diff(outs[Path.FUSED]!!, outs[Path.RESIDENT]!!, 24)))
            }
            val dir = File(System.getProperty("srpipeline.burst", ""))
            if (File(dir, "burst.f32").exists()) {
                val txt = File(dir, "cfg.json").readText()
                fun num(k: String) = Regex("\"$k\":\\s*([-0-9.eE]+)").find(txt)!!.groupValues[1].toDouble()
                fun arr(k: String) = Regex("\"$k\":\\s*\\[([^]]*)]").find(txt)!!.groupValues[1].split(',').map { it.trim().toDouble() }.toDoubleArray()
                val nF = num("n").toInt(); val hh = num("h").toInt(); val ww = num("w").toInt()
                val fb = ByteBuffer.wrap(File(dir, "burst.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val frames = List(nF) { Array(hh) { FloatArray(ww).also { r -> fb.get(r) } } }
                val fin = BurstConfig.finalize(num("snr"))
                val m = run(handle, frames, cfg(Path.MOBILE, fin, arr("alpha"), arr("beta"), 1f), Path.MOBILE)
                val f = run(handle, frames, cfg(Path.FUSED, fin, arr("alpha"), arr("beta"), 1f), Path.FUSED)
                println("GATE real night crop ${ww}x$hh mobile vs fused: ${diff(m, f, 64)}")
                val r = run(handle, frames, cfg(Path.RESIDENT, fin, arr("alpha"), arr("beta"), 1f), Path.RESIDENT)
                println("GATE real night crop ${ww}x$hh fused vs resident: ${diff(f, r, 64)}")
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }
}
