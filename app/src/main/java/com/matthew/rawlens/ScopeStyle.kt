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
 * Presentation is tuned for direct sunlight on a phone: an opaque
 * near-black plate (a sheer glass lets the bright preview wash the traces
 * out), level lines on the waveform only, and data inset from the rounded wall so the
 * 0 % and 100 % edges — where clipping lives — are never faded or clipped.
 * The histogram follows darktable's look (overlapping RGB fills blended
 * additively, so overlaps read yellow / cyan / magenta / white, linear
 * vertical scale with log as the option, no grid); the waveform follows RawTherapee's (bright
 * traces on black with faint 25 / 50 / 75 % level lines; clipping only as red
 * cells on the 100 % row).
 */
object ScopeStyle {
    /** Opaque near-black plate shared by both scopes: identical in every scene, never washed out by the preview. */
    const val SCRIM = 0xFF080808.toInt()

    /** Waveform 25 / 50 / 75 % level lines (white at ~18%). */
    const val GRID = 0x2EFFFFFF

    /** Highlight clip marker (histogram dot, waveform 100 % cells). */
    const val CLIP_HIGH = 0xFFFF4D4D.toInt()

    /** Share of a channel's samples in its extreme bin that counts as clipped. */
    const val CLIP_FRACTION = 0.005f

    /**
     * Data floats in an inner rounded window this far from the wall, so the
     * 0 % / 100 % edges stay fully visible and no trace meets a square edge.
     */
    const val PLOT_INSET_X_DP = 5f
    const val PLOT_INSET_Y_DP = 5f

    /** Inner window radius: concentric with the outer wall, never sharp. */
    fun plotCornerRadiusDp(): Float = (CORNER_RADIUS_DP - PLOT_INSET_X_DP).coerceAtLeast(4f) + 1f

    /** Gap kept between the scope and the first vertical thirds line. */
    const val GRID_CLEARANCE_DP = 4f

    /** Scope height as a share of its width. */
    const val ASPECT = 0.66f

    /**
     * Scope width that fits the bottom-left thirds cell of a [parentPx]-wide
     * viewfinder: a third of the width minus the start margin and
     * [GRID_CLEARANCE_DP], so the scope never covers a grid line.
     */
    fun slotWidthPx(parentPx: Int, marginStartPx: Int, density: Float): Int =
        (parentPx / 3 - marginStartPx - (GRID_CLEARANCE_DP * density).toInt()).coerceAtLeast(0)

    /** Waveform density gain over darktable's area scale: brighter traces for glare. */
    const val WAVEFORM_GAIN = 2f

    /** Hairline baseline anchoring the bottom edge (white at ~20%). */
    const val BASELINE = 0x33FFFFFF

    /** Single-piece scope surface: small radius so data can run close to the edge. */
    const val CORNER_RADIUS_DP = 10f
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

    /** Histogram fill alpha per channel, blended ADD (darktable-style overlap mixing). */
    const val HISTOGRAM_FILL_ALPHA = 170

    /** Histogram top-edge trace alpha (SRC_OVER, per channel): crisp outline in glare. */
    const val HISTOGRAM_LINE_ALPHA = 255

    /** Area-bias divisor shared with darktable (`num_tones / 40`). */
    const val BRIGHTNESS_DIVISOR = 40f

    /**
     * Edge taper width for scope traces, in bins/columns. Curves and rasters
     * fade to nothing over this many samples at every edge so data never hits
     * the pill wall as a hard clipped line.
     */
    const val HISTOGRAM_EDGE_BINS = 4
    const val WAVEFORM_EDGE_FADE = 3

    /**
     * Smooth 0..1 window for sample [index] of [size]: 0 at both extreme
     * samples, ramping to 1 over [edgeBins] via smoothstep. Multiplied into
     * trace heights so curves land on the baseline before the view edge.
     */
    fun edgeTaper(index: Int, size: Int, edgeBins: Int = HISTOGRAM_EDGE_BINS): Float {
        if (size <= 0) return 0f
        if (edgeBins <= 0) return 1f
        val distance = minOf(index, size - 1 - index).coerceAtLeast(0).toFloat() / edgeBins
        val t = distance.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 2D edge fade for a waveform raster cell: smooth 0..1 over [fade] cells
     * at all four raster borders, 1 inside. Applied to pixel alpha so dense
     * edge columns/levels dissolve instead of drawing a hard frame.
     */
    fun rasterEdgeFade(
        column: Int,
        level: Int,
        columns: Int,
        levels: Int,
        fade: Int = WAVEFORM_EDGE_FADE,
        fadeLevels: Boolean = true,
    ): Float {
        if (columns <= 0 || levels <= 0) return 0f
        if (fade <= 0) return 1f
        fun axisTaper(at: Int, extent: Int): Float {
            val distance = minOf(at, extent - 1 - at).coerceAtLeast(0).toFloat() / fade
            val t = distance.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        return axisTaper(column, columns) * (if (fadeLevels) axisTaper(level, levels) else 1f)
    }

    /** Scales only the alpha channel of an ARGB [pixel] by [fade] 0..1. */
    fun applyEdgeFade(pixel: Int, fade: Float): Int {
        val t = fade.coerceIn(0f, 1f)
        if (t >= 1f) return pixel
        val alpha = ((pixel ushr 24 and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (pixel and 0x00FFFFFF)
    }

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
        // Alpha floor: any nonzero density starts at ~45% so faint traces
        // survive direct sunlight instead of dissolving into the plate.
        val alpha = 0.45f + 0.55f * peak.coerceIn(0f, 1f)
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
