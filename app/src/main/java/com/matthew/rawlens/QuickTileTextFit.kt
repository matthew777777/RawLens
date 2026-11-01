// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

internal object QuickTileTextFit {
    fun scale(textWidth: Float, textHeight: Float, availableWidth: Float, availableHeight: Float): Float =
        minOf(1f, availableWidth.coerceAtLeast(1f) / textWidth.coerceAtLeast(1f),
            availableHeight.coerceAtLeast(1f) / textHeight.coerceAtLeast(1f))
}
