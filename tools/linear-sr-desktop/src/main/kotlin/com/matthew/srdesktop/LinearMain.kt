// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.srdesktop

import android.content.Context
import com.matthew.rawlens.DngFrameLoader
import com.matthew.rawlens.LinearRgbDngSaver
import com.matthew.rawlens.LinearRgbDngWriter
import com.matthew.rawlens.MergeProvenance
import com.matthew.rawlens.MergedLinearRgb
import com.matthew.rawlens.NcnnLoader
import com.matthew.rawlens.RawSrBayerMerge
import com.matthew.rawlens.RawSrChromaFromLuma
import com.matthew.rawlens.RawSrCovarianceGuide
import com.matthew.rawlens.RawSrDeadLaneInpaint
import com.matthew.rawlens.RawSrKernelCovariance
import com.matthew.rawlens.RawSrKernelNetAniso
import com.matthew.rawlens.RawSrKernelPreset
import com.matthew.rawlens.RawSrLinearScale
import com.matthew.rawlens.RawSrMergeDecisions
import com.matthew.rawlens.RawSrMergeJob
import com.matthew.rawlens.RawSrMergedNoise
import com.matthew.rawlens.RawSrNoiseLut
import com.matthew.rawlens.RawSrPackedFrame
import com.matthew.rawlens.RawSrTuning
import com.matthew.rawlens.VkRawSrProcessor
import com.matthew.rawlens.vkDownloadR32f
import com.matthew.rawlens.vkDownloadRgba32f
import java.io.File

/**
 * Linear SR desktop CLI. Same shape as the phone's linear save
 * (RawCameraController super-resolution Linear DNG block): same decision,
 * same chain inputs, same provenance math, same writer. The merge itself
 * runs on the CPU oracle ([RawSrBayerMerge.merge], the reference
 * implementation the GPU path is pinned against) until the unified Vulkan
 * backend lands; then this CLI gains `--backend=vulkan` with no other
 * change.
 *
 * Usage: linear-sr-desktop --in <dng-dir> --out <dir> [--ref N] [--cache DIR]
 *   [--crop x,y,w,h] [--limit N] [--backend cpu|vulkan] [--kernelnet]
 *   [--kernelnet-sigma-mpy X] [--kernelnet-kernel-mpy X]
 *   [--kernelnet-min-sigma X]
 *   [--no-kernelnet] [--no-inpaint] [--no-cfl] [--chroma-mpy X]
 *   [--kernel-preset reference|decoupled_sharp] [--k-detail X] [--flat-sigma X]
 *   [--detail-floor X] [--flow-regularize-sigma X]
 *   [--capture-id ID] [--linear-scale 1x|sr]
 */
object LinearMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "vkcheck") {
            VkCheck.main(args.drop(1).toTypedArray())
            return
        }
        val opts = Options.parse(args, "linear-sr-desktop")
        run(opts)
    }

    fun run(opts: Options) {
        val t0 = System.nanoTime()
        val kernelPreset = RawSrKernelPreset.fromPreference(opts.kernelPreset)
        require(opts.kernelPreset == kernelPreset.preferenceValue) {
            "kernel-preset must be reference or decoupled_sharp, got ${opts.kernelPreset}"
        }
        for ((name, v) in listOf("--k-detail" to opts.kDetail, "--flat-sigma" to opts.flatSigma,
            "--Detail-floor" to opts.detailFloor,
            "--flow-regularize-sigma" to opts.flowRegularizeSigma)) {
            if (v != null) require(v.isFinite() && v > 0.0) { "$name must be finite and positive, got $v" }
        }
        val loaded = opts.files?.let { DngFrameLoader.loadFiles(it, opts.crop) }
            ?: DngFrameLoader.load(opts.inDir, opts.limit, opts.crop)
        loaded.forEachIndexed { i, f ->
            println(
                "linear-sr: frame $i ${f.file.name} ${f.admitted.width}x${f.admitted.height}" +
                    " exp=${f.admitted.exposureSec}s iso=${f.admitted.iso}" +
                    " noise=${if (f.admitted.noiseProfile != null) "yes" else "MISSING"}"
            )
        }
        android.os.Build.MANUFACTURER = loaded.first().admitted.make
        android.os.Build.MODEL = loaded.first().admitted.model
        val ref = if (opts.ref < 0) loaded.size / 2 else opts.ref.coerceIn(loaded.indices)
        val refMetadata = loaded[ref].metadata
        // KernelNet is ON by default (RawSrKernelNetAniso.enabled);
        // --no-kernelnet opts out to the analytic path for this run.
        // --kernelnet is accepted for compatibility and forces a hard failure
        // when the model is not ready (test A/B); without it, a missing model
        // falls back to analytic with a warning instead of aborting the run.
        // --kernelnet-sigma-mpy stacks on top of auto-sigma (upstream
        // ESD4D.noiseMpy, default 1.0); ignored with --no-kernelnet. The
        // conversion itself is the direct upstream law (no cap, no floor,
        // no clamp).
        if (opts.noKernelnet && opts.kernelnetSigmaMpy != 1.0f) {
            println("linear-sr: WARNING --kernelnet-sigma-mpy ignored with --no-kernelnet")
        }
        if (opts.noKernelnet && opts.kernelnetKernelMpy != RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MPY) {
            println("linear-sr: WARNING --kernelnet-kernel-mpy ignored with --no-kernelnet")
        }
        if (opts.noKernelnet && opts.kernelnetMajorMpy != RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MAJOR_MPY) {
            println("linear-sr: WARNING --kernelnet-major-mpy ignored with --no-kernelnet")
        }
        RawSrKernelNetAniso.sigmaMpy = opts.kernelnetSigmaMpy
        RawSrKernelNetAniso.kernelSigmaMpy = opts.kernelnetKernelMpy
        RawSrKernelNetAniso.kernelSigmaMajorMpy = opts.kernelnetMajorMpy
        RawSrKernelNetAniso.minSigma = opts.kernelnetMinSigma
        RawSrKernelNetAniso.enabled = !opts.noKernelnet
        RawSrKernelNetAniso.zipperGates = opts.zipperGates
        println("linear-sr: zipperGates=${opts.zipperGates}")
        if (RawSrKernelNetAniso.enabled) {
            extractModels(opts.cacheDir)
            val ncnnOk = NcnnLoader.tryLoad()
            println(
                "linear-sr: kernelnet ncnnLib=$ncnnOk enabled=${RawSrKernelNetAniso.enabled}" +
                    " sigmaMpy=${RawSrKernelNetAniso.sigmaMpy}" +
                    " kernelMpy=${RawSrKernelNetAniso.kernelSigmaMpy}" +
                    " majorMpy=${RawSrKernelNetAniso.kernelSigmaMajorMpy}" +
                    " minSigma=${RawSrKernelNetAniso.minSigma}"
            )
            RawSrKernelNetAniso.preload(Context(opts.cacheDir))
            val ready = try {
                com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor
                    .getInstance()?.waitReady(120_000) == true
            } catch (_: Throwable) {
                RawSrKernelNetAniso.isReady()
            }
            println("linear-sr: kernelnet ready=$ready (isReady=${RawSrKernelNetAniso.isReady()})")
            if (!ready) {
                require(!opts.kernelnet) {
                    "kernelnet requested (--kernelnet) but model not ready; aborting test run"
                }
                println("linear-sr: kernelnet model not ready, falling back to analytic kernels")
                RawSrKernelNetAniso.enabled = false
            }
        } else {
            println("linear-sr: kernelnet off (analytic kernels)")
        }
        val decision = RawSrMergeDecisions.decide(loaded.indices.toList(), 0, ref, loaded.size)
        val rejectionPolicy = Options.rejectionPolicy(opts)
        val mergeIndices = opts.maxFrames?.let { max ->
            require(max in 2..30) { "--max-frames must be 2..30, got $max" }
            decision.mergeIndices.take(max).also {
                if (it.size < decision.mergeIndices.size) {
                    println("linear-sr: capped merge set to ${it.size}/${decision.mergeIndices.size} frames (--max-frames)")
                }
            }
        } ?: decision.mergeIndices
        Options.reportBracketCoverage("linear-sr",
            loaded.map { f ->
                val sec = f.admitted.exposureSec
                val iso = f.admitted.iso
                if (sec == null || iso == null || sec <= 0.0 || iso <= 0) null else sec * iso
            }, ref, opts.bracketStops)
        val inputs = mergeIndices.map { index ->
            val frame = loaded[index]
            RawSrMergeJob.MosaicInput(RawSrPackedFrame.fromMetadata(frame.plane, frame.metadata), frame.metadata)
        }
        // Overrides apply atop the preset; on the adaptive preset they pin
        // a scanned base (inputs[0] is the reference after reorder).
        var tuningOverride: RawSrTuning? = null
        val kDetail = opts.kDetail
        val flatSigma = opts.flatSigma
        val detailFloor = opts.detailFloor
        val flowRegularizeSigma = opts.flowRegularizeSigma
        if (kernelPreset != RawSrKernelPreset.REFERENCE || kDetail != null || flatSigma != null || detailFloor != null || flowRegularizeSigma != null) {
            val scanned = RawSrTuning.fromReference(inputs[0].packed).tuning
            tuningOverride = kernelPreset.resolve(scanned) ?: scanned
            if (kDetail != null) tuningOverride = tuningOverride!!.withKDetail(kDetail)
            if (flatSigma != null) tuningOverride = tuningOverride!!.withFlatSigma(flatSigma)
            if (detailFloor != null) tuningOverride = tuningOverride!!.withDetailFloor(detailFloor)
            if (flowRegularizeSigma != null) tuningOverride = tuningOverride!!.withFlowRegularizeSigma(flowRegularizeSigma)
        }
        println("linear-sr: kernelPreset=${kernelPreset.preferenceValue}" +
            (tuningOverride?.let { " kDetail=${it.kDetail} flatSigma=${it.flatSigma} detailFloor=${it.detailFloor} flowReg=${it.flowRegularizeSigma}" } ?: " (snr-adaptive)"))
        val noiseLut = resolveNoiseLut(refMetadata, opts.cacheDir)
        require(opts.backend == "cpu" || opts.backend == "vulkan") {
            "backend must be cpu or vulkan, got ${opts.backend}"
        }
        val linearScale = RawSrLinearScale.fromPreference(opts.linearScale)
        require(opts.linearScale == linearScale.preferenceValue) {
            "linear-scale must be 1x or sr, got ${opts.linearScale}"
        }
        val merged: MergedLinearRgb
        val meanSupport: Double
        val accepted: Int
        // Tuning that actually ran: the CPU chain resolves the adaptive scan
        // internally, so it reports back; the Vulkan path mirrors the phone
        // rule (fixed override, else the snr-adaptive label).
        var chainTuning: RawSrTuning? = null
        if (opts.backend == "vulkan") {
            val dumpDir = opts.dumpFields?.also { it.mkdirs() }
            if (dumpDir != null) {
                File(dumpDir, "vk_map.txt").writeText(
                    "mergeIndices=${mergeIndices}\n")
            }
            val out = VkRawSrProcessor(Context(opts.cacheDir)).use { proc ->
                proc.processPacked(inputs.map { it.packed }, tuningOverride = tuningOverride,
                    noiseLut = noiseLut, referenceOnly = opts.referenceOnly,
                    scale = linearScale,
                    onFlow = { index, id, cols, rows ->
                        if (dumpDir != null) {
                            val v = vkDownloadRgba32f(id)
                            writeF32(dumpDir, "vk_flow_$index", cols, rows, 4, v)
                        }
                    },
                    onCovariance = { index, id, w, h ->
                        if (dumpDir != null) {
                            val v = vkDownloadRgba32f(id)
                            writeF32(dumpDir, "vk_cov_$index", w, h, 4, v)
                        }
                    },
                    onRobustness = { index, rId, _, w, h ->
                        if (dumpDir != null) {
                            val v = vkDownloadR32f(rId)
                            writeF32(dumpDir, "vk_r_$index", w, h, 1, v)
                        }
                    }) { output ->
                    // Banded readback straight into the RGB triplets: peak is
                    // one 256-row RGBA band (~17MB) plus the retained RGB,
                    // not the ~380MB whole-frame RGBA + triplets. Values are
                    // bitwise-identical to the full download + repack slice.
                    val rgb = FloatArray(outWidthTimesHeightTimes3(output.width, output.height))
                    var y = 0
                    while (y < output.height) {
                        val rows = minOf(LinearRgbDngWriter.DEFAULT_STRIP_ROWS, output.height - y)
                        RawSrMergeJob.readMergedRgbStrip(
                            output.mergedTextureId, output.width, output.height,
                            y, rows, rgb,
                            Math.multiplyExact(Math.multiplyExact(y, output.width), 3))
                        y += rows
                    }
                    // Rc is source-anchored (quad grid): banded mean without
                    // retaining the full plane, like the phone save path.
                    val srcW = inputs[0].packed.width
                    val srcH = inputs[0].packed.height
                    val mean = RawSrMergeJob.readRcMeanSupport(
                        output.rcTextureId, srcW / 2, srcH / 2)
                    VulkanMerge(output.width, output.height, output.acceptedFrames, rgb, mean)
                }
            }
            merged = MergedLinearRgb(out.width, out.height, out.rgb)
            meanSupport = out.meanSupport
            accepted = out.acceptedFrames
            println("linear-sr: vulkan merged ${out.width}x${out.height} accepted=$accepted")
        } else {
            val chain = RawSrMergeJob.mosaicChain(inputs, 0, noiseLut = noiseLut,
                tuningOverride = tuningOverride, rejectionPolicy = rejectionPolicy)
            chainTuning = chain.tuning
            println(
                "linear-sr: chain kept ${chain.moving.size} moving frame(s) " +
                    "tilePx=${chain.alignmentTileSize} snr=${chain.tuning.snr}"
            )
            for (r in chain.rejections) {
                println("linear-sr: F${r.index} REJECTED (${r.reason})" +
                    (if (r.evRelative == null) "" else " ev=%+.2f".format(r.evRelative)) +
                    (if (r.meanRobustness == null) "" else " meanR=%.3f".format(r.meanRobustness)))
            }
            // Causation experiment: swap the CPU merge onto unclamped
            // GAT-guide covariances (both paths consume the GAT guide since
            // 2026-10-08; the default additionally applies the zipper-gate
            // minor-axis clamp) to test whether the clamp explains output
            // differences.
            var refFrame: RawSrBayerMerge.MergeFrame
            var movFrames: List<RawSrBayerMerge.MergeFrame>
            if (opts.cpuGatCov) {
                val gatRef = RawSrKernelCovariance.covariance(
                    RawSrCovarianceGuide.guide(inputs[0].packed).gray, chain.tuning)
                refFrame = chain.reference.copy(covariance = gatRef)
                movFrames = chain.moving.mapIndexed { j, frame ->
                    val g = RawSrCovarianceGuide.guide(
                        inputs[chain.survivorIndices[j]].packed).gray
                    frame.copy(covariance = RawSrKernelCovariance.covariance(g, chain.tuning))
                }
                println("linear-sr: cpu merge uses GAT-guide covariances (--cpu-gat-cov)")
            } else {
                refFrame = chain.reference
                movFrames = chain.moving
            }
            // KernelNet on the CPU oracle (default unless --no-kernelnet):
            // replace analytic covariances with learned fields (per-pixel
            // analytic fallback for rejected triples inside precisionFor).
            // Reuses each frame's own corrected samples, so no re-unpack;
            // sigma honors the chain's auto-sigma ratio plus
            // --kernelnet-sigma-mpy via sigmaFor. The Vulkan path needs no
            // swap: its provider reads the same object state.
            if (RawSrKernelNetAniso.enabled) {
                if (RawSrKernelNetAniso.isReady()) {
                    val auto = chain.noiseSigmaRatio
                    println("linear-sr: kernelnet auto-sigma ratio=${auto ?: "n/a"} " +
                        "(clamped to [1, ${RawSrKernelNetAniso.AUTO_SIGMA_MAX}])")
                    refFrame = RawSrMergeJob.kernelNetSwap(
                        refFrame, inputs[0].packed.noiseProfile, "ref", auto)
                    movFrames = movFrames.mapIndexed { j, frame ->
                        RawSrMergeJob.kernelNetSwap(frame,
                            inputs[chain.survivorIndices[j]].packed.noiseProfile,
                            "mov$j", auto)
                    }
                    println("linear-sr: cpu merge uses KernelNet covariances")
                } else {
                    println("linear-sr: kernelnet not ready at merge time, staying analytic")
                }
            }
            opts.dumpFields?.also { dumpDir ->
                dumpDir.mkdirs()
                val cov = refFrame.covariance
                writeF32(dumpDir, "cpu_cov_ref", cov.width, cov.height, 4, cov.values)
                val map = StringBuilder("mergeIndices=${mergeIndices}\n")
                map.append("survivors=${chain.survivorIndices}\n")
                map.append("tilePx=${chain.alignmentTileSize} snr=${chain.tuning.snr}\n")
                map.append("gatCov=${opts.cpuGatCov}\n")
                movFrames.forEachIndexed { j, frame ->
                    val surv = chain.survivorIndices[j]
                    val c = frame.covariance
                    writeF32(dumpDir, "cpu_cov_mov${j}_surv$surv", c.width, c.height, 4, c.values)
                    val flow = frame.flow!!
                    val fv = FloatArray(flow.columns * flow.rows * 2)
                    flow.tiles.forEachIndexed { t, tile ->
                        fv[t * 2] = tile.dx
                        fv[t * 2 + 1] = tile.dy
                    }
                    writeF32(dumpDir, "cpu_flow_mov${j}_surv$surv",
                        flow.columns, flow.rows, 2, fv)
                    val r = frame.robustness!!
                    writeF32(dumpDir, "cpu_r_mov${j}_surv$surv", r.width, r.height, 1, r.r)
                }
                File(dumpDir, "cpu_map.txt").writeText(map.toString())
                println("linear-sr: dumped CPU fields to $dumpDir")
            }
            val result = RawSrBayerMerge.merge(refFrame, movFrames,
                referenceOnly = opts.referenceOnly, scale = linearScale,
                chromaSigmaMpy = opts.chromaMpy ?: RawSrBayerMerge.CHROMA_SIGMA_MPY)
            // Merge-internals dump (Jamy-L debug-writer analogue, raw floats;
            // tools/render_sr_debug.py turns these into PNGs): pre-CFL rgb,
            // per-channel num/den, support/Rc/OOB/fallback/evidence. The rgb
            // copy below must precede the in-place finishing passes.
            opts.dumpFields?.also { dumpDir ->
                dumpDir.mkdirs()
                writeF32(dumpDir, "cpu_merge_rgb_pre", result.width, result.height, 3, result.rgb.copyOf())
                writeF32(dumpDir, "cpu_merge_num", result.width, result.height, 3, result.numerator)
                writeF32(dumpDir, "cpu_merge_den", result.width, result.height, 3, result.denominator)
                writeF32(dumpDir, "cpu_merge_support", result.rc.width,
                    result.rc.height, 1, result.support)
                writeF32(dumpDir, "cpu_merge_rc", result.rc.width, result.rc.height, 1, result.rc.values)
                writeF32(dumpDir, "cpu_merge_fallback", result.width, result.height, 1,
                    FloatArray(result.width * result.height) { if (result.fallback[it]) 1f else 0f })
                writeF32(dumpDir, "cpu_merge_evidence", result.width, result.height, 1,
                    FloatArray(result.width * result.height) { result.channelEvidence[it].toFloat() })
                writeF32(dumpDir, "cpu_merge_oob", result.width, result.height, 1,
                    FloatArray(result.width * result.height) { result.oobCount[it].toFloat() })
                println("linear-sr: dumped CPU merge internals to $dumpDir")
            }
            if (opts.referenceOnly) {
                println("linear-sr: cpu merge REFERENCE-ONLY (moving frames skipped)")
            }
            // Diagnostic A/B flags (default: both finishing passes on).
            // --no-inpaint exposes raw dead lanes (denominator <= eps reads 0);
            // --no-cfl exposes raw merged R/B latch.
            if (opts.noInpaint) {
                println("linear-sr: cpu dead-lane inpaint SKIPPED (--no-inpaint)")
            } else {
                val healed = RawSrDeadLaneInpaint.inpaint(
                    result.rgb, result.denominator, result.width, result.height)
                println("linear-sr: cpu dead-lane inpaint healed $healed lane(s)")
            }
            if (opts.noCfl) {
                println("linear-sr: cpu chroma-from-luma SKIPPED (--no-cfl)")
            } else {
                val steadied = RawSrChromaFromLuma.stabilize(result.rgb, result.width, result.height)
                println("linear-sr: cpu chroma-from-luma steadied $steadied pixel(s)")
            }
            merged = MergedLinearRgb(result.width, result.height, result.rgb)
            meanSupport = result.support.average()
            accepted = 1 + chain.moving.size
        }
        val effectiveFrames = RawSrMergedNoise.effectiveFrames(meanSupport, accepted.toDouble())
        val noiseOverride = refMetadata.cfaPattern?.let { pattern ->
            RawSrMergedNoise.scaleProfile(
                refMetadata.noiseProfile?.toDoubleArray(), pattern, effectiveFrames,
                refMetadata.blackLevels?.toFloatArray(), refMetadata.whiteLevel
            )
        }
        // Canonical outputScale is an integer area scale: 1 = native merge,
        // 2 = the 2x-area super-resolved grid (linear factor and tuning ride
        // in the version token, which the block interpolates verbatim).
        val tuningDesc = chainTuning?.describe()
            ?: tuningOverride?.describe() ?: "snr-adaptive"
        val provenance = MergeProvenance(
            algorithmVersion = LinearRgbDngWriter.ALGORITHM_VERSION +
                "/${linearScale.preferenceValue}/tuning=$tuningDesc",
            selectedFrames = mergeIndices.size,
            acceptedFrames = accepted,
            rejectedFrames = mergeIndices.size - accepted,
            referenceTimestampNs = refMetadata.timestampNanos,
            outputScale = if (linearScale == RawSrLinearScale.X1) 1 else 2,
            sourceCameraId = refMetadata.cameraId,
            lensShadingApplied = refMetadata.lensShadingAlreadyApplied,
            effectiveFrames = effectiveFrames
        )
        val name = LinearRgbDngSaver(opts.outDir).saveLinearRgb(
            merged, refMetadata, provenance, opts.captureId, gps = null,
            noiseProfileOverride = noiseOverride
        )
        println(
            "linear-sr: wrote ${File(opts.outDir, name)} " +
                "backend=${opts.backend} in ${(System.nanoTime() - t0) / 1_000_000}ms"
        )
    }

    /**
     * CPU-oracle KernelNet swap for one merge frame: rebuilds the corrected
     * CFA from the frame's own samples and converts learned precision onto
     * the analytic grid (per-pixel analytic fallback for rejected triples
     * inside [RawSrKernelNetAniso.precisionFor]). Read-only over samples;
     * sigma honors --kernelnet-sigma-mpy.
     */
    /** Diagnostic field dump: raw LE float32 + dims sidecar. */
    private fun writeF32(dir: File, name: String, w: Int, h: Int, channels: Int, values: FloatArray) {
        require(values.size == w * h * channels) {
            "$name: expected ${w * h * channels} floats, got ${values.size}"
        }
        File(dir, "$name.txt").writeText("$w $h $channels\n")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(File(dir, "$name.f32")))).use { out ->
            val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in values) {
                buf.clear()
                buf.putFloat(v)
                out.write(buf.array())
            }
        }
    }

    private data class VulkanMerge(
        val width: Int,
        val height: Int,
        val acceptedFrames: Int,
        val rgb: FloatArray,
        val meanSupport: Double
    )

    private fun outWidthTimesHeightTimes3(w: Int, h: Int): Int =
        Math.multiplyExact(Math.multiplyExact(w, h), 3)

    private fun resolveNoiseLut(
        refMetadata: com.matthew.rawlens.RawFrameMetadata,
        cacheDir: File
    ): RawSrNoiseLut.Lut? {
        val normalization = refMetadata.normalizationOrNull() ?: return null
        return runCatching {
            RawSrNoiseLut.cached(
                refMetadata.noiseProfile,
                normalization.blackLevels.toFloatArray(),
                normalization.whiteLevel,
                normalization.sensorPattern,
                cacheDir
            )
        }.getOrNull()
    }

    private fun extractModels(cacheDir: File) {
        val target = File(cacheDir, "models")
        target.mkdirs()
        listOf(
            "kernelnet_aniso_v2_2_params.ncnn.param",
            "kernelnet_aniso_v2_2_params.ncnn.bin"
        ).forEach { name ->
            val dest = File(target, name)
            if (!dest.isFile) {
                val stream = LinearMain::class.java.getResourceAsStream("/$name")
                    ?: return@forEach
                stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
