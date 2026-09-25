// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Map-backed androidx ExifInterface:
// only setAttribute/getAttribute plus the TAG constants GpsLocation.writeTo
// uses, with the real EXIF tag names. Exists so GpsLocation.kt compiles
// byte-identical; the DNG desktop path never calls it (writers use
// GpsTiffDirectory, like the phone DNG path).
package androidx.exifinterface.media

class ExifInterface {
    private val attributes = HashMap<String, String>()

    fun setAttribute(tag: String, value: String?) {
        if (value == null) attributes.remove(tag) else attributes[tag] = value
    }

    fun getAttribute(tag: String): String? = attributes[tag]

    companion object {
        const val TAG_GPS_VERSION_ID = "GPSVersionID"
        const val TAG_GPS_LATITUDE_REF = "GPSLatitudeRef"
        const val TAG_GPS_LATITUDE = "GPSLatitude"
        const val TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef"
        const val TAG_GPS_LONGITUDE = "GPSLongitude"
        const val TAG_GPS_ALTITUDE_REF = "GPSAltitudeRef"
        const val TAG_GPS_ALTITUDE = "GPSAltitude"
        const val TAG_GPS_TIMESTAMP = "GPSTimeStamp"
        const val TAG_GPS_DATESTAMP = "GPSDateStamp"
        const val TAG_GPS_PROCESSING_METHOD = "GPSProcessingMethod"
    }
}
