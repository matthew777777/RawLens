// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow

/**
 * Conditioning of the BGU regression input, not a display transform: encode
 * merged, normalized sensor RGB before both fitting and applying the matrix.
 * The developed target stays unchanged. This avoids spending a coarse affine
 * grid on the singular dark-end slope of linear -> encoded display values.
 * Standard sRGB OETF; same-RAW evidence and alternatives: bgu-study.md.
 */
internal object BguGuideEncoding {
    fun encode(linear: Float): Float =
        if (linear <= 0.0031308f) 12.92f * linear
        else 1.055f * linear.pow(1f / 2.4f) - 0.055f
}
