// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences

/**
 * "LK-SR Lab": every RawLens LK-SR tuning value, exposed in Settings -> Merge
 * behind a hidden-by-default switch. Indices mirror `lkr_tuning.h`
 * (tools/lkr-pipeline). Defaults are NOT duplicated here: the sliders show
 * [LkrBridge.nativeTuningDefaults], the native code's own values, and only
 * values the user changed are stored (prefs `lkr_lab_<key>`) and sent; every
 * other entry goes over as NaN = "keep the code default". Paper parity still
 * overrides all of them.
 */
internal object LkrTuning {
    enum class Kind { SWITCH, INT, FLOAT }

    /** One row: native index, label, help line, range and slider step. */
    enum class Entry(
        val index: Int,
        val label: String,
        val help: String,
        val kind: Kind,
        val min: Float = 0f,
        val max: Float = 1f,
        val step: Float = 1f
    ) {
        ITERATIONS(0, "Iterations", "Solver iterations. Fewer = faster, less fine detail.", Kind.INT, 4f, 24f),
        Z_STEPS(1, "Data steps per iteration", "Gradient steps on the data term per iteration.", Kind.INT, 1f, 5f),
        PRIOR_EVERY(2, "Denoiser every n-th iteration", "How often the FFDNet prior runs (NPU calls).", Kind.INT, 1f, 4f),
        LOOK_DAY_PRIOR(3, "Look: day denoise", "Prior strength at clean daylight noise (DSLR look).", Kind.FLOAT, 0f, 1f, .01f),
        LOOK_NIGHT_PRIOR(4, "Look: night denoise", "Prior strength at night noise.", Kind.FLOAT, 0f, 1f, .01f),
        LOOK_DAY_GRAIN(5, "Look: day grain", "Fine grain returned in daylight.", Kind.FLOAT, 0f, 1f, .01f),
        LOOK_NIGHT_GRAIN(6, "Look: night grain", "Fine grain returned at night.", Kind.FLOAT, 0f, 1f, .01f),
        ROBUST(7, "Motion rejection", "Reject frames where they disagree (moving objects).", Kind.SWITCH),
        ROBUST_K(8, "Rejection threshold", "In noise sigmas; default blends 2 (day) to 3 (night). Setting it fixes it at all light levels. Lower rejects more (less smear, more noise).", Kind.FLOAT, 1f, 8f, .1f),
        ROBUST_TAU(9, "Rejection edge tolerance", "Misregistration allowed at edges (LR px).", Kind.FLOAT, 0f, 1f, .01f),
        ROBUST_SPREAD(10, "Rejection spread", "Spread a rejection over this radius (LR px).", Kind.INT, 0f, 6f),
        TEMPORAL_K(11, "Reference check threshold", "Reject frames that disagree with the reference frame (moving water). 0 = off.", Kind.FLOAT, 0f, 8f, .1f),
        TEMPORAL_TAU(12, "Reference check edge tolerance", "Misregistration allowed by the reference check.", Kind.FLOAT, 0f, 2f, .05f),
        EQUALIZE(13, "Even out rejected areas", "Denoise harder where frames were rejected.", Kind.SWITCH),
        LOCAL_MOTION(14, "Local motion", "Per-16px-block motion (wind, parallax).", Kind.SWITCH),
        LOCAL_LAMBDA(15, "Local motion stiffness", "Base value; scaled up automatically with noise.", Kind.FLOAT, 0f, 2f, .01f),
        MOTION_EVERY(16, "Motion refine every n-th iteration", "After 4 warm-up iterations. 1 = every iteration.", Kind.INT, 1f, 4f),
        TILE_LK(17, "Per-tile alignment", "Align each tile on its own (sharp corners).", Kind.SWITCH),
        MEAN_PRESERVE(18, "Colour preservation radius", "Keeps the denoiser from shifting colour (px). 0 = off.", Kind.INT, 0f, 32f),
        SHADOW_BOOST(19, "Day shadow cleanup", "Extra denoise in daylight shadows. 0 = off.", Kind.FLOAT, 0f, 4f, .05f),
        SHADOW_CHROMA(20, "Day shadow colour cleanup", "Smooth shadow colour noise in daylight. 0 = off.", Kind.FLOAT, 0f, 1f, .01f),
        NOTCH(21, "Bayer pattern filter", "Removes zipper / grid patterns.", Kind.SWITCH),
        CLIP(22, "Clipped level", "Raw level treated as clipped.", Kind.FLOAT, .90f, 1f, .005f),
        MHC_INIT(23, "Video demosaic start", "Start from the encoded-video demosaic (off = bilinear).", Kind.SWITCH),
        STEP_SCALE(24, "Step size", "Data step size; above 1.95 can diverge.", Kind.FLOAT, .5f, 1.95f, .05f),
        OVERLAP(25, "Tile overlap", "Overlap between tiles (px).", Kind.INT, 16f, 96f, 2f),
        WORKERS(26, "Parallel tiles", "Tiles solved at once (GPU/NPU overlap).", Kind.INT, 1f, 4f),
        SIGMA_MAX(27, "Schedule start", "First denoise level, in multiples of the noise.", Kind.FLOAT, 2f, 20f, .5f);

        val key: String get() = "lkr_lab_" + name.lowercase()
    }

    /** Must match lkr::kTuningCount. */
    const val COUNT = 28
    const val KEY_SHOW_LAB = "lkr_lab_visible"

    private var cachedDefaults: FloatArray? = null

    /** Native defaults (single source of truth); null if the library failed to load. */
    fun defaults(): FloatArray? = cachedDefaults ?: runCatching { LkrBridge.nativeTuningDefaults() }
        .getOrNull()?.takeIf { it.size >= COUNT }?.also { cachedDefaults = it }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun isOverridden(prefs: SharedPreferences, e: Entry) = prefs.contains(e.key)

    fun value(prefs: SharedPreferences, e: Entry): Float? =
        if (prefs.contains(e.key)) prefs.getFloat(e.key, 0f) else defaults()?.get(e.index)

    fun set(prefs: SharedPreferences, e: Entry, v: Float) {
        val d = defaults()?.get(e.index)
        // Back at the code default: forget the override so future default changes apply.
        if (d != null && kotlin.math.abs(v - d) < e.step / 2) prefs.edit().remove(e.key).apply()
        else prefs.edit().putFloat(e.key, v).apply()
    }

    fun resetAll(prefs: SharedPreferences) =
        prefs.edit().apply { Entry.entries.forEach { remove(it.key) } }.apply()

    /** Table for [LkrBridge.nativeMerge]: NaN everywhere except user overrides; null if none. */
    fun overrides(context: Context): FloatArray? {
        val p = prefs(context)
        val out = FloatArray(COUNT) { Float.NaN }
        var any = false
        for (e in Entry.entries) if (p.contains(e.key)) { out[e.index] = p.getFloat(e.key, Float.NaN); any = true }
        return if (any) out else null
    }

    fun format(e: Entry, v: Float): String = when (e.kind) {
        Kind.SWITCH -> if (v >= .5f) "on" else "off"
        Kind.INT -> v.toInt().toString()
        Kind.FLOAT -> if (e.step < .01f) String.format(java.util.Locale.US, "%.3f", v)
            else if (e.step < .05f) String.format(java.util.Locale.US, "%.2f", v)
            else String.format(java.util.Locale.US, "%.1f", v)
    }
}
