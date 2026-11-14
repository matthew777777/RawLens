package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Zipper root cause at 2x (owner 2026-11-14): which factor creates the
 * site-periodic edge pattern? Per output channel, on the 5 % strongest edges,
 * the max/min HP RMS over output phases of period P (P = 2 and P = 4 = one
 * Bayer period at 2x). Factors: KernelNet floor, colour-difference merge,
 * 1 frame vs all, KernelNet vs Wronski kernel, R/B (chroma) kernel width.
 * Finding (2026-11-14): the 2x zipper is aliasing from incomplete sub-pixel
 * coverage; no kernel width or order-1 regression removes it without blur;
 * it is mostly chroma, so R/B get a wider kernel than green (SrTuning.chromaScale2x).
 * Run: SRP_ZIP_BURST=<dir> ./gradlew :tools:sr-pipeline:test --rerun --tests '*ZipperRootCauseTest*'
 *      -PsrpDiag=1 -PsrpHeap=12g -PsrpLib=$PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib -i
 */
class ZipperRootCauseTest {
    private val spvDir = System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host"
    private fun spv(name: String) = File(spvDir, "$name.spv").readBytes()
    private val n = 512
    private val out = File("build/zipper").apply { mkdirs() }

    /** Phase ratio along one axis only (axis 0 = x%period, 1 = y%period). */
    private fun axisRatio(img: Array<Array<FloatArray>>, c: Int, period: Int, axis: Int, edges: List<Pair<Int, Int>>): Double {
        val ss = DoubleArray(period); val cnt = IntArray(period)
        for ((y, x) in edges) {
            var s = 0.0; for (dy in -1..1) for (dx in -1..1) s += img[y + dy][x + dx][c]
            val h = img[y][x][c] - s / 9
            val ph = if (axis == 0) x % period else y % period; ss[ph] += h * h; cnt[ph]++
        }
        val r = DoubleArray(period) { sqrt(ss[it] / maxOf(1, cnt[it])) }
        return r.max() / r.min()
    }

    private fun phaseRatio(img: Array<Array<FloatArray>>, c: Int, period: Int, edges: List<Pair<Int, Int>>): Double {
        val ss = DoubleArray(period * period); val cnt = IntArray(period * period)
        for ((y, x) in edges) {
            var s = 0.0; for (dy in -1..1) for (dx in -1..1) s += img[y + dy][x + dx][c]
            val h = img[y][x][c] - s / 9
            val ph = (y % period) * period + (x % period); ss[ph] += h * h; cnt[ph]++
        }
        val r = DoubleArray(ss.size) { sqrt(ss[it] / maxOf(1, cnt[it])) }
        return r.max() / r.min()
    }

