// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image

/**
 * Viewfinder engine seam: everything the camera controller, recorders, and
 * activity need from a RAW viewfinder, implemented by the legacy
 * [RawViewfinder] and the from-scratch BGU [BguViewfinder]. Engine selection
 * ([VfEngineMode]) lives in the controller; engines only render.
 *
 * Threading mirrors the legacy contract: [offer] runs inline on the camera
 * handler (no [Image] retention — all plane access ends before return);
 * setters are safe from any thread; [snapshot] is safe from any thread.
 */
interface VfEngine {
    /** Called inline on the camera handler. All plane access ends before return. */
    fun offer(image: Image, c: CameraCharacteristics, result: CaptureResult?)

    /** Drop all session state (queued frames, caches, stickiness). Any thread. */
    fun invalidateSession()

    /** Fired when the engine starves (no presentable frames). */
    var onStarvation: (() -> Unit)?

    /** Direct-Log record throttle. Any thread. */
    fun setRecordMode(active: Boolean)

    /** Sensor cadence hint for the starvation watchdog. Any thread. */
    fun expectFrameInterval(nanos: Long)

    /** Guide long edge (see [VfResolution]). Applies to the next frame. Any thread. */
    fun setTargetLongEdge(longEdge: Int)

    /**
     * Legacy-tier override. The BGU engine accepts but ignores this (single
     * render path); the controller maps the global mode before calling.
     * Any thread.
     */
    fun setEngineMode(mode: VfEngineMode)

    /** Live-link AgX sliders. Only volatile writes; applies to the next frame. Any thread. */
    fun setAgx(settings: JpegOutputSettings)

    /** Adaptive preview-EV strength (same rule as saves). Any thread. */
    fun setPreviewExposureStrength(strength: Float)

    /** Switch the tonemap without touching the stream. Any thread. */
    fun setRenderJpeg(jpeg: Boolean)

    /** System battery-saver throttle. Any thread. */
    fun setPowerSave(active: Boolean)

    /** Latest stats for the debug overlay. Any thread. */
    fun snapshot(): RawVfStats

    /** Tear down workers and GL state. Must survive repeated calls. */
    fun dispose()
}
