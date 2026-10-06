// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.srdesktop

import android.content.Context
import com.matthew.rawlens.DngFrameLoader
import com.matthew.rawlens.LinearRgbDngWriter
import com.matthew.rawlens.MosaicSrCfa
import com.matthew.rawlens.MosaicSrDngSaver
import com.matthew.rawlens.MosaicSrProvenance
import com.matthew.rawlens.MosaicSrReconstructor
import com.matthew.rawlens.NcnnLoader
import com.matthew.rawlens.RawSrKernelNetAniso
import com.matthew.rawlens.RawSrKernelPreset
import com.matthew.rawlens.RawSrMergeDecisions
import com.matthew.rawlens.RawSrMergeJob
import com.matthew.rawlens.RawSrMosaicScale
import com.matthew.rawlens.RawSrNoiseLut
import com.matthew.rawlens.RawSrPackedFrame
import com.matthew.rawlens.RawSrTuning
import com.matthew.rawlens.SrRuntime
import java.io.File

/**
 * Mosaic SR desktop CLI. Mirrors RawCameraController.runSrMosaicDng line by
 * line (same calls, same order, same provenance math); only the frame source
 * (DNG files instead of Camera2) and the sink (file saver instead of
 * MediaStore) are desktop-owned.
 *
 * Usage: mosaic-desktop --in <dng-dir> --out <dir> [--ref N] [--cache DIR]
 *   [--crop x,y,w,h] [--limit N] [--kernelnet] [--kernelnet-sigma-mpy X]
 *   [--kernelnet-kernel-mpy X] [--kernelnet-min-sigma X] [--no-kernelnet]
 *   [--kernel-preset reference|decoupled_sharp] [--k-detail X] [--flat-sigma X]
 *   [--detail-floor X] [--flow-regularize-sigma X]
 *   [--capture-id ID] [--mosaic-scale native|sr]
 * KernelNet covariances with auto-sigma are the default merge path (swapped
 * per frame inside the mosaic stream); --no-kernelnet opts out to analytic.
 */
object MosaicMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "vkcheck") {
            VkCheck.main(args.drop(1).toTypedArray())
            return
        }
        val opts = Options.parse(args, "mosaic-desktop")
        run(opts)
    }

    fun run(opts: Options) {
        val t0 = System.nanoTime()
        val kernelPreset = RawSrKernelPreset.fromPreference(opts.kernelPreset)
        require(opts.kernelPreset == kernelPreset.preferenceValue) {
            "kernel-preset must be reference or decoupled_sharp, got ${opts.kernelPreset}"
        }
        val mosaicScale = RawSrMosaicScale.fromPreference(opts.mosaicScale)
        require(opts.mosaicScale == mosaicScale.preferenceValue) {
            "mosaic-scale must be native or sr, got ${opts.mosaicScale}"
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
                "mosaic-sr: frame $i ${f.file.name} ${f.admitted.width}x${f.admitted.height}" +
                    " exp=${f.admitted.exposureSec}s iso=${f.admitted.iso}" +
                    " noise=${if (f.admitted.noiseProfile != null) "yes" else "MISSING"}"
            )
        }
        android.os.Build.MANUFACTURER = loaded.first().admitted.make
        android.os.Build.MODEL = loaded.first().admitted.model
        val ref = if (opts.ref < 0) loaded.size / 2 else opts.ref.coerceIn(loaded.indices)
        val refMetadata = loaded[ref].metadata
        val cameraId = refMetadata.cameraId
        // KernelNet is ON by default (RawSrKernelNetAniso.enabled);
        // --no-kernelnet opts out to the analytic path for this run.
        // --kernelnet is accepted for compatibility and forces a hard failure
        // when the model is not ready; without it, a missing model falls back
        // to analytic with a warning instead of aborting the run.
        // --kernelnet-sigma-mpy stacks on top of auto-sigma (same law as the
        // linear CLI); ignored with --no-kernelnet. Readiness is awaited
        // BEFORE the stream assembles: the stream freezes its swap decision
        // at assembly, so awaiting here keeps every frame consistent.
        if (opts.noKernelnet && opts.kernelnetSigmaMpy != 1.0f) {
            println("mosaic-sr: WARNING --kernelnet-sigma-mpy ignored with --no-kernelnet")
        }
        if (opts.noKernelnet && opts.kernelnetKernelMpy != RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MPY) {
            println("mosaic-sr: WARNING --kernelnet-kernel-mpy ignored with --no-kernelnet")
        }
        if (opts.noKernelnet && opts.kernelnetMajorMpy != RawSrKernelNetAniso.DEFAULT_KERNEL_SIGMA_MAJOR_MPY) {
            println("mosaic-sr: WARNING --kernelnet-major-mpy ignored with --no-kernelnet")
        }
        RawSrKernelNetAniso.sigmaMpy = opts.kernelnetSigmaMpy
        RawSrKernelNetAniso.kernelSigmaMpy = opts.kernelnetKernelMpy
        RawSrKernelNetAniso.kernelSigmaMajorMpy = opts.kernelnetMajorMpy
        RawSrKernelNetAniso.minSigma = opts.kernelnetMinSigma
        RawSrKernelNetAniso.enabled = !opts.noKernelnet
        RawSrKernelNetAniso.zipperGates = opts.zipperGates
        println("mosaic-sr: zipperGates=${opts.zipperGates}")
        if (RawSrKernelNetAniso.enabled) {
            extractModels(opts.cacheDir)
            val ncnnOk = NcnnLoader.tryLoad()
            println(
                "mosaic-sr: kernelnet ncnnLib=$ncnnOk enabled=${RawSrKernelNetAniso.enabled}" +
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
            println("mosaic-sr: kernelnet ready=$ready (isReady=${RawSrKernelNetAniso.isReady()})")
            if (!ready) {
                require(!opts.kernelnet) {
                    "kernelnet requested (--kernelnet) but model not ready; aborting test run"
                }
                println("mosaic-sr: kernelnet model not ready, falling back to analytic kernels")
                RawSrKernelNetAniso.enabled = false
            }
        } else {
            println("mosaic-sr: kernelnet off (analytic kernels)")
        }
        val decision = RawSrMergeDecisions.decide(loaded.indices.toList(), 0, ref, loaded.size)
        val rejectionPolicy = Options.rejectionPolicy(opts)
        val mergeIndices = opts.maxFrames?.let { max ->
            require(max in 2..30) { "--max-frames must be 2..30, got $max" }
            decision.mergeIndices.take(max).also {
                if (it.size < decision.mergeIndices.size) {
                    println("mosaic-sr: capped merge set to ${it.size}/${decision.mergeIndices.size} frames (--max-frames)")
                }
            }
        } ?: decision.mergeIndices
        Options.reportBracketCoverage("mosaic-sr",
            loaded.map { f ->
                val sec = f.admitted.exposureSec
                val iso = f.admitted.iso
                if (sec == null || iso == null || sec <= 0.0 || iso <= 0) null else sec * iso
            }, ref, opts.bracketStops)
        val inputs = mergeIndices.map { index ->
            val frame = loaded[index]
            RawSrMergeJob.MosaicInput(RawSrPackedFrame.fromMetadata(frame.plane, frame.metadata), frame.metadata)
        }
        val noiseLut = resolveNoiseLut(refMetadata, opts.cacheDir)
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
        println("mosaic-sr: kernelPreset=${kernelPreset.preferenceValue}" +
            (tuningOverride?.let { " kDetail=${it.kDetail} flatSigma=${it.flatSigma} detailFloor=${it.detailFloor} flowReg=${it.flowRegularizeSigma}" } ?: " (snr-adaptive)"))
        val mosaicT0 = android.os.SystemClock.elapsedRealtime()
        val stream = RawSrMergeJob.mosaicStream(inputs, 0, noiseLut = noiseLut,
            tuningOverride = tuningOverride, rejectionPolicy = rejectionPolicy,
            onRejected = { r ->
                println("mosaic-sr: F${r.index} REJECTED (${r.reason})" +
                    (if (r.evRelative == null) "" else " ev=%+.2f".format(r.evRelative)) +
                    (if (r.meanRobustness == null) "" else " meanR=%.3f".format(r.meanRobustness)))
            })
        // The stream froze its KernelNet decision at assembly (readiness was
        // awaited above, so enabled here means every frame swaps).
        if (RawSrKernelNetAniso.enabled) {
            println("mosaic-sr: kernelnet auto-sigma ratio=${stream.noiseSigmaRatio ?: "n/a"} " +
                "(clamped to [1, ${RawSrKernelNetAniso.AUTO_SIGMA_MAX}])")
            println("mosaic-sr: cpu merge uses KernelNet covariances")
        }
        val result = MosaicSrReconstructor.reconstructStreaming(
            stream.geometry,
            stream.frames,
            buildReference = stream.referenceFrame,
            tempDir = opts.cacheDir,
            scale = mosaicScale,
            chromaSigmaMpy = opts.chromaMpy ?: com.matthew.rawlens.RawSrBayerMerge.CHROMA_SIGMA_MPY
        )
        val effectiveFrames = com.matthew.rawlens.RawSrMergedNoise.effectiveFrames(
            result.meanSupport, 1.0 + result.acceptedFrames
        )
        println(
            "mosaic-sr: merged ${stream.geometry.width}x${stream.geometry.height}" +
                " -> ${result.width}x${result.height} scale=${mosaicScale.preferenceValue}" +
                " selected=${stream.selected} accepted=${result.acceptedFrames}" +
                " effectiveFrames=${LinearRgbDngWriter.formatEffectiveFrames(effectiveFrames)}" +
                " mergeMs=${android.os.SystemClock.elapsedRealtime() - mosaicT0}" +
                " workers=${SrRuntime.workerCount}"
        )
        val accepted = 1 + result.acceptedFrames
        val noiseOverride = refMetadata.cfaPattern?.let { pattern ->
            com.matthew.rawlens.RawSrMergedNoise.scaleProfile(
                refMetadata.noiseProfile?.toDoubleArray(), pattern, effectiveFrames,
                refMetadata.blackLevels?.toFloatArray(), refMetadata.whiteLevel
            )
        }
        val provenance = MosaicSrProvenance(
            selectedFrames = mergeIndices.size,
            acceptedFrames = accepted,
            rejectedFrames = mergeIndices.size - accepted,
            referenceTimestampNs = refMetadata.timestampNanos,
            sourceWidth = stream.geometry.width,
            sourceHeight = stream.geometry.height,
            sourceCameraId = cameraId,
            lensShadingApplied = refMetadata.lensShadingAlreadyApplied,
            effectiveFrames = effectiveFrames
        )
        // Scale already prints with the result dims above; tuning has no
        // provenance slot, so it stays on stdout like the linear CLI.
        println("mosaic-sr: tuning=${stream.tuning.describe()}")
        val mergedCfa = MosaicSrCfa(result.width, result.height, result.pattern, result.cfa)
        val name = MosaicSrDngSaver(opts.outDir).saveMosaicSr(
            mergedCfa,
            refMetadata, provenance, opts.captureId, gps = null,
            noiseProfileOverride = noiseOverride
        )
        println("mosaic-sr: wrote ${File(opts.outDir, name)} in ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

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
                val stream = MosaicMain::class.java.getResourceAsStream("/$name")
                    ?: return@forEach
                stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
