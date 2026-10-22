// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.os.SystemClock
import android.util.Log

/**
 * Live RAW scope (histogram / waveform parade) and focus peaking: switches,
 * ~4 Hz throttles, the AgX scope LUT, cost telemetry, and the saved-frame
 * scopes (developed JPEG bitmap / JXL). Which repeating stream feeds the
 * samplers stays with the controller's stream plan.
 *
 * Extracted from RawCameraController (A5, docs/refactor/controller-remaining-plan.md);
 * bodies unchanged except Host calls.
 */
internal class LiveScopes(
    private val host: Host,
    private val guard: SamplerGuard,
    initialHistogramEnabled: Boolean,
    initialAgxApplied: Boolean,
    initialMode: ScopeMode,
    initialPeakingEnabled: Boolean,
) {
    interface Host {
        fun jpegOutputSettings(): JpegOutputSettings
        /** Active VF's stats, or null without a VF. May throw; callers guard. */
        fun vfStats(): RawVfStats?
        fun manualFocusSet(): Boolean
        fun touchFocusActive(): Boolean
        fun onRawHistogram(histogram: RgbHistogram)
        fun onRawWaveform(waveform: RgbWaveform)
        fun onFocusPeaking(frame: FocusPeakingFrame)
        fun onFocusPeakingActive(active: Boolean)
    }

    @Volatile var histogramEnabled = initialHistogramEnabled
        private set
    /** AgX-curved (JPEG preview) vs linear-RAW scope data; follows the capture format. */
    @Volatile private var agxApplied = initialAgxApplied
    /** AgX scope LUT reused until the JPEG look changes (camera thread). */
    private val lutCache = ScopeLutCache()
    @Volatile private var mode = initialMode
    private var lastSampleMs = Long.MIN_VALUE
    /** Scope cost telemetry accumulators (see noteScopeSample); camera thread only. */
    private var lutAccumNs = 0L
    private var scanAccumNs = 0L
    private var samples = 0
    private var lastScopeLogMs = 0L
    /** Focus-peaking master switch (default on); the auto-show policy gates delivery. */
    @Volatile private var peakingEnabled = initialPeakingEnabled
    private var lastPeakingSampleMs = Long.MIN_VALUE
    private var lastPeakingActivePublished = false

    /** The next RAW frame is sampled at once (MIN_VALUE sentinel, see RawHistogramThrottle). */
    fun requestImmediateSample() {
        lastSampleMs = Long.MIN_VALUE
    }

    fun setHistogramEnabled(enabled: Boolean) {
        histogramEnabled = enabled
        if (enabled) lastSampleMs = Long.MIN_VALUE
    }

    fun setAgxApplied(applied: Boolean) {
        agxApplied = applied
        lastSampleMs = Long.MIN_VALUE
    }

    fun setMode(value: ScopeMode) {
        mode = value
        lastSampleMs = Long.MIN_VALUE
    }

    fun setPeakingEnabled(enabled: Boolean) {
        peakingEnabled = enabled
        if (enabled) lastPeakingSampleMs = Long.MIN_VALUE
        publishPeakingActiveIfChanged()
    }

    /** True while the user is focusing: manual distance set or a tap scan/lock in flight. */
    private fun peakingActive(): Boolean =
        FocusPeakingPolicy.shouldShow(
            peakingEnabled,
            host.manualFocusSet(),
            host.touchFocusActive()
        )

    /**
     * Edge-triggered overlay visibility. Call on the camera thread after any
     * mutation of the policy inputs (master switch, manual focus, touch-focus
     * state) so the overlay never sticks on a stale verdict.
     */
    fun publishPeakingActiveIfChanged() {
        val active = peakingActive()
        if (active == lastPeakingActivePublished) return
        lastPeakingActivePublished = active
        if (active) lastPeakingSampleMs = Long.MIN_VALUE
        Log.i(
            LOG_TAG,
            "Focus peaking ${if (active) "SHOWN" else "HIDDEN"} " +
                "(manual=${host.manualFocusSet()} " +
                "touch=${host.touchFocusActive()} enabled=$peakingEnabled)"
        )
        host.onFocusPeakingActive(active)
    }

    fun publishFocusPeakingIfDue(image: Image, cameraCharacteristics: CameraCharacteristics) {
        if (!peakingActive()) return
        val now = SystemClock.elapsedRealtime()
        // The MIN_VALUE sentinel means "sample immediately"; see RawHistogramThrottle.
        if (!RawHistogramThrottle.shouldSample(now, lastPeakingSampleMs, FOCUS_PEAKING_INTERVAL_MS)) return
        lastPeakingSampleMs = now
        guard.run("focus-peaking") {
            RawFocusPeakingSampler.sample(image, cameraCharacteristics)?.let(host::onFocusPeaking)
        }
    }

    /**
     * Sampling a full-resolution Bayer buffer is CPU work.  A live scope only needs a few
     * updates per second, so never perform it for hidden UI and never let it run at RAW-stream
     * frame rate on the camera callback thread.  A saved frame is sampled immediately.
     * The AgX curve LUT tracks the live JPEG sliders; a 1024-entry rebuild per sample is
     * negligible next to the full-resolution Bayer scan.
     */
    fun publishRawScopeIfDue(
        image: Image,
        cameraCharacteristics: CameraCharacteristics,
        force: Boolean = false
    ) {
        if (!histogramEnabled) return
        val now = SystemClock.elapsedRealtime()
        // The MIN_VALUE sentinel means "sample immediately"; see RawHistogramThrottle.
        if (!RawHistogramThrottle.shouldSample(now, lastSampleMs, RAW_HISTOGRAM_INTERVAL_MS, force)) return
        lastSampleMs = now
        val lutStartNs = System.nanoTime()
        val lut = if (agxApplied) lutCache.get(host.jpegOutputSettings()) else null
        // JPEG scope predicts the saved file: the same adaptive EV develop
        // applies pre-tonemap. DNG scopes stay unexposed linear (lut null
        // ignores the gain), matching the linear DNG.
        val scopeEv = if (agxApplied) liveScopeExposureEv() else 0.0
        val scanStartNs = System.nanoTime()
        guard.run("scope") {
            if (mode == ScopeMode.WAVEFORM) {
                RawWaveformSampler.sample(image, cameraCharacteristics, lut, scopeEv)?.let(host::onRawWaveform)
            } else {
                RawHistogramSampler.sample(image, cameraCharacteristics, lut, scopeEv)?.let(host::onRawHistogram)
            }
        }
        noteScopeSample(now, scanStartNs - lutStartNs, System.nanoTime() - scanStartNs)
    }

    /**
     * Scope cost telemetry, same 5 s average discipline as the NEON sampler
     * line: LUT build vs Bayer scan split per scope mode, so a slow scope
     * (waveform vs histogram, AgX LUT on/off) is attributable in field logs.
     * Camera thread only.
     */
    private fun noteScopeSample(nowMs: Long, lutNs: Long, scanNs: Long) {
        lutAccumNs += lutNs
        scanAccumNs += scanNs
        samples++
        if (nowMs - lastScopeLogMs < SCOPE_LOG_INTERVAL_MS || samples == 0) return
        lastScopeLogMs = nowMs
        val lutMs = lutAccumNs / 1_000_000f / samples
        val scanMs = scanAccumNs / 1_000_000f / samples
        Log.i(LOG_TAG, String.format(java.util.Locale.US,
            "Scope sample: avg lut=%.1fms scan=%.1fms over %d frames (%s)",
            lutMs, scanMs, samples, mode.name.lowercase()))
        lutAccumNs = 0L
        scanAccumNs = 0L
        samples = 0
    }

    /**
     * Live adaptive EV the VF is currently rendering with — the same
     * scene-referred correction the save's own dense meter converges to (VF EV
     * == save EV by construction), so the JPEG scope predicts the saved file
     * instead of metering at 0 EV. Zero when the VF isn't rendering JPEG
     * (user-forced RAW VF mode): the scope keeps the unexposed AgX
     * approximation until the saved-frame snapshot lands exact. Snapshot is
     * a volatile-field read, safe from the camera thread at 4 Hz.
     */
    private fun liveScopeExposureEv(): Double {
        val stats = try {
            host.vfStats()
        } catch (_: Exception) {
            null
        } ?: return 0.0
        if (!stats.jpeg) return 0.0
        val ev = stats.exposureEv
        if (!ev.isFinite()) return 0.0
        return ev.toDouble().coerceIn(-8.0, 8.0)
    }

    /**
     * Saved-frame JPEG scope: samples the developed bitmap (the exact pixels
     * the JPEG is encoded from), so the scope matches the saved JPEG the way
     * the linear RAW scope matches the saved DNG. Runs on the saver thread
     * after a successful encode, before the bitmap is recycled; delivery
     * reuses the live scope callbacks (UI-thread downstream).
     */
    fun publishDevelopedScope(bitmap: Bitmap) {
        if (!histogramEnabled) return
        guard.run("developed-scope") {
            val scope = DevelopedScopeSampler.sampleBitmap(bitmap) ?: return@run
            host.onRawHistogram(scope.histogram)
            host.onRawWaveform(scope.waveform)
        }
    }

    /**
     * Saved-frame JXL scope: the half-float developed frame downsampled to
     * 8-bit for the overlay only (the saved JXL keeps full precision), so
     * the scope matches the saved still the way the bitmap scope matches
     * the saved JPEG. Skipped entirely unless the scope overlay is enabled,
     * mirroring [publishDevelopedScope].
     */
    fun publishDevelopedJxlScope(developed: DevelopedJxl) {
        if (!histogramEnabled) return
        guard.run("developed-jxl-scope") {
            val rgba8 = JxlPixelPack.halfRgbaToScopeRgba8(
                developed.halfRgba, developed.width, developed.height
            )
            val bitmap = Bitmap.createBitmap(
                developed.width, developed.height, Bitmap.Config.ARGB_8888
            )
            try {
                bitmap.copyPixelsFromBuffer(rgba8)
                val scope = DevelopedScopeSampler.sampleBitmap(bitmap) ?: return@run
                host.onRawHistogram(scope.histogram)
                host.onRawWaveform(scope.waveform)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        const val RAW_HISTOGRAM_INTERVAL_MS = 250L
        /** Scope cost telemetry cadence: one LUT/scan average line per 5 s. */
        const val SCOPE_LOG_INTERVAL_MS = 5000L
        /** Live peaking verdicts ride the same ~4 Hz cadence as the scope. */
        const val FOCUS_PEAKING_INTERVAL_MS = 250L
    }
}
