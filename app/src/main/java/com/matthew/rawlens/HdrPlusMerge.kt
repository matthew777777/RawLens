// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HDR+ merge bridge: ZSL [Image] frames in, merged [UnpackedRawCfa] out
 * over the HDR+ Vulkan pipeline ([HdrPlusVulkan]). Returns the merged CFA (same dims/pattern
 * crop as the inputs) or null when the merge is unavailable — failure,
 * OOM, and unsupported geometry all fall back to the caller's error
 * path. Never throws for HDR+ reasons; callers map null to HDR+ ERROR.
 *
 * Frames upload as raw uint16 codes (never normalized on the host), so
 * the GPU sees bit-identical input to the upstream ring views; per-frame
 * black/white travel alongside for the in-shader normalization. Packed
 * frames and the merge output live in malloc-backed native buffers
 * ([HdrPlusVulkan.allocBuffer]) freed per merge — ART direct buffers
 * are charged to the Java heap and OOM past ~12 frames at 12MP. The
 * Vulkan handle is process-lifetime (pipeline creation is too slow per
 * capture) and [merge] is synchronized: one in-flight run at a time.
 */
class HdrPlusMerge(context: Context) {
    private val assets = context.applicationContext.assets
    private val appContext = context.applicationContext

    @Volatile private var handle = 0L

    private fun initVulkan(): Long {
        val cache = VulkanPipelineCache.file(appContext.cacheDir, VulkanPipelineCache.HOST_HDRPLUS)
        return HdrPlusVulkan.init(assets, cache)
    }

    /** Per-run cost of the last [merge] call (any thread). */
    data class Timings(val packMs: Long, val gpuMs: Double, val unpackMs: Long)
    @Volatile var lastTimings = Timings(0, 0.0, 0)
        private set
    /** Motion metering of the last [merge] call (any thread). */
    @Volatile var lastMotion: HdrPlusMotionMeter.MotionSummary? = null
        private set
    /** Auto decision of the last [merge] call (any thread). */
    @Volatile var lastDecision: HdrPlusAutoDecision? = null
        private set

    /** One owned input frame with its frozen unpack geometry. */
    data class FrameInput(
        /**
         * Live gralloc frame; null offline, where [pixels] carries the
         * decoded uint16 Bayer plane instead. Exactly one must be set.
         */
        val image: Image?,
        val layout: RawPlaneLayout,
        val crop: RawCrop,
        val normalization: RawNormalization,
        /** Sensor capture timestamp (ns, boot clock); 0 = unknown. */
        val timestampNanos: Long = 0L,
        /** Gyro ego-motion (rad/s) during this frame; NaN = unreported. */
        val gyroRadiansPerSecond: Float = Float.NaN,
        /** Sensor sensitivity for the darkness term; 0 = unknown. */
        val sensitivityIso: Int = 0,
        /** Decoded offline pixels (row-major uint16, native order). */
        val pixels: ByteBuffer? = null
    ) {
        init {
            require((image != null) != (pixels != null)) {
                "HDR+ FrameInput needs exactly one of image or pixels"
            }
        }
    }

