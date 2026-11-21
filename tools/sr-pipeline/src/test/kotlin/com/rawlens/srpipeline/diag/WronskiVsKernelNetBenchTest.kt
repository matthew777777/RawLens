package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.SrTuning
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Owner goal 2026-11-18: improve the Wronski path until KernelNet can be
 * removed. This scores both through the REAL VulkanPipeline with the app's
 * shipping settings (MfsrMerge: FIR7 grey, workgroup align, resident pyramid,
 * colour differences, SrTuning) on the known-truth burst of [Sr2xTruthTest]:
 *   static  = 8 frames, global +-3 px shifts (kernel / SR quality)
 *   motion  = static + a 70 px textured disc moving 2-6 px per frame
 *             (rejection / ghosting; truth = reference position)
 * at 1x and 2x, three noise levels. Metrics: PSNR and detailSNR (interior,
 * [Sr2xTruthTest.score]), flat-patch noise std (green, vs truth), and PSNR
 * inside the disc's swept area for the motion case.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*WronskiVsKernelNetBench*' -PsrpDiag=1 -PsrpHeap=8g
 *      -PsrpLib=$PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib -Dsrpipeline.spvdir=... -i
 */
class WronskiVsKernelNetBenchTest {
    private val spvDir = System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host"
    private fun spv(name: String) = File(spvDir, "$name.spv").readBytes()

