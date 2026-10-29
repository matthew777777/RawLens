// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import java.util.Locale

internal enum class RawExposureOverlayMode(val label: String) {
    OFF("Off"), ZEBRAS("RAW zebras"), FALSE_COLOR("RAW false colour");
    fun next() = values()[(ordinal + 1) % values().size]
    companion object {
        fun fromName(name: String?) = values().firstOrNull { it.name == name } ?: OFF
    }
}

internal enum class RawExposureMeterMode(val label: String) {
    SPOT("Spot"), AVERAGE("Average");
    fun next() = if (this == SPOT) AVERAGE else SPOT
    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: AVERAGE
    }
}

internal data class RawExposureAssistSettings(
    val overlay: RawExposureOverlayMode = RawExposureOverlayMode.OFF,
    // Retain the existing enabled preference and positional constructor contract.
    val spotMeter: Boolean = true,
    val meterMode: RawExposureMeterMode = RawExposureMeterMode.AVERAGE
) {
    val enabled get() = overlay != RawExposureOverlayMode.OFF || spotMeter
    companion object {
        const val KEY_OVERLAY = "raw_exposure_overlay"
        const val KEY_SPOT = "raw_spot_meter"
        const val KEY_METER_MODE = "raw_exposure_meter_mode"
    }
}