    /**
     * Creates the Vulkan device + loads all SPIR-V modules on a background
     * thread so the first capture's [merge] does not pay that cost.
     * Safe to call repeatedly; the handle is created once.
     */
    fun prewarm() {
        if (handle != 0L) return
        Thread {
            try {
                synchronized(this) {
                    if (handle == 0L) handle = initVulkan()
                }
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "HDR+ prewarm failed, first capture will init lazily", failure)
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun merge(
        frames: List<FrameInput>,
        refIndex: Int,
        settings: HdrPlusSettings,
        tileSize: Int = HdrPlusSettings.TILE_SIZE,
        searchDistance: Int = HdrPlusSettings.SEARCH_DISTANCE,
        alignOnce: Boolean = true,
        /** Reference frame's key into the learned per-device noise model; null = burst estimate only. */
        noiseSource: WaveletDenoiser.NoiseSource? = null,
        /** Internal: true when [memoryPlan] already sized this call. */
        budgetChecked: Boolean = false
    ): UnpackedRawCfa? {
        try {
            require(frames.size in 2..MAX_NATIVE_FRAMES) {
                "HDR+ needs 2..$MAX_NATIVE_FRAMES frames, got ${frames.size}"
            }
            require(refIndex in frames.indices) { "HDR+ refIndex $refIndex out of range" }
            val first = frames.first()
            val width = first.crop.width
            val height = first.crop.height
            require(width >= 16 && height >= 16 && width % 2 == 0 && height % 2 == 0) {
                "HDR+ needs an even crop of at least 16x16"
            }
            frames.forEachIndexed { index, frame ->
                require(frame.crop.width == width && frame.crop.height == height) {
                    "HDR+ frame $index crop ${frame.crop.width}x${frame.crop.height} != ${width}x$height"
                }
            }
            if (handle == 0L) handle = initVulkan()
            if (HdrPlusVulkan.loadedShaderCount(handle) != HdrPlusVulkan.expectedShaderCount()) {
                Log.w(LOG_TAG, "HDR+ shader modules incomplete, aborting merge")
                return null
            }
            if (!budgetChecked) {
                val plan = memoryPlan(width, height, frames.size, refIndex, settings)
                    ?: return null
                if (plan.window.first != 0 || plan.window.last != frames.lastIndex || plan.forceFast) {
                    return mergeWithinBudget(plan, frames, refIndex, settings, tileSize,
                        searchDistance, alignOnce, noiseSource)
                }
            }
            val nativeBefore = MemoryLeakDiagnostics.nativeBufferOutstandingBytes()
            MemoryLeakDiagnostics.sample(
                "hdrplus-merge-start",
                extra = "N=${frames.size} ref=$refIndex ${width}x$height"
            )
            var t = System.nanoTime()
            // Packed frames live in malloc-backed native buffers (never ART
            // direct buffers: those are charged to the 512MB Java heap and
            // OOM past ~12 frames at 12MP). The finally below frees every
            // packed frame even when a later pack/meter/merge step throws.
            val packed = ArrayList<ByteBuffer>(frames.size)
            var keptCount = 0
            val result = try {
                frames.forEachIndexed { index, frame -> packed += packFrame(frame, index) }
                val blacks = FloatArray(frames.size * 4)
                val whites = FloatArray(frames.size)
                frames.forEachIndexed { index, frame ->
                    rotatedBlacks(frame, blacks, index * 4)
                    whites[index] = frame.normalization.whiteLevel
                }
                // Meter first: the auto brain picks strength/path/frames/ref
                // from read-only views of the packed buffers. Scores travel in
                // locals, so the decision can never observe a stale volatile.
                val metering = meterMotion(frames, packed, blacks, whites, width, height,
                    settings.autoStrength, refIndex, learnedPrior(noiseSource))
                val decision = HdrPlusAutoSelect.select(
                    metering = metering,
                    frameCount = frames.size,
                    sensitivityIso = frames[refIndex].sensitivityIso,
                    manualStrength = settings.strength.takeIf { !settings.autoStrength },
                    manualQuality = settings.highQuality.takeIf { !settings.autoQuality },
                    preferredRef = refIndex
                )
                val keptPacked = decision.keepIndices.map { packed[it] }.toTypedArray()
                val keptBlacks = FloatArray(decision.keepIndices.size * 4)
                val keptWhites = FloatArray(decision.keepIndices.size)
                decision.keepIndices.forEachIndexed { keptPos, srcPos ->
                    blacks.copyInto(keptBlacks, keptPos * 4, srcPos * 4, srcPos * 4 + 4)
                    keptWhites[keptPos] = whites[srcPos]
                }
                val output = HdrPlusVulkan.allocBuffer(width.toLong() * height * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                val packedAt: Long
                val gpuMs: Double
                val values: FloatArray
                try {
                    packedAt = System.nanoTime()
                    val frameStrengths = decision.frameStrengths?.toFloatArray()
                    require(frameStrengths == null || frameStrengths.size == keptPacked.size) {
                        "HDR+ frame strengths ${frameStrengths?.size} != kept ${keptPacked.size}"
                    }
                    val maps = decision.strengthMaps
                    val flatMaps = maps?.let { list ->
                        require(list.size == keptPacked.size) {
                            "HDR+ strength maps ${list.size} != kept ${keptPacked.size}"
                        }
                        val cells = metering.mapCellsX * metering.mapCellsY
                        require(cells > 0 && list.all { it.size == cells }) {
                            "HDR+ strength maps must each hold ${metering.mapCellsX}x${metering.mapCellsY}"
                        }
                        FloatArray(list.size * cells) { i -> list[i / cells][i % cells] }
                    }
                    gpuMs = HdrPlusVulkan.merge(
                        handle, keptPacked, keptBlacks, keptWhites, decision.refIndex,
                        IntArray(0), decision.strength, frameStrengths, flatMaps, tileSize, searchDistance,
                        decision.highQuality, alignOnce, width, height, output
                    )
                    val inferredAt = System.nanoTime()
                    values = FloatArray(width * height)
                    output.asFloatBuffer().get(values)
                    lastTimings = Timings(
                        (packedAt - t) / 1_000_000, gpuMs, (System.nanoTime() - inferredAt) / 1_000_000
                    )
                } finally {
                    runCatching { HdrPlusVulkan.freeBuffer(output) }
                }
                val sensorCropX = first.layout.sensorOriginX + first.crop.left
                val sensorCropY = first.layout.sensorOriginY + first.crop.top
                lastMotion = metering
                lastDecision = decision
                Log.i(LOG_TAG, "HDR+ merge ${width}x$height N=${keptPacked.size}/${frames.size} " +
                    "ref=${decision.refIndex} " +
                    "gpu=${"%.1f".format(gpuMs)}ms strength=${decision.strength}" +
                    (if (decision.highQuality) " HQ" else " fast"))
                Log.i(LOG_TAG, metering.logLine(frames.size))
                Log.i(LOG_TAG, decision.logLine(frames.size))
                keptCount = keptPacked.size
                UnpackedRawCfa(
                    width, height,
                    first.normalization.sensorPattern.shifted(sensorCropX, sensorCropY),
                    values, first.crop, sensorCropX, sensorCropY
                )
            } finally {
                packed.forEach { runCatching { HdrPlusVulkan.freeBuffer(it) } }
            }
            // Packed frames and the merge output are freed by the finally
            // above, so the balance check and done-sample below observe the
            // true post-merge state (sampling before it false-alarms every
            // merge, logcat 2026-10-12).
            MemoryLeakDiagnostics.hdrMerge()
            checkNativeBalance(nativeBefore, "merge")
            MemoryLeakDiagnostics.sample(
                "hdrplus-merge-done",
                extra = "N=$keptCount/${frames.size} ${width}x$height"
            )
            return result
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "HDR+ merge failed", failure)
            MemoryLeakDiagnostics.sample("hdrplus-merge-failed", extra = failure.javaClass.simpleName)
            return null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "HDR+ merge OOM", oom)
            MemoryLeakDiagnostics.sample("hdrplus-merge-failed", extra = "OOM")
            return null
        }
    }

    /**
     * Packed/output native buffers are freed by the merge finally-blocks, so the process-wide
     * outstanding total must be unchanged afterwards (merges are single-flight). Any delta is
     * a per-merge native leak, surfaced here instead of hiding inside the PSS trend.
     */
    private fun checkNativeBalance(before: Long, where: String) {
        val after = MemoryLeakDiagnostics.nativeBufferOutstandingBytes()
        if (after != before) {
            Log.w(LOG_TAG, "HDR+ native-buffer imbalance after $where: " +
                "before=${before / 1024 / 1024}MB after=${after / 1024 / 1024}MB")
        }
    }

    /**
     * Read-only metering over the packed buffers: per-pair difference +
     * local-motion scores plus per-frame brightness/texture, all bounded
     * (~48k samples per pair at 12 MP). Runs before the native call so
     * the auto brain can decide strength/path/frames/ref.
     */
    private fun meterMotion(
        frames: List<FrameInput>,
        packed: List<ByteBuffer>,
        blacks: FloatArray,
        whites: FloatArray,
        width: Int,
        height: Int,
        measureNoise: Boolean,
        refIndex: Int,
        prior: HdrPlusNoisePrior?
    ): HdrPlusMotionMeter.MotionSummary {
        val ranges = DoubleArray(frames.size) { i ->
            val blackMean = (blacks[i * 4] + blacks[i * 4 + 1] +
                blacks[i * 4 + 2] + blacks[i * 4 + 3]) / 4.0
            whites[i].toDouble() - blackMean
        }
        val stats = frames.indices.map { i ->
            HdrPlusMotionMeter.frameStats(
                packed[i], width, height, normalizationRange = ranges[i]
            )
        }
        val deltas = ArrayList<Double>(frames.size - 1)
        val hots = ArrayList<Double>(frames.size - 1)
        val prevTs = ArrayList<Long>(frames.size - 1)
        val currTs = ArrayList<Long>(frames.size - 1)
        val gyros = ArrayList<Double>(frames.size - 1)
        val ratios = ArrayList<DoubleArray>(frames.size - 1)
        val madDense = ArrayList<DoubleArray>(frames.size - 1)
        val texDense = ArrayList<DoubleArray>(frames.size - 1)
        // One native call per pair replaces the two Kotlin map passes
        // (bitwise identical, host-gated) and adds the dense maps; the
        // scratch is reused across pairs (overwritten every call).
        val nativeScratch = HdrPlusMeterNative.Scratch(width, height)
            .takeIf { HdrPlusMeterNative.available }
        if (nativeScratch == null) {
            Log.w(LOG_TAG, "HDR+ native meter unavailable, Kotlin fallback (strength maps off)")
        }
        for (i in 1 until frames.size) {
            deltas += HdrPlusMotionMeter.meanAbsDiffNormalized(
                packed[i - 1], packed[i], width * height,
                normalizationRange = ranges[i]
            )
            val native = nativeScratch?.let {
                HdrPlusMeterNative.meterPair(
                    packed[i - 1], packed[i], width, height, ranges[i],
                    HdrPlusAutoTuning.HOT_RATIO, it
                )
            }
            if (native != null) {
                hots += native.hot
                ratios += native.ratio
                madDense += native.madDense
                texDense += native.texDense
            } else {
                hots += HdrPlusMotionMeter.blockHotFraction(
                    packed[i - 1], packed[i], width, height,
                    normalizationRange = ranges[i],
                    hotRatio = HdrPlusAutoTuning.HOT_RATIO
                )
                HdrPlusMotionMeter.blockMismatchRatios(
                    packed[i - 1], packed[i], width, height,
                    normalizationRange = ranges[i]
                )?.let { ratios += it }
            }
            prevTs += frames[i - 1].timestampNanos
            currTs += frames[i].timestampNanos
            val gyro = frames[i].gyroRadiansPerSecond
            gyros += if (gyro.isFinite()) gyro.toDouble() else Double.NaN
        }
        // Ratio grids ride along only when every pair measured one (else
        // the map builder would mix resolutions); the brain treats a
        // partial set as missing and falls back to frame strengths.
        // Dense grids follow the same all-or-nothing discipline (the
        // danger term stays off unless every pair measured dense).
        val fullRatios = ratios.takeIf { it.size == frames.size - 1 } ?: emptyList()
        val fullMadDense = madDense.takeIf { it.size == frames.size - 1 } ?: emptyList()
        val fullTexDense = texDense.takeIf { it.size == frames.size - 1 } ?: emptyList()
        val (mapX, mapY) = if (fullRatios.isEmpty()) (0 to 0)
        else HdrPlusMotionMeter.mapCells(width, height)
        // Noise: one model for the burst (blind estimate over every frame,
        // refined by the learned per-device prior), then the noise each
        // frame is expected to show on the meter's own sample grids.
        fun black(i: Int) = FloatArray(4) { blacks[i * 4 + it] }
        val noise = if (!measureNoise) null else HdrPlusSceneNoise.fit(
            frames.indices.map { i -> HdrPlusSceneNoise.sample(packed[i], width, height, black(i), whites[i]) },
            prior
        )
        val sigmas = if (noise == null) emptyList() else frames.indices.map { i ->
            HdrPlusSceneNoise.frameSigma(packed[i], width, height, black(i), ranges[i], noise)
        }
        val cellSigmas = if (noise == null || fullMadDense.isEmpty()) emptyList()
        else frames.indices.mapNotNull { i ->
            HdrPlusSceneNoise.cellSigma(packed[i], width, height, black(i), ranges[i], noise)
        }.takeIf { it.size == frames.size } ?: emptyList()
        if (noise != null) Log.i(LOG_TAG, String.format(java.util.Locale.US,
            "HDR+ noise: a=%.3g b=%.3g bins=%d learnedPrior=%s", noise.a, noise.b, noise.bins,
            if (prior == null) "none" else "offered"))
        return HdrPlusMotionMeter.summarize(
            deltas, prevTs, currTs, gyros, hots,
            stats.map { it.meanLevel }, stats.map { it.sharpness },
            fullRatios, mapX, mapY, fullMadDense, fullTexDense,
            noise, ranges[refIndex], sigmas, cellSigmas,
            if (noise == null) Double.NaN else HdrPlusSceneNoise.spatialNoiseReference(
                packed[refIndex], width, height, ranges[refIndex])
        )
    }

    /**
     * The learned per-device noise model (shared store with the wavelet
     * stage and MFSR) as prior for the burst's own estimate. Null when the
     * source is unknown; the prior itself declines when untrained for the ISO.
     */
    private fun learnedPrior(source: WaveletDenoiser.NoiseSource?): HdrPlusNoisePrior? {
        val path = source?.let { WaveletDenoiser.storePath(WaveletDenoiser.noiseDir(appContext), it) }
            ?: return null
        return HdrPlusNoisePrior { a, b, bins ->
            WaveletVulkan.combineNoise(path, source.iso.toFloat(), a.toFloat(), b.toFloat(), bins)
                ?.let { it[0].toDouble() to it[1].toDouble() }
        }
    }

    /**
     * Tight-packs the frame's crop as uint16 codes (native order) into a
     * malloc-backed native buffer ([HdrPlusVulkan.allocBuffer], freed by
     * the caller — never an ART direct buffer, which is charged to the
     * Java heap). Row strides are honored; no normalization is applied
     * (black/white travel separately, exactly like the upstream ring views).
     */
    private fun packFrame(frame: FrameInput, index: Int): ByteBuffer {
        val planeBuffer = frame.pixels
            ?: frame.image?.planes?.single()?.buffer
            ?: throw IllegalStateException("HDR+ frame $index has no pixels")
        val input = planeBuffer.duplicate().order(ByteOrder.nativeOrder())
        val dataOrigin = input.position()
        val layout = frame.layout
        val crop = frame.crop
        val lastIndex = dataOrigin +
            (crop.top + crop.height - 1).toLong() * layout.rowStride +
            (crop.left + crop.width - 1).toLong() * layout.pixelStride
        require(lastIndex + U16_BYTES <= input.limit().toLong()) {
            "HDR+ frame $index buffer is truncated for its stride and crop"
        }
        val out = HdrPlusVulkan.allocBuffer(crop.width.toLong() * crop.height * U16_BYTES)
            .order(ByteOrder.nativeOrder())
        if (layout.pixelStride == U16_BYTES.toInt()) {
            val rowBytes = crop.width * U16_BYTES.toInt()
            val firstByte = dataOrigin + crop.top * layout.rowStride +
                crop.left * layout.pixelStride
            if (layout.rowStride == rowBytes) {
                input.limit(firstByte + crop.height * rowBytes)
                input.position(firstByte)
                out.put(input)
            } else {
                for (y in 0 until crop.height) {
                    val rowStart = firstByte + y * layout.rowStride
                    input.limit(rowStart + rowBytes)
                    input.position(rowStart)
                    out.put(input)
                }
            }
        } else {
            for (y in 0 until crop.height) {
                val rowStart = dataOrigin + (crop.top + y) * layout.rowStride +
                    crop.left * layout.pixelStride
                for (x in 0 until crop.width) {
                    out.putShort(input.getShort(rowStart + x * layout.pixelStride))
                }
            }
        }
        out.rewind()
        return out
    }

    /**
     * Rotates the frame's camera2-order black levels so phase 0 sits at the
     * uploaded crop's (0,0): native indexes phases as (y&1)*2+(x&1) over
     * uploaded coordinates, while [RawNormalization.blackLevels] is in
     * sensor coordinates.
     */
    /** Contiguous frame window (original indices) and path the memory budget allows. */
    private data class MemoryPlan(val window: IntRange, val forceFast: Boolean)

    /**
     * Sizes the merge to what the device can hold right now. HDR+ has no
     * tiling: every byte below is live at the GPU peak, and on unified
     * memory it competes with the whole system — an unbounded 50 MP x8 HQ
     * merge (~5 GB) drove lmkd to kill launcher, SystemUI and then RawLens
     * (logcat 2026-11-12, RawLens RSS 3.3 GB at kill). The budget is the
     * measured headroom above the kernel's kill threshold, never a constant.
     * Degrades HQ -> fast, then trims frames around the reference; returns
     * null (caller's HDR+ ERROR path) if even a 2-frame fast merge won't fit.
     */
    private fun memoryPlan(
        width: Int, height: Int, frameCount: Int, refIndex: Int, settings: HdrPlusSettings
    ): MemoryPlan? {
        val budget = MergeMemoryBudget.systemHeadroomBytes(appContext)
            ?: return MemoryPlan(0 until frameCount, false)
        val px = width.toDouble() * height
        fun bytes(n: Int, hq: Boolean): Double =
            px * (FIXED_BYTES_PER_PX + n * PER_FRAME_BYTES_PER_PX +
                (if (hq) HQ_SCRATCH_BYTES_PER_PX else FAST_SCRATCH_BYTES_PER_PX)) * MergeMemoryBudget.ESTIMATE_MARGIN
        val wantsHq = if (settings.autoQuality) true else settings.highQuality
        val hqFits = !wantsHq || bytes(frameCount, true) <= budget
        if (hqFits) return MemoryPlan(0 until frameCount, false)
        // Fast at full count, else the largest count that fits.
        var n = frameCount
        while (n >= HdrPlusSettings.MIN_MERGE_FRAMES && bytes(n, false) > budget) n--
        val mb = MergeMemoryBudget::mb
        if (n < HdrPlusSettings.MIN_MERGE_FRAMES) {
            Log.e(LOG_TAG, "HDR+ skipped: ${width}x$height needs " +
                "${mb(bytes(HdrPlusSettings.MIN_MERGE_FRAMES, false))}MB, budget ${mb(budget.toDouble())}MB")
            return null
        }
        val start = (refIndex - n / 2).coerceIn(0, frameCount - n)
        Log.w(LOG_TAG, "HDR+ memory budget ${mb(budget.toDouble())}MB: " +
            "${if (wantsHq) "HQ->fast, " else ""}frames $frameCount->$n " +
            "(est ${mb(bytes(n, false))}MB, HQ x$frameCount would need ${mb(bytes(frameCount, true))}MB)")
        return MemoryPlan(start until start + n, forceFast = wantsHq)
    }

    /** Runs the budgeted merge and maps its decision back to the caller's indices. */
    private fun mergeWithinBudget(
        plan: MemoryPlan, frames: List<FrameInput>, refIndex: Int, settings: HdrPlusSettings,
        tileSize: Int, searchDistance: Int, alignOnce: Boolean,
        noiseSource: WaveletDenoiser.NoiseSource?
    ): UnpackedRawCfa? {
        val offset = plan.window.first
        val sub = frames.subList(plan.window.first, plan.window.last + 1)
        val subSettings = if (plan.forceFast) settings.copy(highQuality = false, autoQuality = false)
        else settings
        val out = merge(sub, (refIndex - offset).coerceIn(sub.indices), subSettings, tileSize,
            searchDistance, alignOnce, noiseSource, budgetChecked = true) ?: return null
        lastDecision = lastDecision?.let { d ->
            val outside = frames.indices.filter { it !in plan.window }
            d.copy(
                keepIndices = d.keepIndices.map { it + offset },
                droppedIndices = (d.droppedIndices.map { it + offset } + outside).sorted()
            )
        }
        // Pair deltas are indexed by the sub-window; drop rather than misattribute.
        lastMotion = null
        return out
    }

    private fun rotatedBlacks(frame: FrameInput, out: FloatArray, offset: Int) {
        val norm = frame.normalization
        val ox = frame.layout.sensorOriginX + frame.crop.left
        val oy = frame.layout.sensorOriginY + frame.crop.top
        out[offset] = norm.blackAt(ox, oy)
        out[offset + 1] = norm.blackAt(ox + 1, oy)
        out[offset + 2] = norm.blackAt(ox, oy + 1)
        out[offset + 3] = norm.blackAt(ox + 1, oy + 1)
    }

    companion object {
        private const val LOG_TAG = "HdrPlusMerge"
        private const val U16_BYTES = 2L
        /** Upstream kMaxFrequencyFrames (align-store slots); spatial allows more. */
        const val MAX_NATIVE_FRAMES = 64
        // Peak bytes per CFA pixel, from hdrplus_run.cpp allocations:
        // fixed = native output 4 + Kotlin FloatArray 4 + download staging 4 + ref upload staging 2
        //   + ref and companion R16 GPU slots 4 + companion frame staging 2 (inputs stream, see hdrplus_run.cpp);
        // per frame = packed u16 copy 2;
        // fast scratch = padded ref/comp 8 + aligned/blurs/accum/cfa 20 + pyramids/tmp ~4;
        // HQ scratch = 3 RGBA32F half-res 12 + 3 double-width 24 + accum/cfa 8 + padded 8 + pyramids ~4.
        private const val FIXED_BYTES_PER_PX = 20.0
        private const val PER_FRAME_BYTES_PER_PX = 2.0
        private const val FAST_SCRATCH_BYTES_PER_PX = 32.0
        private const val HQ_SCRATCH_BYTES_PER_PX = 56.0
    }
}
