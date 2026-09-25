// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Minimal android.location.Location:
// the fields GpsLocation.toAndroidLocation sets. Exists so GpsLocation.kt
// compiles byte-identical; the DNG desktop path never calls it (writers use
// GpsTiffDirectory, like the phone DNG path).
package android.location

class Location(val provider: String) {
    var latitude: Double = 0.0
    var longitude: Double = 0.0
    var altitude: Double = 0.0
    var time: Long = 0L
    var accuracy: Float = 0f
}
