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
import com.matthew.rawlens.RawSrMergeDecisions
import com.matthew.rawlens.RawSrMergeJob
import com.matthew.rawlens.RawSrNoiseLut
import com.matthew.rawlens.RawSrPackedFrame
import com.matthew.rawlens.SrRuntime
import java.io.File

/**
 * Mosaic SR desktop CLI. Mirrors RawCameraController.runSrMosaicDng line by
 * line (same calls, same order, same provenance math); only the frame source
 * (DNG files instead of Camera2) and the sink (file saver instead of
 * MediaStore) are desktop-owned.
 *
 * Usage: mosaic-desktop --in <dng-dir> --out <dir> [--ref N] [--cache DIR]
 *   [--crop x,y,w,h] [--limit N] [--no-kernelnet] [--capture-id ID]
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
        val mosaicT0 = android.os.SystemClock.elapsedRealtime()
        val stream = RawSrMergeJob.mosaicStream(inputs, 0, noiseLut = noiseLut)
        val result = MosaicSrReconstructor.reconstructStreaming(
            stream.geometry,
            stream.frames,
            buildReference = { RawSrMergeJob.buildReferenceFrame(inputs[0]) },
            tempDir = opts.cacheDir
        )
        val effectiveFrames = com.matthew.rawlens.RawSrMergedNoise.effectiveFrames(
            result.meanSupport, 1.0 + result.acceptedFrames
        )
        println(
            "mosaic-sr: merged ${stream.geometry.width}x${stream.geometry.height}" +
                " selected=${stream.selected} accepted=${result.acceptedFrames}" +
                " effectiveFrames=${LinearRgbDngWriter.formatEffectiveFrames(effectiveFrames)}" +
                " mergeMs=${android.os.SystemClock.elapsedRealtime() - mosaicT0}" +
                " workers=${SrRuntime.workerCount}"
        )
        val accepted = 1 + result.acceptedFrames
        val noiseOverride = refMetadata.cfaPattern?.let { pattern ->
            com.matthew.rawlens.RawSrMergedNoise.scaleProfile(
                refMetadata.noiseProfile?.toDoubleArray(), pattern, effectiveFrames
            )
        }
        val provenance = MosaicSrProvenance(
            selectedFrames = decision.mergeIndices.size,
            acceptedFrames = accepted,
            rejectedFrames = decision.mergeIndices.size - accepted,
            referenceTimestampNs = refMetadata.timestampNanos,
            sourceWidth = stream.geometry.width,
            sourceHeight = stream.geometry.height,
            sourceCameraId = cameraId,
            lensShadingApplied = refMetadata.lensShadingAlreadyApplied,
            effectiveFrames = effectiveFrames
        )
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
