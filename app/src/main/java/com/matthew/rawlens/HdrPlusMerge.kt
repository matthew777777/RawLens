// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HDR+ merge bridge: ZSL [Image] frames in, merged [UnpackedRawCfa] out
 * over the HDR+ Vulkan pipeline ([HdrPlusVulkan]). Mirrors
 * [GaloshDenoiser] semantics: returns the merged CFA (same dims/pattern
 * crop as the inputs) or null when the merge is unavailable — failure,
 * OOM, and unsupported geometry all fall back to the caller's error
 * path. Never throws for HDR+ reasons; callers map null to HDR+ ERROR.
 *
 * Frames upload as raw uint16 codes (never normalized on the host), so
 * the GPU sees bit-identical input to RAWR's ring views; per-frame
 * black/white travel alongside for the in-shader normalization. The
 * Vulkan handle is process-lifetime (pipeline creation is too slow per
 * capture) and [merge] is synchronized: one in-flight run at a time.
 */
class HdrPlusMerge(context: Context) {
    private val assets = context.applicationContext.assets

    @Volatile private var handle = 0L

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
        val image: Image,
        val layout: RawPlaneLayout,
        val crop: RawCrop,
        val normalization: RawNormalization,
        /** Sensor capture timestamp (ns, boot clock); 0 = unknown. */
        val timestampNanos: Long = 0L,
        /** Gyro ego-motion (rad/s) during this frame; NaN = unreported. */
        val gyroRadiansPerSecond: Float = Float.NaN,
        /** Sensor sensitivity for the darkness term; 0 = unknown. */
        val sensitivityIso: Int = 0
    )

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
                    if (handle == 0L) handle = HdrPlusVulkan.init(assets)
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
        alignOnce: Boolean = true
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
            if (handle == 0L) handle = HdrPlusVulkan.init(assets)
            if (HdrPlusVulkan.loadedShaderCount(handle) != HdrPlusVulkan.expectedShaderCount()) {
                Log.w(LOG_TAG, "HDR+ shader modules incomplete, aborting merge")
                return null
            }
            var t = System.nanoTime()
            val packed = frames.mapIndexed { index, frame -> packFrame(frame, index) }
            val blacks = FloatArray(frames.size * 4)
            val whites = FloatArray(frames.size)
            frames.forEachIndexed { index, frame ->
                rotatedBlacks(frame, blacks, index * 4)
                whites[index] = frame.normalization.whiteLevel
            }
            // Meter first: the auto brain picks strength/path/frames/ref
            // from read-only views of the packed buffers. Scores travel in
            // locals, so the decision can never observe a stale volatile.
            val metering = meterMotion(frames, packed, blacks, whites, width, height)
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
            val output = ByteBuffer.allocateDirect(width * height * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val packedAt = System.nanoTime()
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
            val gpuMs = HdrPlusVulkan.merge(
                handle, keptPacked, keptBlacks, keptWhites, decision.refIndex,
                IntArray(0), decision.strength, frameStrengths, flatMaps, tileSize, searchDistance,
                decision.highQuality, alignOnce, width, height, output
            )
            val inferredAt = System.nanoTime()
            val values = FloatArray(width * height)
            output.asFloatBuffer().get(values)
            val sensorCropX = first.layout.sensorOriginX + first.crop.left
            val sensorCropY = first.layout.sensorOriginY + first.crop.top
            lastTimings = Timings(
                (packedAt - t) / 1_000_000, gpuMs, (System.nanoTime() - inferredAt) / 1_000_000
            )
            lastMotion = metering
            lastDecision = decision
            Log.i(LOG_TAG, "HDR+ merge ${width}x$height N=${keptPacked.size}/${frames.size} " +
                "ref=${decision.refIndex} " +
                "gpu=${"%.1f".format(gpuMs)}ms strength=${decision.strength}" +
                (if (decision.highQuality) " HQ" else " fast"))
            Log.i(LOG_TAG, metering.logLine(frames.size))
            Log.i(LOG_TAG, decision.logLine(frames.size))
            return UnpackedRawCfa(
                width, height,
                first.normalization.sensorPattern.shifted(sensorCropX, sensorCropY),
                values, first.crop, sensorCropX, sensorCropY
            )
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "HDR+ merge failed", failure)
            return null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "HDR+ merge OOM", oom)
            return null
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
        height: Int
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
        for (i in 1 until frames.size) {
            deltas += HdrPlusMotionMeter.meanAbsDiffNormalized(
                packed[i - 1], packed[i], width * height,
                normalizationRange = ranges[i]
            )
            hots += HdrPlusMotionMeter.blockHotFraction(
                packed[i - 1], packed[i], width, height,
                normalizationRange = ranges[i],
                hotRatio = HdrPlusAutoTuning.HOT_RATIO
            )
            HdrPlusMotionMeter.blockMismatchRatios(
                packed[i - 1], packed[i], width, height,
                normalizationRange = ranges[i]
            )?.let { ratios += it }
            prevTs += frames[i - 1].timestampNanos
            currTs += frames[i].timestampNanos
            val gyro = frames[i].gyroRadiansPerSecond
            gyros += if (gyro.isFinite()) gyro.toDouble() else Double.NaN
        }
        // Ratio grids ride along only when every pair measured one (else
        // the map builder would mix resolutions); the brain treats a
        // partial set as missing and falls back to frame strengths.
        val fullRatios = ratios.takeIf { it.size == frames.size - 1 } ?: emptyList()
        val (mapX, mapY) = if (fullRatios.isEmpty()) (0 to 0)
        else HdrPlusMotionMeter.mapCells(width, height)
        return HdrPlusMotionMeter.summarize(
            deltas, prevTs, currTs, gyros, hots,
            stats.map { it.meanLevel }, stats.map { it.sharpness },
            fullRatios, mapX, mapY
        )
    }

    /**
     * Tight-packs the frame's crop as uint16 codes (native order) into a
     * direct buffer. Row strides are honored; no normalization is applied
     * (black/white travel separately, exactly like RAWR's ring views).
     */
    private fun packFrame(frame: FrameInput, index: Int): ByteBuffer {
        val plane = frame.image.planes.single()
        val input = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val dataOrigin = input.position()
        val layout = frame.layout
        val crop = frame.crop
        val lastIndex = dataOrigin +
            (crop.top + crop.height - 1).toLong() * layout.rowStride +
            (crop.left + crop.width - 1).toLong() * layout.pixelStride
        require(lastIndex + U16_BYTES <= input.limit().toLong()) {
            "HDR+ frame $index buffer is truncated for its stride and crop"
        }
        val out = ByteBuffer.allocateDirect(crop.width * crop.height * U16_BYTES.toInt())
            .order(ByteOrder.nativeOrder())
        if (layout.pixelStride == U16_BYTES.toInt()) {
            val row = ByteArray(crop.width * U16_BYTES.toInt())
            for (y in 0 until crop.height) {
                val rowStart = dataOrigin + (crop.top + y) * layout.rowStride +
                    crop.left * layout.pixelStride
                input.position(rowStart)
                input.get(row)
                out.put(row)
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
        /** RAWR kMaxFrequencyFrames (align-store slots); spatial allows more. */
        const val MAX_NATIVE_FRAMES = 64
    }
}
