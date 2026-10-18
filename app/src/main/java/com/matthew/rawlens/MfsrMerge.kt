// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.util.Log
import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import java.nio.ByteBuffer

/**
 * ZSL sharp-merge orchestration: sharpest-first burst merge at native
 * sensor resolution through the shared SR engine (tools/sr-pipeline,
 * scale 1) instead of the HDR+ merge. HDR+ files are pattern reference
 * only (shutter discipline, settings shape, DNG/JPEG tail); nothing
 * HDR+ merges here.
 *
 * Input frames are live ZSL buffers (unpacked uint16, sensor order);
 * the engine ingests RGGB float rows, so decode flips + normalises
 * exactly like the DNG path ([DngRead.flipToRggb] index maps,
 * per-plane `(v - black) / (white - black)`). Live frames have no DNG
 * twins, so the live-adapter gate is: capture with [MfsrSettings]
 * .keepSourceBurst, run the same frames through the desktop CLI, and
 * census the pair (same envelope class as the crop gate).
 *
 * Noise correction stays off (no live LUT file; zero curves, the
 * recorded nc=false leg); kernel GAT uses the live SENSOR_NOISE_PROFILE
 * via [DngRead.noiseAlphaBeta], same as the CLI's DNG profile leg.
 */
internal object MfsrMerge {

    data class MfsrFrame(
        val frame: BufferedRawFrame,
        val metadata: RawFrameMetadata,
        val norm: RawNormalization,
        val cfa: UnpackedRawCfa
    )

    data class MfsrResult(
        /** Merged linear RGB, pipeline row order (bottom-up), NaN-free. */
        val rgb: ByteBuffer,
        val width: Int,
        val height: Int,
        val keptCount: Int,
        val refIndex: Int,
        val mergeMs: Long,
        val gpuMs: Double,
        /** Sharpest frame's metadata (DNG tags + provenance). */
        val refMetadata: RawFrameMetadata,
        /**
         * Live reference result for the JPEG EXIF tail; null for offline
         * takes, which carry no Camera2 result (the no-result [JpegSaver]
         * overload covers that tail).
         */
        val refResult: android.hardware.camera2.CaptureResult?
    )

    /**
     * One offline input: a decoded `_BURST.crs` frame plus its unpacked
     * CFA. Mirrors [MfsrFrame] without the live [BufferedRawFrame].
     */
    data class MfsrDecodedFrame(
        val source: OfflineBurstFrame,
        val cfa: UnpackedRawCfa
    )

    /**
     * Unpacks + normalises every frame (sensor order) and picks the
     * sharpest as reference via [BurstReferenceSelection] (green-gradient
     * sharpness; ties resolve temporal-central, same as the still path).
     * Returns the frames with the sharpest first, or null when no frame
     * is usable.
     */
    fun prepare(frames: List<BufferedRawFrame>, metadataOf: (BufferedRawFrame) -> RawFrameMetadata): List<MfsrFrame>? {
        if (frames.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val unpacked = frames.mapIndexedNotNull { index, frame ->
            try {
                val metadata = metadataOf(frame)
                val norm = metadata.normalizationOrNull() ?: return@mapIndexedNotNull null
                val plane = frame.image.planes?.firstOrNull() ?: return@mapIndexedNotNull null
                val layout = RawPlaneLayout(
                    metadata.imageWidth, metadata.imageHeight,
                    plane.rowStride, plane.pixelStride, 0, 0
                )
                val cfa = RawSensorUnpacker.unpackNormalized(
                    plane.buffer.duplicate(), layout, norm
                )
                MfsrFrame(frame, metadata, norm, cfa)
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "sharp prepare: frame $index unusable", failure)
                null
            }
        }
        if (unpacked.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val refOrder = selectSharpest(unpacked) ?: return null
        return refOrder.map { unpacked[it] }
    }

    private fun selectSharpest(frames: List<MfsrFrame>): List<Int>? {
        return try {
            val candidates = frames.mapIndexed { index, sharp ->
                val image = sharp.frame.image
                val thumb = GreenThumbnailSampler.sample(
                    image, sharp.cfa.width, sharp.cfa.height,
                    0, 0, sharp.cfa.width, sharp.cfa.height,
                    image.planes[0].rowStride, image.planes[0].pixelStride,
                    sharp.norm.sensorPattern, 0, 0,
                    sharp.norm.blackLevels, sharp.norm.whiteLevel
                )
                val result = sharp.frame.result
                ReferenceCandidateInput(
                    index = index,
                    timestampNanos = sharp.frame.timestampNanos,
                    thumbnail = thumb,
                    exposureNanos = sharp.frame.exposureNanos,
                    sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    noiseProfile = sharp.metadata.noiseProfile?.toDoubleArray(),
                    blackLevel = sharp.norm.blackLevels[1],
                    whiteLevel = sharp.norm.whiteLevel,
                    gyroSamples = emptyList(),
                    rollingShutterSkewNanos = sharp.frame.rollingShutterSkewNanos,
                    afState = result.get(CaptureResult.CONTROL_AF_STATE),
                    aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                    lensState = result.get(CaptureResult.LENS_STATE)
                )
            }
            val selection = BurstReferenceSelection.select(candidates)
            Log.i(LOG_TAG, "sharp reference: frame ${selection.selectedIndex}/${frames.size} " +
                candidates.indices.joinToString(",") {
                    "$it=${"%.2f".format(selection.records[it].total)}"
                })
            listOf(selection.selectedIndex) + candidates.indices.filter { it != selection.selectedIndex }
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "sharp selection failed, keeping capture order", failure)
            frames.indices.toList()
        }
    }

