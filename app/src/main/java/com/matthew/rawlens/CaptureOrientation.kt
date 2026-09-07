// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Canonical OrientationEventListener/sensor rotation -> EXIF orientation mapping. */
object CaptureOrientation {
    fun exif(
        sensorOrientationDegrees: Int,
        deviceOrientationDegrees: Int,
        frontFacing: Boolean
    ): Int {
        val sensor = normalizeQuarterTurn(sensorOrientationDegrees)
        val listener = normalizeQuarterTurn(deviceOrientationDegrees)

        // OrientationEventListener angles run in the opposite rotational sense from the
        // display/device rotation convention used by Camera2's JPEG orientation formula.
        // Convert the listener angle first, then apply the documented Camera2 convention.
        //
        // Example for the common 90-degree back-camera sensor mount:
        //   portrait listener=0   -> EXIF 6 (rotate 90 CW)
        //   phone CCW 90 listener=270 -> EXIF 1 (native landscape, no rotation)
        //   phone CW  90 listener=90  -> EXIF 3 (rotate 180)
        val deviceRotation = (360 - listener) % 360
        val sign = if (frontFacing) 1 else -1
        return when ((sensor + sign * deviceRotation + 360) % 360) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
    }

    private fun normalizeQuarterTurn(value: Int): Int =
        ((((value % 360) + 360) % 360 + 45) / 90 * 90) % 360
}
