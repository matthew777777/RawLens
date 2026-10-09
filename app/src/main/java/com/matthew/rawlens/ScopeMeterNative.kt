// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Live-scope density target shared by the histogram/waveform samplers and the fused filter. */
internal const val SCOPE_TARGET_BLOCKS = 8_000

/**
 * Systematic-grid step for a [spanPixels] run targeting [targetPixels]
 * samples: the visit count stays near the target on any sensor size while
 * rows advance sequentially for cache locality. Single source of truth for
 * the histogram, waveform, ETTR, and fused-native step formulas (identical
 * sqrt-truncation the samplers previously inlined separately).
 */
internal fun meterGridStep(spanPixels: Long, targetPixels: Int): Int =
    kotlin.math.sqrt((spanPixels / targetPixels.toDouble()).coerceAtLeast(1.0))
        .toInt().coerceAtLeast(1)

/**
 * Native fused scope+meter sampler (Halide AOT, `rawLensScopeMeter`).
 *
 * One call meters a Bayer frame for every live consumer — histogram,
 * waveform, ETTR/PROGRAM metering (main region + full-frame guard), and
 * focus-peaking energy/mean — bitwise identical to the four Kotlin
 * samplers (host gate in tools/halide). All buffers are direct
 * (zero-copy); the plane keeps its HAL row stride, outputs are tight.
 * Missing/failed native code returns null (caller falls back to the
 * Kotlin samplers) — never a crash, never a wrong map.
 */
internal object ScopeMeterNative {
    /** iparams layout for [scopeMeterNative] (BguLook packed-array style). */
    const val IP_COUNT = 18
    const val IP_WHITE = 0
    const val IP_CFA = 1
    const val IP_BLACK0 = 2
    const val IP_BLACK1 = 3
    const val IP_BLACK2 = 4
    const val IP_BLACK3 = 5
    const val IP_USE_LUT = 6
    const val IP_SCOPE_STEP = 7
    const val IP_ETTR_STEP = 8
    const val IP_EL = 9
    const val IP_ET = 10
    const val IP_ER = 11
    const val IP_EB = 12
    const val IP_GUARD_STEP = 13
    const val IP_DO_GUARD = 14
    const val IP_W = 15
    const val IP_H = 16
    const val IP_ROW_STRIDE = 17

    const val HIST_BINS = 64
    const val WAVE_LEVELS = 48
    const val ETTR_BINS = 256
    const val FOCUS_COLS = 96
    const val LUT_SIZE = 1024

    val available: Boolean = try {
        System.loadLibrary("rawLensScopeMeter")
        true
    } catch (unsatisfied: UnsatisfiedLinkError) {
        false
    } catch (failed: SecurityException) {
        false
    }

    /** ETTR scan density: FULL mirrors sample(), PROGRAM mirrors sampleProgram(). */
    enum class EttrDensity(val targetPx: Int) {
        MINIMAL(RawEttrSampler.GUARD_PIXELS),
        PROGRAM(RawEttrSampler.TARGET_BLOCKS_PROGRAM * 4),
        FULL(RawEttrSampler.TARGET_BLOCKS * 4),
    }

    /** Frozen per-frame calibration + orientation for one sample call. */
    data class MeterCalibration(
        val cfa: Int,
        val black0: Int,
        val black1: Int,
        val black2: Int,
        val black3: Int,
        val white: Int,
        val rotation: Int = 0,
        val mirrored: Boolean = false
    ) {
        fun blackAt(phase: Int): Int = when (phase) {
            0 -> black0
            1 -> black1
            2 -> black2
            else -> black3
        }
    }

