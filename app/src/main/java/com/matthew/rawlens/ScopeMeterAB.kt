// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.util.Log

/**
 * On-device transfer check: runs the fused native sampler and the four
 * Kotlin samplers over the same frame, times both, and diffs every output
 * bitwise. The host gate already pins the filter against an independent
 * scalar transcription; this confirms the verdict transfers to the
 * phone's CPU/ABI and measures the real speedup. Debug path only (not
 * wired to any UI); the controller-wiring step decides where it runs.
 *
 * Focus energy/mean grids are NOT compared here (Kotlin's sampler only
 * exposes the mask); the mask derives from them through the pure tested
 * [RawFocusPeakingSampler.maskOf], so mask equality over varied real
 * frames plus the host gate's grid-level pinning is the transfer check.
 */
internal object ScopeMeterAB {
    private const val TAG = "RawLensScopeAB"
    private const val MAX_DIFF_LINES = 8

    data class ABReport(
        val passed: Boolean,
        val diffs: List<String>,
        val nativeNs: Long,
        val histNs: Long,
        val waveNs: Long,
        val ettrNs: Long,
        val focusNs: Long
    ) {
        val kotlinNs: Long get() = histNs + waveNs + ettrNs + focusNs
        val speedup: Double get() = if (nativeNs <= 0) Double.NaN else kotlinNs.toDouble() / nativeNs
    }

    /** Bitwise int-array diff, appending up to [cap] detail lines. Pure. */
    internal fun diffInts(
        name: String,
        native: IntArray,
        kotlin: IntArray,
        diffs: MutableList<String>,
        cap: Int = MAX_DIFF_LINES
    ): Boolean {
        if (native.size != kotlin.size) {
            diffs += "$name size ${native.size} != ${kotlin.size}"
            return false
        }
        var ok = true
        for (i in native.indices) {
            if (native[i] != kotlin[i]) {
                ok = false
                if (diffs.size < cap) diffs += "$name[$i] native=${native[i]} kotlin=${kotlin[i]}"
            }
        }
        return ok
    }

    /** Bitwise float-array diff (raw NaN bits compare — canonicalizing
     * NaN would hide divergence). Pure. */
    internal fun diffFloats(
        name: String,
        native: FloatArray,
        kotlin: FloatArray,
        diffs: MutableList<String>,
        cap: Int = MAX_DIFF_LINES
    ): Boolean {
        if (native.size != kotlin.size) {
            diffs += "$name size ${native.size} != ${kotlin.size}"
            return false
        }
        var ok = true
        for (i in native.indices) {
            if (native[i].toRawBits() != kotlin[i].toRawBits()) {
                ok = false
                if (diffs.size < cap) {
                    diffs += "$name[$i] native=${native[i]} (${native[i].toRawBits().toUInt().toString(16)}) " +
                        "kotlin=${kotlin[i]} (${kotlin[i].toRawBits().toUInt().toString(16)})"
                }
            }
        }
        return ok
    }

    internal fun diffBools(
        name: String,
        native: BooleanArray,
        kotlin: BooleanArray,
        diffs: MutableList<String>,
        cap: Int = MAX_DIFF_LINES
    ): Boolean {
        if (native.size != kotlin.size) {
            diffs += "$name size ${native.size} != ${kotlin.size}"
            return false
        }
        var ok = true
        for (i in native.indices) {
            if (native[i] != kotlin[i]) {
                ok = false
                if (diffs.size < cap) diffs += "$name[$i] native=${native[i]} kotlin=${kotlin[i]}"
            }
        }
        return ok
    }