    private val alignSpv by lazy {
        AlignGpu.Spirvs(
            align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"),
            alignWg = spv("align_wg"), argminWg = spv("corr_argmin_wg"), l2Fused = spv("corr_l2_fused"),
            flowPick = File(spvDir, "flow_pick.spv").takeIf { it.exists() }?.readBytes(),
        )
    }
    private val stageSpv by lazy {
        VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"),
            fft = spv("fft"), corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"),
            pyrDown = spv("pyr_down"),
            noiseAcc = spv("noise_acc"), noiseEven = spv("noise_even"),
            mergeTwoBand = File(spvDir, "merge_twoband.spv").takeIf { it.exists() }?.readBytes(),
            twoBandResolve = File(spvDir, "twoband_resolve.spv").takeIf { it.exists() }?.readBytes(),
            twoBandDct = File(spvDir, "twoband_dct.spv").takeIf { it.exists() }?.readBytes(),
            twoBandTensor = File(spvDir, "twoband_tensor.spv").takeIf { it.exists() }?.readBytes(),
            robFeather = File(spvDir, "rob_feather.spv").takeIf { it.exists() }?.readBytes())
    }

    enum class Kernel { WRONSKI_IPOL, WRONSKI_NEW, WRONSKI_EVEN, WRONSKI_GHOST, WRONSKI_BLUR, WRONSKI_ALIGN2, TWOBAND, CM_ONLY, DETAIL_ONLY }

    private fun cfg(k: Kernel, fin: BurstConfig.Final, a: DoubleArray, b: DoubleArray, scale: Float): PipelineConfig {
        val tuning = SrTuning()
        val al = FloatArray(4) { a[it].toFloat() }; val be = FloatArray(4) { b[it].toFloat() }
        val v2 = k == Kernel.WRONSKI_ALIGN2 || k == Kernel.TWOBAND
        val lv = if (v2) BurstConfig.alignV2Levels(16, 512, 512) else null
        val all = setOf(Kernel.WRONSKI_BLUR, Kernel.WRONSKI_ALIGN2, Kernel.TWOBAND)
        return PipelineConfig(
            scale = scale, bayer = true, greyMethod = "FIR7",
            // 512 px truth is too small for 32/64 px tiles (as Sr2xTruthTest).
            factors = lv?.factors ?: listOf(1, 2, 4, 4), tileSizes = lv?.tileSizes ?: listOf(16, 16, 16, 8),
            searchRadii = lv?.searchRadii, alignV2 = v2,
            // BENCH_GRAIN: grain retention sweep (default the app's 1).
            grainRetention = System.getenv("BENCH_GRAIN")?.toFloatOrNull() ?: PipelineConfig.DEFAULT_GRAIN_RETENTION,
            kernelParams = tuning.kernelParams(fin), kernelPreprocessing = "gat",
            kernelAlpha = al, kernelBeta = be,
            robustnessParams = tuning.robustnessParams(haveNoiseLut = false, s3 = false), robustnessEnabled = true,
            memoryTight = true,
            colourDiff = floatArrayOf(1f, 1f),
            chromaScale = tuning.chromaScale(scale),
            icaIterations = tuning.icaIterations,
            continuousMotion = k != Kernel.WRONSKI_IPOL && k != Kernel.DETAIL_ONLY,
            detailReconstruction = k in setOf(Kernel.WRONSKI_NEW, Kernel.WRONSKI_EVEN, Kernel.WRONSKI_GHOST, Kernel.WRONSKI_BLUR, Kernel.WRONSKI_ALIGN2, Kernel.DETAIL_ONLY),
            noiseEvening = k in setOf(Kernel.WRONSKI_EVEN, Kernel.WRONSKI_GHOST) || k in all,
            ghostGrow = k == Kernel.WRONSKI_GHOST || k in all,
            blurGamma = if (k in all) (System.getenv("BENCH_BLUR_GAMMA")?.toFloatOrNull() ?: 1f) else 0f,
            twoBand = if (k == Kernel.TWOBAND) com.rawlens.srpipeline.stages.TwoBandReconstruction.Params(
                sigmaHigh = System.getenv("BENCH_TB_HIGH")?.toDoubleOrNull() ?: 0.35,
                confK = System.getenv("BENCH_TB_CONFK")?.toDoubleOrNull() ?: 0.05,
                deconvStrength = System.getenv("BENCH_TB_DECONV")?.toDoubleOrNull() ?: 0.25,
                gainCap = System.getenv("BENCH_TB_CAP")?.toDoubleOrNull() ?: 2.0,
                opticsSigma = System.getenv("BENCH_TB_OPTICS")?.toDoubleOrNull() ?: 0.5,
                steer = System.getenv("BENCH_TB_STEER") != "0",
                maxStretch = System.getenv("BENCH_TB_STRETCH")?.toDoubleOrNull() ?: 2.0,
                phaseInvariant = System.getenv("BENCH_TB_PHASE") != "0",
                featherRadius = System.getenv("BENCH_TB_FEATHER")?.toIntOrNull() ?: 2) else null,
        )
    }

    private fun run(handle: Long, frames: List<Array<FloatArray>>, c: PipelineConfig): Array<Array<FloatArray>> {
        val buf = VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
            frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } },
            c, handle, alignSpv, stageSpv)
        val h = (frames[0].size * c.scale).toInt(); val w = (frames[0][0].size * c.scale).toInt()
        val fb = buf.asFloatBuffer()
        return Array(h) { y -> Array(w) { x -> FloatArray(3) { ch -> fb.get((y * w + x) * 3 + ch).let { if (it.isNaN()) 0f else it } } } }
    }

    // Disc: centre (cx, cy) radius R in reference scene coordinates; frame k
    // draws the disc's texture displaced by motion[k] (object motion).
    private val cx = 330.0; private val cy = 330.0; private val discR = 35.0

    // Texture patch (oil-painting metric): low-contrast random texture with a
    // dense spectrum, 1200 waves log-uniform over 0.04-0.75 cycles/px,
    // amplitude 0.004 (0.1/f)^0.5, pixel aperture applied exactly (sinc per
    // axis), so frames and truth sample the same continuous texture. Patch
    // [356, 492) x [36, 172) sensor px; TextureTransfer measures the 128 px
    // inside it. Replaces the scene there (static, not under the disc).
    private val texWaves: Array<DoubleArray> = run {
        val rng = java.util.Random(77)
        Array(1200) {
            val f = 0.04 * Math.pow(0.75 / 0.04, rng.nextDouble())
            val th = rng.nextDouble() * Math.PI
            val fx = f * kotlin.math.cos(th); val fy = f * kotlin.math.sin(th)
            fun sinc(x: Double) = if (kotlin.math.abs(x) < 1e-9) 1.0 else kotlin.math.sin(Math.PI * x) / (Math.PI * x)
            doubleArrayOf(fx, fy, rng.nextDouble() * 2 * Math.PI, 0.004 * Math.sqrt(0.1 / f) * sinc(fx) * sinc(fy))
        }
    }
    private fun inTexPatch(u: Double, v: Double) = u >= 356.0 && u < 492.0 && v >= 36.0 && v < 172.0
    private fun texPatch(u: Double, v: Double): Double {
        var s = 0.42
        for (w in texWaves) s += w[3] * kotlin.math.cos(2 * Math.PI * (w[0] * u + w[1] * v) + w[2])
        return s
    }

    private fun frameWithMotion(t: Sr2xTruthTest, sx: Double, sy: Double, mx: Double, my: Double,
                                a: Double, b: Double, seed: Long, blurLen: Double = 0.0): Array<FloatArray> {
        val rng = java.util.Random(seed)
        // blurLen > 0: camera motion blur along (1, 0.4), 9 taps over the length.
        val taps = if (blurLen > 0) 9 else 1
        return Array(t.n) { y -> FloatArray(t.n) { x ->
            var s = 0.0
            for (k in 0 until taps) {
                val o = if (taps == 1) 0.0 else blurLen * (k / (taps - 1.0) - 0.5)
                val u = x - sx + o; val v = y - sy + 0.4 * o
                val inside = hypot(u - mx - cx, v - my - cy) < discR
                // The disc carries a different texture (scene mirrored) so a
                // misaligned merge ghosts visibly.
                s += (if (inside) 0.75 - 0.6 * (t.box(2 * cx - (u - mx), v - my) - 0.42)
                      else if (inTexPatch(u, v)) texPatch(u, v) else t.box(u, v)) / taps
            }
            val g = t.gains[(y and 1) * 2 + (x and 1)]
            val vv = (s * g).coerceIn(0.0, 1.0)
            (vv + if (a > 0) rng.nextGaussian() * sqrt(a * vv + b) else 0.0).toFloat()
        } }
    }

    private fun truthWithDisc(t: Sr2xTruthTest, scale: Int): Array<Array<FloatArray>> {
        val chGain = doubleArrayOf(0.7, 1.0, 0.55)
        val n = t.n * scale
        return Array(n) { i -> Array(n) { j ->
            val u = (j + 0.5) / scale - 0.5; val v = (i + 0.5) / scale - 0.5
            val s = if (hypot(u - cx, v - cy) < discR) 0.75 - 0.6 * (t.box(2 * cx - u, v) - 0.42)
                else if (inTexPatch(u, v)) texPatch(u, v) else t.box(u, v)
            FloatArray(3) { c -> (s * chGain[c]).toFloat() }
        } }
    }

    private fun psnr(est: Array<Array<FloatArray>>, truth: Array<Array<FloatArray>>, sel: (Int, Int) -> Boolean): Double {
        var se = 0.0; var c = 0
        for (y in est.indices) for (x in est[0].indices) if (sel(y, x)) for (ch in 0..2) {
            val d = (est[y][x][ch] - truth[y][x][ch]).toDouble(); se += d * d; c++ }
        return 10 * log10(0.6 * 0.6 / (se / c))
    }

    /** Detail SNR at any scale (Sr2xTruthTest.score assumes 2x). */
    private fun detail(est: Array<Array<FloatArray>>, truth: Array<Array<FloatArray>>): Double {
        fun hp(img: Array<Array<FloatArray>>, c: Int): Array<DoubleArray> {
            val h = img.size; val w = img[0].size
            val k = DoubleArray(7) { kotlin.math.exp(-0.5 * (it - 3) * (it - 3)) }.let { q -> val s = q.sum(); DoubleArray(7) { q[it] / s } }
            val t = Array(h) { y -> DoubleArray(w) { x -> var s = 0.0; for (q in -3..3) s += k[q + 3] * img[y][(x + q).coerceIn(0, w - 1)][c]; s } }
            return Array(h) { y -> DoubleArray(w) { x -> var s = 0.0; for (q in -3..3) s += k[q + 3] * t[(y + q).coerceIn(0, h - 1)][x]; img[y][x][c] - s } }
        }
        val m = 24 * est.size / 512
        var hs = 0.0; var he = 0.0
        for (c in 0..2) { val ht = hp(truth, c); val hx = hp(est, c)
            for (y in m until est.size - m) for (x in m until est[0].size - m) {
                hs += ht[y][x] * ht[y][x]; val e = hx[y][x] - ht[y][x]; he += e * e } }
        return 10 * log10(hs / he)
    }

    @Test
    fun bench() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue("needs -PsrpLib", lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        check(handle != 0L) { "no Vulkan device" }
        try {
            val t = Sr2xTruthTest()
            val truths = mapOf(1 to truthWithDisc(t, 1), 2 to truthWithDisc(t, 2))
            // BENCH_SCENES=motion / BENCH_LEVELS=clean / BENCH_KERNELS=WRONSKI_NEW,WRONSKI_EVEN narrow a run.
            val sceneSel = System.getenv("BENCH_SCENES")?.split(',')?.toSet()
            val kernelSel = System.getenv("BENCH_KERNELS")?.split(',')?.toSet()
            // "blur" (opt-in): static scene, frames 2 and 5 with 6 px camera motion blur.
            for (scene in listOf("static", "motion", "blur").filter { if (sceneSel == null) it != "blur" else it in sceneSel })
            for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))
                .filter { l -> System.getenv("BENCH_LEVELS")?.split(',')?.contains(l.first) ?: true }) {
                val (a0, b0) = ab
                val rng = java.util.Random(5)
                val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
                val motion = List(8) { k -> if (scene != "motion") 0.0 to 0.0 else (k * 4.0) to (k * -2.5) }
                val frames = shifts.indices.map { k ->
                    frameWithMotion(t, shifts[k].first, shifts[k].second, motion[k].first, motion[k].second, a0, b0, 100L + k,
                        if (scene == "blur" && (k == 2 || k == 5)) 6.0 else 0.0) }
                val al = DoubleArray(4) { maxOf(a0, 1e-6) }; val be = DoubleArray(4) { maxOf(b0, 1e-8) }
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
                for (scale in listOf(1, 2)) {
                    val truth = truths[scale]!!
                    val m = 24 * scale
                    val sweptMax = hypot(7 * 4.0, 7 * 2.5) + discR + 4
                        fun report(img: Array<Array<FloatArray>>, k: String) {
                        val all = psnr(img, truth) { y, x -> y in m until img.size - m && x in m until img.size - m }
                        val swept = psnr(img, truth) { y, x ->
                            val u = (x + 0.5) / scale - 0.5; val v = (y + 0.5) / scale - 0.5
                            hypot(u - cx - 14, v - cy + 9) < sweptMax }
                        // Split the swept area: inside the reference disc (single-frame
                        // reconstruction, truth = disc) vs the trail the disc left in the
                        // other frames (truth = background: ghost leakage). p99 catches
                        // localized ghosts the mean hides.
                        fun distTrail(u: Double, v: Double): Double {
                            var best = Double.MAX_VALUE
                            for (j in 1..7) best = minOf(best, hypot(u - cx - 4.0 * j, v - cy + 2.5 * j))
                            return best
                        }
                        val discSel = { y: Int, x: Int -> val u = (x + 0.5) / scale - 0.5; val v = (y + 0.5) / scale - 0.5
                            hypot(u - cx, v - cy) < discR - 3 }
                        val trailSel = { y: Int, x: Int -> val u = (x + 0.5) / scale - 0.5; val v = (y + 0.5) / scale - 0.5
                            hypot(u - cx, v - cy) > discR + 3 && distTrail(u, v) < discR + 4 }
                        val discP = psnr(img, truth, discSel)
                        val trailP = psnr(img, truth, trailSel)
                        val trailP99 = run {
                            val e = ArrayList<Double>()
                            for (y in img.indices) for (x in img[0].indices) if (trailSel(y, x))
                                e.add((0..2).maxOf { kotlin.math.abs((img[y][x][it] - truth[y][x][it]).toDouble()) })
                            e.sort(); if (e.isEmpty()) 0.0 else e[(e.size * 0.99).toInt()]
                        }
                        // Flat noise: the lower-left 60x60 region has little texture energy
                        val flat = run {
                            var s = 0.0; var c = 0
                            for (y in (400 * scale) until (460 * scale)) for (x in (40 * scale) until (100 * scale)) {
                                val d = (img[y][x][1] - truth[y][x][1]).toDouble(); s += d * d; c++ }
                            sqrt(s / c)
                        }
                        println("BENCH %-6s %-6s %dx %-18s PSNR %6.2f  detail %6.2f  swept %6.2f  disc %6.2f  trail %6.2f  trailP99 %.4f  flatErr %.5f".format(
                            scene, label, scale, k, all, detail(img, truth), swept, discP, trailP, trailP99, flat))
                        // Texture-only 128x128 sensor-px region (no edge, chart or disc):
                        // per-band transfer / residual, the oil-painting metric.
                        println("TEX   %-6s %-6s %dx %-18s %s".format(scene, label, scale, k,
                            TextureTransfer.format(TextureTransfer.measure(img, truth, scale, 360, 40, 128))))
                        }
                    // BENCH_TWOBAND=sigmaHigh[:d[:floor]],... CPU two-band prototype, oracle
                    // shifts, static scene only (no robustness). d = DCT Wiener on.
                    if (scene == "static") System.getenv("BENCH_TWOBAND")?.split(',')?.filter { it.isNotEmpty() }?.forEach { spec ->
                        val q = spec.split(':')
                        // spec sigmaHigh[:d|n[:floor[:2p]]]: d = DCT Wiener, 2p = two-pass (stored samples).
                        val prm = com.rawlens.srpipeline.stages.TwoBandReconstruction.Params(sigmaHigh = q[0].toDouble(),
                            denoise = q.getOrNull(1) == "d", grainFloor = q.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
                            singlePass = q.getOrNull(3) != "2p",
                            sigmaLow = System.getenv("BENCH_TB_LOW")?.toDoubleOrNull() ?: 1.0)
                        // BENCH_TB_JITTER=s: Gaussian alignment error (px) on frames 1..7.
                        val jit = System.getenv("BENCH_TB_JITTER")?.toDoubleOrNull() ?: 0.0
                        val jr = java.util.Random(9)
                        val sh = shifts.mapIndexed { i, (x, y) -> if (i == 0) x to y else (x + jit * jr.nextGaussian()) to (y + jit * jr.nextGaussian()) }
                        report(TwoBandMerge.merge(frames, sh, t.gains, scale, prm, a0, b0), "2B$spec" + if (jit > 0) "j$jit" else "")
                    }
                    for (k in Kernel.values().filter { if (kernelSel == null) it != Kernel.CM_ONLY && it != Kernel.DETAIL_ONLY else it.name in kernelSel }) {
                        val merged = run(handle, frames, cfg(k, fin, al, be, scale.toFloat()))
                        report(merged, k.name)
                        // BENCH_DECONV=l1,l2,...: merge-matched deconvolution sweep on this result.
                        System.getenv("BENCH_DECONV")?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.forEach { lam ->
                            report(com.rawlens.srpipeline.stages.MergeDeconvolution.applyRgb(merged,
                                com.rawlens.srpipeline.stages.MergeDeconvolution.taps(scale.toFloat(), lam)), "${k.name}+dc$lam")
                        }
                    }
                }
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }
}