    /**
     * Decodes one unpacked frame to the engine's RGGB float rows (same
     * flip index maps as [DngRead.flipToRggb]; values arrive normalised
     * in sensor coordinates and even dims preserve the black planes
     * across the flip, same as the DNG leg).
     */
    fun decodeRows(cfa: UnpackedRawCfa): Array<FloatArray> {
        val w = cfa.width
        val h = cfa.height
        require(w % 2 == 0 && h % 2 == 0) { "sharp merge needs even dimensions, got ${w}x$h" }
        val flipped = flipFloatsToRggb(cfa.values, w, h, dngCodesFor(cfa.pattern))
        return Array(h) { y -> FloatArray(w) { x -> flipped[y * w + x] } }
    }

    /**
     * DNG-LibRaw code array for the physical layout. The DNG GRBG/GBRG
     * labels are transposed vs the camera2 names ([DngRead.flipToRggb]
     * GRBG [[1,2],[0,1]] is physically GBRG), so the mapping keys off
     * the RED cell position, which is unambiguous: R top-left identity,
     * top-right horizontal flip, bottom-left vertical flip, bottom-right
     * both. The DNG leg is self-consistent (proven by the green gates);
     * this only aligns the live adapter with it.
     */
    fun dngCodesFor(pattern: BayerPattern): IntArray {
        return when (pattern.cellOrdinals.indexOf(0)) {
            0 -> intArrayOf(0, 1, 1, 2)
            1 -> intArrayOf(1, 0, 2, 1)
            2 -> intArrayOf(1, 2, 0, 1)
            else -> intArrayOf(2, 1, 1, 0)
        }
    }

    private fun flipFloatsToRggb(pixels: FloatArray, w: Int, h: Int, cfa: IntArray): FloatArray {
        require(pixels.size == w * h) { "sharp merge: raster size mismatch" }
        if (cfa[0] == 0 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 2) return pixels
        // BGGR: flip both axes. GBRG: flip horizontal. GRBG: flip vertical.
        // Same index maps as DngRead.flipToRggb (values already normalised).
        if (cfa[0] == 2 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 0) {
            return FloatArray(w * h) { i -> pixels[(h - 1 - i / w) * w + (w - 1 - i % w)] }
        }
        if (cfa[0] == 1 && cfa[1] == 0 && cfa[2] == 2 && cfa[3] == 1) {
            return FloatArray(w * h) { i -> pixels[(i / w) * w + (w - 1 - i % w)] }
        }
        if (cfa[0] == 1 && cfa[1] == 2 && cfa[2] == 0 && cfa[3] == 1) {
            return FloatArray(w * h) { i -> pixels[(h - 1 - i / w) * w + (i % w)] }
        }
        throw IllegalArgumentException("sharp merge: unsupported CFA pattern ${cfa.toList()}")
    }

