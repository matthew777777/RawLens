package com.rawlens.srpipeline.stages

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Per-frame sharpness for down-weighting blurry burst frames (handshake or
 * subject blur in one frame of an otherwise sharp burst).
 *
 * [energy] is the noise-corrected high-frequency energy of the CFA greens:
 * mean over same-colour neighbours two pixels apart of (G1 - G2)^2 minus its
 * expectation under noise alone, 2 (a G + b) (a, b = green noise model, the
 * same normalized units as the frame). Blur removes the signal part only, so
 * the ratio to the reference is a direct measure of lost detail. Every
 * fourth row pair is enough (the statistic averages millions of pairs).
 */
object FrameSharpness {
    /** RGGB-normalised CFA rows. */
    fun energy(f: Array<FloatArray>, a: Float, b: Float, rowStep: Int = 4): Double {
        val h = f.size and 1.inv(); val w = f[0].size and 1.inv()
        var s = 0.0; var noise = 0.0; var n = 0L
        var y = 0
        while (y + 1 < h) {
            val r0 = f[y]; val r1 = f[y + 1]
            // G at (y, odd x) and (y+1, even x): horizontal pairs 2 apart.
            var x = 1
            while (x + 2 < w) {
                val g0 = r0[x]; val g1 = r0[x + 2]
                val d = (g1 - g0).toDouble()
                if (!d.isNaN()) { s += d * d; noise += 2.0 * (a * max(0f, 0.5f * (g0 + g1)) + b); n++ }
                val h0 = r1[x - 1]; val h1 = r1[x + 1]
                val e = (h1 - h0).toDouble()
                if (!e.isNaN()) { s += e * e; noise += 2.0 * (a * max(0f, 0.5f * (h0 + h1)) + b); n++ }
                x += 2
            }
            y += 2 * rowStep
        }
        if (n == 0L) return 0.0
        return max(0.0, (s - noise) / n)
    }

    /**
     * Frame weight from its energy relative to the reference's:
     * min(1, e / eRef)^[gamma]. Frames at least as sharp as the reference keep
     * weight 1; a frame with half the detail energy gets 0.5^gamma.
     */
    fun weight(e: Double, eRef: Double, gamma: Float): Float =
        if (eRef <= 0.0 || e.isNaN()) 1f else min(1.0, e / eRef).pow(gamma.toDouble()).toFloat()
}
