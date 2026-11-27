package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.HotPixels
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.SceneNoise
import com.rawlens.srpipeline.stages.SrTuning
import com.rawlens.srpipeline.stages.GreenNormalTile
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
 * MFSR softness A/B (user 2026-11-27: "softer than rawr Rexture"): the real 8-frame burst
 * 170823_957 through the app's mobile merge config (APP_DEFAULT tuning, zipper fix, green fit at 1x,
 * own noise estimate), varying only the kernel widths. Per crop: detail = RMS of Y minus its 3x3
 * box blur on the most textured crop, noise = same on the flattest crop, both relative to the
 * single reference frame (bilinear). Sheets in build/kwidth.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*MfsrKernelWidthAbTest*' -PsrpDiag=1 -PsrpHeap=3g \
 *      -PsrpLib=<libsrpipeline_jni.dylib> -i      (spv: build/host)
 */
class MfsrKernelWidthAbTest {
    private val dir = "/Users/monikamalinowska/Downloads/My/Photos/IMG_20260929_170823_957"
    private val burst = "IMG_20260929_170823_957"
    private val W = 4080
    private val H = 3060
    private val n = 512
    private val out = File("build/kwidth").apply { mkdirs() }

    private fun spv(name: String): ByteArray =
        File(System.getProperty("srpipeline.spvdir")?.takeIf { it.isNotEmpty() } ?: "build/host", "$name.spv").readBytes()

    private class Tags(val strip: Long, val black: Float, val white: Float, val neutral: DoubleArray)

    private fun tags(path: String): Tags {
        val bb = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val off = bb.getInt(4)
        val cnt = bb.getShort(off).toInt() and 0xffff
        var strip = 0L; var black = 0f; var white = 0f; var neutral = doubleArrayOf(1.0, 1.0, 1.0)
        for (i in 0 until cnt) {
            val e = off + 2 + 12 * i
            val tag = bb.getShort(e).toInt() and 0xffff
            val type = bb.getShort(e + 2).toInt() and 0xffff
            val c = bb.getInt(e + 4); val v = e + 8
            when (tag) {
                273 -> strip = (if (type == 3) bb.getShort(v).toInt() and 0xffff else bb.getInt(v)).toLong()
                50714 -> { val p = bb.getInt(v); black = bb.getInt(p).toFloat() / bb.getInt(p + 4) }
                50717 -> white = (if (type == 3) bb.getShort(v).toInt() and 0xffff else bb.getInt(v)).toFloat()
                50728 -> { val p = bb.getInt(v); neutral = DoubleArray(c) { bb.getInt(p + 8 * it).toDouble() / bb.getInt(p + 8 * it + 4) } }
            }
        }
        return Tags(strip, black, white, neutral)
    }

    /** Full frame flipped vertically: GBRG -> RGGB like the app. */
    private fun full(path: String, t: Tags): Array<FloatArray> = RandomAccessFile(path, "r").use { f ->
        val row = ByteArray(W * 2)
        Array(H) { y ->
            f.seek(t.strip + (H - 1 - y).toLong() * W * 2); f.readFully(row)
            val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(W) { x -> ((bb.getShort(x * 2).toInt() and 0xffff) - t.black) / (t.white - t.black) }
        }
    }

    private fun crop(f: Array<FloatArray>, x0: Int, y0: Int) = Array(n) { y -> f[y0 + y].copyOfRange(x0, x0 + n) }

    /** Crop origin with the most (or least, among non-dark) texture. */
    private fun origin(f: Array<FloatArray>, most: Boolean): Pair<Int, Int> {
        var best = if (most) -1.0 else Double.MAX_VALUE; var bx = 0; var by = 0
        for (y0 in 64 until H - n - 64 step 128) for (x0 in 64 until W - n - 64 step 128) {
            var s = 0.0; var m = 0.0
            for (y in y0 until y0 + n step 8) for (x in x0 until x0 + n step 8) {
                s += abs(f[y][x + 2] - f[y][x]) + abs(f[y + 2][x] - f[y][x]); m += f[y][x]
            }
            m /= (n / 8.0) * (n / 8.0)
            if (!most && m < 0.05) continue
            if (if (most) s > best else s < best) { best = s; bx = x0; by = y0 }
        }
        return (bx and -2) to (by and -2)
    }

