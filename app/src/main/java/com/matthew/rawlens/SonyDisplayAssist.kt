// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Technical inverse of I1 followed by the existing BT.709 encode, no creative look. */
internal object SonyDisplayAssist {
    fun apply(encoded: FloatArray, intensity: Float = 1f): FloatArray {
        require(encoded.size == 3 && encoded.all { it.isFinite() })
        require(intensity.isFinite() && intensity in 0f..1f)
        val linear = SonyGamut.toLinearSrgb(FloatArray(3) { VfLogGrade.invSonySlog3(encoded[it]) })
        return FloatArray(3) { encoded[it] + intensity * (VfLogGrade.bt709Oetf(linear[it]) - encoded[it]) }
    }
}
