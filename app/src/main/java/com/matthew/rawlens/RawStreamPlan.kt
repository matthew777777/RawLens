// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Which RAW consumers the next repeating request feeds. Pure decision behind
 * RawCameraController.updateRepeatingRequest (moved verbatim); the controller
 * builds and submits the requests. Every repeating RAW request uses
 * TEMPLATE_PREVIEW with both targets, as MotionCam's repeat request does
 * (CameraSession.cpp, TEMPLATE_PREVIEW + preview and RAW outputs).
 */
internal data class RawStreamPlan(
    val zsl: Boolean,
    val histogram: Boolean,
    val ettr: Boolean,
    val program: Boolean,
    val viewfinder: Boolean,
) {
    /** Any consumer needs the RAW target on the repeating request. */
    val anyRaw: Boolean get() = viewfinder || zsl || histogram || ettr || program

    /** Controller state the plan reads; all values are a snapshot of one moment. */
    data class Inputs(
        val allowRawZsl: Boolean,
        val zslRestartFitsReader: Boolean,
        val zslRequested: Boolean,
        val zslBlocked: Boolean,
        val zslCapacity: Int,
        val readerPresent: Boolean,
        /** RAW-preview retry backoff has expired. */
        val rawRetryReady: Boolean,
        val captureInProgress: Boolean,
        /** A still sequence is physically executing (not merely reserved). */
        val stillExecuting: Boolean,
        val histogramEnabled: Boolean,
        val histogramDisabledForSession: Boolean,
        val ettrEnabled: Boolean,
        val ettrModeAvailable: Boolean,
        val manualIsoOrShutter: Boolean,
        val exposureModeManual: Boolean,
        val programCustomActive: Boolean,
        val hasRawViewfinder: Boolean,
    )

    companion object {
        fun plan(i: Inputs): RawStreamPlan {
            val readerReady = i.zslCapacity > 0 && i.readerPresent && i.rawRetryReady
            // Split gate: a reserved capture slot (ZSL burst being assembled in the
            // retry wait) must NOT block the ZSL ring itself; only a physically
            // executing still sequence blocks it, since interleaved repeating RAW
            // frames would steal reader slots from the in-flight still captures.
            // Metering streams keep the strict gate: they stay down across any held
            // capture so forward captures keep full reader headroom.
            val zsl = i.allowRawZsl && i.zslRestartFitsReader && run(
                i.zslRequested, i.zslBlocked, readerReady, i.captureInProgress && i.stillExecuting
            )
            // Live RAW scope for every capture mode. Never competes with the ZSL
            // ring: scope-only frames are sampled and closed.
            val histogram = !zsl && run(
                i.histogramEnabled, i.histogramDisabledForSession, readerReady, i.captureInProgress
            )
            // RAW ETTR needs the same metering stream even when the histogram is
            // off; AUTO-only, and manual exposure wins.
            val ettr = !zsl && i.ettrEnabled && i.ettrModeAvailable &&
                run(true, false, readerReady, i.captureInProgress) &&
                !i.manualIsoOrShutter && !i.exposureModeManual
            // PROGRAM custom AE meters from the same stream; ZSL is exclusive.
            val program = !zsl && i.programCustomActive &&
                run(true, false, readerReady, i.captureInProgress)
            // WYSIWYG preview renders the scene-referred pipeline from the RAW
            // feed in every mode; never ISP YUV.
            val viewfinder = i.hasRawViewfinder && i.readerPresent && i.rawRetryReady
            return RawStreamPlan(zsl, histogram, ettr, program, viewfinder)
        }

        private fun run(
            requested: Boolean,
            disabledForSession: Boolean,
            readerReady: Boolean,
            captureActive: Boolean,
        ): Boolean = RawCameraController.shouldRunRawStream(
            requested, disabledForSession, readerReady, captureActive
        )
    }
}