    private fun luma(img: Array<Array<FloatArray>>) = Array(n) { y -> DoubleArray(n) { x -> (img[y][x][0] + 2.0 * img[y][x][1] + img[y][x][2]) / 4 } }

    /** RMS of Y minus 3x3 box (high-frequency energy). */
    private fun hf(img: Array<Array<FloatArray>>): Double {
        val l = luma(img); var s = 0.0; var c = 0
        for (y in 2 until n - 2) for (x in 2 until n - 2) {
            var b = 0.0
            for (dy in -1..1) for (dx in -1..1) b += l[y + dy][x + dx]
            val d = l[y][x] - b / 9; s += d * d; c++
        }
        return sqrt(s / c)
    }

    private fun bilinear(raw: Array<FloatArray>): Array<Array<FloatArray>> = Array(n) { y -> Array(n) { x -> FloatArray(3) { c ->
        var s = 0f; var k = 0
        for (dy in -1..1) for (dx in -1..1) {
            val yy = y + dy; val xx = x + dx
            if (yy < 0 || xx < 0 || yy >= n || xx >= n) continue
            val cc = if (yy % 2 == 0 && xx % 2 == 0) 0 else if (yy % 2 == 1 && xx % 2 == 1) 2 else 1
            if (cc == c) { s += raw[yy][xx]; k++ }
        }
        if (k > 0) s / k else 0f
    } } }

    private fun sheet(name: String, panels: List<Array<Array<FloatArray>>>, wb: FloatArray, gain: Float) {
        val bi = BufferedImage(n * panels.size, n, BufferedImage.TYPE_INT_RGB)
        for ((p, img) in panels.withIndex()) for (y in 0 until n) for (x in 0 until n) {
            val v = IntArray(3) { c ->
                val lin = (img[y][x][c] * gain * (if (c == 0) wb[0] else if (c == 2) wb[1] else 1f)).coerceIn(0f, 1f)
                (255 * lin.toDouble().pow(1 / 2.2)).toInt()
            }
            bi.setRGB(p * n + x, y, (v[0] shl 16) or (v[1] shl 8) or v[2])
        }
        ImageIO.write(bi, "png", File(out, "$name.png"))
    }

