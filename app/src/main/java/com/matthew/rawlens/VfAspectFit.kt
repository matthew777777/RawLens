// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Rect

/**
 * WYSIWYG content geometry carried from a present path to the UI: the
 * fitted [rect] (surface pixels) plus the frame it was fitted from, so a
 * layout change can refit synchronously instead of waiting for the next
 * frame. [rotation] is the sensor orientation (90/270 swap displayed axes).
 */
data class VfContentGeometry(
    val rect: Rect,
    val frameW: Int,
    val frameH: Int,
    val rotation: Int
)

/**
 * Aspect-fit geometry shared by every viewfinder present path (RAW
 * viewfinder CPU/GPU draws, BGU slice, Direct-Log monitor). A frame drawn
 * fullscreen into a surface of a different aspect stretches (tall-box
 * portrait shows circles as tall ellipses); fitting the fitted rect and
 * clearing the bars keeps the preview WYSIWYG against the saved file.
 * Pure integer math, no Android dependencies, unit-tested.
 */
object VfAspectFit {
    /**
     * Centered aspect-fit of an [imgW]x[imgH] frame into a [surfW]x[surfH]
     * surface for [rotation] (sensor orientation; 90/270 swap the displayed
     * axes). Returns `[x, y, w, h]` in surface pixels. Exact-fit returns the
     * full surface.
     */
    fun fit(surfW: Int, surfH: Int, imgW: Int, imgH: Int, rotation: Int): IntArray {
        require(surfW > 0 && surfH > 0 && imgW > 0 && imgH > 0) {
            "viewport needs positive dims"
        }
        val (dw, dh) = if (rotation == 90 || rotation == 270) imgH to imgW else imgW to imgH
        // Compare surfW/surfH vs dw/dh without floats: surface wider
        // than the image -> pillarbox (fit height), else letterbox.
        return if (surfW.toLong() * dh > surfH.toLong() * dw) {
            val w = (surfH.toLong() * dw / dh).toInt().coerceAtLeast(1)
            intArrayOf((surfW - w) / 2, 0, w, surfH)
        } else {
            val h = (surfW.toLong() * dh / dw).toInt().coerceAtLeast(1)
            intArrayOf(0, (surfH - h) / 2, surfW, h)
        }
    }

    /**
     * Centered aspect-fill (cover) of an [imgW]x[imgH] frame into a
     * [surfW]x[surfH] surface for [rotation]: the frame spans the surface
     * edge to edge with no bars and no stretch, cropping the overflow
     * symmetrically. Returns `[x, y, w, h]` in surface pixels; x/y are
     * negative on the cropped axis, w/h exceed the surface there.
     * Exact-fit returns the full surface.
     */
    fun fill(surfW: Int, surfH: Int, imgW: Int, imgH: Int, rotation: Int): IntArray {
        require(surfW > 0 && surfH > 0 && imgW > 0 && imgH > 0) {
            "viewport needs positive dims"
        }
        val (dw, dh) = if (rotation == 90 || rotation == 270) imgH to imgW else imgW to imgH
        // Cover scale = max(surfW/dw, surfH/dh), compared without floats:
        // fitting the width overflows the height -> crop top/bottom,
        // else fitting the height overflows the width -> crop sides.
        return if (surfW.toLong() * dh > surfH.toLong() * dw) {
            val h = (surfW.toLong() * dh / dw).toInt().coerceAtLeast(1)
            intArrayOf(0, (surfH - h) / 2, surfW, h)
        } else {
            val w = (surfH.toLong() * dw / dh).toInt().coerceAtLeast(1)
            intArrayOf((surfW - w) / 2, 0, w, surfH)
        }
    }
}