    /** Pure comparison of one native frame against the four Kotlin results. */
    fun compare(
        native: ScopeMeterNative.ScopeMeterFrame,
        hist: RgbHistogram,
        wave: RgbWaveform,
        ettr: EttrRawSample,
        focus: FocusPeakingFrame,
        nativeNs: Long,
        histNs: Long,
        waveNs: Long,
        ettrNs: Long,
        focusNs: Long
    ): ABReport {
        val diffs = mutableListOf<String>()
        var ok = true
        ok = diffInts("hist.r", native.histogram.red, hist.red, diffs) && ok
        ok = diffInts("hist.g", native.histogram.green, hist.green, diffs) && ok
        ok = diffInts("hist.b", native.histogram.blue, hist.blue, diffs) && ok
        ok = diffInts("hist.lum", native.histogram.luminance, hist.luminance, diffs) && ok
        if (native.histogram.agxApplied != hist.agxApplied) {
            ok = false
            diffs += "hist.agx ${native.histogram.agxApplied} != ${hist.agxApplied}"
        }
        ok = diffInts("wave.r", native.waveform.red, wave.red, diffs) && ok
        ok = diffInts("wave.g", native.waveform.green, wave.green, diffs) && ok
        ok = diffInts("wave.b", native.waveform.blue, wave.blue, diffs) && ok
        if (native.waveform.agxApplied != wave.agxApplied) {
            ok = false
            diffs += "wave.agx ${native.waveform.agxApplied} != ${wave.agxApplied}"
        }
        val nl = native.ettr.levels
        val kl = ettr.levels
        ok = diffFloats("ettr.levels",
            floatArrayOf(nl.r, nl.gr, nl.gb, nl.b),
            floatArrayOf(kl.r, kl.gr, kl.gb, kl.b), diffs) && ok
        for (ch in 0..3) {
            ok = diffInts("ettr.bins[$ch]", native.ettr.bins[ch], ettr.bins[ch], diffs) && ok
        }
        ok = diffInts("ettr.saturated", native.ettr.saturated, ettr.saturated, diffs) && ok
        ok = diffInts("ettr.totals", native.ettr.totals, ettr.totals, diffs) && ok
        ok = diffFloats("ettr.means",
            floatArrayOf(native.ettr.centerWeightedGreen, native.ettr.spotGreen,
                native.ettr.guardHottest),
            floatArrayOf(ettr.centerWeightedGreen, ettr.spotGreen, ettr.guardHottest),
            diffs) && ok
        if (native.focus.cols != focus.cols || native.focus.rows != focus.rows) {
            ok = false
            diffs += "focus grid ${native.focus.cols}x${native.focus.rows} != ${focus.cols}x${focus.rows}"
        } else {
            ok = diffBools("focus.mask", native.focus.mask, focus.mask, diffs) && ok
        }
        return ABReport(ok, diffs, nativeNs, histNs, waveNs, ettrNs, focusNs)
    }

    /**
     * Runs native + Kotlin over [image] with matching requests and logs
     * the verdict. [fullEttr] selects FULL-density full-frame metering
     * (mirrors sample()); otherwise PROGRAM-density metering-region
     * sampling with the cropped-mode guard (mirrors sampleProgram()).
     * Null when either side cannot sample (logged; distinct from mismatch).
     */
    fun run(
        image: Image,
        characteristics: CameraCharacteristics,
        metering: ProgramMetering,
        fullEttr: Boolean,
        scopeLut: FloatArray?,
        scratch: ScopeMeterNative.Scratch = ScopeMeterNative.Scratch()
    ): ABReport? {
        val cal = ScopeMeterNative.readCalibration(characteristics)
        if (cal == null) {
            Log.w(TAG, "AB skipped: unreadable calibration")
            return null
        }
        val req = ScopeMeterNative.ScopeMeterRequest(
            scopeLut = scopeLut,
            metering = if (fullEttr) ProgramMetering.AVERAGE else metering,
            density = if (fullEttr) ScopeMeterNative.EttrDensity.FULL
            else ScopeMeterNative.EttrDensity.PROGRAM
        )
        val t0 = System.nanoTime()
        val frame = ScopeMeterNative.sample(image, cal, req, scratch)
        val nativeNs = System.nanoTime() - t0
        if (frame == null) {
            Log.w(TAG, "AB skipped: native sample failed (unavailable or rejected frame)")
            return null
        }
        fun <T> timed(block: () -> T?): Pair<T?, Long> {
            val s = System.nanoTime()
            val v = try {
                block()
            } catch (failed: Exception) {
                Log.w(TAG, "AB kotlin sampler threw", failed)
                null
            }
            return v to (System.nanoTime() - s)
        }
        val (hist, histNs) = timed { RawHistogramSampler.sample(image, characteristics, scopeLut) }
        val (wave, waveNs) = timed { RawWaveformSampler.sample(image, characteristics, scopeLut) }
        val (ettr, ettrNs) = timed {
            if (fullEttr) RawEttrSampler.sample(image, characteristics)
            else RawEttrSampler.sampleProgram(image, characteristics, req.metering)
        }
        val (focus, focusNs) = timed { RawFocusPeakingSampler.sample(image, characteristics) }
        if (hist == null || wave == null || ettr == null || focus == null) {
            Log.w(TAG, "AB skipped: kotlin sampler null " +
                "(h=${hist != null} w=${wave != null} e=${ettr != null} f=${focus != null})")
            return null
        }
        val report = compare(frame, hist, wave, ettr, focus,
            nativeNs, histNs, waveNs, ettrNs, focusNs)
        Log.i(TAG, "AB ${if (report.passed) "PASS" else "FAIL"} " +
            "native=${"%.2f".format(nativeNs / 1e6)}ms " +
            "kotlin=${"%.2f".format(report.kotlinNs / 1e6)}ms " +
            "(h=${"%.2f".format(histNs / 1e6)} w=${"%.2f".format(waveNs / 1e6)} " +
            "e=${"%.2f".format(ettrNs / 1e6)} f=${"%.2f".format(focusNs / 1e6)}) " +
            "speedup=${"%.2f".format(report.speedup)}x diffs=${report.diffs.size}")
        report.diffs.forEach { Log.i(TAG, "AB diff: $it") }
        return report
    }
}
