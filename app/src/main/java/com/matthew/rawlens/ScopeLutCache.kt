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

    fun get(current: JpegOutputSettings): FloatArray {
        val cached = lut
        if (cached != null && current == settings) return cached
        return build(current).also {
            settings = current
            lut = it
        }
    }
}
