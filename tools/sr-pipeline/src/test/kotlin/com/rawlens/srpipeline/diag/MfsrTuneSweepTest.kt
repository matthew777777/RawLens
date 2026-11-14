package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.HotPixels
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.SrTuning
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * KB-SR tuning sweep on real bursts (owner 2026-11-14: organic DSLR look, no
 * false colour, no zipper, no artefacts), through the app's exact engine
 * config on the real VulkanPipeline (MoltenVK): KernelNet, zipper fix,
 * S.3, hot pixels, FIR grey + mobile shaders, at 1x and 2x.
 * One [SrTuning] value is varied at a time around the defaults (env
 * SRP_SWEEP=name to run one group). Output-only metrics on WB'd RGB:
 *   fc      false colour: RMS HP chroma / RMS HP luma on the 5 % strongest edges
 *   zip     max/min HP RMS over the 4 (y%2, x%2) phases, R and B, on edges
 *   chk     |mean((-1)^(x+y) HP)| / RMS HP on the 20 % flattest (checker)
 *   spk     isolated chroma outliers per 100k px
 *   det     RMS HP luma on the 30 % most textured, relative to the defaults
 *   noise   RMS HP luma on the flattest 20 %, relative to the defaults
 * Per variant: geometric mean over bursts of each metric's ratio to the
 * defaults (< 1 = better for fc/zip/chk/spk; det should stay ~1).
 * Run: SRP_BURSTS_DIR=~/Downloads/My/Bursts ./gradlew :tools:sr-pipeline:test --tests '*MfsrTuneSweepTest*'
 *      -PsrpDiag=1 -PsrpHeap=12g -PsrpLib=$PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib -i
 */
class MfsrTuneSweepTest {
    private val spvDir = System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host"
    private fun spv(name: String) = File(spvDir, "$name.spv").readBytes()
    private val n = 1024
    private val out = File("build/mfsrtune").apply { mkdirs() }

