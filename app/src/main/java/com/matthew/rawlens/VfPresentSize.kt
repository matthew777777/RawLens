// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Shade at preview resolution; SurfaceView scales the buffer to its layout. */
internal object VfPresentSize {
    data class Size(val width: Int, val height: Int)

    fun forLayout(width: Int, height: Int, longEdge: Int): Size {
        require(width > 0 && height > 0 && longEdge > 0)
        val edge = maxOf(width, height)
        if (edge <= longEdge) return Size(width, height)
        // Round only the short edge. Preserve aspect/crop while never creating
        // a buffer larger than the view or the selected preview resolution.
        fun scaled(value: Int) = ((value.toLong() * longEdge + edge / 2) / edge).toInt().coerceAtLeast(1)
        return Size(scaled(width), scaled(height))
    }
}
