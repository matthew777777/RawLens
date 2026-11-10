// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView

/**
 * LK-SR zoom chip (viewfinder bottom-right corner, opposite the scope;
 * independent of the lens count) and matching preview zoom. The chip shows only
 * while LK-SR is the active sharp merge in photo mode; tapping cycles
 * 1x -> 1.5x -> 2x -> full 2x. The preview is a centre scale of the viewfinder views
 * (no capture change: every frame is still full sensor), so what you frame
 * is the crop the merge solves at x2. Output stays the sensor resolution.
 */
internal fun MainActivity.wireLkrZoomChip() {
    findViewById<TextView>(R.id.lkrZoomChip).setOnClickListener { chip ->
        val next = mfsrSettings.lkrZoom.next()
        if (applyMfsrSettings(mfsrSettings.copy(lkrZoom = next))) {
            chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setStatus("LK-SR ZOOM ${next.label}")
            updateLkrZoomChip()
        }
    }
}

internal fun MainActivity.updateLkrZoomChip() {
    val chip = findViewById<TextView>(R.id.lkrZoomChip) ?: return
    val shown = !isVideoMode && mfsrSettings.isOn(MfsrEngine.LKR)
    chip.visibility = if (shown) View.VISIBLE else View.GONE
    (chip as? RotatingContent)?.contentRotation = controlRotationDegrees
    val zoom = if (shown) mfsrSettings.lkrZoom else LkrZoom.X1
    chip.text = "SR ${zoom.label}"
    chip.contentDescription = "LK-SR zoom ${zoom.label}. Tap to cycle 1×, 1.5×, 2×, full 2×."
    val active = zoom != LkrZoom.X1
    chip.setBackgroundResource(if (active) R.drawable.control_chip_active else R.drawable.control_chip)
    chip.setTextColor(getColor(if (active) R.color.accent_dark else R.color.text_primary))
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
