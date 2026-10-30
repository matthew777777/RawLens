// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * Session recovery decisions: stillborn-stream ladder steps, compat-session
 * escalation, the GPU-sampled reader trial, and open-rejection lens fallback.
 *
 * Moved verbatim from RawCameraController's companion (A10,
 * docs/refactor/controller-remaining-plan.md).
 */
internal object SessionRecoveryPolicy {
    /** Request-level recoveries before a stillborn stream rebuilds the session. */
    internal const val RAW_PREVIEW_COMPAT_ESCALATION_FAILURES = 1

    /** Gralloc producer bit (GRALLOC_USAGE_HW_CAMERA_WRITE) the HAL adds at allocation. */
    internal const val GRALLOC_USAGE_HW_CAMERA_WRITE = 0x20000L

    /**
     * Stillborn-stream escalation: request-level RAW preview recovery already
     * had its chance ([RAW_PREVIEW_COMPAT_ESCALATION_FAILURES] attempts) and
     * zero repeating buffers arrived, so only a session rebuild can help.
     * Fires at most once: compat mode sticks once engaged.
     */
    internal fun shouldEscalateRawPreviewToCompatSession(
        failures: Int,
        deliveredImages: Int,
        compatMode: Boolean
    ): Boolean = !compatMode && deliveredImages == 0 &&
        failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES

    /**
     * GPU-reader trial disarm: the RAW reader trial with GPU-sampled
     * usage delivered zero buffers after request-level recovery had its
     * chance, so the allocation itself starves this HAL (MT6878: the
     * session configures, zero images arrive). Disarm once and rebuild
     * with the default CPU-only reader; the first delivered image of
     * the disarmed session persists the verdict, so later cold starts
     * skip the stillborn attempt entirely. Fires before the compat
     * escalation: the usage axis resolves before the plan axis.
     */
    internal fun shouldDisarmVfGpuReaderTrial(
        failures: Int,
        deliveredImages: Int,
        trialArmed: Boolean
    ): Boolean = trialArmed && deliveredImages == 0 &&
        failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES

    /**
     * Allocator probe usage for the GPU-reader trial: exactly the trial
     * reader usage plus the camera-write producer bit, so a refusal
     * means the trial allocation itself is unbackable (a looser
     * superset would risk false refusals that wrongly disarm a working
     * trial). No GPU_DATA_BUFFER: unproven need, extra refusal surface.
     */
    internal fun gpuReaderProbeUsage(): Long =
        HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            GRALLOC_USAGE_HW_CAMERA_WRITE

    /**
     * Stillborn-in-compat escalation: the compat rebuild already proved
     * request toggles cannot revive this session, so a RAW stream that
     * still delivers zero buffers advances the session fallback ladder
     * (direct-physical route, then degraded combos) instead of parking
     * on a black viewfinder. Fires only after the same cheap
     * request-level retry the compat escalation allows, and never while
     * buffers flow (a rendering problem) or past the last combo.
     */
    internal fun shouldAdvanceStillbornSessionLadder(
        failures: Int,
        deliveredImages: Int,
        compatMode: Boolean,
        altRouteAvailable: Boolean,
        comboIndex: Int,
        comboCount: Int
    ): Boolean = compatMode && deliveredImages == 0 &&
        failures >= RAW_PREVIEW_COMPAT_ESCALATION_FAILURES &&
        (altRouteAvailable || comboIndex + 1 < comboCount)

    /**
     * Next-lens fallback after [android.hardware.camera2.CameraManager.openCamera]
     * rejects an open ID as an unknown device. Rejection is deterministic
     * per open ID, so every candidate whose route opens through a rejected
     * ID is skipped together (composite siblings sharing one logical
     * camera die with it); unresolvable candidates are skipped as well.
     * Null means no fallback remains and the open must surface a terminal
     * state instead of retrying.
     */
    internal fun nextLensAfterOpenRejected(
        candidates: List<String>,
        rejectedOpenIds: Set<String>,
        openIdOf: (String) -> String?
    ): String? = candidates.firstOrNull { id ->
        val openId = openIdOf(id)
        openId != null && openId !in rejectedOpenIds
    }

    /**
     * A rejection is only denylist-worthy when the ID never opened: an ID
     * that opened before and now reports unknown-device means the camera
     * service hiccuped (in the field log every lens failed within 2 ms),
     * so the caller retries instead of excluding a healthy lens.
     *
     * The same holds for any ID shortly after a device error or disconnect
     * ([msSinceDeviceFault] below [HAL_RESTART_GRACE_MS]): the camera
     * provider is restarting and reports every ID unknown, including ones
     * this run never opened (field log 2026-10-29: a HAL fault on 6/3, then
     * 9 rejected 0.4 s later and persisted DEAD for every 9/x route).
     */
    internal fun shouldDenylistOnOpenRejected(
        openId: String,
        openedOnceOpenIds: Set<String>,
        msSinceDeviceFault: Long? = null
    ): Boolean {
        if (msSinceDeviceFault != null && msSinceDeviceFault in 0 until HAL_RESTART_GRACE_MS) return false
        return openId !in openedOnceOpenIds
    }

    /** Window after a device error/disconnect in which open rejections are transient. */
    internal const val HAL_RESTART_GRACE_MS = 15_000L
}
