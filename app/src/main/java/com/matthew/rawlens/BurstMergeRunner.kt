// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline merge over a staged `_BURST.craw` take: decode (with
 * per-frame progress), merge (MFSR or HDR+), save (DNG and/or JPEG).
 *
 * Runs on the caller's thread (the Merges screen uses one background
 * thread per run). Pixel buffers are malloc-backed native allocations
 * ([HdrPlusVulkan.allocBuffer], never ART direct buffers, which are
 * charged to the Java heap and OOM past a dozen full-res frames) and
 * are freed exactly once on every path. Frame pools are even-sampled
 * to live-engine parity ([BurstMergeTakes.evenSampleIndices]) so an
 * offline merge never holds more than an immediate merge would.
 */
internal class BurstMergeRunner(private val appContext: Context) {
    enum class Method { MFSR, HDRPLUS, LKR }
    enum class Stage { DECODE, MERGE, SAVE }

    data class Progress(
        val stage: Stage,
        val done: Int,
        val total: Int,
        val elapsedMs: Long,
        /** Remaining-time estimate, or null while nothing finished. */
        val etaMs: Long?,
        val inBytes: Long,
        /** Rough output estimate; exact sizes land with the outputs. */
        val outEstimateBytes: Long?
    )

    data class Completed(
        val outputs: List<String>,
        val keptFrames: Int,
        val pooledFrames: Int,
        val mergeMs: Long,
        val width: Int,
        val height: Int
    )

    data class Inputs(
        val mfsr: MfsrSettings = MfsrSettings(),
        val hdrPlus: HdrPlusSettings = HdrPlusSettings(),
        val jpeg: JpegOutputSettings = JpegOutputSettings(),
        val denoise: DenoiseSettings = DenoiseSettings(),
        /** Shared AgX look lives in [jpeg]; this owns only the JXL container options. */
        val jxl: JxlOutputSettings = JxlOutputSettings(),
        val developedFormat: DevelopedFormat = DevelopedFormat.JPEG,
        val frameSelection: OfflineFrameSelection = OfflineFrameSelection()
    )

    fun interface Listener {
        fun onProgress(progress: Progress)
    }

