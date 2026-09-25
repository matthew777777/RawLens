// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Reference-anchored highlight rolloff, shared by linear and mosaic SR.
 * Equal camera RGB is NOT white before AsShotNeutral is applied. Resolve
 * censored chroma toward the camera neutral, with one mask for all colours.
 * A 3x3 footprint covers every Bayer colour and the merge's censored support;
 * testing only the site's own colour left Bayer-shaped holes and crosses.
 */
object RawSrHighlights {
    /**
     * Rolloff starts at the censor boundary itself: only neighbourhoods
     * containing a truly censored tap whiten. Fused near-white detail
     * (0.95-0.99 — lamp halos, water glints, bright texture) keeps its
     * colour; starting the ramp below the guard bloomed every such tap
     * into a 3x3 white square.
     */
    const val ROLLOFF_START = 0.99
    const val ROLLOFF_END = 1.0

    fun neutral(metadata: RawFrameMetadata): FloatArray =
        normalizeNeutral(metadata.neutralColorPoint?.toDoubleArray())

    fun normalizeNeutral(values: DoubleArray?): FloatArray {
        if (values == null || values.size != 3 || values.any { !it.isFinite() || it <= 0.0 })
            return floatArrayOf(1f, 1f, 1f)
        val peak = values.maxOrNull()!!
        // Keep the neutral ray within the writer's [0,1] range. Independent
        // quantizer clipping would otherwise introduce colour again.
        return FloatArray(3) { (values[it] / peak).toFloat() }
    }

    fun peak(samples: FloatArray, width: Int, height: Int, x: Int, y: Int): Double {
        var peak = 0.0
        for (sy in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
            for (sx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                val value = samples[sy * width + sx].toDouble()
                if (value.isFinite()) peak = maxOf(peak, value)
            }
        }
        return peak
    }

    fun amount(peak: Double): Double {
        if (!peak.isFinite()) return 0.0
        val t = ((peak - ROLLOFF_START) /
            (ROLLOFF_END - ROLLOFF_START)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun resolve(value: Double, peak: Double, neutral: Float): Double {
        val amount = amount(peak)
        val finite = if (value.isFinite()) value else 0.0
        if (amount == 0.0) return finite
        return finite * (1.0 - amount) + neutral * peak.coerceIn(0.0, 1.0) * amount
    }

    fun channel(color: CfaColor): Int = when (color) {
        CfaColor.RED -> 0
        CfaColor.GREEN -> 1
        CfaColor.BLUE -> 2
    }
}
