// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Last AgX scope LUT, rebuilt only when the JPEG look changes. The LUT is a
 * pure function of [JpegOutputSettings] (a data class, compared by value), and
 * the scope samplers only read it, so a cached copy is bit-identical to a
 * fresh build. Rebuilding it on every 250 ms scope sample cost ~2 ms of
 * camera-thread time on Xiaomi 25080RABDG (docs/refactor/AUDIT.md).
 * Camera-thread only.
 */
internal class ScopeLutCache(
    private val build: (JpegOutputSettings) -> FloatArray = { AgxDisplayTransform.buildScopeLut(it) },
) {
    private var settings: JpegOutputSettings? = null
    private var lut: FloatArray? = null
    private var exposureEv = 0.0

    fun get(current: JpegOutputSettings, ev: Double = 0.0): FloatArray {
        val safeEv = if (ev.isFinite()) ev.coerceIn(-8.0, 8.0) else 0.0
        val cached = lut
        if (cached != null && current == settings && safeEv == exposureEv) return cached
        return (if (safeEv == 0.0) build(current) else
            AgxDisplayTransform.buildScopeLut(current, exposureEv = safeEv)).also {
            exposureEv = safeEv
            settings = current
            lut = it
        }
    }
}
