// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Dual-axis virtual-level math. Portrait-natural phone axes (X right, Y up,
 * Z out of the screen); the activity stays portrait-locked so sensor axes map
 * 1:1 to canvas axes and the horizon rotation works in every physical
 * orientation: portrait, both landscapes, and upside-down.
 *
 * Roll ([State.tiltDegrees]): 0 = upright portrait, -90 = clockwise landscape
 * (top to the right), +90 = counter-clockwise landscape, ±180 = upside-down.
 * The canvas line is drawn rotated by it, so it stays world-horizontal:
 * vertical on the portrait-locked canvas in landscape, which the user holding
 * the rotated phone sees as horizontal.
 *
 * Pitch ([State.pitchDegrees]): elevation of the camera axis above the
 * horizon, -90..90. 0 keeps verticals parallel; positive points to the sky,
 * negative to the ground. Pitching is what makes vertical lines fall /
 * converge (keystoning), so a horizon-only (roll) level cannot prevent it —
 * the level is only [State.isLevel] when both axes are level.
 */
object VirtualHorizon {
    /** Inside this roll error the horizon snaps and turns level-colored. */
    const val LEVEL_THRESHOLD_DEGREES = 1.5f

    /** Inside this pitch error verticals stay effectively parallel. */
    const val PITCH_LEVEL_THRESHOLD_DEGREES = 1.5f

    /** Pitch shown at the end of the bubble track; beyond it the bubble pins. */
    const val PITCH_DISPLAY_RANGE_DEGREES = 30f

    /**
     * Minimum horizontal gravity fraction for a trustworthy roll. Below this
     * the device points far enough up/down that roll is noise (0.35 ≈ 69° of
     * pitch). Pitch itself stays readable there (it nears ±90°).
     */
    const val FLAT_CONFIDENCE_THRESHOLD = 0.35f

    data class State(
        /** Canvas rotation that keeps the line world-horizontal, -180..180. */
        val tiltDegrees: Float,
        /** Signed roll distance to the nearest 90° quadrant, -45..45. */
        val errorDegrees: Float,
        /** Camera elevation above the horizon, -90..90 (+ sky, - ground). */
        val pitchDegrees: Float = 0f,
        val isRollLevel: Boolean = false,
        val isPitchLevel: Boolean = false,
        /** True only when roll and pitch are both level. */
        val isLevel: Boolean = false,
        val isFlat: Boolean = false,
        /** Horizontal gravity fraction 0..1. */
        val confidence: Float = 0f
    ) {
        /** Rotation to draw: snapped to the quadrant when roll-level. */
        fun drawDegrees(): Float = if (isRollLevel) {
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
            return State(0f, 0f, isFlat = true)
        }
        val confidence = (horizontal / total).coerceIn(0f, 1f)
        // Sensor vector points to the sky, camera looks along -Z, so a
        // negative gz means the camera axis itself points skyward.
        val pitch = Math.toDegrees(
            asin((-gz / total).coerceIn(-1f, 1f).toDouble())
        ).toFloat()
        val pitchLevel = abs(pitch) <= PITCH_LEVEL_THRESHOLD_DEGREES
        if (confidence < FLAT_CONFIDENCE_THRESHOLD) {
            return State(
                tiltDegrees = 0f,
                errorDegrees = 0f,
                pitchDegrees = pitch,
                isRollLevel = false,
                isPitchLevel = pitchLevel,
                isLevel = false,
                isFlat = true,
                confidence = confidence
            )
        }
        val tilt = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val nearest = kotlin.math.round(tilt / 90f) * 90f
        val error = wrap180(tilt - nearest)
        val rollLevel = abs(error) <= LEVEL_THRESHOLD_DEGREES
        return State(
            tiltDegrees = tilt,
            errorDegrees = error,
            pitchDegrees = pitch,
            isRollLevel = rollLevel,
            isPitchLevel = pitchLevel,
            isLevel = rollLevel && pitchLevel,
            isFlat = false,
            confidence = confidence
        )
    }

    /** Bubble position along its track, -1..1 (+ toward the sky end). */
    fun pitchOffsetFraction(pitchDegrees: Float): Float =
        (pitchDegrees / PITCH_DISPLAY_RANGE_DEGREES).coerceIn(-1f, 1f)

    internal fun wrap180(degrees: Float): Float {
        var wrapped = (degrees + 180f) % 360f
        if (wrapped < 0f) wrapped += 360f
        val result = wrapped - 180f
        return if (result == -180f) 180f else result
    }
}
