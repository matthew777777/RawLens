// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * BGU viewfinder spike facade: box-downsamples a u16 RGB plane by an integer
 * factor through the Halide AOT filter. Values ride in [ShortArray] bit-
 * preserved (u16 codes fit; sign is irrelevant). Proves the Halide toolchain
 * end to end on-device; the real BGU engine builds on this exact path.
 */
internal object BguSpike {
    val available: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("rawLensBgu")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
        available = loaded
    }

    /**
     * Downsample [pixels] ([w] x [h] x [c], row-major, channel-interleaved
     * planes) by [factor]. Returns the (ceil(w/f) x ceil(h/f) x c) plane,
     * or null on invalid input or filter failure.
     */
    fun downsample(pixels: ShortArray, w: Int, h: Int, c: Int, factor: Int): ShortArray? {
        if (!available) return null
        if (pixels.size != w * h * c) return null
        return downsampleNative(pixels, w, h, c, factor)
    }

    external fun downsampleNative(pixels: ShortArray, w: Int, h: Int, c: Int, factor: Int): ShortArray?
}
