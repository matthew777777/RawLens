// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Same sensor-code normalization for capture development as the preview and original DNG.
 * Select before RAW unpacking, WB fallback, denoise, and merge; changing tags afterwards
 * cannot undo processing with the HAL's stale white level in a vendor sensor mode.
 * This never rescales or edits the original RAW payload.
 */
internal fun DngMetadataOverrides.captureSensorLevels(
    reportedBlack: FloatArray?, reportedWhite: Float?
): Pair<FloatArray?, Float?> {
    validate()
    return (blackLevels?.let { values -> FloatArray(4) { values[it].toFloat() } }
        ?: reportedBlack) to (whiteLevel?.toFloat() ?: reportedWhite)
}