data class RawSpotReading(
    val ev: Double, val atBlack: Boolean, val clippedChannels: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

/** Clipping is the estimated area of sampled Bayer quads with any phase at white. */
data class RawAverageReading(
    val ev: Double, val atBlack: Boolean, val clippedChannels: Int,
    val clippedCells: Int, val sampledCells: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    val clippedPercent: Double get() = 100.0 * clippedCells / sampledCells
}

data class RawExposureAssistFrame(
    val cols: Int, val rows: Int, val colors: IntArray?, val spot: RawSpotReading?,
    val rotation: Int, val mirrored: Boolean, val timestampNanos: Long,
    val frameWidth: Int, val frameHeight: Int, val monitorCrop: IntArray? = null,
    val underUsesNoiseProfile: Boolean = false, val sampledAtElapsedMs: Long = 0L,
    val average: RawAverageReading? = null
)

/** Primitive signature: avoids boxing three Ints on every RAW sample in the hot loop. */
internal fun interface RawExposureCodeReader {
    fun codeAt(x: Int, y: Int): Int
}

/** One sparse RAW quad per display cell; the ML spot mean scans its complete small ROI. */
internal object RawExposureAssistSampler {

    fun sample(image: Image, c: CameraCharacteristics, settings: RawExposureAssistSettings,
               focus: Pair<Float, Float>? = null, result: CaptureResult? = null,
               meterCrop: IntArray? = null): RawExposureAssistFrame? {
        val p = image.planes.singleOrNull() ?: return null
        val pattern = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ?: return null
        if (pattern !in 0..3 || image.width < 2 || image.height < 2) return null
        val black = c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN] ?: return null
        val matching = result?.takeIf { it[CaptureResult.SENSOR_TIMESTAMP] == image.timestamp }
        val physical = if (Build.VERSION.SDK_INT >= 31 && matching is TotalCaptureResult) {
            val id = matching[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
            id?.let { matching.physicalCameraTotalResults[it] }
        } else null
        val dynamicBlack = (physical?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: matching?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL))?.takeIf { it.size == 4 && it.all(Float::isFinite) }
        val blackLevels = DoubleArray(4) { dynamicBlack?.get(it)?.toDouble() ?: black.getOffsetForIndex(it % 2, it / 2).toDouble() }
        val white = physical?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: matching?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] ?: return null
        val noise = physical?.get(CaptureResult.SENSOR_NOISE_PROFILE) ?: matching?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val offsets = noise?.map { it.second }?.filter { it.isFinite() && it > 0.0 }
        val dynamicRangeEv = if (!offsets.isNullOrEmpty()) MagicLanternEttr.dynamicRangeEv(offsets.average()) else Double.NaN
        val rotation = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val mirrored = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        return sampleBuffer(p.buffer, image.width, image.height, p.rowStride, p.pixelStride, image.format,
            pattern, blackLevels, white, rotation, mirrored, image.timestamp, settings, focus, dynamicRangeEv, meterCrop)
    }

    /** Platform-free buffer adapter, shared by RAW_SENSOR and packed RAW10/12. */
    internal fun sampleBuffer(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                              format: Int, pattern: Int, black: DoubleArray, white: Int, rotation: Int,
                              mirrored: Boolean, timestamp: Long, settings: RawExposureAssistSettings,
                              focus: Pair<Float, Float>? = null, dynamicRangeEv: Double = Double.NaN,
                              meterCrop: IntArray? = null): RawExposureAssistFrame? {
        if (!settings.enabled || width < 2 || height < 2 || rowStride <= 0) return null
        if (format != android.graphics.ImageFormat.RAW_SENSOR && PackedRawFormat.ofInt(format) == null) return null
        val buffer = source.duplicate().order(ByteOrder.nativeOrder())
        val packed = if (pixelStride < 2) {
            val packedFormat = PackedRawFormat.ofInt(format) ?: return null
            // Quads alternate between adjacent Bayer rows. Keep one row cache per
            // parity so sparse sampling never unpacks a full row again for every cell.
            try { Array(2) { RawPackedUnpacker.PackedSampleReader(buffer, width, height, rowStride, format = packedFormat) } }
            catch (_: IllegalArgumentException) { return null }
        } else null
        val origin = buffer.position()
        if (packed == null && (pixelStride < 2 || rowStride < width * pixelStride ||
                origin.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride + 2 > buffer.limit())) return null
        return sampleCodes(width, height, pattern, black, white, rotation, mirrored,
            timestamp, settings, focus, dynamicRangeEv, meterCrop) { x, y ->
            packed?.get(y and 1)?.codeAt(x, y) ?: (buffer.getShort(origin + y * rowStride + x * pixelStride).toInt() and 0xffff)
        }
    }

    internal fun sensorPoint(u: Float, v: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        val x = if (mirrored) 1f - u else u
        return when (rotation) { 90 -> v to 1f - x; 180 -> 1f - x to 1f - v; 270 -> 1f - v to x; else -> x to v }
    }

    internal fun sampleCodes(width: Int, height: Int, pattern: Int, black: DoubleArray, white: Int,
                             rotation: Int, mirrored: Boolean, timestamp: Long,
                             settings: RawExposureAssistSettings, focus: Pair<Float, Float>? = null,
                             dynamicRangeEv: Double = Double.NaN, meterCrop: IntArray? = null,
                             code: RawExposureCodeReader): RawExposureAssistFrame? {
        if (!settings.enabled || width < 2 || height < 2 || pattern !in 0..3 || black.size != 4 || black.any { !it.isFinite() || it < 0 || it >= white }) return null
        if (focus != null && (!focus.first.isFinite() || !focus.second.isFinite())) return null
        val channels = RawEttrSampler.phaseChannelLut(pattern)
        val oneCode = 1.0 / (white - black.min()).toDouble()
        val underNoise = dynamicRangeEv.isFinite() && dynamicRangeEv > 0
        val under = if (underNoise) MagicLanternRawExposure.evToLevel(-dynamicRangeEv) else 0.0
        fun level(x: Int, y: Int): Double {
            val b = black[(y and 1) * 2 + (x and 1)]
            return ((code.codeAt(x, y) - b).toDouble() / (white - b)).coerceIn(0.0, 1.0)
        }
        val target = if (settings.overlay == RawExposureOverlayMode.FALSE_COLOR) 512 else 256
        val cols = minOf(target, width / 2)
        val rows = (cols.toDouble() * height / width).roundToInt().coerceIn(1, minOf(target, height / 2))
        val colors = if (settings.overlay == RawExposureOverlayMode.OFF) null else IntArray(cols * rows)
        val quad = DoubleArray(4)
        if (colors != null) for (row in 0 until rows) for (col in 0 until cols) {
            val x = (((col + 0.5) * width / cols).toInt().coerceIn(0, width - 2)) and -2
            val y = (((row + 0.5) * height / rows).toInt().coerceIn(0, height - 2)) and -2
            for (dy in 0..1) for (dx in 0..1) quad[channels[dy * 2 + dx]] = level(x + dx, y + dy)
            val g = maxOf(quad[1], quad[2]) // Camera2 has two individually calibrated green phases.
            colors[row * cols + col] = if (settings.overlay == RawExposureOverlayMode.ZEBRAS) {
                MagicLanternRawExposure.zebraColor(g <= under,
                    quad[0] >= 1.0, g >= 1.0, quad[3] >= 1.0)
            } else {
                val ev = MagicLanternRawExposure.rawToEv(maxOf(quad[0], g, quad[3]), oneCode)
                TurboColormap.argb((ev + MagicLanternRawExposure.PREVIEW_RANGE_EV) / MagicLanternRawExposure.PREVIEW_RANGE_EV)
            }
        }
        val average = if (!settings.spotMeter || settings.meterMode != RawExposureMeterMode.AVERAGE) null else {
            if (meterCrop != null && (meterCrop.size != 4 || meterCrop[0] < 0 || meterCrop[1] < 0 ||
                    meterCrop[2] <= 0 || meterCrop[3] <= 0 || meterCrop[0] > width - meterCrop[2] ||
                    meterCrop[1] > height - meterCrop[3])) return null
            // Inward alignment keeps every sample a complete, correctly phased Bayer quad.
            val l = ((meterCrop?.get(0) ?: 0) + 1) and -2
            val t = ((meterCrop?.get(1) ?: 0) + 1) and -2
            val r = ((meterCrop?.let { it[0] + it[2] } ?: width)) and -2
            val b = ((meterCrop?.let { it[1] + it[3] } ?: height)) and -2
            if (r - l < 2 || b - t < 2) return null
            val w = (r - l) / 2; val h = (b - t) / 2
            // Same sparse RAW-quad approach as RawHistogramSampler; fixed grid across overlay modes.
            // No histogram bins, bitmap or image-sized allocation for average-only metering.
            val nx = minOf(128, w)
            val ny = (nx.toDouble() * h / w).roundToInt().coerceIn(1, minOf(128, h))
            var sum = 0.0; var clipped = 0; var mask = 0
            for (iy in 0 until ny) for (ix in 0 until nx) {
                val x = l + 2 * ((ix + 0.5) * w / nx).toInt()
                val y = t + 2 * ((iy + 0.5) * h / ny).toInt()
                var cellMask = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val value = level(x + dx, y + dy)
                    sum += value
                    if (value >= 1.0) cellMask = cellMask or when (channels[dy * 2 + dx]) {
                        0 -> MagicLanternRawExposure.RED; 3 -> MagicLanternRawExposure.BLUE; else -> MagicLanternRawExposure.GREEN
                    }
                }
                if (cellMask != 0) clipped++
                mask = mask or cellMask
            }
            val count = nx * ny
            RawAverageReading(MagicLanternRawExposure.rawToEv(sum / (4 * count), oneCode), sum == 0.0,
                mask, clipped, count, l.toFloat() / width, t.toFloat() / height, r.toFloat() / width, b.toFloat() / height)
        }
        val spot = if (!settings.spotMeter || settings.meterMode != RawExposureMeterMode.SPOT) null else {
            val point = sensorPoint(focus?.first ?: 0.5f, focus?.second ?: 0.5f, rotation, mirrored)
            val half = (width * MagicLanternRawExposure.SPOT_HALF_WIDTH).roundToInt().coerceAtLeast(1)
            val cx = (point.first.coerceIn(0f, 1f) * (width - 1)).roundToInt()
            val cy = (point.second.coerceIn(0f, 1f) * (height - 1)).roundToInt()
            val l = maxOf(0, cx - half); val t = maxOf(0, cy - half)
            val r = minOf(width, cx + half + 1); val b = minOf(height, cy + half + 1)
            var sum = 0.0; var count = 0; var clip = 0
            for (y in t until b) for (x in l until r) {
                val value = level(x, y)
                sum += value; count++
                if (value >= 1.0) clip = clip or when (channels[(y and 1) * 2 + (x and 1)]) {
                    0 -> MagicLanternRawExposure.RED; 3 -> MagicLanternRawExposure.BLUE; else -> MagicLanternRawExposure.GREEN
                }
            }
            // Port of spotmeter_step: average RAW values first, then raw_to_ev, not average(log2).
            RawSpotReading(MagicLanternRawExposure.rawToEv(sum / count, oneCode), sum == 0.0, clip,
                l.toFloat() / width, t.toFloat() / height, r.toFloat() / width, b.toFloat() / height)
        }
        return RawExposureAssistFrame(cols, rows, colors, spot, rotation, mirrored, timestamp, width, height,
            underUsesNoiseProfile = underNoise, average = average)
    }
}

