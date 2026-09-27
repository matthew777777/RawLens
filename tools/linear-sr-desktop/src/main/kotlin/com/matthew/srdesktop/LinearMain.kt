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
import com.matthew.rawlens.RawSrMergeDecisions
import com.matthew.rawlens.RawSrMergeJob
import com.matthew.rawlens.RawSrMergedNoise
import com.matthew.rawlens.RawSrNoiseLut
import com.matthew.rawlens.RawSrPackedFrame
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
 *   [--crop x,y,w,h] [--limit N] [--no-kernelnet] [--no-inpaint] [--no-cfl]
 *   [--capture-id ID]
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
        // KernelNet is disconnected by default (RawSrKernelNetAniso.enabled);
        // skip the model extract/load unless an A/B run opts back in.
        if (!opts.noKernelnet && RawSrKernelNetAniso.enabled) {
            extractModels(opts.cacheDir)
            NcnnLoader.tryLoad()
            RawSrKernelNetAniso.preload(Context(opts.cacheDir))
        }
        val decision = RawSrMergeDecisions.decide(loaded.indices.toList(), 0, ref, loaded.size)
        val inputs = decision.mergeIndices.map { index ->
            val frame = loaded[index]
            RawSrMergeJob.MosaicInput(RawSrPackedFrame.fromMetadata(frame.plane, frame.metadata), frame.metadata)
        }
        val noiseLut = resolveNoiseLut(refMetadata, opts.cacheDir)
        require(opts.backend == "cpu" || opts.backend == "vulkan") {
            "backend must be cpu or vulkan, got ${opts.backend}"
        }
        val merged: MergedLinearRgb
        val meanSupport: Double
        val accepted: Int
        if (opts.backend == "vulkan") {
            val dumpDir = opts.dumpFields?.also { it.mkdirs() }
            if (dumpDir != null) {
                File(dumpDir, "vk_map.txt").writeText(
                    "mergeIndices=${decision.mergeIndices}\n")
            }
            val out = VkRawSrProcessor(Context(opts.cacheDir)).use { proc ->
                proc.processPacked(inputs.map { it.packed }, noiseLut = noiseLut,
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
                    val rgba = vkDownloadRgba32f(output.mergedTextureId)
                    val rc = vkDownloadR32f(output.rcTextureId)
                    VulkanMerge(output.width, output.height, output.acceptedFrames, rgba, rc)
                }
            }
            val rgb = FloatArray(out.width * out.height * 3)
            for (i in rgb.indices) rgb[i] = out.rgba[(i / 3) * 4 + i % 3]
            merged = MergedLinearRgb(out.width, out.height, rgb)
            meanSupport = vkRcMeanSupport(out.rc)
            accepted = out.acceptedFrames
            println("linear-sr: vulkan merged ${out.width}x${out.height} accepted=$accepted")
        } else {
            val chain = RawSrMergeJob.mosaicChain(inputs, 0, noiseLut = noiseLut)
            println(
                "linear-sr: chain kept ${chain.moving.size} moving frame(s) " +
                    "tileQuads=${chain.alignmentTileQuads} snr=${chain.tuning.snr}"
            )
            // Causation experiment: swap the CPU merge onto GAT-guide
            // covariances (exactly what the Vulkan path consumes) to test
            // whether the guide input explains the VK spikes.
            val refFrame: RawSrBayerMerge.MergeFrame
            val movFrames: List<RawSrBayerMerge.MergeFrame>
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
            opts.dumpFields?.also { dumpDir ->
                dumpDir.mkdirs()
                val cov = refFrame.covariance
                writeF32(dumpDir, "cpu_cov_ref", cov.width, cov.height, 4, cov.values)
                val map = StringBuilder("mergeIndices=${decision.mergeIndices}\n")
                map.append("survivors=${chain.survivorIndices}\n")
                map.append("tileQuads=${chain.alignmentTileQuads} snr=${chain.tuning.snr}\n")
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
            val result = RawSrBayerMerge.merge(refFrame, movFrames)
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
        val provenance = MergeProvenance(
            algorithmVersion = LinearRgbDngWriter.ALGORITHM_VERSION,
            selectedFrames = decision.mergeIndices.size,
            acceptedFrames = accepted,
            rejectedFrames = decision.mergeIndices.size - accepted,
            referenceTimestampNs = refMetadata.timestampNanos,
            outputScale = 1,
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
        val rgba: FloatArray,
        val rc: FloatArray
    )

    /** Mirrors RawSrMergeJob.readRcMeanSupport: mean of (1 + Rc), non-finite as 1. */
    private fun vkRcMeanSupport(rc: FloatArray): Double {
        var sum = 0.0
        for (v in rc) {
            val s = 1.0 + v.toDouble()
            sum += if (s.isFinite()) s else 1.0
        }
        return sum / rc.size
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
                val stream = LinearMain::class.java.getResourceAsStream("/$name")
                    ?: return@forEach
                stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
