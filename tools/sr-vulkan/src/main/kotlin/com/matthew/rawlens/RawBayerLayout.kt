// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

internal object RawBayerLayout {
    const val QUAD_REQUIRES_DEMOSAIC = "Quad-Bayer requires its dedicated demosaic path"
    // Camera2 defines rawBinningFactorUsed only for UHR + REMOSAIC_REPROCESSING.
    // Other cameras deliver regular Bayer RAW even if a vendor emits this flag.
    fun requiresRemosaic(
        rawBinning: Boolean?, ultraHighResolution: Boolean, remosaic: Boolean,
        groupWidth: Int? = null, groupHeight: Int? = null
    ): Boolean = rawBinning == true &&
        ((ultraHighResolution && remosaic) || (groupWidth ?: 1) > 1 || (groupHeight ?: 1) > 1)
}
