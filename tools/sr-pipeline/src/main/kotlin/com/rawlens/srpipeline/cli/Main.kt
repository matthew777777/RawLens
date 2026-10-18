package com.rawlens.srpipeline.cli

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.NoiseLut
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
)

private fun usage(): String = """
    Usage: sr-pipeline --in DIR --out DIR [--ref N] [--crop x,y,w,h] [--limit N] --backend cpu|vulkan [--scale F] [--lib PATH] [--spv-dir DIR] [--lut PATH] [--heap-budget-mb N]
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
      --lut PATH     noise LUT .npz; enables noise correction with its curves, validated
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
    val curves = plan.lut?.let { NoiseLut.load(it, alpha, beta) }
    val noiseCorrection = curves != null

    // SNR-based tile size + kernel shaping (`update_snr_config`), then the
    // shape leg of `sanitize_config` with the Python 4-level pyramid.
    val snr = BurstConfig.estimateSnr(refImg, alpha, beta)
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
        kernelPreprocessing = "gat",
        kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
        kernelBeta = FloatArray(4) { beta[it].toFloat() },
        robustnessParams = RobustnessParams(noiseCorrection = noiseCorrection),
        robustnessEnabled = true,
        sigmaSqCurve = curves?.sigmaSq ?: floatArrayOf(0f, 0f),
        dSqCurve = curves?.dSq ?: floatArrayOf(0f, 0f),
    )
    println("sr-pipeline: ${w}x$h snr=${"%.2f".format(snr)} tile=${fin.tileSize} " +
        "backend=${plan.backend} noiseCorrection=$noiseCorrection")

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
                    { refImg }, compImgs, config, handle,
                    AlignGpu.Spirvs(
                        align = spv(dir, "align"),
                        corr = spv(dir, "corr"),
                        fft = fft,
                        ica = spv(dir, "ica"),
                    ),
                    VulkanPipeline.Spirvs(
                        gat = spv(dir, "gat"),
                        grad = spv(dir, "grad"),
                        kernels = spv(dir, "kernels"),
                        divide = spv(dir, "divide"),
                        robust = spv(dir, "robustness"),
                        mergeSplat = spv(dir, "merge_splat"),
                        fft = fft,
                    ),
                )
                buf
            } finally {
                SrpBridge.nativeDestroy(handle)
            }
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