/** At most one RAW borrow; never queue camera frames or process on the encoder/camera thread. */
internal class RawExposureAssist(private val publish: (RawExposureAssistFrame?) -> Unit) : AutoCloseable {
    @Volatile var settings = RawExposureAssistSettings(spotMeter = false)
        private set
    private val worker = Executors.newSingleThreadExecutor { r -> Thread({
        // Android threading guidance: less-urgent sampling must yield to recording/UI.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "raw-exposure-assist") }
    private val busy = AtomicBoolean(false)
    @Volatile private var epoch = 0L
    private var lastMs = Long.MIN_VALUE
    private var closed = false

    @Synchronized fun configure(value: RawExposureAssistSettings) {
        settings = value
        clear()
    }
    @Synchronized fun clear() { epoch++; lastMs = Long.MIN_VALUE; publish(null) }

    @Synchronized fun offer(image: Image, c: CameraCharacteristics, focus: Pair<Float, Float>? = null,
                            monitorCrop: IntArray? = null, result: CaptureResult? = null,
                            meterCrop: IntArray? = monitorCrop) {
        val config = settings
        if (closed || !config.enabled) return
        val now = SystemClock.elapsedRealtime()
        val interval = if (config.overlay == RawExposureOverlayMode.OFF) 250L else 125L
        if (!RawHistogramThrottle.shouldSample(now, lastMs, interval) || !busy.compareAndSet(false, true)) return
        val lease = RawImageOwnership.borrow(image)
        if (lease == null) { busy.set(false); return }
        lastMs = now
        val generation = epoch
        worker.execute {
            try {
                val frame = RawExposureAssistSampler.sample(image, c, config, focus, result, meterCrop = meterCrop)
                synchronized(this) { if (!closed && epoch == generation) publish(frame?.copy(monitorCrop = monitorCrop, sampledAtElapsedMs = now)) }
            } catch (e: Exception) {
                Log.w("RawLensRawExposure", "RAW assist sample unavailable", e)
                synchronized(this) { if (!closed && epoch == generation) publish(null) }
            } finally { try { lease.close() } finally { busy.set(false) } }
        }
    }
    override fun close() {
        synchronized(this) { closed = true; epoch++; publish(null); worker.shutdown() }
        if (!worker.awaitTermination(3, TimeUnit.SECONDS)) Log.w("RawLensRawExposure", "RAW assist worker still draining")
    }
}

/** One-decimal ML-style EV readout for the fixed top HUD; never reports stale data as zero. */
internal object RawSpotMeterText {
    fun evText(ev: Double): String {
        val magnitude = kotlin.math.floor(kotlin.math.abs(ev) * 10.0 + 0.5) / 10.0
        val value = if (magnitude == 0.0) 0.0 else if (ev < 0) -magnitude else magnitude
        return String.format(Locale.US, "%.1f", value).replace('-', '−')
    }
    fun format(spot: RawSpotReading?): String {
        if (spot == null) return "RAW SPOT  —"
        val ev = evText(spot.ev)
        val clip = buildString {
            if (spot.clippedChannels != 0) {
                append(" · CLIP ")
                if (spot.clippedChannels and 4 != 0) append('R')
                if (spot.clippedChannels and 2 != 0) append('G')
                if (spot.clippedChannels and 1 != 0) append('B')
            }
        }
        return "RAW SPOT  ${if (spot.atBlack) "≤" else ""}$ev EV$clip"
    }
}

internal object RawAverageMeterText {
    fun format(reading: RawAverageReading?, compact: Boolean = false): String {
        if (reading == null) return if (compact) "AVG —" else "RAW average waiting for RAW"
        val ev = (if (reading.atBlack) "≤" else "") + RawSpotMeterText.evText(reading.ev)
        val percent = when {
            reading.clippedCells == 0 -> "0%"
            reading.clippedPercent < 0.1 -> "<0.1%"
            else -> String.format(Locale.US, "%.1f%%", reading.clippedPercent)
        }
        return if (compact) "AVG ${ev}EV $percent"
        else "RAW average $ev EV · $percent clipped (estimated from ${reading.sampledCells} Bayer cells)"
    }
}
