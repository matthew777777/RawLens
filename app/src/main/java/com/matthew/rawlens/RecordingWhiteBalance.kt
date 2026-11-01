// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Optional camera metadata must never turn a valid RAW frame into a failed recording. */
internal object RecordingWhiteBalance {
    data class Selection(val neutral: DoubleArray?, val source: String)

    fun select(neutral: DoubleArray?, gains: FloatArray?, estimate: () -> DoubleArray?): Selection {
        fun valid(n: DoubleArray?) = n != null && n.size == 3 && n.all { it.isFinite() && it > 1e-6 }
        if (valid(neutral)) return Selection(neutral!!.copyOf(), "camera")
        if (!RawWhiteBalanceFallback.halAwbMissing(neutral, gains) && gains != null) {
            val green = (gains[1].toDouble() + gains[2].toDouble()) / 2
            val derived = doubleArrayOf(green / gains[0], 1.0, green / gains[3])
            if (valid(derived)) return Selection(derived, "cameraGains")
        }
        val sampled = estimate()
        return if (valid(sampled)) Selection(sampled!!.copyOf(), "rawEstimate")
        else Selection(null, "unavailable")
    }
}