    fun run(
        take: File,
        method: Method,
        format: CaptureFormat,
        inputs: Inputs = Inputs(),
        listener: Listener = Listener { }
    ): Completed {
        require(BurstCrawStore.isBurstFile(take)) { "Not a burst take: ${take.name}" }
        val t0 = SystemClock.elapsedRealtime()
        val inBytes = take.length()
        val handle = CrawReader.open(take.absolutePath)
        val nativeBufs = ArrayList<ByteBuffer>()
        try {
            val timestamps = CrawReader.frameTimestamps(handle)
                ?: throw IllegalStateException("Frame index unreadable: ${take.name}")
            require(timestamps.isNotEmpty()) { "Take holds no frames: ${take.name}" }
            val containerJson = CrawReader.containerMetadata(handle)
                ?: throw IllegalStateException("Container metadata unreadable: ${take.name}")
            val poolCap = when (method) {
                Method.MFSR -> inputs.mfsr.copy(engine = MfsrEngine.MFSR).activeFrameCount(timestamps.size)
                Method.LKR -> inputs.mfsr.copy(engine = MfsrEngine.LKR).activeFrameCount(timestamps.size)
                Method.HDRPLUS -> minOf(
                    inputs.hdrPlus.activeFrameCount(timestamps.size),
                    HdrPlusMerge.MAX_NATIVE_FRAMES
                )
            }
            val sourceIndices = inputs.frameSelection.resolve(timestamps.size, poolCap)
            val pool = sourceIndices
                .map { timestamps[it] }
            require(pool.size >= 2) { "Take holds fewer than 2 frames" }
            val frames = pool.mapIndexed { i, ts ->
                val meta = CrawReader.frameMetadata(handle, ts)
                    ?: throw IllegalStateException("Frame metadata unreadable ts=$ts")
                val json = org.json.JSONObject(meta)
                val w = json.getInt("width")
                val h = json.getInt("height")
                val buf = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2)
                    .order(ByteOrder.nativeOrder())
                nativeBufs += buf
                val need = w.toLong() * h * 2
                require(buf.remaining().toLong() >= need) { "Native pixel buffer too small" }
                val off = buf.position()
                val written = CrawReader.decodeFrame(handle, ts, buf, off, buf.remaining())
                if (written < 0) throw IllegalStateException("Decode failed ts=$ts code=$written")
                prepareDecodedPixels(buf, off, written, need)
                val metadata = OfflineBurstMetadata.synthesize(containerJson, meta, sourceIndices[i])
                val norm = OfflineBurstMetadata.normalizationFor(metadata)
                listener.onProgress(Progress(Stage.DECODE, i + 1, pool.size,
                    SystemClock.elapsedRealtime() - t0,
                    etaRemaining(SystemClock.elapsedRealtime() - t0, i + 1, pool.size),
                    inBytes, null))
                OfflineBurstFrame(buf, w, h, w * 2, ts,
                    metadata.exposureTimeNanos ?: 0L,
                    metadata.sensitivityIso ?: 0, metadata, norm)
            }
            listener.onProgress(Progress(Stage.MERGE, 0, 1,
                SystemClock.elapsedRealtime() - t0, null, inBytes, null))
            val completed = when (method) {
                Method.MFSR -> runMfsr(take, frames, format, inputs, t0, listener)
                Method.HDRPLUS -> runHdrPlus(take, frames, format, inputs, t0, listener)
                Method.LKR -> runLkr(take, frames, format, inputs, t0, listener)
            }
            listener.onProgress(Progress(Stage.SAVE, 1, 1,
                SystemClock.elapsedRealtime() - t0, 0L, inBytes,
                estimateOutBytes(completed.width, completed.height, format, method)))
            return completed
        } catch (failure: Exception) {
            Log.e("BurstMergeRunner", "Offline merge failed: method=$method output=$format " +
                "developed=${inputs.developedFormat} take=${take.name}", failure)
            throw failure
        } finally {
            nativeBufs.forEach { runCatching { HdrPlusVulkan.freeBuffer(it) } }
            runCatching { CrawReader.closeReader(handle) }
        }
    }

    private fun runMfsr(
        take: File,
        frames: List<OfflineBurstFrame>,
        format: CaptureFormat,
        inputs: Inputs,
        t0: Long,
        listener: Listener
    ): Completed {
        val captureId = take.lastModified()
        val ordered = MfsrMerge.prepareDecoded(frames)
            ?: throw IllegalStateException("No usable frames for KB-SR")
        val tMerge = SystemClock.elapsedRealtime()
        val merged = MfsrMerge.mergeDecoded(appContext, ordered, inputs.mfsr)
            ?: throw IllegalStateException("KB-SR merge failed")
        try {
            val mergeMs = SystemClock.elapsedRealtime() - tMerge
            reportSaving(listener, t0, take, merged.width, merged.height, format, Method.MFSR)
            val provenance = sharpMergeProvenance(merged, inputs.mfsr.copy(engine = MfsrEngine.MFSR), frames.size,
                ordered.take(merged.keptCount).map { it.source.metadata }, "auto (sharpness and blur rejection)")
            val outputs = ArrayList<String>()
            if (format.includesDng) {
                merged.rgb.rewind()
                // C11: striped from the native merge buffer (no 150 MB heap copy)
                outputs += LinearRgbDngSaver(appContext).saveLinearRgbStriped(
                    merged.width, merged.height, merged.rgb.asFloatBuffer(),
                    merged.refMetadata,
                    provenance,
                    captureId = captureId, gps = null
                )
            }
            if (format.includesJpeg) {
                val developer = RawDevelopmentCoordinator(appContext)
                if (inputs.developedFormat == DevelopedFormat.JXL) {
                    val developed = developer.developMfsrJxl(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, RawDevelopmentSettings(denoise = inputs.denoise),
                        look = inputs.jpeg, jxl = inputs.jxl)
                    outputs += JxlSaver(appContext).save(developed, merged.refMetadata, null,
                        captureTimeMillis = captureId,
                        typeSuffix = CaptureFileNames.TYPE_MFSR)
                } else {
                    val developed = developer.developMfsrJpeg(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, RawDevelopmentSettings(denoise = inputs.denoise), inputs.jpeg)
                    try {
                        outputs += JpegSaver(appContext).save(developed, merged.refMetadata, null,
                            captureTimeMillis = captureId,
                            typeSuffix = CaptureFileNames.TYPE_MFSR, gps = null,
                            description = LinearRgbDngWriter.provenanceBlock(provenance))
                    } finally {
                        developed.bitmap.recycle()
                    }
                }
            }
            return Completed(outputs, merged.keptCount, ordered.size, mergeMs,
                merged.width, merged.height)
        } finally {
            // Native output (MfsrMerge): free once the DNG/JPEG tail is done.
            merged.release()
        }
    }

    /**
     * LK-Reloaded (tools/lkr-pipeline): native over the decoded uint16
     * buffers, full frame at scale 1; same linear-RGB DNG / developed tail
     * as MFSR (shared [MfsrMerge.MfsrResult]).
     */
    private fun runLkr(
        take: File,
        frames: List<OfflineBurstFrame>,
        format: CaptureFormat,
        inputs: Inputs,
        t0: Long,
        listener: Listener
    ): Completed {
        val captureId = take.lastModified()
        val merged = LkrMerge.mergeDecoded(appContext, frames,
            LkrMerge.Settings(dctPrior = inputs.mfsr.lkrDctPrior, tuning = LkrTuning.overrides(appContext),
                zoom = inputs.mfsr.lkrZoom, fullRes = inputs.mfsr.lkrFullRes))
            ?: throw IllegalStateException("LK-SR merge failed")
        try {
            reportSaving(listener, t0, take, merged.width, merged.height, format, Method.LKR)
            val provenance = sharpMergeProvenance(merged, inputs.mfsr.copy(engine = MfsrEngine.LKR), frames.size,
                frames.map { it.metadata }, "first frame of sampled burst")
            val outputs = ArrayList<String>()
            if (format.includesDng) {
                merged.rgb.rewind()
                outputs += LinearRgbDngSaver(appContext).saveLinearRgbStriped(
                    merged.width, merged.height, merged.rgb.asFloatBuffer(),
                    merged.refMetadata,
                    provenance,
                    captureId = captureId, gps = null
                )
            }
            if (format.includesJpeg) {
                val developer = RawDevelopmentCoordinator(appContext)
                if (inputs.developedFormat == DevelopedFormat.JXL) {
                    val developed = developer.developMfsrJxl(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, RawDevelopmentSettings(denoise = inputs.denoise),
                        look = inputs.jpeg, jxl = inputs.jxl)
                    outputs += JxlSaver(appContext).save(developed, merged.refMetadata, null,
                        captureTimeMillis = captureId, typeSuffix = CaptureFileNames.TYPE_LKR)
                } else {
                    val developed = developer.developMfsrJpeg(merged.rgb, merged.width, merged.height,
                        merged.refMetadata, RawDevelopmentSettings(denoise = inputs.denoise), inputs.jpeg)
                    try {
                        outputs += JpegSaver(appContext).save(developed, merged.refMetadata, null,
                            captureTimeMillis = captureId, typeSuffix = CaptureFileNames.TYPE_LKR, gps = null,
                            description = LinearRgbDngWriter.provenanceBlock(provenance))
                    } finally {
                        developed.bitmap.recycle()
                    }
                }
            }
            return Completed(outputs, merged.keptCount, frames.size, merged.mergeMs,
                merged.width, merged.height)
        } finally {
            merged.release()
        }
    }

    private fun reportSaving(listener: Listener, t0: Long, take: File, width: Int, height: Int,
                             format: CaptureFormat, method: Method) {
        listener.onProgress(Progress(Stage.SAVE, 0, 1,
            SystemClock.elapsedRealtime() - t0, null, take.length(),
            estimateOutBytes(width, height, format, method)))
    }

    private fun runHdrPlus(
        take: File,
        frames: List<OfflineBurstFrame>,
        format: CaptureFormat,
        inputs: Inputs,
        t0: Long,
        listener: Listener
    ): Completed {
        val captureId = take.lastModified()
        val processor = HdrPlusMerge(appContext)
        val mergedInputs = frames.map { frame ->
            HdrPlusMerge.FrameInput(
                image = null,
                layout = RawPlaneLayout(frame.width, frame.height, frame.rowStride, 2, 0, 0),
                crop = RawCrop(0, 0, frame.width, frame.height),
                normalization = frame.normalization,
                timestampNanos = frame.timestampNanos,
                gyroRadiansPerSecond = Float.NaN,
                sensitivityIso = frame.sensitivityIso,
                pixels = frame.pixels
            )
        }
        val referenceIndex = (mergedInputs.size / 2).coerceIn(mergedInputs.indices)
        val tMerge = SystemClock.elapsedRealtime()
        val mergedRaw = processor.merge(mergedInputs, referenceIndex, inputs.hdrPlus,
            noiseSource = WaveletDenoiser.NoiseSource.of(frames[referenceIndex].metadata))
            ?: throw IllegalStateException("HDR+ merge failed")
        val mergeMs = SystemClock.elapsedRealtime() - tMerge
        val decision = processor.lastDecision
            ?.takeIf {
                it.keepIndices.size >= HdrPlusSettings.MIN_MERGE_FRAMES &&
                    it.keepIndices.all { index -> index in frames.indices } &&
                    it.refIndex in it.keepIndices.indices
            }
            ?: HdrPlusAutoDecision(
                strength = inputs.hdrPlus.strength, highQuality = inputs.hdrPlus.highQuality,
                keepIndices = frames.indices.toList(),
                refIndex = referenceIndex.coerceIn(frames.indices),
                droppedIndices = emptyList(), strengthAuto = false, pathAuto = false
            )
        val keptFrames = decision.keepIndices.map { frames[it] }
        val refMeta = keptFrames[decision.refIndex].metadata
        listener.onProgress(Progress(Stage.SAVE, 0, 1,
            SystemClock.elapsedRealtime() - t0, null, take.length(),
            estimateOutBytes(mergedRaw.width, mergedRaw.height, format, Method.HDRPLUS)))
        Log.i("BurstMergeRunner", "Offline HDR+ preprocessing ${mergedRaw.width}x${mergedRaw.height}")
        val merged = requireNotNull(
            RawPreDemosaicPipeline.process(mergedRaw, refMeta, PreDemosaicSettings()).cfa
        )
        val effective = if (inputs.denoise.aiEnabled) {
            RawDevelopmentCoordinator(appContext).denoiseCfa(merged, refMeta) ?: merged
        } else merged
        val outputs = ArrayList<String>()
        val provenance = offlineHdrPlusProvenance(frames.map { it.metadata }, decision,
            processor.lastTimings, processor.lastMotion, mergeMs)
        if (format.includesDng) {
            // Same store MainActivity's DNG toggle writes (single shared
            // preferences file): offline merges honor the user's choice.
            val dngCompression = DngCompression.fromPreference(
                appContext.getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_DNG_COMPRESSION, null)
            )
            Log.i("BurstMergeRunner", "Offline HDR+ saving DNG: compression=$dngCompression camera=${refMeta.cameraId}")
            outputs += DngSaver(appContext).saveMerged(
                effective, refMeta, captureId, gps = null,
                displayName = CaptureFileNames.hdrPlusDng(captureId),
                sixteenBit = true,
                dngCompression = dngCompression,
                hdrPlusProvenance = provenance
            )
        }
        if (format.includesJpeg) {
            Log.i("BurstMergeRunner", "Offline HDR+ developing ${inputs.developedFormat}")
            val developer = RawDevelopmentCoordinator(appContext)
            if (inputs.developedFormat == DevelopedFormat.JXL) {
                val developed = developer.developMergedJxl(effective, refMeta,
                    RawDevelopmentSettings(denoise = inputs.denoise, exposureEv = 0.0),
                    look = inputs.jpeg, jxl = inputs.jxl)
                outputs += JxlSaver(appContext).save(developed, refMeta, null,
                    captureTimeMillis = captureId,
                    typeSuffix = CaptureFileNames.TYPE_HDRPLUS)
            } else {
                val developed = developer.developMergedJpeg(effective, refMeta,
                    RawDevelopmentSettings(denoise = inputs.denoise, exposureEv = 0.0), inputs.jpeg)
                try {
                    outputs += JpegSaver(appContext).save(developed, refMeta, null,
                        captureTimeMillis = captureId,
                        typeSuffix = CaptureFileNames.TYPE_HDRPLUS, gps = null,
                        description = HdrPlusDngWriter.provenanceBlock(provenance))
                } finally {
                    developed.bitmap.recycle()
                }
            }
        }
        return Completed(outputs, keptFrames.size, frames.size, mergeMs,
            effective.width, effective.height)
    }

    companion object {
        /** Derive every source identity from the same kept reference used by the DNG writer. */
        internal fun offlineHdrPlusProvenance(
            metadata: List<RawFrameMetadata>,
            decision: HdrPlusAutoDecision,
            timings: HdrPlusMerge.Timings,
            motion: HdrPlusMotionMeter.MotionSummary?,
            mergeMs: Long
        ): HdrPlusProvenance {
            val keptFrames = decision.keepIndices.map { metadata[it] }
            val refMeta = keptFrames[decision.refIndex]
            val frameStrengths = if (decision.strengthMaps != null) null
            else decision.frameStrengths?.takeIf { it.toSet().size > 1 }
            return HdrPlusProvenance(
                mergedFrames = keptFrames.size, selectedFrames = metadata.size,
                rejectedFrames = decision.droppedIndices.size,
                highQuality = decision.highQuality,
                strength = decision.strength,
                tileSize = HdrPlusSettings.TILE_SIZE,
                searchDistance = HdrPlusSettings.SEARCH_DISTANCE,
                referenceIndex = decision.refIndex,
                referenceTimestampNs = refMeta.timestampNanos,
                referenceFrameNumber = refMeta.frameNumber,
                sourceWidth = refMeta.imageWidth, sourceHeight = refMeta.imageHeight,
                sourceCameraId = refMeta.cameraId,
                mergeMs = mergeMs,
                packMs = timings.packMs, gpuMs = timings.gpuMs, unpackMs = timings.unpackMs,
                mapsActive = decision.strengthMaps != null,
                frames = keptFrames.mapIndexed { keptPos, input ->
                    val origIndex = decision.keepIndices[keptPos]
                    val delta = motion?.pairDeltas?.getOrNull(origIndex - 1)
                        ?.takeIf { it.isFinite() }?.toFloat()
                    HdrPlusFrameInfo(
                        input.exposureTimeNanos, input.sensitivityIso,
                        deltaVsPrev = delta,
                        gyroRadiansPerSecond = null,
                        mergeStrength = frameStrengths?.getOrNull(keptPos)
                    )
                }
            )
        }

        /** JNI writes at [offset] without moving the Java cursor. Publish only complete pixels. */
        internal fun prepareDecodedPixels(buffer: ByteBuffer, offset: Int, written: Int, expected: Long) {
            require(expected > 0 && written.toLong() == expected) {
                "Decoded frame is incomplete ($written/$expected bytes)"
            }
            require(offset >= 0 && offset.toLong() + written <= buffer.capacity().toLong()) {
                "Decoded frame exceeds its pixel buffer"
            }
            buffer.limit(offset + written)
            buffer.position(offset)
        }

        /** Remaining-time estimate from linear progress, or null before the first unit. */
        fun etaRemaining(elapsedMs: Long, done: Int, total: Int): Long? {
            if (done <= 0 || total <= 0 || done > total) return null
            if (done >= total) return 0L
            return maxOf(0L, elapsedMs * total / done - elapsedMs)
        }

        /**
         * Rough merged-output estimate for the progress line (exact sizes
         * land with the outputs): linear DNG ≈ 6 bytes/px, mosaic DNG ≈
         * 2 bytes/px, JPEG ≈ 1 byte/px.
         */
        fun estimateOutBytes(width: Int, height: Int, format: CaptureFormat, method: Method): Long {
            var estimate = 0L
            if (format.includesDng) {
                estimate += width.toLong() * height *
                    if (method == Method.HDRPLUS) 2 else 6
            }
            if (format.includesJpeg) estimate += width.toLong() * height
            return estimate
        }
    }
}
