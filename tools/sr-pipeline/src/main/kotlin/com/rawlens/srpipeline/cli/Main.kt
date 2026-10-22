package com.rawlens.srpipeline.cli

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.NoiseLut
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private data class Crop(val x: Int, val y: Int, val w: Int, val h: Int)

private data class Plan(
    val inputDir: Path,
    val outputDir: Path,
    val ref: Int,
    val crop: Crop?,
    val limit: Int?,
    val backend: String,
    val scale: Float,
    val lib: String?,
    val spvDir: Path?,
    val lut: String?,
    val heapBudgetMb: Int?,
    /** "wronski" (paper S.1 kernels, IPOL parity) or "kernelnet" (PhotonCamera). */
    val kernel: String = "wronski",
    val knScale: Float = KernelNet.DEFAULT_SIGMA_SCALE,
    val knCap: Float = KernelNet.DEFAULT_SIGMA_CAP,
    /** Supplement S.3 high-frequency rejection (off = IPOL parity). */
    val s3: Boolean = true,
    /** Robustness strengths: [s1] where the flow varies (M > Mt), [s2] elsewhere (IPOL 2 / 12; paper S.2 12 / 2). */
    val s1: Float = 2f,
    val s2: Float = 12f,
    /** GAT before Wronski kernel estimation (IPOL; the paper has none). */
    val kernelGat: Boolean = true,
)

private fun usage(): String = """
    Usage: sr-pipeline --in DIR --out DIR [--ref N] [--crop x,y,w,h] [--limit N] --backend cpu|vulkan [--scale F] [--lib PATH] [--spv-dir DIR] [--lut PATH] [--heap-budget-mb N] [--kernel wronski|kernelnet] [--kn-scale F] [--kn-cap F] [--s3 on|off] [--s1 F --s2 F] [--kernel-gat on|off]
      --in DIR       input frame directory with .dng burst frames (required)
      --out DIR      output directory, receives merged.dng (required)
      --ref N        reference frame index into the sorted frames, >= 0 (default 0)
      --crop x,y,w,h crop rectangle on the raw frames, all values >= 0 and w,h > 0 (optional;
                     x,y must be even so the RGGB phase is preserved)
      --limit N      max total frames to merge, > 0 (optional; reference + first comparisons)
      --backend NAME compute backend, one of: cpu, vulkan (required)
      --scale F      super-resolution factor, >= 1 (default 1, mirroring the Python default)
      --lib PATH     JNI library file to load (default: System.loadLibrary lookup)
      --spv-dir DIR  directory with the 10 stage .spv modules (required for --backend vulkan)
      --lut PATH     noise LUT .npz (or "auto" to simulate it in-process); enables noise correction with its curves, validated
                     against the DNG noise profile (without it, noise correction is off)
      --heap-budget-mb N
                     peak JVM heap-pool gate in MB; fails the run when exceeded
""".trimIndent()

private fun fail(message: String): Nothing {
    System.err.println("sr-pipeline: error: $message")
    System.err.println(usage())
    exitProcess(2)
}

private fun parseCrop(raw: String): Crop {
    val parts = raw.split(",").map { it.trim() }
    if (parts.size != 4) fail("--crop must be x,y,w,h (got \"$raw\")")
    val nums = parts.map { it.toIntOrNull() ?: fail("--crop values must be integers (got \"$raw\")") }
    val (x, y, w, h) = nums
    if (x < 0 || y < 0) fail("--crop x and y must be >= 0 (got \"$raw\")")
    if (w <= 0 || h <= 0) fail("--crop w and h must be > 0 (got \"$raw\")")
    if (x % 2 != 0 || y % 2 != 0) fail("--crop x and y must be even (RGGB phase, got \"$raw\")")
    return Crop(x, y, w, h)
}

