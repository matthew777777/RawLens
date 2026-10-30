// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.stages.AlignGpu
import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.DngRead
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.NoiseLutGen
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.VulkanPipeline
import java.io.File
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

    /**
     * One selected frame. The unpacked CFA is deliberately NOT retained:
     * 8 full-res CFAs (50 MB each) exceed the 512 MB heap before the
     * engine allocates; unpack runs just in time inside the decode thunk
     * (one live CFA at a time), cf. the OOM this ordering fixes.
     */
    data class MfsrFrame(
        val frame: BufferedRawFrame,
        val metadata: RawFrameMetadata,
        val norm: RawNormalization
    )

    data class MfsrResult(
        /** Merged linear RGB in original sensor coordinates, top-down, NaN-free. */
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
    ) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Frees [rgb] (native memory, outside the Java heap). Call exactly
         * when the DNG/JPEG tail is done with it; later calls are no-ops.
         */
        fun release() {
            if (released.compareAndSet(false, true)) SrpBridge.nativeFreeDirect(rgb)
        }
    }

    /**
     * One offline input: a decoded `_BURST.craw` frame plus its unpacked
     * CFA. Mirrors [MfsrFrame] without the live [BufferedRawFrame].
     */
    data class MfsrDecodedFrame(
        val source: OfflineBurstFrame,
        val cfa: UnpackedRawCfa
    )

    /**
     * Unpacks + normalises every frame (capture order) and plans the merge
     * via [BurstSharpness] (HDR+: reference = sharpest of the newest three
     * at full resolution; blurred frames dropped). Returns the frames in
     * merge order, reference first, or null when no frame is usable.
     */
    fun prepare(frames: List<BufferedRawFrame>, metadataOf: (BufferedRawFrame) -> RawFrameMetadata): List<MfsrFrame>? {
        if (frames.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val usable = frames.mapIndexedNotNull { index, frame ->
            try {
                val metadata = metadataOf(frame)
                val norm = metadata.normalizationOrNull() ?: return@mapIndexedNotNull null
                frame.image.planes?.firstOrNull() ?: return@mapIndexedNotNull null
                MfsrFrame(frame, metadata, norm)
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "mfsr prepare: frame $index unusable", failure)
                null
            }
        }
        if (usable.size < MfsrSettings.MIN_MERGE_FRAMES) return null
        val refOrder = selectSharpest(usable) ?: return null
        return refOrder.map { usable[it] }
    }

    /**
     * HDR+ reference + blur rejection ([BurstSharpness]) over frames in
     * capture order: full-resolution green-gradient sharpness straight
     * from each RAW16 plane (black-subtracted), reference = sharpest of
     * the newest three, blurred frames dropped. Returns merge order.
     */
    private fun selectSharpest(frames: List<MfsrFrame>): List<Int>? {
        return try {
            val sharpness = frames.map { sharp ->
                val plane = sharp.frame.image.planes[0]
                val shorts = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                val rowShorts = plane.rowStride / 2
                val colShorts = plane.pixelStride / 2
                val pattern = sharp.norm.sensorPattern
                val norm = sharp.norm
                BurstSharpness.measure(
                    sharp.metadata.imageWidth, sharp.metadata.imageHeight,
                    { x, y -> pattern.colorAt(x, y) == CfaColor.GREEN }
                ) { x, y ->
                    (shorts.get(y * rowShorts + x * colShorts).toInt() and 0xffff) - norm.blackAt(x, y)
                }
            }
            val plan = BurstSharpness.plan(sharpness, MfsrSettings.MIN_MERGE_FRAMES)
            logPlan("ZSL", frames.map { it.frame.timestampNanos }, sharpness, plan)
            plan.order
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "sharp selection failed, keeping capture order", failure)
            frames.indices.toList()
        }
    }

    private fun logPlan(label: String, timestamps: List<Long>, sharpness: List<Double>, plan: BurstSharpness.Plan) {
        val newest = timestamps.maxOrNull() ?: 0L
        val ref = plan.order.first()
        Log.i(LOG_TAG, "$label MFSR plan: ref=F$ref merge=${plan.order.size}/${sharpness.size} " +
            "rejected=${plan.rejected.map { "F$it" }} | " +
            sharpness.indices.joinToString(" ") { i ->
                "F$i(${(newest - timestamps[i]) / 1_000_000}ms)=${"%.4f".format(sharpness[i])}" +
                    when (i) { ref -> "*"; in plan.rejected -> "x"; else -> "" }
            })
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
        // Single-alloc rows straight from the unpacked raster (same
        // index map as flipFloatsToRggb): the old flat-flip-then-wrap
        // held three full-frame float arrays per decode (150 MB at
        // full-res), which OOM'd the on-device merge inside the very
        // first frame decode. Values-identical by construction.
        val pixels = cfa.values
        require(pixels.size == w * h) { "sharp merge: raster size mismatch" }
        val codes = dngCodesFor(cfa.pattern)
        return Array(h) { y ->
            FloatArray(w) { x -> pixels[flipSrcIndex(y * w + x, w, h, codes)] }
        }
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

    /**
     * Flat destination index → source index for the RGGB flip (same
     * index maps as DngRead.flipToRggb; values already normalised).
     * Shared by [flipFloatsToRggb] and the single-alloc [decodeRows].
     */
    private fun flipSrcIndex(i: Int, w: Int, h: Int, cfa: IntArray): Int {
        // Identity RGGB.
        if (cfa[0] == 0 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 2) return i
        // BGGR: flip both axes. GBRG: flip horizontal. GRBG: flip vertical.
        if (cfa[0] == 2 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 0) {
            return (h - 1 - i / w) * w + (w - 1 - i % w)
        }
        if (cfa[0] == 1 && cfa[1] == 0 && cfa[2] == 2 && cfa[3] == 1) {
            return (i / w) * w + (w - 1 - i % w)
        }
        if (cfa[0] == 1 && cfa[1] == 2 && cfa[2] == 0 && cfa[3] == 1) {
            return (h - 1 - i / w) * w + (i % w)
        }
        throw IllegalArgumentException("sharp merge: unsupported CFA pattern ${cfa.toList()}")
    }

    /** Undo decodeRows' spatial permutation on interleaved RGB, in place. */
    internal fun restoreSensorCoordinates(rgb: java.nio.FloatBuffer, w: Int, h: Int, pattern: BayerPattern) {
        require(rgb.capacity() == w * h * 3)
        val codes = dngCodesFor(pattern)
        for (dst in 0 until w * h) {
            val src = flipSrcIndex(dst, w, h, codes)
            if (src <= dst) continue
            for (channel in 0..2) {
                val a = dst * 3 + channel
                val b = src * 3 + channel
                val value = rgb.get(a)
                rgb.put(a, rgb.get(b))
                rgb.put(b, value)
            }
        }
        rgb.rewind()
    }

    private fun flipFloatsToRggb(pixels: FloatArray, w: Int, h: Int, cfa: IntArray): FloatArray {
        require(pixels.size == w * h) { "sharp merge: raster size mismatch" }
        if (cfa[0] == 0 && cfa[1] == 1 && cfa[2] == 1 && cfa[3] == 2) return pixels
        return FloatArray(w * h) { i -> pixels[flipSrcIndex(i, w, h, cfa)] }
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
        val count = minOf(ordered.size, MfsrSettings.MAX_MERGE_FRAMES)
        if (count < MfsrSettings.MIN_MERGE_FRAMES) return null
        val kept = ordered.take(count)
        val w = kept[0].metadata.imageWidth
        val h = kept[0].metadata.imageHeight
        if (kept.any { it.metadata.imageWidth != w || it.metadata.imageHeight != h }) {
            Log.w(LOG_TAG, "mfsr merge: mixed frame geometries, aborting")
            return null
        }
        // Just-in-time unpack inside the engine thunks (one live CFA at
        // a time): retaining all unpacked CFAs OOMs the 512 MB heap at
        // full-res before the engine allocates. Each thunk also releases
        // its source image and (via decodeSharp) applies that frame's
        // lens-shading map; post-merge reads need no Image.
        val shadingMaps = kept.count {
            it.metadata.lensShadingMap != null && !it.metadata.lensShadingAlreadyApplied
        }
        Log.i(LOG_TAG, "sharp merge lens shading: $shadingMaps/${kept.size} frames carry an unapplied map")
        //
        // Each source image is released right after its bytes are
        // unpacked: keeping all 8 RAW16 Images open pins ~200 MB of
        // registered native allocation against the same 512 MB ART
        // ceiling the engine allocates from, which OOM'd the on-device
        // merge inside the align loop. Registry-safe: release() marks
        // camera-released (the native close defers while a borrower
        // still holds the frame) and late duplicates no-op via the
        // completed set, so the job-finally release stays safe.
        // Post-merge code reads only merged.rgb + metadata/result, and
        // the kept source burst (when enabled) is already on disk
        // before merge runs — no post-merge Image reads exist.
        val engine = runEngine(
            context, kept.map { sharp ->
                {
                    decodeSharp(sharp).also {
                        RawImageOwnership.release(sharp.frame.image)
                    }
                }
            }, w, h,
            kept[0].metadata.noiseProfile?.toDoubleArray(), settings,
            kept[0].norm.sensorPattern
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
        val count = minOf(ordered.size, MfsrSettings.MAX_MERGE_FRAMES)
        if (count < MfsrSettings.MIN_MERGE_FRAMES) return null
        val kept = ordered.take(count)
        val w = kept[0].cfa.width
        val h = kept[0].cfa.height
        val engine = runEngine(
            context, kept.map { decoded -> { decodeRows(decoded.cfa) } }, w, h,
            kept[0].source.metadata.noiseProfile?.toDoubleArray(), settings,
            kept[0].cfa.pattern
        ) ?: return null
        return MfsrResult(engine.rgb, engine.width, engine.height, count, 0,
            engine.mergeMs, 0.0, kept[0].source.metadata, null)
    }

    /**
     * Offline prepare: unpacks decoded frames and plans the merge with the
     * same [BurstSharpness] rule as the live path.
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
        val ordered = refOrder.map { unpacked[it] }
        // Lens-shading correction after selection (same model as the
        // live decodeSharp path): selection ranks uncorrected frames on
        // both paths, the merge sees corrected ones. In-place, no extra
        // allocation; identity when a take carries no map.
        for (frame in ordered) {
            LensShadingCorrector.applyOwnedInPlace(
                frame.cfa, RawPreDemosaicPipeline.lensShadingModel(frame.source.metadata))
        }
        return ordered
    }

    /** Offline twin of [selectSharpest] on unpacked (normalized) CFAs. */
    private fun selectSharpestDecoded(frames: List<MfsrDecodedFrame>): List<Int>? {
        return try {
            val inTime = frames.indices.sortedBy { frames[it].source.timestampNanos }
            val sharpness = inTime.map { i ->
                val cfa = frames[i].cfa
                BurstSharpness.measure(
                    cfa.width, cfa.height,
                    { x, y -> cfa.pattern.colorAt(x + cfa.sensorCropLeft, y + cfa.sensorCropTop) == CfaColor.GREEN }
                ) { x, y -> cfa.values[y * cfa.width + x] }
            }
            val plan = BurstSharpness.plan(sharpness, MfsrSettings.MIN_MERGE_FRAMES)
            logPlan("offline", inTime.map { frames[it].source.timestampNanos }, sharpness, plan)
            plan.order.map { inTime[it] }
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
     * Unpacks + decodes one live frame to the engine's RGGB float rows.
     * Runs inside the decode thunk (one live CFA at a time, dead on
     * return), never retained across frames.
     */
    fun decodeSharp(sharp: MfsrFrame): Array<FloatArray> {
        val plane = sharp.frame.image.planes[0]
        val layout = RawPlaneLayout(
            sharp.metadata.imageWidth, sharp.metadata.imageHeight,
            plane.rowStride, plane.pixelStride, 0, 0
        )
        val cfa = RawSensorUnpacker.unpackNormalized(plane.buffer.duplicate(), layout, sharp.norm)
        // Lens-shading correction (same model the still path applies via
        // RawPreDemosaicPipeline): live ZSL buffers are uncorrected while
        // saved DNGs carry the GainMap opcode for the reader — without
        // this the merge bakes the lens vignetting into both outputs
        // (linear DNG + RGB JPEG). In-place, no extra allocation;
        // identity when the map is missing or already applied.
        LensShadingCorrector.applyOwnedInPlace(
            cfa, RawPreDemosaicPipeline.lensShadingModel(sharp.metadata))
        return decodeRows(cfa)
    }

    /**
     * Shared SR engine: native-resolution scale-1 merge over decode
     * thunks. Called by [merge] (live) and [mergeDecoded] (offline)
     * alike; thunks decode just in time (one live frame at a time under
     * memoryTight), mirroring the CLI's decode-then-release discipline.
     */
    private fun runEngine(
        context: Context,
        decoders: List<() -> Array<FloatArray>>,
        w: Int,
        h: Int,
        noise: DoubleArray?,
        settings: MfsrSettings,
        pattern: BayerPattern
    ): EngineOut? {
        val count = decoders.size
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
        var nativeOut: ByteBuffer? = null
        try {
            val (alpha, beta) = if (noise != null && noise.size == 8) {
                DngRead.noiseAlphaBeta(noise, 8)
            } else {
                DoubleArray(4) { 1e-4 } to DoubleArray(4) { 1e-6 }
            }
            // Same recipe as the desktop CLI / crop gate (BurstConfig SNR
            // scan sizes the tiles); scale 1 keeps the source grid.
            // First thunk decodes now (reference rows); the rest decode
            // just in time inside the engine, one frame at a time.
            val first = decoders[0]()
            val snr = BurstConfig.estimateSnr(first, alpha, beta)
            val fin = BurstConfig.finalize(snr)
            val factors = listOf(1, 2, 4, 4)
            BurstConfig.sanitize(1f, fin.tileSize, factors, fin.tileSizes, h, w)
            // Robustness noise correction (Wronski 2019 Sec. 5.2.1): the
            // Monte Carlo noise LUT for this profile, simulated once per
            // sensor/ISO and cached (NoiseLutGen). Only with a real tag
            // profile; failure falls back to uncorrected robustness.
            val curves = if (noise == null || noise.size != 8) null else try {
                val t0 = SystemClock.elapsedRealtime()
                NoiseLutGen.cached(File(context.cacheDir, "noise_lut"), alpha, beta).also {
                    Log.i(LOG_TAG, "sharp merge: noise LUT ready in ${SystemClock.elapsedRealtime() - t0} ms")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "sharp merge: noise LUT failed, correction off: ${e.message}")
                null
            }
            val config = PipelineConfig(
                scale = 1f, bayer = true, factors = factors, tileSizes = fin.tileSizes,
                kernelPreprocessing = "gat",
                kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
                kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(noiseCorrection = curves != null, s3 = settings.s3),
                robustnessEnabled = true,
                sigmaSqCurve = curves?.sigmaSq ?: floatArrayOf(0f, 0f),
                dSqCurve = curves?.dSq ?: floatArrayOf(0f, 0f),
                memoryTight = true,
                icaIterations = 3,
                // KernelNet (when selected): one kernel map from the reference
                // frame, which on the device IS the full frame, so no
                // separate full-frame copy; the net runs in sensor
                // orientation via the RGGB-normalization flips.
                kernelNet = if (settings.kernel != MfsrKernel.KERNELNET) null else {
                    val (fx, fy) = KernelNet.rggbFlips(dngCodesFor(pattern))
                    KernelNetConfig(
                        weights = KernelNet.bundledWeights(),
                        sigma = KernelNet.sigma(
                            FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }),
                        flipX = fx,
                        flipY = fy
                    )
                }
            )
            Log.i(LOG_TAG, "sharp merge ${w}x$h N=$count snr=%.2f tile=%d kernel=%s s3=%s".format(snr, fin.tileSize, settings.kernel, settings.s3))
            fun asset(name: String): ByteArray =
                context.assets.open("sr_spv/$name.spv").use { it.readBytes() }
            val fft = asset("fft")
            val t0 = System.nanoTime()
            // Output in native memory (see VulkanPipeline.run): released by
            // MfsrResult.release(), or below if the engine fails.
            nativeOut = SrpBridge.nativeFloats(w * h * 3)
            val rgb = VulkanPipeline.run(
                { first },
                decoders.drop(1).map { decode ->
                    { _: ByteBuffer -> decode() }
                },
                config, handle,
                AlignGpu.Spirvs(
                    align = asset("align"), corr = asset("corr"),
                    fft = fft, ica = asset("ica"), corrDirect = asset("corr_direct"), icaWg = asset("ica_wg")
                ),
                VulkanPipeline.Spirvs(
                    gat = asset("gat"), grad = asset("grad"),
                    kernels = asset("kernels"), divide = asset("divide"),
                    robust = asset("robustness"),
                    mergeSplat = asset("merge_splat"),
                    fft = fft, corr = asset("corr"), greyFft = asset("grey_fft"),
                    kernelNet = if (settings.kernel == MfsrKernel.KERNELNET) asset("kernelnet") else null
                ),
                output = nativeOut
            )
            val mergeMs = (System.nanoTime() - t0) / 1_000_000
            VulkanPipeline.nanToBlack(rgb.asFloatBuffer(), w * h * 3)
            rgb.rewind()
            // Compute preserves the RGGB-normalized input coordinates.
            // Undo the Bayer normalization before applying the original
            // sensor metadata / EXIF orientation in either output tail.
            restoreSensorCoordinates(rgb.asFloatBuffer(), w, h, pattern)
            rgb.rewind()
            Log.i(LOG_TAG, "sharp merge done ${w}x$h N=$count in ${mergeMs}ms")
            return EngineOut(rgb, w, h, mergeMs)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "sharp merge failed", failure)
            nativeOut?.let(SrpBridge::nativeFreeDirect)
            return null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "sharp merge OOM", oom)
            nativeOut?.let(SrpBridge::nativeFreeDirect)
            return null
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }

    private const val LOG_TAG = "MfsrMerge"
}
