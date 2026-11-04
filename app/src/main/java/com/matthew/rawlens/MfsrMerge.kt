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
import com.rawlens.srpipeline.stages.HotPixels
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelNetConfig
import com.rawlens.srpipeline.stages.KernelParams
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
 * Noise model (engineNoiseAlphaBeta / withLearnedGreen): the learned
 * per-device model (switch: MfsrSettings.learnedNoise) or the Camera2
 * profile mapped from CFA order; it drives GAT, SNR -> tile/kernel params,
 * the in-app NoiseLutGen robustness LUT and the KernelNet sigma.
 * Before the merge: static hot/dead pixels (HotPixels, C18). In the merge:
 * colour-difference accumulation (Z1, MfsrSettings.zipperFix). After it:
 * lens shading on the merged RGB (C19).
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
        val mergeMs: Long,
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
        // index map as DngRead.flipToRggb): the old flat-flip-then-wrap
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
     * Shared by [decodeRows], [restoreSensorCoordinates], the noise mapping and the hot-pixel verifiers.
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

    /**
     * Noise model (S, O) per engine plane, in the engine's R, G1, B, G2 order
     * (RGGB-normalised raster from [decodeRows]: R (0,0), G1 (0,1), B (1,1),
     * G2 (1,0); the order BurstConfig / GAT / NoiseLutGen / KernelNet expect).
     *
     * Camera2 SENSOR_NOISE_PROFILE lists one (S, O) pair per CFA channel "in
     * the same order as channels listed for the CFA layout key"
     * (AOSP metadata_definitions.xml, android.sensor.noiseProfile), i.e.
     * row-major over the sensor's 2x2 cell. Each engine phase is mapped back to
     * its sensor phase with the same [flipSrcIndex] map [decodeRows] uses
     * (even dims keep parity), so pixels and noise can never disagree.
     * Previously the 8 values were read as R,G1,B,G2 directly: on a GRBG
     * sensor that swapped red and green-1 (R got G's slope, ~half).
     *
     * 6 values: 3 planes R, G, B (DNG CFAPlaneColor order, e.g. a
     * DngCreator NoiseProfile or a user override) -> R, G, B, G.
     * 2 values: one plane for all. Anything else: null.
     */
    internal fun engineNoiseAlphaBeta(noise: DoubleArray?, pattern: BayerPattern): Pair<DoubleArray, DoubleArray>? {
        if (noise == null) return null
        val sensorCell: IntArray = when (noise.size) {
            8 -> {
                val codes = dngCodesFor(pattern)
                // engine cells in R, G1, B, G2 order -> 2x2 row-major index -> sensor cell
                val cells = intArrayOf(0, 1, 3, 2).map { flipSrcIndex(it, 2, 2, codes) }.toIntArray()
                check(pattern.cellOrdinals[cells[0]] == CfaColor.RED.ordinal &&
                    pattern.cellOrdinals[cells[2]] == CfaColor.BLUE.ordinal) {
                    "noise mapping: engine R/B do not land on sensor R/B for $pattern"
                }
                cells
            }
            6 -> intArrayOf(0, 1, 2, 1)
            2 -> intArrayOf(0, 0, 0, 0)
            else -> return null
        }
        if (noise.any { !it.isFinite() } || (0 until noise.size / 2).any { noise[2 * it] <= 0.0 }) return null
        return DoubleArray(4) { noise[2 * sensorCell[it]] } to DoubleArray(4) { noise[2 * sensorCell[it] + 1] }
    }

    /**
     * Learned per-device model (green a, b at this ISO; [learned] = predictNoise
     * output) as the level, the Camera2 profile ([profile], engine R,G1,B,G2) as
     * the per-channel shape: each plane keeps its profile ratio to the mean green.
     * The model is learned from green sites only (noise_estimate.h), so it
     * cannot say R/B on its own. No profile: green values for all planes.
     * Null when there is no learned model.
     */
    internal fun withLearnedGreen(
        profile: Pair<DoubleArray, DoubleArray>?, learned: FloatArray?
    ): Pair<DoubleArray, DoubleArray>? {
        if (learned == null || learned.size < 2 || !(learned[0] > 0f) || !(learned[1] >= 0f)) return null
        val aG = learned[0].toDouble(); val bG = learned[1].toDouble()
        if (profile == null) return DoubleArray(4) { aG } to DoubleArray(4) { bG }
        val (pa, pb) = profile
        val paG = (pa[1] + pa[3]) / 2; val pbG = (pb[1] + pb[3]) / 2
        val alpha = DoubleArray(4) { if (paG > 0) aG * pa[it] / paG else aG }
        val beta = DoubleArray(4) { if (pbG > 0) bG * pb[it] / pbG else bG }
        return alpha to beta
    }

    /**
     * Reads one live frame's raw plane at engine-rows coordinates (same flip
     * map as [decodeRows]), black-subtracted and normalised. Point reads only,
     * for HotPixels.confirm before the frame is decoded (no extra full decode).
     */
    private fun liveVerifier(sharp: MfsrFrame, w: Int, h: Int): (Int, Int) -> Float {
        val plane = sharp.frame.image.planes[0]
        val shorts = plane.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val rowShorts = plane.rowStride / 2
        val colShorts = plane.pixelStride / 2
        val norm = sharp.norm
        val codes = dngCodesFor(norm.sensorPattern)
        return { y, x ->
            val src = flipSrcIndex(y * w + x, w, h, codes)
            val sx = src % w; val sy = src / w
            val black = norm.blackAt(sx, sy)
            ((shorts.get(sy * rowShorts + sx * colShorts).toInt() and 0xffff) - black) / (norm.whiteLevel - black)
        }
    }

    /**
     * White-balance gains (1/neutral R, 1/neutral B, relative to G) for the Z1
     * colour-difference merge, or (1, 1) when the frame has no usable neutral
     * (the fix still removes the zipper; neutral just is not exactly 0).
     */
    internal fun zipperWb(neutral: DoubleArray?): FloatArray {
        if (neutral == null || neutral.size < 3 || neutral.any { !(it > 0.0) || !it.isFinite() }) return floatArrayOf(1f, 1f)
        return floatArrayOf((neutral[1] / neutral[0]).toFloat(), (neutral[1] / neutral[2]).toFloat())
    }

    /**
     * C19: lens shading on the merged RGB (sensor coordinates, interleaved,
     * top-down): R x gain(R), G x mean(gain(G even), gain(G odd)), B x
     * gain(B), same LensShadingModel.gainAt the still path uses. Identity
     * when the map is already applied.
     */
    internal fun applyShadingAfterMerge(rgb: java.nio.FloatBuffer, w: Int, h: Int, model: LensShadingModel) {
        if (model.alreadyApplied) return
        ShardWorkers.forEachShard(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = 3 * (y * w + x)
                val gG = 0.5f * (model.gainAt(x, y, CfaColor.GREEN, 0) + model.gainAt(x, y, CfaColor.GREEN, 1))
                rgb.put(i, rgb.get(i) * model.gainAt(x, y, CfaColor.RED))
                rgb.put(i + 1, rgb.get(i + 1) * gG)
                rgb.put(i + 2, rgb.get(i + 2) * model.gainAt(x, y, CfaColor.BLUE))
            }
        }
    }

    /**
     * Lens shading for a merge of a sensor sub-region (LK-SR zoom): output
     * pixel (x, y) sits at sensor (x0 + (x + 0.5) * sx, y0 + (y + 0.5) * sy).
     */
    internal fun applyShadingMapped(
        rgb: java.nio.FloatBuffer, w: Int, h: Int, model: LensShadingModel,
        x0: Int, y0: Int, sx: Float, sy: Float
    ) {
        if (model.alreadyApplied) return
        ShardWorkers.forEachShard(h) { ya, yb ->
            for (y in ya until yb) {
                val syy = (y0 + (y + 0.5f) * sy).toInt()
                for (x in 0 until w) {
                    val sxx = (x0 + (x + 0.5f) * sx).toInt()
                    val i = 3 * (y * w + x)
                    val gG = 0.5f * (model.gainAt(sxx, syy, CfaColor.GREEN, 0) + model.gainAt(sxx, syy, CfaColor.GREEN, 1))
                    rgb.put(i, rgb.get(i) * model.gainAt(sxx, syy, CfaColor.RED))
                    rgb.put(i + 1, rgb.get(i + 1) * gG)
                    rgb.put(i + 2, rgb.get(i + 2) * model.gainAt(sxx, syy, CfaColor.BLUE))
                }
            }
        }
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
            kept[0].norm.sensorPattern, WaveletDenoiser.NoiseSource.of(kept[0].metadata),
            kept[0].metadata.neutralColorPoint?.toDoubleArray(),
            RawPreDemosaicPipeline.lensShadingModel(kept[0].metadata),
            kept.drop(1).take(2).map { liveVerifier(it, w, h) }
        ) ?: return null
        return MfsrResult(engine.rgb, engine.width, engine.height, count,
            engine.mergeMs, kept[0].metadata, kept[0].frame.result)
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
            kept[0].cfa.pattern, WaveletDenoiser.NoiseSource.of(kept[0].source.metadata),
            kept[0].source.metadata.neutralColorPoint?.toDoubleArray(),
            RawPreDemosaicPipeline.lensShadingModel(kept[0].source.metadata),
            kept.drop(1).take(2).map { decoded ->
                val codes = dngCodesFor(decoded.cfa.pattern)
                val v = decoded.cfa.values
                val f: (Int, Int) -> Float = { y, x -> v[flipSrcIndex(y * w + x, w, h, codes)] }
                f
            }
        ) ?: return null
        return MfsrResult(engine.rgb, engine.width, engine.height, count,
            engine.mergeMs, kept[0].source.metadata, null)
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
        // C19: no lens shading here any more; it is applied to the merged
        // RGB (applyShadingAfterMerge), so the merge sees raw sensor noise.
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
        // C19: lens shading is NOT applied per frame any more. Gains up to
        // 4x+ in the corners scaled noise variance by gain^2 while GAT, SNR
        // and the robustness LUT assumed raw sensor noise (corner frames got
        // rejected as "motion"). HDR+ (Hasinoff et al. 2016) merges raw and
        // shades in finishing; applyShadingAfterMerge does the same on the
        // merged RGB (the vignetting is still removed from both outputs).
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
        pattern: BayerPattern,
        noiseSource: WaveletDenoiser.NoiseSource?,
        /** Reference AsShotNeutral (R, G, B) for the Z1 colour-difference merge. */
        neutral: DoubleArray?,
        /** Reference lens-shading model, applied to the merged RGB (C19). */
        shading: LensShadingModel?,
        /** Value accessors (engine rows coordinates, normalised) of two more frames, for HotPixels.confirm. */
        verifiers: List<(Int, Int) -> Float> = emptyList()
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
            val profile = engineNoiseAlphaBeta(noise, pattern)
            val learned = if (!settings.learnedNoise || noiseSource == null) null else
                WaveletDenoiser.storePath(WaveletDenoiser.noiseDir(context), noiseSource)
                    ?.let { WaveletVulkan.predictNoise(it, noiseSource.iso.toFloat()) }
            val (alpha, beta) = withLearnedGreen(profile, learned)
                ?: profile
                ?: (DoubleArray(4) { 1e-4 } to DoubleArray(4) { 1e-6 }).also {
                    Log.w(LOG_TAG, "sharp merge: no usable noise profile (size ${noise?.size}), using constant fallback")
                }
            Log.i(LOG_TAG, "sharp merge noise: source=%s alpha=%s beta=%s".format(
                when {
                    learned != null -> "learned(samples=${learned[2].toInt()} isos=${learned[3].toInt()})"
                    profile != null -> if (settings.learnedNoise) "profile (model untrained)" else "profile (learned off)"
                    else -> "fallback"
                },
                alpha.joinToString(",") { "%.3g".format(it) }, beta.joinToString(",") { "%.3g".format(it) }))
            // Same recipe as the desktop CLI / crop gate (BurstConfig SNR
            // scan sizes the tiles); scale 1 keeps the source grid.
            // First thunk decodes now (reference rows); the rest decode
            // just in time inside the engine, one frame at a time.
            val first = decoders[0]()
            // C18: static hot/dead pixels (HotPixels: darktable hotpixels rule,
            // confirmed at the same position in two more frames so moving
            // point lights survive), replaced in every frame before merging.
            val defects = if (verifiers.size < 2) IntArray(0) else {
                val t0 = SystemClock.elapsedRealtime()
                HotPixels.confirm(HotPixels.candidates(first), h, w, verifiers.take(2)).also {
                    Log.i(LOG_TAG, "sharp merge defects: ${it.size} confirmed in ${SystemClock.elapsedRealtime() - t0} ms")
                }
            }
            HotPixels.correct(first, defects)
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
                // C26: the SNR-tuned Wronski kernel params (update_snr_config) were
                // computed above but never passed, so the app ran KernelParams()
                // defaults (kDetail 1.0 vs 0.25-0.33); kMin 0.64 = the checker fix.
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(),
                    fin.dTh.toFloat(), fin.dTr.toFloat(), kMin = 0.64f),
                kernelPreprocessing = "gat",
                kernelAlpha = FloatArray(4) { alpha[it].toFloat() },
                kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(noiseCorrection = curves != null, s3 = settings.s3),
                robustnessEnabled = true,
                sigmaSqCurve = curves?.sigmaSq ?: floatArrayOf(0f, 0f),
                dSqCurve = curves?.dSq ?: floatArrayOf(0f, 0f),
                memoryTight = true,
                colourDiff = if (!settings.zipperFix) null else zipperWb(neutral),
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
            Log.i(LOG_TAG, "sharp merge ${w}x$h N=$count snr=%.2f tile=%d kernel=%s s3=%s zipperFix=%s".format(snr, fin.tileSize, settings.kernel, settings.s3, settings.zipperFix))
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
                    { _: ByteBuffer -> decode().also { HotPixels.correct(it, defects) } }
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
            shading?.let { model ->
                val t0 = SystemClock.elapsedRealtime()
                applyShadingAfterMerge(rgb.asFloatBuffer(), w, h, model)
                rgb.rewind()
                Log.i(LOG_TAG, "sharp merge lens shading after merge in ${SystemClock.elapsedRealtime() - t0} ms")
            }
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