private fun parseArgs(args: Array<String>): Plan {
    var inDir: String? = null
    var outDir: String? = null
    var ref = 0
    var crop: Crop? = null
    var limit: Int? = null
    var heapBudgetMb: Int? = null
    var backend: String? = null
    var scale = 1f
    var lib: String? = null
    var spvDir: String? = null
    var lut: String? = null
    var kernel = "wronski"
    var knScale = KernelNet.DEFAULT_SIGMA_SCALE
    var knCap = KernelNet.DEFAULT_SIGMA_CAP
    var s3 = true
    var s1 = 2f
    var s2 = 12f
    var kernelGat = true

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--in" -> {
                inDir = args.getOrNull(++i) ?: fail("--in requires a DIR value")
                i++
            }
            "--out" -> {
                outDir = args.getOrNull(++i) ?: fail("--out requires a DIR value")
                i++
            }
            "--ref" -> {
                val raw = args.getOrNull(++i) ?: fail("--ref requires an N value")
                ref = raw.toIntOrNull() ?: fail("--ref must be an integer >= 0 (got \"$raw\")")
                if (ref < 0) fail("--ref must be >= 0 (got \"$raw\")")
                i++
            }
            "--crop" -> {
                val raw = args.getOrNull(++i) ?: fail("--crop requires an x,y,w,h value")
                crop = parseCrop(raw)
                i++
            }
            "--limit" -> {
                val raw = args.getOrNull(++i) ?: fail("--limit requires an N value")
                val n = raw.toIntOrNull() ?: fail("--limit must be an integer > 0 (got \"$raw\")")
                if (n <= 0) fail("--limit must be > 0 (got \"$raw\")")
                limit = n
                i++
            }
            "--backend" -> {
                backend = args.getOrNull(++i) ?: fail("--backend requires cpu|vulkan")
                i++
            }
            "--scale" -> {
                val raw = args.getOrNull(++i) ?: fail("--scale requires an F value")
                scale = raw.toFloatOrNull() ?: fail("--scale must be a number >= 1 (got \"$raw\")")
                if (scale < 1f) fail("--scale must be >= 1 (got \"$raw\")")
                i++
            }
            "--lib" -> {
                lib = args.getOrNull(++i) ?: fail("--lib requires a PATH value")
                i++
            }
            "--spv-dir" -> {
                spvDir = args.getOrNull(++i) ?: fail("--spv-dir requires a DIR value")
                i++
            }
            "--kernel" -> {
                kernel = args.getOrNull(++i) ?: fail("--kernel requires wronski|kernelnet")
                if (kernel != "wronski" && kernel != "kernelnet") fail("--kernel must be wronski|kernelnet (got \"$kernel\")")
                i++
            }
            "--kn-scale" -> {
                val raw = args.getOrNull(++i) ?: fail("--kn-scale requires an F value")
                knScale = raw.toFloatOrNull()?.takeIf { it > 0f } ?: fail("--kn-scale must be > 0 (got \"$raw\")")
                i++
            }
            "--kn-cap" -> {
                val raw = args.getOrNull(++i) ?: fail("--kn-cap requires an F value")
                knCap = raw.toFloatOrNull()?.takeIf { it >= 0.25f } ?: fail("--kn-cap must be >= 0.25 (got \"$raw\")")
                i++
            }
            "--s3" -> {
                s3 = when (args.getOrNull(++i)) { "on" -> true; "off" -> false; else -> fail("--s3 requires on|off") }
                i++
            }
            "--s1" -> {
                s1 = args.getOrNull(++i)?.toFloatOrNull() ?: fail("--s1 requires a number")
                i++
            }
            "--s2" -> {
                s2 = args.getOrNull(++i)?.toFloatOrNull() ?: fail("--s2 requires a number")
                i++
            }
            "--kernel-gat" -> {
                kernelGat = when (args.getOrNull(++i)) { "on" -> true; "off" -> false; else -> fail("--kernel-gat requires on|off") }
                i++
            }
            "--lut" -> {
                lut = args.getOrNull(++i) ?: fail("--lut requires a PATH value")
                i++
            }
            "--heap-budget-mb" -> {
                val raw = args.getOrNull(++i) ?: fail("--heap-budget-mb requires an N value")
                val n = raw.toIntOrNull() ?: fail("--heap-budget-mb must be an integer > 0 (got \"$raw\")")
                if (n <= 0) fail("--heap-budget-mb must be > 0 (got \"$raw\")")
                heapBudgetMb = n
                i++
            }
            "--help", "-h" -> {
                println(usage())
                exitProcess(0)
            }
            else -> fail("unknown argument \"${args[i]}\"")
        }
    }

    if (inDir.isNullOrBlank()) fail("--in DIR is required")
    if (outDir.isNullOrBlank()) fail("--out DIR is required")
    val b = backend ?: fail("--backend cpu|vulkan is required")
    if (b != "cpu" && b != "vulkan") fail("--backend must be cpu or vulkan (got \"$b\")")
    if (b == "vulkan" && spvDir.isNullOrBlank()) fail("--spv-dir DIR is required for --backend vulkan")

    return Plan(
        inputDir = Paths.get(inDir!!),
        outputDir = Paths.get(outDir!!),
        ref = ref,
        crop = crop,
        limit = limit,
        heapBudgetMb = heapBudgetMb,
        backend = b,
        scale = scale,
        lib = lib,
        spvDir = if (spvDir == null) null else Paths.get(spvDir),
        lut = lut,
        kernel = kernel,
        knScale = knScale,
        knCap = knCap,
        s3 = s3,
        s1 = s1,
        s2 = s2,
        kernelGat = kernelGat,
    )
}

