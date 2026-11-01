// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs

/** Deliberate vertical gestures, interpreted in the orientation the user is holding. */
internal object PreviewPanelSwipe {
    enum class Action { QUICK_CONTROLS, SETTINGS }

    fun classify(dx: Float, dy: Float, minimumDistance: Float, orientationDegrees: Int = 0): Action? {
        val (horizontal, vertical) = when ((orientationDegrees % 360 + 360) % 360) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
        if (abs(vertical) < minimumDistance || abs(vertical) < abs(horizontal) * 1.5f) return null
        return if (vertical < 0f) Action.QUICK_CONTROLS else Action.SETTINGS
    }
}
