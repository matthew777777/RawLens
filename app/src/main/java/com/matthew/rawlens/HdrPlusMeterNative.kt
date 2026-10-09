// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native HDR+ motion meter (Halide AOT, `rawLensHdrMeter`).
 *
 * One call meters a frame pair: stride-16 ratio/MAD/texture maps and
 * the hot fraction — bitwise identical to [HdrPlusMotionMeter] (host
 * gate in tools/halide) — plus full-res dense MAD/texture maps per
 * 32px cell, which Kotlin cannot afford (hundreds of ms per burst)
 * and which catch the sub-sample jitter that actually ghosts.
 * All buffers are direct (zero-copy); outputs are native-order f64.
 * Missing/failed native code falls back to the Kotlin meter with the
 * danger term off — never a crash, never a wrong map.
 */
internal object HdrPlusMeterNative {
    /** Per-pair native result; grids are row-major mw x mh doubles. */
    data class PairMaps(
        val ratio: DoubleArray,
        val hot: Double,
        val madDense: DoubleArray,
        val texDense: DoubleArray
    )

    val available: Boolean = try {
        System.loadLibrary("rawLensHdrMeter")
        true
    } catch (unsatisfied: UnsatisfiedLinkError) {
        false
    } catch (failed: SecurityException) {
        false
    }

    /**
     * Scratch for one pair: five mw*mh f64 maps plus the hot scalar.
     * Reused across pairs (the native call overwrites every byte).
     */
    class Scratch(width: Int, height: Int) {
        val cells: Int = ((width + 31) / 32) * ((height + 31) / 32)
        private fun map(): ByteBuffer =
            ByteBuffer.allocateDirect(cells * Double.SIZE_BYTES).order(ByteOrder.nativeOrder())

        val ratio: ByteBuffer = map()
        val mad16: ByteBuffer = map()
        val tex16: ByteBuffer = map()
        val madDense: ByteBuffer = map()
        val texDense: ByteBuffer = map()
        val hot: ByteBuffer =
            ByteBuffer.allocateDirect(Double.SIZE_BYTES).order(ByteOrder.nativeOrder())
    }

    /**
     * Meters one pair into [scratch]; returns the maps, or null when
     * the native call fails (caller falls back to Kotlin). [prev] and
     * [curr] must be direct w*h uint16 buffers (position 0).
     */
    fun meterPair(
        prev: ByteBuffer,
        curr: ByteBuffer,
        width: Int,
        height: Int,
        range: Double,
        hotRatio: Double,
        scratch: Scratch
    ): PairMaps? {
        if (!available) return null
        if (!prev.isDirect || !curr.isDirect) return null
        val ok = try {
            meterPairNative(
                prev, curr, width, height, range, hotRatio,
                scratch.ratio, scratch.mad16, scratch.tex16,
                scratch.madDense, scratch.texDense, scratch.hot
            )
        } catch (failed: Exception) {
            return null
        }
        if (!ok) return null
        fun ByteBuffer.doubles(): DoubleArray {
            val out = DoubleArray(scratch.cells)
            asDoubleBuffer().get(out)
            return out
        }
        return PairMaps(
            ratio = scratch.ratio.doubles(),
            hot = scratch.hot.asDoubleBuffer().get(0),
            madDense = scratch.madDense.doubles(),
            texDense = scratch.texDense.doubles()
        )
    }

    // Instance (not static) external: matches the jobject JNI binding.
    private external fun meterPairNative(
        prev: ByteBuffer,
        curr: ByteBuffer,
        width: Int,
        height: Int,
        range: Double,
        hotRatio: Double,
        outRatio: ByteBuffer,
        outMad16: ByteBuffer,
        outTex16: ByteBuffer,
        outMadDense: ByteBuffer,
        outTexDense: ByteBuffer,
        outHot: ByteBuffer
    ): Boolean
}