/**
 * Copies the CPU oracle's `[H][W][3]` nesting into flat `[H]` rows of
 * `[W*3]` (exact values; the Vulkan path already returns flat rows).
 * Desktop-heap only: one 150MB pass at full-res, off the phone path.
 */
private fun flattenImage(img: Array<Array<FloatArray>>): Array<FloatArray> {
    val h = img.size
    val w = img[0].size
    return Array(h) { y -> FloatArray(w * 3) { i -> img[y][i / 3][i % 3] } }
}

/** Reads one burst frame: single-pass decode + RGGB flip + normalise, then the CLI crop. */
private fun readFrame(path: Path, crop: Crop?): Array<FloatArray> {
    val p = path.toString()
    val meta = DngRead.readMeta(p)
    val img = DngRead.readNormalized(p, meta)
    if (crop == null) return img
    require(crop.x + crop.w <= meta.width && crop.y + crop.h <= meta.height) {
        "sr-pipeline: --crop ${crop.x},${crop.y},${crop.w},${crop.h} exceeds ${meta.width}x${meta.height}"
    }
    return Array(crop.h) { y -> img[crop.y + y].copyOfRange(crop.x, crop.x + crop.w) }
}

private fun spv(dir: Path, base: String): ByteArray {
    val f = dir.resolve("$base.spv").toFile()
    require(f.isFile) { "sr-pipeline: missing stage module: $f" }
    return f.readBytes()
}

