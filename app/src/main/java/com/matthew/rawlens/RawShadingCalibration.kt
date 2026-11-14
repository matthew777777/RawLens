// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Camera2 maps describe the correction remaining after any sensor-side shading. */
internal object RawShadingCalibration {
    fun alreadyApplied(map: LensShadingSnapshot?, sensorApplied: Boolean): Boolean =
        map == null && sensorApplied

    /** The verified lapis HAL returns a map for the actual readout, including ISZ crops. */
    fun readoutMap(map: LensShadingSnapshot?): LensShadingSnapshot? = map?.takeIf {
        it.rows in 1..64 && it.columns in 1..64 && it.gains.size == it.rows * it.columns * 4 &&
            it.gains.toList().all { gain -> gain.isFinite() && gain > 0f && gain <= 64f }
    }
}
