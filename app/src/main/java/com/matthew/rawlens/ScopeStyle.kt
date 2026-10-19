// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Shared histogram/waveform scope rendering.
 *
 * The data pipeline follows darktable's scopes module (GPL-3.0-or-later,
 * darktable-org/darktable `src/libs/scopes/histogram.c`,
 * `src/libs/scopes/waveform.c`): area-normalized waveform density
 * (`brightness / samplesPerColumn`, with `brightness = tones / 40`) lifted
 * perceptually with the HLG OETF, composited in two passes (ADD pure
 * primaries, then HARD_LIGHT near-white channel tints).
 *
 * The presentation is RawLens' own modern treatment, not darktable's theme
 * (`graph_bg` #262626, `graph_grid` #111111): the scope is a near-opaque
 * dark pill floating over the viewfinder, with no grid graticule — just a
 * hairline baseline anchoring the bottom edge. The scrim stays near-opaque
 * deliberately so traces stay legible in direct sunlight; a sheer glass
 * bleeds the bright preview through and washes out. Traces use SRC_OVER
 * primaries at full stroke strength tuned for dark glass, so overlaps mix
 * naturally instead of relying on saturating ADD to clip to neutral white.
 */
object ScopeStyle {
    /** Near-opaque scrim (black at ~90%): sunlight-legible, still borderless. */
    const val SCRIM = 0xE6000000.toInt()

    /** Hairline baseline anchoring the bottom edge (white at ~20%). */
    const val BASELINE = 0x33FFFFFF

    /** Single-piece scope surface: same 20dp radius + stroke as the top/bottom islands. */
    const val CORNER_RADIUS_DP = 20f
    const val BORDER = 0x4DFFFFFF
    const val BORDER_WIDTH_DP = 1f

    /** Modern primaries tuned for dark glass (sRGB). */
    const val RED = 0xFFFF6B62.toInt()
    const val GREEN = 0xFF3DDC84.toInt()
    const val BLUE = 0xFF5AA9FF.toInt()

    /** Waveform ADD pass alpha (`alpha_chroma`). */
    const val WAVEFORM_CHROMA_ALPHA = 0.75f

    /** Waveform HARD_LIGHT pass alpha (`alpha_over`). */
    const val WAVEFORM_OVER_ALPHA = 0.35f

    /** HARD_LIGHT tint for the two off-channels (`desat_over`). */
    const val WAVEFORM_DESAT_OVER = 0.75f

    /** Histogram area-fill alpha (SRC_OVER, per channel). */
    const val HISTOGRAM_FILL_ALPHA = 90

    /** Histogram trace-line alpha (SRC_OVER, per channel). */
    const val HISTOGRAM_LINE_ALPHA = 255

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
     * One waveform pixel composited over transparency. [masks] holds the three
     * HLG-encoded densities 0..1; empty cells are fully transparent so the
     * scrim shows through, lit cells fade in with density. Returns ARGB.
     */
    fun compositeWaveformPixel(redMask: Float, greenMask: Float, blueMask: Float): Int {
        var r = 0f
        var g = 0f
        var b = 0f
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
        val peak = maxOf(masks[0], masks[1], masks[2])
        if (peak <= 0f) return 0
        // Alpha floor: any nonzero density starts at ~30% so faint traces
        // survive glare instead of dissolving into single-digit alphas.
        val alpha = 0.30f + 0.70f * peak.coerceIn(0f, 1f)
        // Manual ARGB packing keeps this object pure JVM (unit-testable without Robolectric).
        val aq = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        val rq = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gq = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bq = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (aq shl 24) or (rq shl 16) or (gq shl 8) or bq
    }

    private fun hardLightUp(dst: Float, src: Float): Float = 1f - 2f * (1f - src) * (1f - dst)

    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073
}
