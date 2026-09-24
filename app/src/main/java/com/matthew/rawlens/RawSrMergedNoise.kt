// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Merged noise model (Sabre `GetMergedNoiseModel(dng_noise_model_bayer)`
 * analogue).
 *
 * Averaging N independent samples scales both the shot-noise slope and the
 * read-noise offset by 1/N. A merged burst DNG that carries the reference
 * single-frame profile therefore overstates its noise, and downstream
 * converters over-blur it. This object derives the merged profile from the
 * measured per-quad support (1 + Rc), which already folds alignment
 * robustness, hot-pixel rails, and unblocker attenuation.
 *
 * The DNG NoiseProfile tag is global per plane, so the per-quad support
 * collapses to its mean — a uniform-gain approximation, documented here and
 * recorded per file in the provenance `effectiveFrames` key. Pure
 * functions; no camera, GL, or filesystem access.
 */
object RawSrMergedNoise {
    /**
     * Mean per-quad support (1 + Rc) over a robustness grid. Non-finite
     * quads contribute 1 (no measured support, never a fabricated gain).
     */
    fun meanSupport(rc: FloatArray): Double {
        require(rc.isNotEmpty()) { "Support grid must not be empty" }
        var sum = 0.0
        for (v in rc) {
            val s = 1.0 + v.toDouble()
            sum += if (s.isFinite()) s else 1.0
        }
        return sum / rc.size
    }

    /**
     * Effective merged frame count from measured mean support. Broken
     * measurements (non-finite) and sub-unity support both resolve to 1 —
     * the unscaled reference profile, i.e. exactly today's behaviour, never
     * an amplified profile.
     */
    fun effectiveFrames(meanSupport: Double): Double =
        if (!meanSupport.isFinite()) 1.0 else maxOf(meanSupport, 1.0)

    /**
     * Scale a sensor noise profile by the merged frame count (S/N and O/N
     * per plane). Accepts the same 8/6-coefficient camera2 forms as
     * [DngNoiseProfile] and always returns the 6-value RGB-plane form the
     * DNG tag carries. Returns null — the tag is omitted, never fabricated —
     * when the input profile is null, invalid, or zero-noise, or when the
     * frame count is not measurable.
     */
    fun scaleProfile(
        values: DoubleArray?,
        pattern: BayerPattern,
        effectiveFrames: Double
    ): DoubleArray? {
        val n = effectiveFrames
        if (!n.isFinite() || n < 1.0) return null
        val rgb = DngNoiseProfile.toRgb(values, pattern) ?: return null
        return DoubleArray(6) { rgb[it] / n }
    }
}
