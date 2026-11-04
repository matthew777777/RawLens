package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.HotPixels
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The app's merge on the Mac's Vulkan (MoltenVK): VulkanPipeline.run with the
 * app's compiled .spv, configured like MfsrMerge.runEngine (DNG noise profile
 * R,G,B -> R,G1,B,G2; full-frame SNR -> tiles/kernel params; NoiseLutGen
 * robustness LUT, S.3 on; HotPixels before the merge; KernelNet on the full
 * flipped reference with the app's flips; ColourDiff with AsShotNeutral WB).
 * Frames are full sensor rows flipped to RGGB exactly like the app (GRBG ->
 * horizontal flip), cropped in pipeline coordinates.
 *
 * Per crop and variant (Wronski / KernelNet x zipper fix off / on):
 *  zipper   R/B 3x3-HP RMS max/min over Bayer sites on the 5 % strongest edges
 *           (green's own ratio printed as the no-zipper yardstick)
 *  checker  max over channels of |mean((-1)^(x+y) hp)| / rms(hp) on the 20 % flattest
 *  speckle  isolated WB-chroma outliers per 100k px (|u - med3x3(u)| > 8 * MAD(u))
 * Sheet per crop: single frame | Wronski off | Wronski on | KernelNet off | KernelNet on.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*VulkanBurstCheckTest*' -PsrpDiag=1 \
 *      -PsrpLib=<libsrpipeline.dylib> -i      (spv: -Dsrpipeline.spvdir, default the app build)
 */
class VulkanBurstCheckTest {
    private val root = "/Users/monikamalinowska/Downloads/My/Photos/Photos"
    private val bursts = listOf(
        "IMG_20260929_170823_957", "IMG_20260917_141353_404", "IMG_20260919_184641_979",
        "IMG_20260920_133434_814", "IMG_20260921_194618_520",
    )
    private val W = 4080
    private val H = 3060
    private var n = 512
    private val out = File("build/vkcheck").apply { mkdirs() }

    private class Tags(val strip: Long, val black: Float, val white: Float, val noise: DoubleArray, val neutral: DoubleArray, val iso: Int)

    private fun tags(path: String): Tags {
        val bb = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val off = bb.getInt(4)
        val cnt = bb.getShort(off).toInt() and 0xffff
        var strip = 0L; var black = 0f; var white = 0f; var iso = 0
        var noise = DoubleArray(0); var neutral = doubleArrayOf(1.0, 1.0, 1.0)
        for (i in 0 until cnt) {
            val e = off + 2 + 12 * i
            val tag = bb.getShort(e).toInt() and 0xffff
            val type = bb.getShort(e + 2).toInt() and 0xffff
            val c = bb.getInt(e + 4)
            val v = e + 8
            when (tag) {
                273 -> strip = (if (type == 3) bb.getShort(v).toInt() and 0xffff else bb.getInt(v)).toLong()
                34855 -> iso = bb.getShort(v).toInt() and 0xffff
                50714 -> { val p = bb.getInt(v); black = bb.getInt(p).toFloat() / bb.getInt(p + 4) }
                50717 -> white = (if (type == 3) bb.getShort(v).toInt() and 0xffff else bb.getInt(v)).toFloat()
                50728 -> { val p = bb.getInt(v); neutral = DoubleArray(c) { bb.getInt(p + 8 * it).toDouble() / bb.getInt(p + 8 * it + 4) } }
                51041 -> { val p = bb.getInt(v); noise = DoubleArray(c) { bb.getDouble(p + 8 * it) } }
            }
        }
        return Tags(strip, black, white, noise, neutral, iso)
    }

    /** Full frame, flipped to RGGB like the app (GRBG sensor -> horizontal flip). */
    private fun fullFlipped(path: String, t: Tags): Array<FloatArray> = RandomAccessFile(path, "r").use { f ->
        val row = ByteArray(W * 2)
        // DNG CFAPattern 01 02 00 01 = G B / R G (GBRG): red bottom-left -> vertical flip
        Array(H) { y ->
            f.seek(t.strip + (H - 1 - y).toLong() * W * 2)
            f.readFully(row)
            val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(W) { x -> ((bb.getShort(x * 2).toInt() and 0xffff) - t.black) / (t.white - t.black) }
        }
    }

    private fun crop(f: Array<FloatArray>, x0: Int, y0: Int) = Array(n) { y -> f[y0 + y].copyOfRange(x0, x0 + n) }

    /** Pipeline-coordinate crop of the flipped frame, read straight from the file (pipeline x <-> sensor W-1-x). */
    private fun cropFlipped(path: String, t: Tags, x0: Int, y0: Int): Array<FloatArray> = RandomAccessFile(path, "r").use { f ->
        val row = ByteArray(n * 2)
        Array(n) { y ->
            f.seek(t.strip + ((H - 1 - (y0 + y)).toLong() * W + x0) * 2)
            f.readFully(row)
            val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(n) { x -> ((bb.getShort(x * 2).toInt() and 0xffff) - t.black) / (t.white - t.black) }
        }
    }

    private fun spv(name: String, dirOverride: String? = null): ByteArray {
        val dir = dirOverride ?: System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "../../app/build/generated/srpspv/sr_spv"
        return File(dir, "$name.spv").readBytes()
    }

    /** Mean |grad Y| on the top 5 % edges of the single frame (sharpness, compare across methods). */
    private fun edgeSharp(img: Array<Array<FloatArray>>, ref: Array<FloatArray>): Double {
        val g = ArrayList<Pair<Double, Double>>()
        for (y in 16 until n - 16) for (x in 16 until n - 16) {
            fun Y(yy: Int, xx: Int) = (img[yy][xx][0] + 2 * img[yy][xx][1] + img[yy][xx][2]) / 4.0
            val e = (abs(ref[y][x + 2] - ref[y][x - 2]) + abs(ref[y + 2][x] - ref[y - 2][x])).toDouble()
            g += e to (abs(Y(y, x + 1) - Y(y, x - 1)) + abs(Y(y + 1, x) - Y(y - 1, x)))
        }
        g.sortByDescending { it.first }
        return g.take(g.size / 20).map { it.second }.average()
    }

    /** Checker in TEXTURE: per 8x8 block (top 30 % edge energy), |sum (-1)^(x+y) hpY| / sqrt(64 sum hpY^2), mean. */
    private fun textureChecker(img: Array<Array<FloatArray>>, ref: Array<FloatArray>): Double {
        val blocks = ArrayList<Pair<Double, Double>>()
        for (by in 16 until n - 24 step 8) for (bx in 16 until n - 24 step 8) {
            var e = 0.0; var num = 0.0; var den = 0.0
            for (y in by until by + 8) for (x in bx until bx + 8) {
                e += abs(ref[y][x + 2] - ref[y][x]) + abs(ref[y + 2][x] - ref[y][x])
                fun Y(yy: Int, xx: Int) = (img[yy][xx][0] + 2 * img[yy][xx][1] + img[yy][xx][2]) / 4.0
                var m = 0.0; for (dy in -1..1) for (dx in -1..1) m += Y(y + dy, x + dx)
                val h = Y(y, x) - m / 9
                num += if ((x + y) % 2 == 0) h else -h; den += h * h
            }
            blocks += e to abs(num) / sqrt(64 * den + 1e-20)
        }
        val sorted = blocks.sortedByDescending { it.first }
        return sorted.take(sorted.size * 3 / 10).map { it.second }.average()
    }

    /** The most textured 512 window (coarse structure search on the reference). */
    private fun texturedOrigin(f: Array<FloatArray>): Pair<Int, Int> {
        var best = -1.0; var bx = 0; var by = 0
        for (y0 in 64 until H - n - 64 step 128) for (x0 in 64 until W - n - 64 step 128) {
            var s = 0.0
            for (y in y0 until y0 + n step 8) for (x in x0 until x0 + n step 8) s += abs(f[y][x + 2] - f[y][x]) + abs(f[y + 2][x] - f[y][x])
            if (s > best) { best = s; bx = x0; by = y0 }
        }
        return (bx and -2) to (by and -2)
    }

    private class Metrics(val zipper: Double, val gYard: Double, val checker: Double, val speckle: Double)

    private fun metrics(img: Array<Array<FloatArray>>, ref: Array<FloatArray>, wbR: Float, wbB: Float): Metrics {
        val m = 16
        val g = ArrayList<Double>()
        for (y in m until n - m) for (x in m until n - m)
            g += (abs(ref[y][x + 2] - ref[y][x - 2]) + abs(ref[y + 2][x] - ref[y - 2][x])).toDouble()
        val sorted = g.sorted()
        val eThr = sorted[(sorted.size * 0.95).toInt()]; val fThr = sorted[(sorted.size * 0.20).toInt()]
        fun hp(c: Int, y: Int, x: Int): Double {
            var s = 0.0
            for (dy in -1..1) for (dx in -1..1) s += img[y + dy][x + dx][c]
            return img[y][x][c] - s / 9
        }
        val ss = Array(3) { DoubleArray(4) }; val cnt = Array(3) { IntArray(4) }
        val chk = DoubleArray(3); val chkN = DoubleArray(3); var flatN = 0
        var k = 0
        for (y in m until n - m) for (x in m until n - m) {
            val gv = g[k++]
            if (gv >= eThr) for (c in 0..2) { val h = hp(c, y, x); val ph = (y % 2) * 2 + (x % 2); ss[c][ph] += h * h; cnt[c][ph]++ }
            if (gv <= fThr) { flatN++; for (c in 0..2) { val h = hp(c, y, x); chk[c] += if ((x + y) % 2 == 0) h else -h; chkN[c] += h * h } }
        }
        fun ratio(c: Int): Double { val r = DoubleArray(4) { sqrt(ss[c][it] / maxOf(1, cnt[c][it])) }; return r.max() / r.min() }
        val zipper = maxOf(ratio(0), ratio(2))
        val checker = (0..2).maxOf { abs(chk[it] / flatN) / sqrt(chkN[it] / flatN) }
        // speckles on WB chroma
        var spk = 0; var tot = 0
        for (which in 0..1) {
            val u = Array(n) { y -> DoubleArray(n) { x -> if (which == 0) wbR * img[y][x][0] - img[y][x][1].toDouble() else wbB * img[y][x][2] - img[y][x][1].toDouble() } }
            val dev = ArrayList<Double>()
            val d = Array(n) { DoubleArray(n) }
            for (y in 1 until n - 1) for (x in 1 until n - 1) {
                val v = DoubleArray(9); var i = 0
                for (dy in -1..1) for (dx in -1..1) v[i++] = u[y + dy][x + dx]
                v.sort(); d[y][x] = u[y][x] - v[4]; dev += abs(d[y][x])
            }
            val mad = dev.sorted()[dev.size / 2].coerceAtLeast(1e-6)
            for (y in m until n - m) for (x in m until n - m) { tot++; if (abs(d[y][x]) > 8 * mad) spk++ }
        }
        return Metrics(zipper, ratio(1), checker, 1e5 * spk / tot)
    }

    private fun sheet(name: String, panels: List<Array<Array<FloatArray>>>, wbR: Float, wbB: Float, gain: Float, ref: Array<FloatArray>) {
        // the most detailed 128x128 window of the crop (edge energy in the single frame), 4x
        val s = 128; val z = 4
        var best = -1.0; var x0 = 0; var y0 = 0
        for (yy in 16 until n - s - 16 step 16) for (xx in 16 until n - s - 16 step 16) {
            var e = 0.0
            for (y in yy until yy + s step 2) for (x in xx until xx + s step 2) e += abs(ref[y][x + 2] - ref[y][x]) + abs(ref[y + 2][x] - ref[y][x])
            if (e > best) { best = e; x0 = xx; y0 = yy }
        }
        val bi = BufferedImage(panels.size * s * z, s * z, BufferedImage.TYPE_INT_RGB)
        val wb = floatArrayOf(wbR, 1f, wbB)
        for ((p, img) in panels.withIndex()) for (y in 0 until s) for (x in 0 until s) {
            val px = img[y0 + y][x0 + x]
            val v = IntArray(3) { c -> ((px[c] * wb[c] * gain).coerceIn(0f, 1f).toDouble().pow(1 / 2.2) * 255).toInt() }
            val rgb = (v[0] shl 16) or (v[1] shl 8) or v[2]
            for (j in 0 until z) for (i in 0 until z) bi.setRGB(p * s * z + x * z + i, y * z + j, rgb)
        }
        ImageIO.write(bi, "png", File(out, "$name.png"))
    }

    private fun bilinear(raw: Array<FloatArray>): Array<Array<FloatArray>> = Array(n) { y -> Array(n) { x -> FloatArray(3) { c ->
        var s = 0f; var k = 0
        for (dy in -1..1) for (dx in -1..1) {
            val yy = y + dy; val xx = x + dx
            if (yy !in 0 until n || xx !in 0 until n || yy % 2 + xx % 2 != c || (c == 1 && dx != 0 && dy != 0)) continue
            s += raw[yy][xx]; k++
        }
        s / k
    } } }

    @Test
    fun allBurstsOnVulkan() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue("needs -PsrpLib=<libsrpipeline.dylib>", lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        check(handle != 0L) { "no Vulkan device" }
        val fft = spv("fft")
        val alignSpv = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = fft, ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"))
        fun stageSpv(kn: Boolean) = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = fft, corr = spv("corr"),
            greyFft = spv("grey_fft"), kernelNet = if (kn) spv("kernelnet") else null)
        val only = (System.getenv("SRP_BURSTS") ?: "").split(',').filter { it.isNotBlank() }
        try {
            for (burst in bursts.filter { only.isEmpty() || it in only }) {
                val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
                val t = tags(files[0])
                val alpha = doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
                val beta = doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
                val wbR = (t.neutral[1] / t.neutral[0]).toFloat(); val wbB = (t.neutral[1] / t.neutral[2]).toFloat()
                val full = listOf(fullFlipped(files[0], t))
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full[0], alpha, beta))
                val curves = NoiseLutGen.generate(alpha, beta)
                // 32/64 px tiles (low SNR) need a bigger crop through the 4-level pyramid; the app runs full frames
                n = if (fin.tileSize >= 32) 1024 else 512
                val tex = texturedOrigin(full[0])
                fun fit(p: Pair<Int, Int>) = (minOf(p.first, W - n) and -2) to (minOf(p.second, H - n) and -2)
                val places = listOf("tl" to (400 to 300), "c" to (1784 to 1274), "br" to (3200 to 2300), "tex" to tex).map { it.first to fit(it.second) }
                println("VK $burst ISO ${t.iso} snr %.1f tile ${fin.tileSize} wb R %.2f B %.2f".format(fin.snr, wbR, wbB))
                for ((place, xy) in places) {
                    val (ox, oy) = xy
                    val frames = listOf(crop(full[0], ox, oy)) + files.drop(1).map { cropFlipped(it, t, ox, oy) }
                    // HotPixels exactly as MfsrMerge.runEngine (ref candidates, confirm on frames 1-2)
                    val defects = HotPixels.confirm(HotPixels.candidates(frames[0]), n, n,
                        frames.subList(1, 3).map { f -> { y: Int, x: Int -> f[y][x] } })
                    frames.forEach { HotPixels.correct(it, defects) }
                    val tiles = try { BurstConfig.sanitize(1f, fin.tileSize, listOf(1, 2, 4, 4), fin.tileSizes, n, n); fin.tileSizes }
                        catch (_: IllegalArgumentException) { listOf(16, 16, 16, 8) }
                    fun cfg(kn: Boolean, zip: Boolean) = PipelineConfig(
                        scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = tiles,
                        kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                        kernelPreprocessing = "gat",
                        kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                        robustnessParams = RobustnessParams(noiseCorrection = true, s3 = true), robustnessEnabled = true,
                        sigmaSqCurve = curves.sigmaSq, dSqCurve = curves.dSq, icaIterations = 3,
                        colourDiff = if (zip) floatArrayOf(wbR, wbB) else null,
                        kernelNet = if (!kn) null else KernelNetConfig(
                            weights = KernelNet.bundledWeights(),
                            sigma = KernelNet.sigma(FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                            fullReference = full[0], cropX = ox, cropY = oy, flipX = false, flipY = true),
                    )
                    val results = ArrayList<Pair<String, Array<Array<FloatArray>>>>()
                    results += "single" to bilinear(frames[0])
                    for ((kn, zip) in listOf(false to false, false to true, true to false, true to true)) {
                        val buf = VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
                            frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } },
                            cfg(kn, zip), handle, alignSpv, stageSpv(kn))
                        val fb = buf.asFloatBuffer()
                        val img = Array(n) { y -> Array(n) { x -> FloatArray(3) { c -> fb.get((y * n + x) * 3 + c).let { if (it.isNaN()) 0f else it } } } }
                        results += "${if (kn) "kernelnet" else "wronski"}_${if (zip) "on" else "off"}" to img
                    }
                    val line = StringBuilder()
                    for ((name, img) in results.drop(1)) {
                        val m = metrics(img, frames[0], wbR, wbB)
                        line.append(" | %s zip %.2f (G %.2f) chk %.3f spk %.0f".format(name, m.zipper, m.gYard, m.checker, m.speckle))
                    }
                    val m0 = metrics(results[0].second, frames[0], wbR, wbB)
                    println("VK $burst $place($ox,$oy) defects ${defects.size} | single spk %.0f chk %.3f$line".format(m0.speckle, m0.checker))
                    val mean = frames[0].sumOf { r -> r.sumOf { it.toDouble() } } / (n * n)
                    sheet("${burst}_$place", results.map { it.second }, wbR, wbB, (0.18 / maxOf(mean, 1e-4)).toFloat().coerceIn(1f, 40f), frames[0])
                }
            }
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
        println("VK sheets in ${out.absolutePath} (panels: single | wronski off | wronski on | kernelnet off | kernelnet on)")
    }

    /** KernelNet minimum kernel width (kernelnet.comp clamp) vs texture checker, zipper fix on, Vulkan. */
    @Test
    fun kernelNetFloorOnVulkan() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", "")
        assumeTrue(lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        val scratch = System.getenv("SRP_SCRATCH") ?: error("SRP_SCRATCH")
        val variants = System.getenv("SRP_KN_VARIANTS")?.split(';')?.filter { it.isNotBlank() }?.map { v ->
            val (label, dir) = v.split('=', limit = 2); label to dir.takeIf { it.isNotEmpty() }
        } ?: listOf("0.25" to null, "0.40" to "$scratch/spv_kn0.40", "0.60" to "$scratch/spv_kn0.60")
        val caps = (System.getenv("SRP_KN_CAPS") ?: "").split(',').mapNotNull { it.toFloatOrNull() }
        val fft = spv("fft")
        val alignSpv = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = fft, ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"))
        val wrMode = System.getenv("SRP_MODE") == "wronski"   // variants override kernels.spv instead of kernelnet.spv
        fun stageSpv(knDir: String?, kn: Boolean) = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"),
            kernels = if (wrMode && !kn) spv("kernels", knDir) else spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = fft, corr = spv("corr"),
            greyFft = spv("grey_fft"), kernelNet = if (kn) spv("kernelnet", knDir) else null)
        try {
            for (burst in bursts) {
                val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
                val t = tags(files[0])
                val alpha = doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
                val beta = doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
                val wbR = (t.neutral[1] / t.neutral[0]).toFloat(); val wbB = (t.neutral[1] / t.neutral[2]).toFloat()
                val full = fullFlipped(files[0], t)
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full, alpha, beta))
                val curves = NoiseLutGen.generate(alpha, beta)
                n = if (fin.tileSize >= 32) 1024 else 512
                fun fit(p: Pair<Int, Int>) = (minOf(p.first, W - n) and -2) to (minOf(p.second, H - n) and -2)
                val places = listOf("tl" to (400 to 300), "c" to (1784 to 1274), "br" to (3200 to 2300), "tex" to texturedOrigin(full)).map { it.first to fit(it.second) }
                for ((place, xy) in places) {
                    val (ox, oy) = xy
                    val frames = listOf(crop(full, ox, oy)) + files.drop(1).map { cropFlipped(it, t, ox, oy) }
                    val defects = HotPixels.confirm(HotPixels.candidates(frames[0]), n, n, frames.subList(1, 3).map { f -> { y: Int, x: Int -> f[y][x] } })
                    frames.forEach { HotPixels.correct(it, defects) }
                    val tiles = try { BurstConfig.sanitize(1f, fin.tileSize, listOf(1, 2, 4, 4), fin.tileSizes, n, n); fin.tileSizes } catch (_: IllegalArgumentException) { listOf(16, 16, 16, 8) }
                    fun cfg(kn: Boolean, cap: Float = KernelNet.DEFAULT_SIGMA_CAP, label: String = "") = PipelineConfig(scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = tiles,
                        kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat(),
                            kMin = if (label == "base") 0f else (System.getenv("SRP_KMIN")?.toFloat() ?: 0f)),
                        kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                        robustnessParams = RobustnessParams(noiseCorrection = true, s3 = true), robustnessEnabled = true,
                        sigmaSqCurve = curves.sigmaSq, dSqCurve = curves.dSq, colourDiff = floatArrayOf(wbR, wbB),
                        kernelNet = if (!kn) null else KernelNetConfig(weights = KernelNet.bundledWeights(),
                            sigma = KernelNet.sigma(FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                            sigmaCap = cap, fullReference = full, cropX = ox, cropY = oy, flipX = false, flipY = true))
                    fun runOnce(kn: Boolean, dir: String?, cap: Float = KernelNet.DEFAULT_SIGMA_CAP, label: String = "base"): Array<Array<FloatArray>> {
                        val buf = VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
                            frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } }, cfg(kn, cap, label), handle, alignSpv, stageSpv(dir, kn))
                        val fb = buf.asFloatBuffer()
                        return Array(n) { y -> Array(n) { x -> FloatArray(3) { c -> fb.get((y * n + x) * 3 + c).let { if (it.isNaN()) 0f else it } } } }
                    }
                    val single = bilinear(frames[0])
                    val wr = runOnce(false, null)
                    val panels = arrayListOf(single, wr)
                    val sharpW = edgeSharp(wr, frames[0])
                    val line = StringBuilder("single texChk %.3f | wronski_on texChk %.3f zip %.2f".format(textureChecker(single, frames[0]), textureChecker(wr, frames[0]), metrics(wr, frames[0], wbR, wbB).zipper))
                    val oneCap = System.getenv("SRP_KN_CAP")?.toFloatOrNull() ?: KernelNet.DEFAULT_SIGMA_CAP
                    val runs = if (caps.isEmpty()) variants.map { v ->
                        val c = v.first.substringAfter('@', "").toFloatOrNull()
                        Triple(v.first, v.second, c ?: if (v.first == "base") KernelNet.DEFAULT_SIGMA_CAP else oneCap) }
                        else caps.map { Triple("cap%.1f".format(it), null as String?, it) }
                    for ((label, dir, cap) in runs) {
                        val img = runOnce(!wrMode, dir, cap, label)
                        panels += img
                        val m = metrics(img, frames[0], wbR, wbB)
                        line.append(" | kn_$label texChk %.3f zip %.2f (G %.2f) spk %.0f sharp %.3f".format(textureChecker(img, frames[0]), m.zipper, m.gYard, m.speckle, edgeSharp(img, frames[0]) / sharpW))
                    }
                    println("KNF $burst $place $line")
                    val mean = frames[0].sumOf { r -> r.sumOf { it.toDouble() } } / (n * n)
                    sheet("knfloor_${burst}_$place", panels, wbR, wbB, (0.18 / maxOf(mean, 1e-4)).toFloat().coerceIn(1f, 40f), frames[0])
                }
            }
        } finally { SrpBridge.nativeDestroy(handle) }
    }

    /** Full-res KernelNet (+zipper fix, HotPixels) merge per burst -> build/vkfull/<burst>.f32 (sensor orientation) + .tags. */
    @Test
    fun fullResKernelNet() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", ""); assumeTrue(lib.isNotEmpty())
        System.load(lib)
        val handle = SrpBridge.nativeCreate()
        val dir = File("build/vkfull").apply { mkdirs() }
        val fft = spv("fft")
        val alignSpv = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = fft, ica = spv("ica"), corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"))
        val stage = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"), divide = spv("divide"),
            robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = fft, corr = spv("corr"), greyFft = spv("grey_fft"), kernelNet = spv("kernelnet"))
        try {
            val onlyB = (System.getenv("SRP_BURSTS") ?: "").split(',').filter { it.isNotBlank() }
            // SRP_NOISE=a,b: our blind estimate (noise_estimate.h) for all planes, no DNG profile
            val est = System.getenv("SRP_NOISE")?.split(',')?.map { it.toDouble() }
            for (burst in bursts.filter { onlyB.isEmpty() || it in onlyB }) {
                val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
                val t = tags(files[0])
                val alpha = if (est != null) DoubleArray(4) { est[0] } else doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
                val beta = if (est != null) DoubleArray(4) { est[1] } else doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
                val ref = fullFlipped(files[0], t)
                val fin = BurstConfig.finalize(BurstConfig.estimateSnr(ref, alpha, beta))
                val curves = NoiseLutGen.generate(alpha, beta)
                val defects = HotPixels.confirm(HotPixels.candidates(ref), H, W, files.subList(1, 3).map { p -> fullFlipped(p, t) }.map { f -> { y: Int, x: Int -> f[y][x] } })
                HotPixels.correct(ref, defects)
                val cfg = PipelineConfig(scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = fin.tileSizes,
                    kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                    kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                    robustnessParams = RobustnessParams(noiseCorrection = true, s3 = true), robustnessEnabled = true,
                    sigmaSqCurve = curves.sigmaSq, dSqCurve = curves.dSq, memoryTight = true,
                    colourDiff = floatArrayOf((t.neutral[1] / t.neutral[0]).toFloat(), (t.neutral[1] / t.neutral[2]).toFloat()),
                    kernelNet = if (System.getenv("SRP_KERNEL") == "wronski") null else KernelNetConfig(weights = KernelNet.bundledWeights(),
                        sigma = KernelNet.sigma(FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }), flipX = false, flipY = true))
                val buf = VulkanPipeline.run({ ref }, files.drop(1).map { p -> { _: ByteBuffer -> fullFlipped(p, t).also { HotPixels.correct(it, defects) } } },
                    cfg, handle, alignSpv, stage)
                // back to sensor orientation (undo the horizontal flip), NaN -> 0, write little-endian f32
                val fb = buf.asFloatBuffer()
                val outB = ByteBuffer.allocate(W * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
                java.io.FileOutputStream(File(dir, "$burst${System.getenv("SRP_KERNEL")?.let { "_$it" } ?: ""}${System.getenv("SRP_TAG") ?: ""}.f32")).channel.use { ch ->
                    for (y in 0 until H) {
                        outB.clear()
                        for (x in 0 until W) for (c in 0..2) outB.putFloat(fb.get(((H - 1 - y) * W + x) * 3 + c).let { if (it.isFinite()) it else 0f })
                        outB.flip(); ch.write(outB)
                    }
                }
                val bb = ByteBuffer.wrap(File(files[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val off = bb.getInt(4); val cnt = bb.getShort(off).toInt() and 0xffff
                val tagText = StringBuilder("w=$W\nh=$H\nneutral=${t.neutral.joinToString(",")}\n")
                for (i in 0 until cnt) {
                    val e = off + 2 + 12 * i; val tag = bb.getShort(e).toInt() and 0xffff; val c = bb.getInt(e + 4)
                    val name = mapOf(50721 to "cm1", 50722 to "cm2", 50964 to "fm1", 50965 to "fm2", 50723 to "cc1", 50724 to "cc2")[tag]
                    if (name != null) { val p = bb.getInt(e + 8); tagText.append("$name=" + (0 until c).joinToString(",") { (bb.getInt(p + 8 * it).toDouble() / bb.getInt(p + 8 * it + 4)).toString() } + "\n") }
                    if (tag == 50778 || tag == 50779) tagText.append("illum${tag - 50777}=${bb.getShort(e + 8).toInt() and 0xffff}\n")
                }
                File(dir, "$burst.tags").writeText(tagText.toString())
                println("VKFULL $burst done (defects ${defects.size})")
            }
        } finally { SrpBridge.nativeDestroy(handle) }
    }

    /** Wraps each build/vkfull/<burst>.f32 merge in a linear DNG (CLI writer, colour profile + orientation from F00). */
    @Test
    fun fullResToDng() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", ""); assumeTrue(lib.isNotEmpty())
        System.load(lib)
        val dir = File("build/vkfull")
        for (burst in bursts) {
            val kn = System.getenv("SRP_KERNEL") ?: "kernelnet"
            val tag = System.getenv("SRP_TAG") ?: ""
            val f = File(dir, "$burst${if (kn == "kernelnet") "" else "_$kn"}$tag.f32"); if (!f.isFile) continue
            val bytes = f.readBytes()
            val rgb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            rgb.asFloatBuffer().put(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()); rgb.rewind()
            val ref = "$root/$burst/${burst}_F00.dng"
            val profile = SrpBridge.readDngColorProfile(ref)
            val rc = SrpBridge.writeDng(File(dir, "${burst}_$kn$tag.dng").path, W, H, rgb, profile.make, profile.model,
                profile.colorMatrix, profile.neutral, profile.illuminant, SrpBridge.readDngOrientation(ref))
            println("VKDNG $burst rc=$rc")
        }
    }
}
