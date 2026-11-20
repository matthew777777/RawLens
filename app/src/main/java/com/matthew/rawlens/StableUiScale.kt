// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * Pins the camera UI to the device's default density and a 1.0 font scale, so the
 * fixed-geometry chrome (islands, tiles, settings sheet) looks the same whatever the
 * system "display size" and "font size" are set to. Activities wrap their base context
 * with [wrap] in `attachBaseContext`.
 */
internal object StableUiScale {
    /** Narrowest / widest phone-or-tablet short side, in dp, a pinned density may produce. */
    private const val MIN_SHORT_SIDE_DP = 320f
    private const val MAX_SHORT_SIDE_DP = 960f

    /**
     * Portrait height, in dp, the camera chrome (top island + bottom panel) is laid out
     * for. Ordinary phones (16:9 and taller) already reach it and render unchanged;
     * squat screens (square 1:1 like the Unihertz Titan, 4:3) get the whole UI shrunk so
     * the viewfinder keeps a usable share of the screen.
     */
    private const val DESIGN_LONG_SIDE_DP = 640f
    /** Never shrink the UI below this fraction, so touch targets stay usable. */
    private const val MIN_FIT_SCALE = 0.7f

    /**
     * Density that fits [DESIGN_LONG_SIDE_DP] into the screen's long side, starting from
     * [dpi]. Returns [dpi] unchanged when the screen is already tall enough.
     */
    fun fitDensityDpi(dpi: Int, longSidePx: Int): Int {
        if (dpi <= 0 || longSidePx <= 0) return dpi
        val longSideDp = longSidePx * DisplayMetrics.DENSITY_DEFAULT.toFloat() / dpi
        if (longSideDp >= DESIGN_LONG_SIDE_DP) return dpi
        val scale = (longSideDp / DESIGN_LONG_SIDE_DP).coerceAtLeast(MIN_FIT_SCALE)
        return (dpi / scale).roundToInt()
    }

    /** Density before the short-screen fit: the stable density, when plausible. */
    private fun unfittedDensityDpi(currentDpi: Int, metrics: DisplayMetrics) = targetDensityDpi(
        currentDpi, DisplayMetrics.DENSITY_DEVICE_STABLE,
        minOf(metrics.widthPixels, metrics.heightPixels))

    /**
     * Density to render at: [stableDpi] when it differs from [currentDpi] and still
     * yields a plausible short side. A stable density that does not belong to the active
     * resolution (some phones keep the factory value after a resolution switch) is rejected.
     */
    fun targetDensityDpi(currentDpi: Int, stableDpi: Int, shortSidePx: Int): Int {
        if (stableDpi <= 0 || currentDpi <= 0 || shortSidePx <= 0) return currentDpi
        val shortSideDp = shortSidePx * DisplayMetrics.DENSITY_DEFAULT.toFloat() / stableDpi
        return if (shortSideDp in MIN_SHORT_SIDE_DP..MAX_SHORT_SIDE_DP) stableDpi else currentDpi
    }

    fun wrap(base: Context): Context {
        val current = base.resources.configuration
        val metrics = base.resources.displayMetrics
        val dpi = fitDensityDpi(
            unfittedDensityDpi(current.densityDpi, metrics),
            maxOf(metrics.widthPixels, metrics.heightPixels))
        if (dpi == current.densityDpi && current.fontScale == 1f) return base
        val config = Configuration(current)
        if (dpi != current.densityDpi) {
            // Keep the dp screen size consistent with the new density.
            val scale = current.densityDpi.toFloat() / dpi
            fun rescale(dp: Int) =
                if (dp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) dp else (dp * scale).roundToInt()
            config.screenWidthDp = rescale(current.screenWidthDp)
            config.screenHeightDp = rescale(current.screenHeightDp)
            config.smallestScreenWidthDp = rescale(current.smallestScreenWidthDp)
            config.densityDpi = dpi
        }
        config.fontScale = 1f
        return base.createConfigurationContext(config)
    }

    /**
     * How much larger the user's system "display size" and "font size" would draw text
     * than the pinned camera UI does. Settings and other text-heavy pages follow it,
     * since rows there grow with their text; the camera chrome stays pinned.
     */
    fun systemTextScale(context: Context): Float {
        val system = context.applicationContext.resources.configuration
        val pinned = context.resources.configuration
        if (pinned.densityDpi <= 0) return 1f
        // Compare against the density before the short-screen fit, so a shrunk camera UI
        // does not blow up text on the settings pages.
        val reference = unfittedDensityDpi(
            system.densityDpi, context.applicationContext.resources.displayMetrics)
        val scale = system.densityDpi.toFloat() / reference * system.fontScale / pinned.fontScale
        return scale.coerceIn(0.85f, 2f)
    }

    /**
     * Scales every TextView under [root] by [systemTextScale], including rows that pages
     * add or rewrite later; each view is scaled once per text size its page sets.
     */
    fun followSystemText(root: android.view.View) {
        val scale = systemTextScale(root.context)
        if (kotlin.math.abs(scale - 1f) < 0.01f) return
        val applied = java.util.WeakHashMap<android.widget.TextView, Float>()
        fun visit(view: android.view.View): Boolean {
            var changed = false
            if (view is android.widget.TextView && applied[view] != view.textSize) {
                val size = view.textSize * scale
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
                applied[view] = view.textSize
                changed = true
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) changed = visit(view.getChildAt(i)) or changed
            }
            return changed
        }
        visit(root)
        // Skip a frame that still has unscaled text rather than showing it.
        root.viewTreeObserver.addOnPreDrawListener { !visit(root) }
    }
}
