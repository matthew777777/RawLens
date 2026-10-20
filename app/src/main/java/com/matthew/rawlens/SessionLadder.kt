// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Camera open / session fallback state: which stream combo and which route
 * (primary or direct-physical) the next session uses, which open IDs failed,
 * and the bounded reopen budgets. State and transitions only — the
 * controller logs, restarts and publishes status. Extracted from
 * RawCameraController; see docs/refactor/session-study.md.
 *
 * HALs that reject the full RAW+preview combo (Xiaomi 14 Ultra rejects it
 * async on every route) retry with degraded combos and, for vendor
 * composites, a direct-physical route instead of wedging on SESSION ERROR.
 * Sticky per lens so a reopen goes straight to the winning combo; reset only
 * when the user changes lens. Camera thread only.
 */
internal class SessionLadder {
    /** Index into the session's ordered stream combos (0 = full combo). */
    var comboIndex = 0

    /** The direct-physical route of a vendor composite is in use. */
    var altRouteActive = false
        private set

    private var lensId: String? = null

    /** Open IDs that failed this lens's ladder (cleared on lens change). */
    private val failedOpenIds = mutableSetOf<String>()

    /**
     * Open IDs the camera service rejected as unknown devices. Unlike the
     * per-lens failed set, this denylist lives with the controller: rejection
     * is deterministic per open ID, so a lens that resolves characteristics
     * yet refuses CameraManager.openCamera stays excluded until the user
     * re-runs lens discovery.
     */
    private val unopenable = mutableSetOf<String>()

    /** Open IDs that opened successfully at least once (controller lifetime). */
    private val openedOnce = mutableSetOf<String>()

    /** Consumed reopen attempts for transient route resolution. */
    private var routeRetries = 0

    /** Consumed reopen attempts after open rejections. */
    private var rejectionRetries = 0

    val unopenableOpenIds: Set<String> get() = unopenable
    val openedOnceOpenIds: Set<String> get() = openedOnce

    /** Lens about to open; a different lens restarts the ladder. */
    fun selectLens(id: String) {
        if (id == lensId) return
        lensId = id
        reset()
    }

    fun reset() {
        comboIndex = 0
        altRouteActive = false
        failedOpenIds.clear()
        routeRetries = 0
        rejectionRetries = 0
    }

    /** Camera start: a fresh lifecycle gets a fresh route-retry budget. */
    fun resetRouteRetries() {
        routeRetries = 0
    }

    fun onOpened(openId: String) {
        routeRetries = 0
        rejectionRetries = 0
        openedOnce += openId
    }

    fun recordFailedOpen(openId: String) {
        failedOpenIds += openId
    }

    fun hasFailed(openId: String?): Boolean = openId != null && openId in failedOpenIds

    /** The open ID refused and the lens's failed IDs join the denylist. */
    fun denylist(openId: String) {
        unopenable += openId
        unopenable += failedOpenIds
    }

    /** Explicit lens re-discovery re-probes every saved lens. */
    fun clearDenylist() {
        unopenable.clear()
    }

    fun altRouteAvailable(altOpenId: String?): Boolean =
        !altRouteActive && altOpenId != null && altOpenId !in failedOpenIds

    /** First rung for vendor composites: direct-physical route, full combo. */
    fun switchToAltRoute() {
        altRouteActive = true
        comboIndex = 0
    }

    /** True when the primary route can still drop to a smaller combo. */
    fun canDegrade(primaryOpenId: String?, comboCount: Int): Boolean =
        !hasFailed(primaryOpenId) && comboIndex + 1 < comboCount

    /**
     * Next combo on the primary route. A failed direct-physical full combo
     * falls back to the primary route's degraded combos; the alternate
     * never degrades sizes. Returns the new index.
     */
    fun degrade(): Int {
        if (altRouteActive) altRouteActive = false
        return ++comboIndex
    }

    /** Consumes one route-resolution retry; null when the budget is spent. */
    fun takeRouteRetry(max: Int): Int? =
        if (routeRetries >= max) null else ++routeRetries

    /** Consumes one open-rejection retry; null when the budget is spent. */
    fun takeRejectionRetry(max: Int): Int? =
        if (rejectionRetries >= max) null else ++rejectionRetries
}