    @Test
    fun kernelWidths() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val lib = System.getProperty("srpipeline.lib", ""); assumeTrue(lib.isNotEmpty())
        assumeTrue(File(dir).isDirectory)
        System.load(lib)
        val handle = SrpBridge.nativeCreate(); check(handle != 0L)
        val align = AlignGpu.Spirvs(align = spv("align"), corr = spv("corr"), fft = spv("fft"), ica = spv("ica"),
            corrDirect = spv("corr_direct"), icaWg = spv("ica_wg"), alignWg = spv("align_wg"),
            argminWg = spv("corr_argmin_wg"), l2Fused = spv("corr_l2_fused"))
        val stages = VulkanPipeline.Spirvs(gat = spv("gat"), grad = spv("grad"), kernels = spv("kernels"),
            divide = spv("divide"), robust = spv("robustness"), mergeSplat = spv("merge_splat"), fft = spv("fft"),
            corr = spv("corr"), greyFft = spv("grey_fft"), greyFir = spv("grey_fir"), pyrDown = spv("pyr_down"),
            noiseAcc = spv("noise_acc"), noiseEven = spv("noise_even"), caWarp = spv("ca_warp"),
            mobile = VulkanPipeline.MobileSpirvs.load(::spv))
        try {
            val files = (0..7).map { "$dir/${burst}_F%02d.dng".format(it) }
            val t = tags(files[0])
            val fulls = files.map { full(it, t) }
            val (alpha, beta) = SceneNoise.estimate(fulls[0])
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(fulls[0], alpha, beta))
            val curves = NoiseLutGen.generate(alpha, beta)
            val wb = floatArrayOf((t.neutral[1] / t.neutral[0]).toFloat(), (t.neutral[1] / t.neutral[2]).toFloat())
            println("KW snr %.1f tile %d kDetail %.3f kDenoise %.2f".format(fin.snr, fin.tileSize, fin.kDetail, fin.kDenoise))
            val app = SrTuning.APP_DEFAULT
            val variants = listOf(
                "app (kDen x1.30)" to app,
                "kDen x0.60" to app.copy(kDenoiseScale = 0.6f),
                "kDen x0.35" to app.copy(kDenoiseScale = 0.35f),
                "kMin 0.50" to app.copy(kMin = 0.5f),
                "kMin 0.40" to app.copy(kMin = 0.4f),
                "kMin 0" to app.copy(kMin = 0f),
            )
            val places = listOf("tex" to origin(fulls[0], true), "flat" to origin(fulls[0], false))
            for (scale in listOf(1f, 2f)) {
                val on = (n * scale).toInt()
                for ((place, xy) in places) {
                    val (ox, oy) = xy
                    val frames = fulls.map { crop(it, ox, oy) }
                    val defects = HotPixels.confirm(HotPixels.candidates(frames[0]), n, n,
                        frames.subList(1, 3).map { f -> { y: Int, x: Int -> f[y][x] } })
                    frames.forEach { HotPixels.correct(it, defects) }
                    val single = bilinear(frames[0]); val h0 = hf(single)
                    val panels = ArrayList<Array<Array<FloatArray>>>()
                    val line = StringBuilder("KW x%.0f %s(%d,%d) single %.5f".format(scale, place, ox, oy, h0))
                    for ((name, tu) in variants) {
                        val c = PipelineConfig(scale = scale, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = fin.tileSizes,
                            greyMethod = "FIR7", kernelParams = tu.kernelParams(fin), kernelPreprocessing = "gat",
                            kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                            robustnessParams = tu.robustnessParams(true, true), robustnessEnabled = true,
                            sigmaSqCurve = curves.sigmaSq, dSqCurve = curves.dSq, memoryTight = true,
                            colourDiff = wb, chromaScale = tu.chromaScale(scale), icaIterations = tu.icaIterations,
                            continuousMotion = true, detailReconstruction = true, noiseEvening = true, lateralCa = true,
                            ghostGrow = true, blurGamma = 1f, blackPedestal = true)
                        // App default: green fit at 1x (zipper fix on, two-band off), none at 2x.
                        val green = if (scale == 1f) GreenNormalTile.createResident(handle, spv("green_residual"),
                            spv("green_normal_resident"), n, n, fin.tileSizes.first(), frames.size) else null
                        val buf = try { VulkanPipeline.run({ frames[0].map { it.copyOf() }.toTypedArray() },
                            frames.drop(1).map { f -> { _: ByteBuffer -> f.map { it.copyOf() }.toTypedArray() } }, c, handle, align, stages,
                            greenFit = green) } finally { green?.close() }
                        val fb = buf.asFloatBuffer()
                        // Area-downsample 2x output to the crop grid so detail/noise compare like for like.
                        val k = scale.toInt()
                        val img = Array(n) { y -> Array(n) { x -> FloatArray(3) { ch ->
                            var s = 0f
                            for (dy in 0 until k) for (dx in 0 until k) fb.get(((y * k + dy) * on + x * k + dx) * 3 + ch).let { if (!it.isNaN()) s += it }
                            s / (k * k)
                        } } }
                        panels += img
                        line.append(" | %s %.3f".format(name, hf(img) / h0))
                    }
                    println(line)
                    val mean = frames[0].sumOf { r -> r.sumOf { it.toDouble() } } / (n * n)
                    sheet("x%.0f_%s".format(scale, place), listOf(single) + panels, wb, (0.18 / maxOf(mean, 1e-4)).toFloat().coerceIn(1f, 40f))
                }
            }
        } finally { SrpBridge.nativeDestroy(handle) }
        println("KW sheets in ${out.absolutePath} (single | app | kDen 0.6 | kDen 0.35 | kMin 0.5 | kMin 0.4 | kMin 0)")
    }
}