    /** Sub-pixel coverage: fractional flow offsets of every frame vs the reference (tile medians). */
    @Test
    fun subpixelCoverage() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        System.getProperty("srpipeline.lib", "").takeIf { it.isNotEmpty() }?.let { System.load(it) }
        val dir = File(System.getenv("SRP_ZIP_BURST") ?: "${System.getProperty("user.home")}/Downloads/hdrplus_subset/IMG_20210228_133623")
        val files = dir.listFiles { f -> f.name.endsWith(".dng") }!!.sortedBy { it.name }.take(8)
        val meta = DngRead.readMeta(files[0].absolutePath)
        val full = DngRead.readNormalized(files[0].absolutePath, meta)
        val ox = (System.getenv("SRP_ZIP_X")?.toInt() ?: 3200) and -2
        val oy = (System.getenv("SRP_ZIP_Y")?.toInt() ?: 896) and -2
        fun crop(f: Array<FloatArray>) = Array(n) { y -> f[oy + y].copyOfRange(ox, ox + n) }
        val ref = crop(full)
        val cfg = PipelineConfig(greyMethod = "FIR7", factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8), edgeExtendFlow = true)
        val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
        val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(ref, "FIR7"), loop)
        val pts = ArrayList<Pair<Double, Double>>(); pts += 0.0 to 0.0
        for (f in files.drop(1)) {
            val fl = com.rawlens.srpipeline.stages.alignLevels(init,
                com.rawlens.srpipeline.stages.Grey.computeGreyImages(crop(DngRead.readNormalized(f.absolutePath, DngRead.readMeta(f.absolutePath))), "FIR7"), loop)
            val xs = fl.flatMap { r -> r.map { it[0].toDouble() } }.sorted(); val ys = fl.flatMap { r -> r.map { it[1].toDouble() } }.sorted()
            val mx = xs[xs.size / 2]; val my = ys[ys.size / 2]
            val spread = fl.flatMap { r -> r.map { kotlin.math.hypot(it[0] - mx, it[1] - my) } }.sorted().let { it[it.size * 9 / 10] }
            pts += mx to my
            println("COVER ${f.name}: median flow (%.3f, %.3f) px, frac (%.2f, %.2f), mod-2 phase (%.2f, %.2f), 90%% tile spread %.2f px"
                .format(mx, my, mx - kotlin.math.floor(mx), my - kotlin.math.floor(my), ((mx % 2) + 2) % 2, ((my % 2) + 2) % 2, spread))
        }
        // Covering radius of the period-1 (G-ish) and period-2 (R/B) cells by the frame offsets.
        for (period in listOf(1.0, 2.0)) {
            var worst = 0.0
            for (gy in 0 until 20) for (gx in 0 until 20) {
                val tx = gx * period / 20; val ty = gy * period / 20
                var best = Double.MAX_VALUE
                for ((px, py) in pts) for (sx in -1..1) for (sy in -1..1) {
                    val dx = tx - (((px % period) + period) % period + sx * period); val dy = ty - (((py % period) + period) % period + sy * period)
                    best = minOf(best, kotlin.math.hypot(dx, dy))
                }
                worst = maxOf(worst, best)
            }
            println("COVER period %.0f cell: covering radius %.2f px (uniform 8 points ~%.2f)".format(period, worst, period * 0.27))
        }
    }

    @Test
    fun rootCause() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", ""); assumeTrue(lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate(); check(handle != 0L)
        val dir = File(System.getenv("SRP_ZIP_BURST") ?: "${System.getProperty("user.home")}/Downloads/hdrplus_subset/IMG_20210228_133623")
        val files = dir.listFiles { f -> f.name.endsWith(".dng") }!!.sortedBy { it.name }.take(8)
        val meta = DngRead.readMeta(files[0].absolutePath)
        val full = DngRead.readNormalized(files[0].absolutePath, meta)
        val (alpha, beta) = if (meta.noiseCount == 0) com.rawlens.srpipeline.stages.SceneNoise.estimate(full)
            else DngRead.noiseAlphaBeta(meta.noise, meta.noiseCount)
        val ox = (System.getenv("SRP_ZIP_X")?.toInt() ?: 3200) and -2
        val oy = (System.getenv("SRP_ZIP_Y")?.toInt() ?: 896) and -2
        fun crop(f: Array<FloatArray>) = Array(n) { y -> f[oy + y].copyOfRange(ox, ox + n) }
        val frames = listOf(crop(full)) + files.drop(1).map { crop(DngRead.readNormalized(it.absolutePath, DngRead.readMeta(it.absolutePath))) }
        val neutral = SrpBridge.readDngColorProfile(files[0].absolutePath).neutral
        val wbR = if (neutral != null && neutral.size == 3) (neutral[1] / neutral[0]).toFloat() else 1f
        val wbB = if (neutral != null && neutral.size == 3) (neutral[1] / neutral[2]).toFloat() else 1f
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full, alpha, beta))
        val (fx, fy) = KernelNet.rggbFlips(meta.cfa)
        val alignSpv = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"), alignWg = spv("align_wg"),
            argminWg = spv("corr_argmin_wg"), l2Fused = spv("corr_l2_fused"))
        val stageSpv = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = spv("fft"),
            corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"), pyrDown = spv("pyr_down"),
            kernelNet = spv("kernelnet"))
        fun run(kn: Boolean, floor: Float, cd: Boolean, nFrames: Int, chroma: Float = 1f): Array<Array<FloatArray>> {
            val cfg = PipelineConfig(scale = 2f, bayer = true, greyMethod = "FIR7", factors = listOf(1, 2, 4, 4),
                tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat(), kMin = 0.64f),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(noiseCorrection = false, s3 = true), robustnessEnabled = true,
                colourDiff = if (cd) floatArrayOf(wbR, wbB) else null, chromaScale = chroma,
                kernelNet = if (!kn) null else KernelNetConfig(weights = KernelNet.bundledWeights(),
                    sigma = KernelNet.sigma(FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                    sigmaFloor = floor, fullReference = full, cropX = ox, cropY = oy, flipX = fx, flipY = fy))
            val use = frames.take(nFrames)
            val buf = VulkanPipeline.run({ use[0].map { it.copyOf() }.toTypedArray() },
                use.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } }, cfg, handle, alignSpv, stageSpv)
            val m = 2 * n; val fb = buf.asFloatBuffer()
            return Array(m) { y -> Array(m) { x -> FloatArray(3) { c -> fb.get((y * m + x) * 3 + c).let { if (it.isNaN()) 0f else it } } } }
        }
        val variants = listOf(
            "KN floor0.90 cd on  N8" to arrayOf<Any>(true, .90f, true, 8),
            "KN floor0.70 cd on  N8" to arrayOf<Any>(true, .70f, true, 8),
            "KN floor0.70 cd off N8" to arrayOf<Any>(true, .70f, false, 8),
            "KN floor0.70 cd on  N1" to arrayOf<Any>(true, .70f, true, 1),
            "KN floor0.70 cd off N1" to arrayOf<Any>(true, .70f, false, 1),
            "KN 0.70 chroma x1.5  N8" to arrayOf<Any>(true, .70f, true, 8, 1.5f),
            "KN 0.70 chroma x2    N8" to arrayOf<Any>(true, .70f, true, 8, 2.0f),
            "KN 0.70 chroma x3    N8" to arrayOf<Any>(true, .70f, true, 8, 3.0f),
            "WR kMin0.64 cd on  N8" to arrayOf<Any>(false, .90f, true, 8),
            "WR kMin0.64 cd off N8" to arrayOf<Any>(false, .90f, false, 8),
        )
        try {
            val imgs = variants.map { (name, v) -> name to run(v[0] as Boolean, v[1] as Float, v[2] as Boolean, v[3] as Int,
                v.getOrNull(4) as? Float ?: 1f) }
            // Edge set from the first variant's WB luma (same pixels for all variants).
            val ref = imgs[0].second; val m = ref.size
            val g = ArrayList<Triple<Double, Int, Int>>()
            for (y in 8 until m - 8) for (x in 8 until m - 8) {
                fun Y(yy: Int, xx: Int) = ref[yy][xx][0] * wbR + 2 * ref[yy][xx][1] + ref[yy][xx][2] * wbB
                g += Triple((abs(Y(y, x + 2) - Y(y, x - 2)) + abs(Y(y + 2, x) - Y(y - 2, x))).toDouble(), y, x)
            }
            val edges = g.sortedByDescending { it.first }.take(g.size / 20).map { it.second to it.third }
            val textured = g.sortedByDescending { it.first }.take(g.size * 3 / 10).map { it.second to it.third }
            fun detail(img: Array<Array<FloatArray>>): Double {
                var s2 = 0.0
                for ((y, x) in textured) {
                    fun Y(yy: Int, xx: Int) = img[yy][xx][0] * wbR + 2 * img[yy][xx][1] + img[yy][xx][2] * wbB
                    var s = 0.0; for (dy in -1..1) for (dx in -1..1) s += Y(y + dy, x + dx)
                    val hpv = Y(y, x) - s / 9; s2 += hpv * hpv
                }
                return sqrt(s2 / textured.size)
            }
            val d0 = detail(imgs[0].second)
            // Raw float dumps (2n x 2n x 3, LE) for external viewing.
            for ((k, p) in imgs.withIndex()) {
                val bb = ByteBuffer.allocate(m * m * 3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until m) for (x in 0 until m) for (c in 0..2) bb.putFloat(p.second[y][x][c])
                File(out, "v%02d.f32".format(k)).writeBytes(bb.array())
            }
            File(out, "wb.txt").writeText("$wbR $wbB $fy")
            for ((name, img) in imgs) {
                val r = (0..2).map { c -> phaseRatio(img, c, 2, edges) }; val r4 = (0..2).map { c -> phaseRatio(img, c, 4, edges) }
                println("ZIP %-24s P2 R %.3f G %.3f B %.3f | P4 R %.3f G %.3f B %.3f | detail %.3f".format(name, r[0], r[1], r[2], r4[0], r4[1], r4[2], detail(img) / d0))
                println("    x-only P2 R %.3f G %.3f B %.3f P4 R %.3f G %.3f B %.3f | y-only P2 R %.3f G %.3f B %.3f P4 R %.3f G %.3f B %.3f".format(
                    axisRatio(img, 0, 2, 0, edges), axisRatio(img, 1, 2, 0, edges), axisRatio(img, 2, 2, 0, edges),
                    axisRatio(img, 0, 4, 0, edges), axisRatio(img, 1, 4, 0, edges), axisRatio(img, 2, 4, 0, edges),
                    axisRatio(img, 0, 2, 1, edges), axisRatio(img, 1, 2, 1, edges), axisRatio(img, 2, 2, 1, edges),
                    axisRatio(img, 0, 4, 1, edges), axisRatio(img, 1, 4, 1, edges), axisRatio(img, 2, 4, 1, edges)))
            }
            // Sheet: most edge-dense 128 window, 4x, upright, WB, all variants.
            var best = -1; var bx = 0; var by = 0
            for (yy in 8 until m - 136 step 32) for (xx in 8 until m - 136 step 32) {
                val c = edges.count { (y, x) -> y in yy until yy + 128 && x in xx until xx + 128 }
                if (c > best) { best = c; bx = xx; by = yy }
            }
            val s = 128; val z = 4; val wb = floatArrayOf(wbR, 1f, wbB)
            // Exposure from the window's 99th percentile green (no clipping in the view).
            val gs = (0 until s).flatMap { y -> (0 until s).map { x -> ref[by + y][bx + x][1].toDouble() } }.sorted()
            val gain = (0.85 / maxOf(gs[gs.size * 99 / 100], 1e-4)).coerceIn(1.0, 40.0)
            val bi = BufferedImage(imgs.size * s * z, s * z, BufferedImage.TYPE_INT_RGB)
            for ((pi, p) in imgs.withIndex()) for (y in 0 until s) for (x in 0 until s) {
                val c = IntArray(3) { ch -> (255 * (p.second[by + y][bx + x][ch] * wb[ch] * gain).coerceIn(0.0, 1.0).pow(1 / 2.2)).toInt() }
                val rgb = (c[0] shl 16) or (c[1] shl 8) or c[2]
                val yy = if (fy) s - 1 - y else y
                for (dy in 0 until z) for (dx in 0 until z) bi.setRGB(pi * s * z + x * z + dx, yy * z + dy, rgb)
            }
            ImageIO.write(bi, "png", File(out, "zipper_sheet.png"))
            println("ZIP sheet window ($bx,$by) -> build/zipper/zipper_sheet.png (panels in variant order)")
        } finally { SrpBridge.nativeDestroy(handle) }
    }
}
