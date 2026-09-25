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
 *   [--crop x,y,w,h] [--limit N] [--no-kernelnet] [--capture-id ID]
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
        if (!opts.noKernelnet) {
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
            val out = VkRawSrProcessor(Context(opts.cacheDir)).use { proc ->
                proc.processPacked(inputs.map { it.packed }, noiseLut = noiseLut) { output ->
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
            val result = RawSrBayerMerge.merge(chain.reference, chain.moving)
            merged = MergedLinearRgb(result.width, result.height, result.rgb)
            meanSupport = result.support.average()
            accepted = 1 + chain.moving.size
        }
        val effectiveFrames = RawSrMergedNoise.effectiveFrames(meanSupport, accepted.toDouble())
        val noiseOverride = refMetadata.cfaPattern?.let { pattern ->
            RawSrMergedNoise.scaleProfile(
                refMetadata.noiseProfile?.toDoubleArray(), pattern, effectiveFrames
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