    /** Reads [MeterCalibration] from characteristics; null when unusable. */
    fun readCalibration(characteristics: CameraCharacteristics): MeterCalibration? {
        val cfa = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        ) ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        fun offset(i: Int): Int = black?.getOffsetForIndex(i % 2, i / 2) ?: 0
        return MeterCalibration(cfa, offset(0), offset(1), offset(2), offset(3), white,
            rotation, mirrored)
    }

    /** One fused sample request: scope curve, metering region, ETTR density. */
    data class ScopeMeterRequest(
        val scopeLut: FloatArray?,
        val metering: ProgramMetering = ProgramMetering.CENTER_WEIGHTED,
        val density: EttrDensity = EttrDensity.PROGRAM
    )

    /** Scan rectangle [l, t, r, b) for a request. FULL density always scans
     * the whole frame (mirrors sample()); otherwise the metering region
     * (mirrors sampleProgram()). Pure. */
    internal fun resolveRegion(width: Int, height: Int, req: ScopeMeterRequest): IntArray =
        if (req.density == EttrDensity.FULL) intArrayOf(0, 0, width, height)
        else RawEttrSampler.meteringScanRegion(width, height, req.metering)

    /** Guard scan runs exactly when the Kotlin path runs one: cropped
     * metering at sub-FULL density. Pure. */
    internal fun resolveGuard(req: ScopeMeterRequest): Boolean =
        req.density != EttrDensity.FULL && req.metering != ProgramMetering.AVERAGE

    /** Every consumer's output from one fused sample. */
    data class ScopeMeterFrame(
        val histogram: RgbHistogram,
        val waveform: RgbWaveform,
        val ettr: EttrRawSample,
        val focus: FocusPeakingFrame,
        val focusEnergy: FloatArray,
        val focusMean: FloatArray
    )

    /**
     * Reusable direct buffers for one geometry; reallocated only when
     * dimensions change. Positions stay 0 (native writes via raw address).
     */
    class Scratch {
        private var key = ""
        private fun direct(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        lateinit var hist: ByteBuffer
            private set
        lateinit var wave: ByteBuffer
            private set
        lateinit var ebins: ByteBuffer
            private set
        lateinit var ecounts: ByteBuffer
            private set
        lateinit var egreen: ByteBuffer
            private set
        lateinit var gbins: ByteBuffer
            private set
        lateinit var fenergy: ByteBuffer
            private set
        lateinit var fmean: ByteBuffer
            private set
        lateinit var lut: ByteBuffer
            private set
        val dummyLut: ByteBuffer = direct(LUT_SIZE * Float.SIZE_BYTES)
        var iparams = IntArray(IP_COUNT)
            private set
        var dparams = DoubleArray(4)
            private set
        var fparams = FloatArray(4)
            private set

        /** (Re)allocates for [w]x[h] with focus [rows]; false on OOM. */
        fun ensure(w: Int, h: Int, rows: Int): Boolean {
            val next = "$w,$h,$rows"
            if (next == key && ::hist.isInitialized) return true
            return try {
                hist = direct(HIST_BINS * 4 * Int.SIZE_BYTES)
                wave = direct(WAVE_LEVELS * RawWaveformSampler.COLUMNS * 3 * Int.SIZE_BYTES)
                ebins = direct(ETTR_BINS * 4 * Int.SIZE_BYTES)
                ecounts = direct(4 * 2 * Int.SIZE_BYTES)
                egreen = direct(4 * Double.SIZE_BYTES)
                gbins = direct(ETTR_BINS * 4 * Int.SIZE_BYTES)
                fenergy = direct(FOCUS_COLS * rows * Float.SIZE_BYTES)
                fmean = direct(FOCUS_COLS * rows * Float.SIZE_BYTES)
                lut = direct(LUT_SIZE * Float.SIZE_BYTES)
                iparams = IntArray(IP_COUNT)
                dparams = DoubleArray(4)
                fparams = FloatArray(4)
                key = next
                true
            } catch (oom: OutOfMemoryError) {
                false
            }
        }
    }

    /**
     * Samples [image] for every consumer in one native call; null when
     * unavailable or on any invalid input (caller falls back to Kotlin).
     * [image]'s plane must be direct with position 0 (ImageReader normal
     * form; anything else falls back rather than risk a base-address skew
     * in JNI, which addresses buffers from position 0).
     */
    fun sample(
        image: Image,
        cal: MeterCalibration,
        req: ScopeMeterRequest,
        scratch: Scratch = Scratch()
    ): ScopeMeterFrame? {
        if (!available) return null
        if (cal.cfa !in 0..3) return null
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride != 2 || plane.rowStride % 2 != 0) return null
        val w = image.width
        val h = image.height
        if (w < 8 || h < 8) return null
        val lut = req.scopeLut
        if (lut != null && lut.size != LUT_SIZE) return null
        val raw = plane.buffer
        if (!raw.isDirect || raw.position() != 0) return null
        val rowStrideElems = plane.rowStride / 2
        if (rowStrideElems < w) return null
        if (raw.remaining() < ((h - 1) * rowStrideElems + w) * 2) return null
        val rows = RawFocusPeakingSampler.rowsFor(w, h)
        if (!scratch.ensure(w, h, rows)) return null
        val region = resolveRegion(w, h, req)
        val rw = region[2] - region[0]
        val rh = region[3] - region[1]
        if (rw <= 0 || rh <= 0) return null
        val scopeStep = meterGridStep((w / 2).toLong() * (h / 2), SCOPE_TARGET_BLOCKS)
        val ettrStep = meterGridStep(rw.toLong() * rh, req.density.targetPx)
        val guardStep = meterGridStep(w.toLong() * h, RawEttrSampler.GUARD_PIXELS)
        val doGuard = resolveGuard(req)
        val ip = scratch.iparams
        ip[IP_WHITE] = cal.white
        ip[IP_CFA] = cal.cfa
        ip[IP_BLACK0] = cal.black0
        ip[IP_BLACK1] = cal.black1
        ip[IP_BLACK2] = cal.black2
        ip[IP_BLACK3] = cal.black3
        ip[IP_USE_LUT] = if (lut == null) 0 else 1
        ip[IP_SCOPE_STEP] = scopeStep
        ip[IP_ETTR_STEP] = ettrStep
        ip[IP_EL] = region[0]
        ip[IP_ET] = region[1]
        ip[IP_ER] = region[2]
        ip[IP_EB] = region[3]
        ip[IP_GUARD_STEP] = guardStep
        ip[IP_DO_GUARD] = if (doGuard) 1 else 0
        ip[IP_W] = w
        ip[IP_H] = h
        ip[IP_ROW_STRIDE] = rowStrideElems
        for (i in 0..3) {
            val denom = (cal.white - cal.blackAt(i)).coerceAtLeast(1)
            scratch.dparams[i] = 1.0 / denom.toDouble()
            scratch.fparams[i] = RawEttrSampler.invRange(cal.white, cal.blackAt(i))
        }
        val lutBuf = if (lut == null) {
            scratch.dummyLut
        } else {
            scratch.lut.asFloatBuffer().put(lut)
            scratch.lut
        }
        val ok = try {
            scopeMeterNative(
                raw, lutBuf, ip, scratch.dparams, scratch.fparams,
                scratch.hist, scratch.wave, scratch.ebins, scratch.ecounts,
                scratch.egreen, scratch.gbins, scratch.fenergy, scratch.fmean
            )
        } catch (failed: Exception) {
            return null
        }
        if (!ok) return null
        val energy = scratch.fenergy.floats(FOCUS_COLS * rows)
        val mean = scratch.fmean.floats(FOCUS_COLS * rows)
        return ScopeMeterFrame(
            histogram = scatterHist(scratch.hist.ints(HIST_BINS * 4), lut != null),
            waveform = scatterWave(scratch.wave.ints(WAVE_LEVELS * RawWaveformSampler.COLUMNS * 3),
                lut != null),
            ettr = scatterEttr(
                scratch.ebins.ints(ETTR_BINS * 4), scratch.ecounts.ints(4 * 2),
                scratch.egreen.doubles(4), scratch.gbins.ints(ETTR_BINS * 4), doGuard
            ),
            focus = scatterFocus(energy, mean, rows, cal.rotation, cal.mirrored),
            focusEnergy = energy,
            focusMean = mean
        )
    }

    private fun ByteBuffer.ints(count: Int): IntArray {
        val out = IntArray(count)
        asIntBuffer().get(out)
        return out
    }

    private fun ByteBuffer.floats(count: Int): FloatArray {
        val out = FloatArray(count)
        asFloatBuffer().get(out)
        return out
    }

    private fun ByteBuffer.doubles(count: Int): DoubleArray {
        val out = DoubleArray(count)
        asDoubleBuffer().get(out)
        return out
    }

    /** Hist flat layout is bin + 64*channel (R/G/B merged-greens/Lum). Pure. */
    internal fun scatterHist(flat: IntArray, agxApplied: Boolean): RgbHistogram {
        require(flat.size == HIST_BINS * 4)
        return RgbHistogram(
            red = flat.copyOfRange(0, 64),
            green = flat.copyOfRange(64, 128),
            blue = flat.copyOfRange(128, 192),
            luminance = flat.copyOfRange(192, 256),
            agxApplied = agxApplied
        )
    }

    /** Wave flat layout is level + 48*(col + 96*ch); per-channel slices
     * already index col*48+level like RawWaveformSampler. Pure. */
    internal fun scatterWave(flat: IntArray, agxApplied: Boolean): RgbWaveform {
        val stride = WAVE_LEVELS * RawWaveformSampler.COLUMNS
        require(flat.size == stride * 3)
        return RgbWaveform(
            columns = RawWaveformSampler.COLUMNS,
            levels = WAVE_LEVELS,
            red = flat.copyOfRange(0, stride),
            green = flat.copyOfRange(stride, stride * 2),
            blue = flat.copyOfRange(stride * 2, stride * 3),
            agxApplied = agxApplied
        )
    }

    /**
     * ETTR assembly mirroring sampleGrid/sampleProgram: percentiles over
     * the bins, pooled-green means from the f64 sums (NaN rules match),
     * guard hottest from summed guard totals when [doGuard]. Pure.
     */
    internal fun scatterEttr(
        ebins: IntArray,
        ecounts: IntArray,
        egreen: DoubleArray,
        gbins: IntArray,
        doGuard: Boolean
    ): EttrRawSample {
        require(ebins.size == ETTR_BINS * 4 && gbins.size == ETTR_BINS * 4)
        require(ecounts.size == 8 && egreen.size == 4)
        val bins = Array(4) { ch -> ebins.copyOfRange(ch * ETTR_BINS, (ch + 1) * ETTR_BINS) }
        val saturated = IntArray(4) { ch -> ecounts[ch] }
        val totals = IntArray(4) { ch -> ecounts[ch + 4] }
        fun percentile(channel: Int): Float = RawEttrSampler.percentileLevel(
            bins[channel], totals[channel], RawEttrMeter.TARGET_PERCENTILE
        )
        val levels = EttrChannelLevels(percentile(0), percentile(1), percentile(2), percentile(3))
        // Exact mirrors of sampleGrid's tail expressions.
        val centerWeightedGreen = if (egreen[1] > 0.0) {
            (egreen[0] / egreen[1]).toFloat().coerceIn(0f, 1f)
        } else Float.NaN
        val spotGreen = if (egreen[3] > 0.0) {
            (egreen[2] / egreen[3]).toFloat().coerceIn(0f, 1f)
        } else Float.NaN
        val guardHottest = if (!doGuard) Float.NaN else {
            var hottest = 0f
            for (ch in 0..3) {
                val gb = gbins.copyOfRange(ch * ETTR_BINS, (ch + 1) * ETTR_BINS)
                var total = 0
                for (v in gb) total += v
                hottest = maxOf(hottest, RawEttrSampler.percentileLevel(
                    gb, total, RawEttrMeter.TARGET_PERCENTILE
                ))
            }
            hottest
        }
        return EttrRawSample(levels, bins, saturated, totals, centerWeightedGreen,
            spotGreen, guardHottest)
    }

    /** Focus frame via the tested mask pipeline over the native grids. Pure. */
    internal fun scatterFocus(
        energy: FloatArray,
        mean: FloatArray,
        rows: Int,
        rotation: Int,
        mirrored: Boolean
    ): FocusPeakingFrame {
        require(energy.size == FOCUS_COLS * rows && mean.size == FOCUS_COLS * rows)
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, FOCUS_COLS, rows)
        return FocusPeakingFrame(FOCUS_COLS, rows, mask, rotation, mirrored)
    }

    // Instance (not static) external: matches the jobject JNI binding.
    private external fun scopeMeterNative(
        raw: ByteBuffer,
        lut: ByteBuffer,
        iparams: IntArray,
        dparams: DoubleArray,
        fparams: FloatArray,
        hist: ByteBuffer,
        wave: ByteBuffer,
        ebins: ByteBuffer,
        ecounts: ByteBuffer,
        egreen: ByteBuffer,
        gbins: ByteBuffer,
        fenergy: ByteBuffer,
        fmean: ByteBuffer
    ): Boolean
}
