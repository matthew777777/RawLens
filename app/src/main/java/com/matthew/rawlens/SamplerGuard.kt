// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log

/**
 * Live RAW samplers (scope, peaking, metering) are best-effort per-frame work
 * on camera/metering threads, where an uncaught throwable kills the app — a
 * NoSuchMethodError in the histogram sampler did exactly that on API 30.
 * Never let one bad frame take down the session; skip it and log (throttled).
 * The camera thread and the metering executor share one guard.
 */
internal class SamplerGuard {
    /** Last sampler-failure log. */
    @Volatile private var lastErrorLogMs = Long.MIN_VALUE

    inline fun <T> run(tag: String, block: () -> T?): T? {
        return try {
            block()
        } catch (t: Throwable) {
            logFailure(tag, t)
            null
        }
    }

    @PublishedApi internal fun logFailure(tag: String, t: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (lastErrorLogMs == Long.MIN_VALUE ||
            now - lastErrorLogMs >= ERROR_LOG_INTERVAL_MS
        ) {
            lastErrorLogMs = now
            Log.e(LOG_TAG, "RAW $tag sample failed; frame skipped", t)
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        /** Sampler-failure log cadence: a sick sampler must not flood logcat. */
        const val ERROR_LOG_INTERVAL_MS = 5000L
    }
}
