// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** A photo LUT and the adjustments applied to its rendered color. */
data class PhotoLutLook(
    val cube: CubeLut,
    val adjustments: PhotoLutAdjustments = PhotoLutAdjustments(),
) {
    val title: String get() = cube.title
}

/** Signed slider values in [-100, 100], with zero preserving the original look. */
data class PhotoLutAdjustments(
    val saturation: Int = 0,
    val contrast: Int = 0,
    val shadows: Int = 0,
    val highlights: Int = 0,
    val warmth: Int = 0,
    val tint: Int = 0,
    val vibrance: Int = 0,
)
