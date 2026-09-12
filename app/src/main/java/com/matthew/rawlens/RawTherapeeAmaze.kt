// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Pinned RawTherapee scalar AMaZE, 160-pixel tiles, border=4, no rendering. */
object RawTherapeeAmaze {
    const val REVISION = "498f623784e33fd9a7077fcd8937fe0734033366"
    init { System.loadLibrary("rawTherapeeAmaze") }

    /** Interleaved RGB floats, in the same 65535 scale as [raw]. */
    fun demosaic(raw: FloatArray, width: Int, height: Int,
                 pattern: BayerPattern, initialGain: Float = 1f): FloatArray {
        require(width >= 34 && height >= 34 && width % 2 == 0 && height % 2 == 0)
        require(raw.size.toLong() == width.toLong() * height)
        require(initialGain.isFinite() && initialGain > 0)
        return demosaicNative(raw, width, height, AmazePipelineContract.cfaUniform(pattern), initialGain)
    }
    private external fun demosaicNative(raw: FloatArray, width: Int, height: Int,
                                        pattern: IntArray, initialGain: Float): FloatArray
}