    /**
     * Merges sharpest-first frames at native resolution (scale 1) and
     * returns linear RGB. Decode thunks run just in time inside the
     * engine (one live frame at a time under memoryTight), mirroring
     * the CLI's decode-then-release discipline.
     */
    fun merge(
        context: Context,
        ordered: List<MfsrFrame>,
        settings: MfsrSettings
    ): MfsrResult? {
        val count = settings.activeFrameCount().coerceAtMost(ordered.size)
        if (count < MfsrSettings.MIN_MERGE_FRAMES) return null
        val kept = ordered.take(count)
        val engine = runEngine(
            context, kept.map { it.cfa }, kept[0].metadata.noiseProfile?.toDoubleArray(),
            settings
        ) ?: return null
        return MfsrResult(engine.rgb, engine.width, engine.height, count, 0,
            engine.mergeMs, 0.0, kept[0].metadata, kept[0].frame.result)
    }

    /**
     * Offline merge over decoded takes: same engine as [merge], reference
     * metadata from the sharpest decoded frame, no Camera2 result.
     */
    fun mergeDecoded(
        context: Context,
        ordered: List<MfsrDecodedFrame>,
        settings: MfsrSettings
    ): MfsrResult? {
        val count = settings.activeFrameCount().coerceAtMost(ordered.size)
        if (count < MfsrSettings.MIN_MERGE_FRAMES) return null
        val kept = ordered.take(count)
        val engine = runEngine(
            context, kept.map { it.cfa }, kept[0].source.metadata.noiseProfile?.toDoubleArray(),
            settings
        ) ?: return null
        return MfsrResult(engine.rgb, engine.width, engine.height, count, 0,
            engine.mergeMs, 0.0, kept[0].source.metadata, null)
    }

    /**
     * Offline prepare: unpacks decoded frames and orders sharpest-first
     * via [BurstReferenceSelection] (same green-thumbnail scoring as the
     * live path; AE/AF/lens states and gyro are unknown offline and read
     * as absent, motion skew as zero).
     */
    fun prepareDecoded(frames: List<OfflineBurstFrame>): List<MfsrDecodedFrame>? {
        if (frames.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val unpacked = frames.mapIndexedNotNull { index, frame ->
            try {
                val layout = RawPlaneLayout(
                    frame.width, frame.height, frame.rowStride, 2, 0, 0
                )
                val cfa = RawSensorUnpacker.unpackNormalized(
                    frame.pixels.duplicate(), layout, frame.normalization
                )
                MfsrDecodedFrame(frame, cfa)
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "sharp offline prepare: frame $index unusable", failure)
                null
            }
        }
        if (unpacked.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val refOrder = selectSharpestDecoded(unpacked) ?: return null
        return refOrder.map { unpacked[it] }
    }

    private fun selectSharpestDecoded(frames: List<MfsrDecodedFrame>): List<Int>? {
        return try {
            val candidates = frames.mapIndexed { index, decoded ->
                val thumb = GreenThumbnailSampler.fromCfa(decoded.cfa)
                ReferenceCandidateInput(
                    index = index,
                    timestampNanos = decoded.source.timestampNanos,
                    thumbnail = thumb,
                    exposureNanos = decoded.source.exposureNanos,
                    sensitivityIso = decoded.source.sensitivityIso,
                    noiseProfile = decoded.source.metadata.noiseProfile?.toDoubleArray(),
                    blackLevel = decoded.source.normalization.blackLevels[1],
                    whiteLevel = decoded.source.normalization.whiteLevel,
                    gyroSamples = emptyList(),
                    rollingShutterSkewNanos = 0L,
                    afState = null,
                    aeState = null,
                    lensState = null
                )
            }
            val selection = BurstReferenceSelection.select(candidates)
            Log.i(LOG_TAG, "sharp offline reference: frame ${selection.selectedIndex}/${frames.size}")
            listOf(selection.selectedIndex) + candidates.indices.filter { it != selection.selectedIndex }
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "sharp offline selection failed, keeping take order", failure)
            frames.indices.toList()
        }
    }

