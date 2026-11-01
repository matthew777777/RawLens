// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Derived EXIF identity, following PhotonCamera's device / camera type / optics format. */
internal object LensExifIdentity {
    fun make(manufacturer: String?): String? = ascii(manufacturer)

    // Use optical field of view, independent of digital zoom or a development crop.
    fun model(device: String?, focalMm: Float?, sensorWidthMm: Float?, sensorHeightMm: Float?, aperture: Float?): String? {
        val name = ascii(device) ?: return null
        val focal = positive(focalMm) ?: return null
        val width = positive(sensorWidthMm) ?: return null
        val height = positive(sensorHeightMm) ?: return null
        val fNumber = positive(aperture) ?: return null
        val equivalent = focal.toDouble() * hypot(36.0, 24.0) / hypot(width.toDouble(), height.toDouble())
        if (!equivalent.isFinite() || equivalent < 1 || equivalent > 65535) return null
        val mm = equivalent.roundToInt()
        val type = when {
            mm <= 18 -> "ultra wide camera"
            mm < 40 -> "wide camera"
            mm < 150 -> "telephoto camera"
            else -> "ultra telephoto camera"
        }
        return String.format(Locale.US, "%s %s %dmm f/%.1f", name, type, mm, fNumber)
    }

    private fun positive(value: Float?) = value?.takeIf { it.isFinite() && it > 0f }
    private fun ascii(value: String?) = value?.takeIf {
        it.all { character -> character.code in 32..126 }
    }?.trim()?.takeIf { it.isNotEmpty() }
}
