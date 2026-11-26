// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import java.io.Closeable

/**
 * Depth and person networks for portrait mode, on square display-encoded RGB
 * (person: [PortraitDepth.NET_SIZE]^2) (row-major, 0..1). The runtime is distribution-specific
 * (see PortraitNetsFactory in the github / fdroid source sets).
 */
interface PortraitNets : Closeable {
    val description: String
    /** Square input/output side of [depth]. */
    val depthSize: Int get() = PortraitDepth.NET_SIZE
    /** Relative inverse depth, bigger = nearer, on [depthSize]^2 RGB. */
    fun depth(rgb: FloatArray): FloatArray
    /** Person probability 0..1, or null when this runtime has no person model. */
    fun person(rgb: FloatArray): FloatArray?
}

/**
 * Process-wide, lazily created networks: NNAPI compilation costs seconds, so
 * they outlive [RawDevelopmentCoordinator] (closed before merges to free memory).
 */
object PortraitNetsHolder {
    @Volatile private var nets: PortraitNets? = null
    @Volatile private var failed = false

    @Synchronized
    fun get(context: Context): PortraitNets? {
        nets?.let { return it }
        if (failed || !PortraitSettings.AVAILABLE) return null
        return runCatching { PortraitNetsFactory.create(context.applicationContext) }
            .onFailure { Log.w("RawLensPortrait", "Portrait networks unavailable", it) }
            .getOrNull()
            .also { nets = it; failed = it == null }
    }

    fun available(context: Context): Boolean =
        PortraitSettings.AVAILABLE && PortraitNetsFactory.available(context.applicationContext)
}