    private val alignSpv by lazy {
        AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"), alignWg = spv("align_wg"),
            argminWg = spv("corr_argmin_wg"), l2Fused = spv("corr_l2_fused"))
    }
    private val stageSpv by lazy {
        VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"),
            fft = spv("fft"), corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"),
            pyrDown = spv("pyr_down"), kernelNet = spv("kernelnet"))
    }

    /**
     * One-at-a-time variants around the defaults. "kn:" = KernelNet kernel (app
     * default), "wr:" = Wronski S.1 kernel; the paper's kernel values only act
     * on the Wronski kernel, KernelNet has its own width values.
     */
    private val variants: List<Triple<String, Boolean, SrTuning>> = run {
        val d = SrTuning()
        val robust = listOf(.08f, .16f).map { "t $it" to d.copy(t = it) } +
            listOf(8f, 16f).map { "s2 $it" to d.copy(s2 = it) } +
            listOf(.5f, 1.2f).map { "Mt $it" to d.copy(mt = it) }
        val kn = listOf("default" to d) +
            listOf(.2f, .3f, .35f).map { "knScale $it" to d.copy(knSigmaScale = it) } +
            listOf(1.0f, 1.4f, 1.6f).map { "knCap $it" to d.copy(knSigmaCap = it) } +
            listOf(.9f, .7f, .55f, .4f).map { "knFloor $it" to d.copy(knSigmaFloor = it, knSigmaFloor2x = it) } +
            listOf(1f, 1.25f, 1.5f, 2f).map { "chroma $it" to d.copy(chromaScale2x = it) } +
            listOf(.7f, .55f, .4f).map { "knFloorCap $it" to d.copy(knSigmaFloor = it, knSigmaFloor2x = it, knSigmaCap = 1.0f) } + robust
        val wr = listOf("default" to d) +
            listOf(.8f, .9f, 1.15f, 1.3f).map { "kDetail x$it" to d.copy(kDetailScale = it) } +
            listOf(.8f, 1.25f, 1.5f).map { "kDenoise x$it" to d.copy(kDenoiseScale = it) } +
            listOf(.85f, 1.15f).map { "dTh x$it" to d.copy(dThScale = it) } +
            listOf(.8f, 1.25f).map { "dTr x$it" to d.copy(dTrScale = it) } +
            listOf(3f, 5f, 6f).map { "kStretch $it" to d.copy(kStretch = it) } +
            listOf(1.5f, 2.5f, 3f).map { "kShrink $it" to d.copy(kShrink = it) } +
            listOf(.5f, .8f, 1f).map { "kMin $it" to d.copy(kMin = it) } +
            robust
        kn.map { Triple("kn:${it.first}", true, it.second) } + wr.map { Triple("wr:${it.first}", false, it.second) }
    }

    private class Burst(val name: String, val frames: List<Array<FloatArray>>, val alpha: DoubleArray, val beta: DoubleArray,
                        val wbR: Float, val wbB: Float, val fin: BurstConfig.Final, val curves: com.rawlens.srpipeline.stages.NoiseLut.Curves?,
                        val fullRef: Array<FloatArray>, val ox: Int, val oy: Int, val flipX: Boolean, val flipY: Boolean)

    private fun loadBurst(dir: File): Burst? {
        val files = dir.listFiles { f -> f.name.endsWith(".dng") }?.sortedBy { it.name }?.take(8) ?: return null
        if (files.size < 4) return null
        val meta = DngRead.readMeta(files[0].absolutePath)
        val full = DngRead.readNormalized(files[0].absolutePath, meta)
        // No NoiseProfile tag: blind estimate from the reference, as the CLI does.
        val (alpha, beta) = if (meta.noiseCount == 0) com.rawlens.srpipeline.stages.SceneNoise.estimate(full)
            else DngRead.noiseAlphaBeta(meta.noise, meta.noiseCount)
        val h = full.size; val w = full[0].size
        // Most textured n x n window (coarse search), even origin (RGGB phase).
        var best = -1.0; var bx = 0; var by = 0
        for (y0 in 64 until h - n - 64 step 128) for (x0 in 64 until w - n - 64 step 128) {
            var s = 0.0
            for (y in y0 until y0 + n step 8) for (x in x0 until x0 + n step 8) s += abs(full[y][x + 2] - full[y][x]) + abs(full[y + 2][x] - full[y][x])
            if (s > best) { best = s; bx = x0; by = y0 }
        }
        val ox = bx and -2; val oy = by and -2
        fun crop(f: Array<FloatArray>) = Array(n) { y -> f[oy + y].copyOfRange(ox, ox + n) }
        val frames = listOf(crop(full)) + files.drop(1).map { crop(DngRead.readNormalized(it.absolutePath, DngRead.readMeta(it.absolutePath))) }
        val defects = HotPixels.confirm(HotPixels.candidates(frames[0]), n, n, frames.subList(1, 3).map { f -> { y: Int, x: Int -> f[y][x] } })
        frames.forEach { HotPixels.correct(it, defects) }
        val neutral = SrpBridge.readDngColorProfile(files[0].absolutePath).neutral
        val wbR = if (neutral != null && neutral.size == 3) (neutral[1] / neutral[0]).toFloat() else 1f
        val wbB = if (neutral != null && neutral.size == 3) (neutral[1] / neutral[2]).toFloat() else 1f
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full, alpha, beta))
        val curves = runCatching { NoiseLutGen.generate(alpha, beta) }.getOrNull()
        val (fx, fy) = KernelNet.rggbFlips(meta.cfa)
        return Burst(dir.name, frames, alpha, beta, wbR, wbB, fin, curves, full, ox, oy, fx, fy)
    }

    private fun run(handle: Long, b: Burst, tune: SrTuning, scale: Float, kernelNet: Boolean): Array<Array<FloatArray>> {
        val tiles = try { BurstConfig.sanitize(scale, b.fin.tileSize, listOf(1, 2, 4, 4), b.fin.tileSizes, n, n); b.fin.tileSizes }
            catch (_: IllegalArgumentException) { listOf(16, 16, 16, 8) }
        val cfg = PipelineConfig(
            scale = scale, bayer = true, greyMethod = "FIR7", factors = listOf(1, 2, 4, 4), tileSizes = tiles,
            kernelParams = tune.kernelParams(b.fin), kernelPreprocessing = "gat",
            kernelAlpha = FloatArray(4) { b.alpha[it].toFloat() }, kernelBeta = FloatArray(4) { b.beta[it].toFloat() },
            robustnessParams = tune.robustnessParams(haveNoiseLut = b.curves != null, s3 = true), robustnessEnabled = true,
            sigmaSqCurve = b.curves?.sigmaSq ?: floatArrayOf(0f, 0f), dSqCurve = b.curves?.dSq ?: floatArrayOf(0f, 0f),
            icaIterations = tune.icaIterations, colourDiff = floatArrayOf(b.wbR, b.wbB), chromaScale = tune.chromaScale(scale),
            kernelNet = if (!kernelNet) null else KernelNetConfig(weights = KernelNet.bundledWeights(),
                sigma = KernelNet.sigma(FloatArray(4) { b.alpha[it].toFloat() }, FloatArray(4) { b.beta[it].toFloat() }),
                sigmaScale = tune.knSigmaScale, sigmaCap = tune.knSigmaCap, sigmaFloor = tune.knFloor(scale),
                fullReference = b.fullRef, cropX = b.ox, cropY = b.oy, flipX = b.flipX, flipY = b.flipY),
        )
        val buf = VulkanPipeline.run({ b.frames[0].map { it.copyOf() }.toTypedArray() },
            b.frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } }, cfg, handle, alignSpv, stageSpv)
        val m = (n * scale).toInt()
        val fb = buf.asFloatBuffer()
        return Array(m) { y -> Array(m) { x -> FloatArray(3) { c -> fb.get((y * m + x) * 3 + c).let { if (it.isNaN()) 0f else it } } } }
    }

    private class M(val fc: Double, val zip: Double, val chk: Double, val spk: Double, val det: Double, val noise: Double)

    private fun metrics(img: Array<Array<FloatArray>>, wbR: Float, wbB: Float): M {
        val m = img.size; val e = 8
        val wb = floatArrayOf(wbR, 1f, wbB)
        fun ch(c: Int, y: Int, x: Int) = img[y][x][c] * wb[c]
        val Y = Array(m) { y -> DoubleArray(m) { x -> (ch(0, y, x) + 2.0 * ch(1, y, x) + ch(2, y, x)) / 4 } }
        fun hp(a: (Int, Int) -> Double, y: Int, x: Int): Double { var s = 0.0; for (dy in -1..1) for (dx in -1..1) s += a(y + dy, x + dx); return a(y, x) - s / 9 }
        // Structure from a 2-px gradient of luma (stable at both scales).
        val g = ArrayList<Double>(); for (y in e until m - e) for (x in e until m - e) g += abs(Y[y][x + 2] - Y[y][x - 2]) + abs(Y[y + 2][x] - Y[y - 2][x])
        val sorted = g.sorted()
        val eThr = sorted[(sorted.size * .95).toInt()]; val fThr = sorted[(sorted.size * .20).toInt()]; val tThr = sorted[(sorted.size * .70).toInt()]
        var lumE = 0.0; var chE = 0.0; var nE = 0; var detS = 0.0; var nT = 0; var noiS = 0.0; var nF = 0
        val ss = Array(3) { DoubleArray(4) }; val cnt = Array(3) { IntArray(4) }
        val chk = DoubleArray(3); val chkN = DoubleArray(3)
        var k = 0
        for (y in e until m - e) for (x in e until m - e) {
            val gv = g[k++]
            val hy = hp({ yy, xx -> Y[yy][xx] }, y, x)
            if (gv >= eThr) {
                val hr = hp({ yy, xx -> (ch(0, yy, xx) - ch(1, yy, xx)).toDouble() }, y, x)
                val hb = hp({ yy, xx -> (ch(2, yy, xx) - ch(1, yy, xx)).toDouble() }, y, x)
                lumE += hy * hy; chE += hr * hr + hb * hb; nE++
                for (c in 0..2) { val h = hp({ yy, xx -> img[yy][xx][c].toDouble() }, y, x); val ph = (y % 2) * 2 + (x % 2); ss[c][ph] += h * h; cnt[c][ph]++ }
            }
            if (gv >= tThr) { detS += hy * hy; nT++ }
            if (gv <= fThr) {
                noiS += hy * hy; nF++
                for (c in 0..2) { val h = hp({ yy, xx -> img[yy][xx][c].toDouble() }, y, x); chk[c] += if ((x + y) % 2 == 0) h else -h; chkN[c] += h * h }
            }
        }
        fun ratio(c: Int): Double { val r = DoubleArray(4) { sqrt(ss[c][it] / maxOf(1, cnt[c][it])) }; return r.max() / r.min() }
        var spk = 0; var tot = 0
        for (which in 0..1) {
            val u = Array(m) { y -> DoubleArray(m) { x -> (if (which == 0) ch(0, y, x) else ch(2, y, x)) - ch(1, y, x).toDouble() } }
            val d = Array(m) { DoubleArray(m) }; val dev = ArrayList<Double>()
            for (y in 1 until m - 1) for (x in 1 until m - 1) {
                val v = DoubleArray(9); var i = 0
                for (dy in -1..1) for (dx in -1..1) v[i++] = u[y + dy][x + dx]
                v.sort(); d[y][x] = u[y][x] - v[4]; dev += abs(d[y][x])
            }
            val mad = dev.sorted()[dev.size / 2].coerceAtLeast(1e-6)
            for (y in e until m - e) for (x in e until m - e) { tot++; if (abs(d[y][x]) > 8 * mad) spk++ }
        }
        return M(sqrt(chE / (2 * nE)) / sqrt(lumE / nE), maxOf(ratio(0), ratio(2)),
            (0..2).maxOf { abs(chk[it] / nF) / sqrt(chkN[it] / nF) }, 1e5 * spk / tot, sqrt(detS / nT), sqrt(noiS / nF))
    }

    private fun sheet(name: String, panels: List<Pair<String, Array<Array<FloatArray>>>>, wbR: Float, wbB: Float) {
        val img0 = panels[0].second; val m = img0.size; val s = 128
        // most detailed s x s window of the first panel
        var best = -1.0; var x0 = 0; var y0 = 0
        for (yy in 8 until m - s - 8 step s / 8) for (xx in 8 until m - s - 8 step s / 8) {
            var e = 0.0
            for (y in yy until yy + s step 4) for (x in xx until xx + s step 4) e += abs(img0[y][x + 2][1] - img0[y][x][1]) + abs(img0[y + 2][x][1] - img0[y][x][1])
            if (e > best) { best = e; x0 = xx; y0 = yy }
        }
        val z = 4
        val mean = (0 until s).sumOf { y -> (0 until s).sumOf { x -> img0[y0 + y][x0 + x][1].toDouble() } } / (s * s)
        val gain = (0.18 / maxOf(mean, 1e-4)).coerceIn(1.0, 40.0)
        val bi = BufferedImage(panels.size * s * z, s * z, BufferedImage.TYPE_INT_RGB)
        val wb = floatArrayOf(wbR, 1f, wbB)
        for ((pi, pair) in panels.withIndex()) {
            val img = pair.second
            for (y in 0 until s) for (x in 0 until s) {
                val c = IntArray(3) { ch -> (255 * (img[y0 + y][x0 + x][ch] * wb[ch] * gain).coerceIn(0.0, 1.0).pow(1 / 2.2)).toInt() }
                val rgb = (c[0] shl 16) or (c[1] shl 8) or c[2]
                for (dy in 0 until z) for (dx in 0 until z) bi.setRGB(pi * s * z + x * z + dx, (s - 1 - y) * z + dy, rgb)  // upright (RGGB rows run bottom-up)
            }
        }
        ImageIO.write(bi, "png", File(out, "$name.png"))
    }

    @Test
    fun sweep() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue("needs -PsrpLib", lib.isNotEmpty())
        val root = File(System.getenv("SRP_BURSTS_DIR") ?: "${System.getProperty("user.home")}/Downloads/My/Bursts")
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        check(handle != 0L) { "no Vulkan device" }
        val only = System.getenv("SRP_SWEEP")?.split(',')?.filter { it.isNotBlank() }
        val todo = variants.filter { (name, _, _) -> name.endsWith(":default") || only == null || only.any { name.startsWith(it) } }
        val scales = (System.getenv("SRP_SCALES") ?: "1,2").split(',').map { it.trim().toFloat() }
        // ratios[variant][scale] = list of per-burst metric ratios to default
        val log = File(out, "sweep.tsv").printWriter()
        log.println("burst\tscale\tvariant\tfc\tzip\tchk\tspk\tdet\tnoise")
        val ratios = HashMap<Pair<String, Float>, MutableList<DoubleArray>>()
        try {
            val onlyBursts = System.getenv("SRP_ONLY")?.split(',')?.filter { it.isNotBlank() }
            for (dir in root.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }.filter { d -> onlyBursts == null || d.name in onlyBursts }) {
                val b = loadBurst(dir) ?: continue
                println("TUNE burst ${b.name} snr %.1f tile ${b.fin.tileSize} crop (${b.ox},${b.oy})".format(b.fin.snr))
                for (scale in scales) {
                    val bases = HashMap<Boolean, M>()
                    val sheets = ArrayList<Pair<String, Array<Array<FloatArray>>>>()
                    for ((name, knOn, tune) in todo) {
                        val img = run(handle, b, tune, scale, knOn)
                        val m = metrics(img, b.wbR, b.wbB)
                        if (name.endsWith(":default")) bases[knOn] = m
                        // Ratios against the KernelNet default (the shipping config) for both kernels.
                        val bm = bases[true] ?: bases[knOn]!!
                        log.println("${b.name}\t$scale\t$name\t${m.fc}\t${m.zip}\t${m.chk}\t${m.spk}\t${m.det / bm.det}\t${m.noise / bm.noise}")
                        ratios.getOrPut(name to scale) { ArrayList() } += doubleArrayOf(m.fc / bm.fc, m.zip / bm.zip,
                            (m.chk + 1e-3) / (bm.chk + 1e-3), (m.spk + 1) / (bm.spk + 1), m.det / bm.det, m.noise / bm.noise)
                        if (name.endsWith(":default") || name in (System.getenv("SRP_SHEET") ?: "").split(',')) sheets += name to img
                        println("TUNE ${b.name} x${scale.toInt()} %-14s fc %.3f zip %.3f chk %.3f spk %.0f det %.3f noise %.3f"
                            .format(name, m.fc, m.zip, m.chk, m.spk, m.det / bm.det, m.noise / bm.noise))
                    }
                    sheet("${b.name}_x${scale.toInt()}", sheets, b.wbR, b.wbB)
                }
            }
        } finally {
            log.close()
            SrpBridge.nativeDestroy(handle)
        }
        fun gm(l: List<DoubleArray>, i: Int) = exp(l.map { ln(it[i]) }.average())
        for (scale in scales) for ((name, _, _) in todo) {
            val l = ratios[name to scale] ?: continue
            println("TUNE SUMMARY x${scale.toInt()} %-14s fc %.3f zip %.3f chk %.3f spk %.3f det %.3f noise %.3f (n=${l.size})"
                .format(name, gm(l, 0), gm(l, 1), gm(l, 2), gm(l, 3), gm(l, 4), gm(l, 5)))
        }
    }
}