    private data class EngineOut(
        val rgb: ByteBuffer,
        val width: Int,
        val height: Int,
        val mergeMs: Long
    )

    /**
     * Shared SR engine: native-resolution scale-1 merge over unpacked
     * CFAs. Called by [merge] (live) and [mergeDecoded] (offline) alike.
     */
    private fun runEngine(
        context: Context,
        cfas: List<UnpackedRawCfa>,
        noise: DoubleArray?,
        settings: MfsrSettings
    ): EngineOut? {
        val count = cfas.size
        val w = cfas[0].width
        val h = cfas[0].height
        if (cfas.any { it.width != w || it.height != h }) {
            Log.w(LOG_TAG, "sharp merge: mixed frame geometries, aborting")
            return null
        }
        try {
            SrpBridge.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(LOG_TAG, "sharp merge: libsrpipeline.so unavailable: ${e.message}")
            return null
        }
        val handle = SrpBridge.nativeCreate()
        if (handle == 0L) {
            Log.w(LOG_TAG, "sharp merge: no Vulkan compute device")
            return null
        }
        try {
            val (alpha, beta) = if (noise != null && noise.size == 8) {
                DngRead.noiseAlphaBeta(noise, 8)
            } else {
                DoubleArray(4) { 1e-4 } to DoubleArray(4) { 1e-6 }
            }
            // Same recipe as the desktop CLI / crop gate (BurstConfig SNR
            // scan sizes the tiles); scale 1 keeps the source grid.
            val first = decodeRows(cfas[0])
            val snr = BurstConfig.estimateSnr(first, alpha, beta)
            val fin = BurstConfig.finalize(snr)
            val factors = listOf(1, 2, 4, 4)
            BurstConfig.sanitize(1f, fin.tileSize, factors, fin.tileSizes, h, w)
            val config = PipelineConfig(
                scale = 1f, bayer = true, factors = factors, tileSizes = fin.tileSizes,
                kernelPreprocessing = "gat",
                kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
                kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(noiseCorrection = false),
                robustnessEnabled = true,
                memoryTight = true,
                icaIterations = 3
            )
            Log.i(LOG_TAG, "sharp merge ${w}x$h N=$count snr=%.2f tile=%d".format(snr, fin.tileSize))
            fun asset(name: String): ByteArray =
                context.assets.open("sr_spv/$name.spv").use { it.readBytes() }
            val fft = asset("fft")
            val t0 = System.nanoTime()
            val rgb = VulkanPipeline.run(
                { first },
                cfas.drop(1).map { cfa ->
                    { _: ByteBuffer -> decodeRows(cfa) }
                },
                config, handle,
                AlignGpu.Spirvs(
                    align = asset("align"), corr = asset("corr"),
                    fft = fft, ica = asset("ica")
                ),
                VulkanPipeline.Spirvs(
                    gat = asset("gat"), grad = asset("grad"),
                    kernels = asset("kernels"), divide = asset("divide"),
                    robust = asset("robustness"),
                    mergeSplat = asset("merge_splat"),
                    fft = fft, corr = asset("corr")
                )
            )
            val mergeMs = (System.nanoTime() - t0) / 1_000_000
            VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), w * h * 3)
            rgb.rewind()
            // Pipeline rows run sensor-bottom-up; consumers (DNG/JPEG)
            // take native top-down, same as the CLI tail.
            VulkanPipeline.flipRowsInPlace(rgb.asFloatBuffer(), w, h)
            rgb.rewind()
            Log.i(LOG_TAG, "sharp merge done ${w}x$h N=$count in ${mergeMs}ms")
            return EngineOut(rgb, w, h, mergeMs)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "sharp merge failed", failure)
            return null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "sharp merge OOM", oom)
            return null
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    private const val LOG_TAG = "MfsrMerge"
}
