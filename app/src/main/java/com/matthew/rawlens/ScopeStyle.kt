// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Shared histogram/waveform scope rendering, following darktable's scopes module
 * (GPL-3.0-or-later, darktable-org/darktable `src/libs/scopes/histogram.c`,
 * `src/libs/scopes/waveform.c`, theme `data/themes/darktable.css`):
 *
 * - Plot background `graph_bg` #262626, grid `graph_grid` #111111.
 * - Primaries `graph_red/green/blue`: rgb(237,30,20), rgb(28,235,26),
 *   rgb(14,14,233). Each channel sums to 279, so ADD-blended overlaps clip to a
 *   neutral white instead of tinting (per the theme's own comment). The histogram
 *   uses these; the waveform ADD pass uses pure primaries, exactly like darktable.
 * - Waveform density is area-normalized (`brightness / samplesPerColumn`, with
 *   `brightness = tones / 40`), never peak-normalized, so a thin trace stays
 *   visible no matter how hot the densest cell is. Density is then perceptually
 *   lifted with the HLG OETF (darktable borrows the HLG Rec.2020 LUT) and
 *   composited in two passes: ADD pure primaries at 0.75, then HARD_LIGHT
 *   near-white channel tints at 0.35.
 *
 * Deviations are deliberate and documented at the use site: darktable maps 1.0 to
 * 8/9 height for HDR headroom, but scope data here never exceeds 1.0, so 1.0 is
 * the top; the histogram keeps a white luminance trace darktable does not draw.
 */
object ScopeStyle {
    const val BACKGROUND = 0xFF262626.toInt()
    const val GRID = 0xFF111111.toInt()

    const val RED = 0xFFED1E14.toInt()
    const val GREEN = 0xFF1CEB1A.toInt()
    const val BLUE = 0xFF0E0EE9.toInt()

    /** Waveform ADD pass alpha (`alpha_chroma`). */
    const val WAVEFORM_CHROMA_ALPHA = 0.75f

    /** Waveform HARD_LIGHT pass alpha (`alpha_over`). */
    const val WAVEFORM_OVER_ALPHA = 0.35f

    /** HARD_LIGHT tint for the two off-channels (`desat_over`). */
    const val WAVEFORM_DESAT_OVER = 0.75f

    /** Histogram group alpha: darktable paints the ADD group at 0.5. */
    const val HISTOGRAM_GROUP_ALPHA = 128

    /** Area-bias divisor shared with darktable (`num_tones / 40`). */
    const val BRIGHTNESS_DIVISOR = 40f

    val PRIMARIES = intArrayOf(RED, GREEN, BLUE)

    /**
     * Approximate HLG OETF (ARIB STD-B67 reference), standing in for darktable's
     * borrowed HLG Rec.2020 LUT. Maps linear density 0..1 to a perceptual mask.
     */
    fun hlgOetf(linear: Float): Float {
        val x = linear.coerceIn(0f, 1f)
        return if (x <= 1f / 12f) {
            kotlin.math.sqrt(3f * x)
        } else {
            (HLG_A * kotlin.math.ln(12f * x - HLG_B) + HLG_C).toFloat()
        }
    }

    /**
     * Area-based density scale: expected samples per column over [columns], so a
     * uniformly spread column lands dim and a concentrated trace lands bright,
     * independent of the frame's hottest cell.
     */
    fun areaScale(levels: Int, columns: Int, channelSamples: Int): Float {
        if (channelSamples <= 0 || columns <= 0) return 0f
        val brightness = levels / BRIGHTNESS_DIVISOR
        return brightness * columns / channelSamples
    }

    /**
     * One darktable-style waveform pixel. [masks] holds the three HLG-encoded
     * densities 0..1; returns opaque sRGB over [BACKGROUND].
     */
    fun compositeWaveformPixel(redMask: Float, greenMask: Float, blueMask: Float): Int {
        var r = BG_LINEAR
        var g = BG_LINEAR
        var b = BG_LINEAR
        val masks = floatArrayOf(redMask, greenMask, blueMask)
        for (channel in 0..2) {
            val mask = masks[channel].coerceIn(0f, 1f)
            if (mask <= 0f) continue
            // Pass 1: ADD pure primary at alpha_chroma, masked by density.
            val add = WAVEFORM_CHROMA_ALPHA * mask
            if (channel == 0) r += add else if (channel == 1) g += add else b += add
            // Pass 2: HARD_LIGHT near-white channel tint at alpha_over.
            // All tint stops sit above 0.5, so only the upper branch applies.
            val tint = floatArrayOf(WAVEFORM_DESAT_OVER, WAVEFORM_DESAT_OVER, WAVEFORM_DESAT_OVER)
            tint[channel] = 1f
            val mix = WAVEFORM_OVER_ALPHA * mask
            r += (hardLightUp(r, tint[0]) - r) * mix
            g += (hardLightUp(g, tint[1]) - g) * mix
            b += (hardLightUp(b, tint[2]) - b) * mix
        }
        // Manual ARGB packing keeps this object pure JVM (unit-testable without Robolectric).
        val rq = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gq = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bq = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rq shl 16) or (gq shl 8) or bq
    }

    private fun hardLightUp(dst: Float, src: Float): Float = 1f - 2f * (1f - src) * (1f - dst)

    private const val BG_LINEAR = 0x26 / 255f
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073
}
