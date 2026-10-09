// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Gcam-style virtual-horizon math. Portrait-natural phone axes (X right, Y up,
 * Z out of the screen); the activity stays portrait-locked so sensor axes map
 * 1:1 to canvas axes and the horizon rotation works in every physical
 * orientation: portrait, both landscapes, and upside-down.
 *
 * Tilt 0 = upright portrait, -90 = clockwise landscape (top to the right),
 * +90 = counter-clockwise landscape, ±180 = upside-down. The canvas line is
 * drawn rotated by [State.tiltDegrees], so it stays world-horizontal: vertical
 * on the portrait-locked canvas in landscape, which the user holding the
 * rotated phone sees as horizontal.
 */
object VirtualHorizon {
    /** Inside this error the horizon snaps and turns level-colored. */
    const val LEVEL_THRESHOLD_DEGREES = 1.5f

    /**
     * Minimum horizontal gravity fraction for a trustworthy tilt. Below this
     * the device points far enough up/down that roll is noise (0.35 ≈ 69°).
     */
    const val FLAT_CONFIDENCE_THRESHOLD = 0.35f

    data class State(
        /** Canvas rotation that keeps the line world-horizontal, -180..180. */
        val tiltDegrees: Float,
        /** Signed distance to the nearest 90° quadrant, -45..45. */
        val errorDegrees: Float,
        val isLevel: Boolean,
        val isFlat: Boolean,
        /** Horizontal gravity fraction 0..1. */
        val confidence: Float
    ) {
        /** Rotation to draw: snapped to the quadrant when level for a crisp lock. */
        fun drawDegrees(): Float = if (isLevel) {
            val snapped = kotlin.math.round(tiltDegrees / 90f) * 90f
            wrap180(snapped)
        } else {
            tiltDegrees
        }
    }

    fun compute(gx: Float, gy: Float, gz: Float): State {
        val horizontal = sqrt(gx * gx + gy * gy)
        val total = sqrt(gx * gx + gy * gy + gz * gz)
        if (total < 1e-6f) {
            return State(0f, 0f, isLevel = false, isFlat = true, confidence = 0f)
        }
        val confidence = (horizontal / total).coerceIn(0f, 1f)
        if (confidence < FLAT_CONFIDENCE_THRESHOLD) {
            return State(0f, 0f, isLevel = false, isFlat = true, confidence = confidence)
        }
        val tilt = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val nearest = kotlin.math.round(tilt / 90f) * 90f
        val error = wrap180(tilt - nearest)
        val level = abs(error) <= LEVEL_THRESHOLD_DEGREES
        return State(
            tiltDegrees = tilt,
            errorDegrees = error,
            isLevel = level,
            isFlat = false,
            confidence = confidence
        )
    }

    internal fun wrap180(degrees: Float): Float {
        var wrapped = (degrees + 180f) % 360f
        if (wrapped < 0f) wrapped += 360f
        val result = wrapped - 180f
        return if (result == -180f) 180f else result
    }
}