private fun merge(plan: Plan) {
    val t0 = System.nanoTime()
    // The DNG reader/writer is native (TinyDNG v3), so every backend loads
    // the JNI library up front.
    if (plan.lib != null) System.load(plan.lib) else System.loadLibrary("srpipeline_jni")
    val frames = Files.list(plan.inputDir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".dng") }
            .sorted().toList()
    }
    if (frames.isEmpty()) fail("no .dng frames in ${plan.inputDir}")
    if (plan.ref >= frames.size) fail("--ref ${plan.ref} exceeds ${frames.size} frames")
    val refPath = frames[plan.ref]
    val rest = frames.filterIndexed { i, _ -> i != plan.ref }
    val comps = if (plan.limit == null) rest else rest.take(maxOf(plan.limit - 1, 0))

    println("sr-pipeline: reading ${1 + comps.size}/${frames.size} frames (ref ${refPath.fileName})")
    // The reference decodes upfront (live the whole run); comparisons
    // decode just in time inside the merge loop via thunks, so only one
    // comparison is ever live (same order, same bytes as an upfront
    // list). A decode thread pool measured strictly worse (bisect: sys
    // 0.5s -> 2.1s on the crop from contention in concurrent native
    // decodes), so serial decode stays.
    val refImg = readFrame(refPath, plan.crop)
    val compImgs = comps.map { p -> { readFrame(p, plan.crop) } }
    val h = refImg.size
    val w = refImg[0].size

    // Noise profile from the reference tags; LUT validated against it like
    // `load_noise_lut`, otherwise noise correction stays off (zero curves).
    val refMeta = DngRead.readMeta(refPath.toString())
    val (alpha, beta) = DngRead.noiseAlphaBeta(refMeta.noise, refMeta.noiseCount)
    // `--lut auto`: simulate in-process (NoiseLutGen, same model as the
    // Python monte_carlo; SRP_LUT_TRIALS overrides the trial count).
    val curves = plan.lut?.let {
        if (it == "auto") {
            val t0 = System.nanoTime()
            NoiseLutGen.generate(alpha, beta,
                trials = System.getenv("SRP_LUT_TRIALS")?.toInt() ?: NoiseLutGen.DEFAULT_TRIALS).also {
                System.err.println("noise LUT generated in ${(System.nanoTime() - t0) / 1_000_000} ms")
                System.getenv("SRP_LUT_DUMP")?.let { d -> java.io.File(d).writeText(
                    it.sigmaSq.joinToString(",") + "\n" + it.dSq.joinToString(",") + "\n") }
            }
        } else NoiseLut.load(it, alpha, beta)
    }
    val noiseCorrection = curves != null

    // SNR-based tile size + kernel shaping (`update_snr_config`), then the
    // shape leg of `sanitize_config` with the Python 4-level pyramid.
    // SNR of the WHOLE reference frame even when cropping: the paper tunes
    // on "the SNR measured from a single frame" (Wronski et al. 2019,
    // supplement S.2) and the IPOL reference measures the full reference
    // in process() before any crop. A crop-local SNR (20.9 vs 29.5 dB on
    // the parity burst) changed tile size and every kernel parameter.
    val fullRef = if (plan.crop == null) refImg else readFrame(refPath, null)
    val snr = BurstConfig.estimateSnr(fullRef, alpha, beta)
    val fin = BurstConfig.finalize(snr)
    val factors = listOf(1, 2, 4, 4)
    BurstConfig.sanitize(plan.scale, fin.tileSize, factors, fin.tileSizes, h, w)
    val config = PipelineConfig(
        scale = plan.scale,
        bayer = true,
        factors = factors,
        tileSizes = fin.tileSizes,
        kernelParams = KernelParams(
            kDetail = fin.kDetail.toFloat(),
            kDenoise = fin.kDenoise.toFloat(),
            dTh = fin.dTh.toFloat(),
            dTr = fin.dTr.toFloat(),
        ),
        kernelPreprocessing = if (plan.kernelGat) "gat" else null,
        kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
        kernelBeta = FloatArray(4) { beta[it].toFloat() },
        robustnessParams = RobustnessParams(noiseCorrection = noiseCorrection, s3 = plan.s3, s1 = plan.s1, s2 = plan.s2),
        robustnessEnabled = true,
        sigmaSqCurve = curves?.sigmaSq ?: floatArrayOf(0f, 0f),
        dSqCurve = curves?.dSq ?: floatArrayOf(0f, 0f),
        kernelNet = if (plan.kernel != "kernelnet") null else {
            val (fx, fy) = KernelNet.rggbFlips(refMeta.cfa)
            KernelNetConfig(
                weights = KernelNet.bundledWeights(),
                sigma = KernelNet.sigma(
                    FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                sigmaScale = plan.knScale,
                sigmaCap = plan.knCap,
                fullReference = if (plan.crop == null) null else fullRef,
                cropX = plan.crop?.x ?: 0,
                cropY = plan.crop?.y ?: 0,
                flipX = fx,
                flipY = fy,
            )
        },
    )
    println("sr-pipeline: ${w}x$h snr=${"%.2f".format(snr)} tile=${fin.tileSize} " +
        "backend=${plan.backend} noiseCorrection=$noiseCorrection kernel=${plan.kernel}" +
        (if (plan.kernel == "kernelnet") " knScale=${plan.knScale} knCap=${plan.knCap}" else ""))

    // Flat floats end to end: the Vulkan path returns the downloaded
    // buffer directly (no row copy); the CPU oracle's `[H][W][3]`
    // nesting is copied once here (exact values, desktop heap only).
    // The tail NaN rule + row flip below are a per-pixel map and a
    // permutation: identical bytes in either order.
    val outH = (h * plan.scale).toInt()
    val outW = (w * plan.scale).toInt()
    val rgb = when (plan.backend) {
        "cpu" -> {
            val merged = flattenImage(EndToEnd.run(refImg, compImgs, config))
            require(merged.size == outH && merged[0].size == outW * 3) {
                "sr-pipeline: oracle shape mismatch"
            }
            val buf = SrpBridge.directFloats(outH * outW * 3)
            val fb = buf.asFloatBuffer()
            for (y in 0 until outH) fb.put(merged[outH - 1 - y])
            buf.rewind()
            buf
        }
        else -> {
            val dir = plan.spvDir!!
            val handle = SrpBridge.nativeCreate()
            require(handle != 0L) { "sr-pipeline: nativeCreate failed" }
            try {
                val fft = spv(dir, "fft")
                val buf = VulkanPipeline.run(
                    { refImg }, compImgs.map { t -> { _: java.nio.ByteBuffer -> t() } },
                    config, handle,
                    AlignGpu.Spirvs(
                        align = spv(dir, "align"),
                        corr = spv(dir, "corr"),
                        fft = fft,
                        ica = spv(dir, "ica"),
                        // SRP_LEGACY_FFT=1: the Bluestein FFT paths (A/B checks).
                        corrDirect = if (System.getenv("SRP_LEGACY_FFT") == "1") null else spv(dir, "corr_direct"),
                        icaWg = if (System.getenv("SRP_LEGACY_ICA") == "1") null else spv(dir, "ica_wg"),
                    ),
                    VulkanPipeline.Spirvs(
                        gat = spv(dir, "gat"),
                        grad = spv(dir, "grad"),
                        kernels = spv(dir, "kernels"),
                        divide = spv(dir, "divide"),
                        robust = spv(dir, "robustness"),
                        mergeSplat = spv(dir, "merge_splat"),
                        fft = fft,
                        corr = spv(dir, "corr"),
                        kernelNet = if (plan.kernel == "kernelnet") spv(dir, "kernelnet") else null,
                        greyFft = if (System.getenv("SRP_LEGACY_FFT") == "1") null else spv(dir, "grey_fft"),
                    ),
                )
                buf
            } finally {
                SrpBridge.nativeDestroy(handle)
            }
        }
    }
    // Parity aid: SRP_KN_DUMP=<file> writes the KernelNet (s1, s2, rho)
    // map (3 x mapH x mapW little-endian float32).
    System.getenv("SRP_KN_DUMP")?.let { path ->
        VulkanPipeline.lastKernelNetMap?.let { m ->
            val bb = java.nio.ByteBuffer.allocate(m.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(m)
            java.io.File(path).writeBytes(bb.array())
        }
    }
    VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), outH * outW * 3)
    rgb.rewind()
    // Pipeline rows run sensor-bottom-up (the GRBG->RGGB input flip is
    // only undone inside the finished PNG path); the DNG stores true
    // native top-down, so emit rows flipped, in place.
    VulkanPipeline.flipRowsInPlace(rgb.asFloatBuffer(), outW, outH)
    Files.createDirectories(plan.outputDir)
    val out = plan.outputDir.resolve("merged.dng").toString()
    // Camera color passthrough from the reference frame (rendering), cf.
    // the app's merge writer; absent tags select the writer defaults.
    // Orientation rides along so raw editors display the file exactly
    // like the finished PNG (0 = unknown, tag omitted).
    val profile = SrpBridge.readDngColorProfile(refPath.toString())
    val orientation = SrpBridge.readDngOrientation(refPath.toString())
    val rc = SrpBridge.writeDng(out, outW, outH, rgb, profile.make, profile.model,
        profile.colorMatrix, profile.neutral, profile.illuminant, orientation)
    require(rc == 0) { "sr-pipeline: writeDng failed with status $rc" }
    val wall = (System.nanoTime() - t0) / 1e9
    println("sr-pipeline: wrote $out (${outW}x$outH) in ${"%.1f".format(wall)}s")
    plan.heapBudgetMb?.let { budget ->
        // Heap gate: process-lifetime peak heap-pool usage must fit the
        // 512 MB Java-heap headroom budget. Peak (not post-GC retained:
        // retained is ~zero once scratch goes out of scope) captures the
        // transient per-frame garbage the reuse slice attacks; the pool
        // sum is conservative (Eden + Survivor + Old peaks overlap), so
        // a pass has real headroom. Exit nonzero above budget.
        val peakMb = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
            .sumOf { it.peakUsage.used } / 1048576.0
        println("sr-pipeline: heap peak ${"%.0f".format(peakMb)} MB (budget $budget MB)")
        require(peakMb <= budget) {
            "sr-pipeline: heap budget exceeded: ${"%.0f".format(peakMb)} MB > $budget MB"
        }
    }
}

fun main(args: Array<String>) {
    val plan = parseArgs(args)
    println("sr-pipeline plan:")
    println("  in:      ${plan.inputDir}")
    println("  out:     ${plan.outputDir}")
    println("  ref:     ${plan.ref}")
    println("  crop:    ${plan.crop?.let { "${it.x},${it.y},${it.w},${it.h}" } ?: "(none)"}")
    println("  limit:   ${plan.limit?.toString() ?: "(none)"}")
    println("  backend: ${plan.backend}")
    println("  scale:   ${plan.scale}")
    println("  lut:     ${plan.lut ?: "(none, noise correction off)"}")
    merge(plan)
}
