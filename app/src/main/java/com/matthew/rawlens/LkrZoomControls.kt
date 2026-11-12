// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView

internal fun MainActivity.redmiRootMode(): LapisRootProfile.Mode =
    if (controllerIfReady?.activeCameraId() == "0" && (!isVideoMode || LapisRootProfile.mode(this)==LapisRootProfile.Mode.DCG14)) LapisRootProfile.mode(this)
    else LapisRootProfile.Mode.NORMAL

/**
 * LK-R zoom chip (viewfinder bottom-right corner, opposite the scope;
 * independent of the lens count) and matching preview zoom. The chip shows only
 * while LK-R is the active sharp merge in photo mode; tapping cycles
 * 1x -> 1.5x -> 2x -> 1x. The preview is a centre scale of the viewfinder views
 * (no capture change: every frame is still full sensor), so what you frame
 * is the crop the merge solves at x2.
 *
 * In the bottom row next to OFF / AUTO, the round resolution button toggles, independently of zoom,
 * the sensor-size output and the kept x2 solve, labelled in MP like the lens switcher.
 */
internal fun MainActivity.wireLkrZoomChip() {
    findViewById<TextView>(R.id.lkrZoomChip).setOnClickListener { chip ->
        val next = mfsrSettings.lkrZoom.nextZoom()
        if (applyMfsrSettings(mfsrSettings.copy(lkrZoom = next))) {
            chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setStatus("LK-R ZOOM ${next.label}")
            updateLkrZoomChip()
        }
    }
    findViewById<TextView>(R.id.lkrResChip).setOnClickListener { button ->
        val next = !mfsrSettings.lkrFullRes
        if (applyMfsrSettings(mfsrSettings.copy(lkrFullRes = next))) {
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setStatus("LK-R ${lkrMegapixelLabel(next, mfsrSettings.lkrZoom)}")
            updateLkrZoomChip()
        }
    }
}

/**
 * Output size in MP from the live RAW size: sensor MP, or the x2 solve of the
 * framed crop (1x: 4x pixels, 1.5x: ~2.25x, 2x: sensor size). Falls back to "1×" / "2×".
 */
private fun MainActivity.lkrMegapixelLabel(fullRes: Boolean, zoom: LkrZoom): String {
    val base = captureRawPixels
    if (base <= 0) return if (fullRes) "2×" else "1×"
    val mp = (if (fullRes) base * 4 / (zoom.factor * zoom.factor) else base.toFloat()) / 1e6
    val rounded = Math.round(mp * 10) / 10.0
    return if (rounded >= 20 || rounded % 1.0 == 0.0) "${Math.round(mp)} MP"
    else String.format(java.util.Locale.US, "%.1f MP", rounded)
}

internal fun MainActivity.updateLkrZoomChip() {
    val chip = findViewById<TextView>(R.id.lkrZoomChip) ?: return
    val res = findViewById<TextView>(R.id.lkrResChip)
    val shown = !isVideoMode && mfsrSettings.isOn(MfsrEngine.LKR)
    chip.visibility = if (shown) View.VISIBLE else View.GONE
    res?.visibility = chip.visibility
    (chip as? RotatingContent)?.contentRotation = controlRotationDegrees
    (res as? RotatingContent)?.contentRotation = controlRotationDegrees
    val zoom = if (shown) mfsrSettings.lkrZoom else LkrZoom.X1
    chip.text = "SR ${zoom.label}"
    chip.contentDescription = "LK-R zoom ${zoom.label}. Tap to cycle 1×, 1.5×, 2×."
    val zoomed = zoom != LkrZoom.X1
    chip.setBackgroundResource(if (zoomed) R.drawable.control_chip_active else R.drawable.control_chip)
    chip.setTextColor(getColor(if (zoomed) R.color.accent_dark else R.color.text_primary))
    if (res != null) {
        val fullRes = shown && mfsrSettings.lkrFullRes
        val label = lkrMegapixelLabel(fullRes, zoom)
        res.text = label.replace(" MP", "\nMP")
        res.contentDescription = "LK-R output resolution $label. Tap to switch sensor / 2× super-resolution."
        res.setBackgroundResource(if (fullRes) R.drawable.control_chip_round_active else R.drawable.control_chip_round)
        res.setTextColor(getColor(if (fullRes) R.color.accent_dark else R.color.text_primary))
    }
    if (shown) seatScopeSlot()
    applyLkrPreviewZoom(zoom.factor)
}

/** Centre-scales every viewfinder surface (the parent frame clips to the screen). */
private fun MainActivity.applyLkrPreviewZoom(factor: Float) {
    for (id in intArrayOf(R.id.viewfinder, R.id.rawViewfinder, R.id.bguViewfinder)) {
        findViewById<View>(id)?.let { v ->
            if (v.scaleX != factor || v.scaleY != factor) {
                v.scaleX = factor
                v.scaleY = factor
            }
        }
    }
}
