// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * A validated fix to embed as GPS metadata in saved images.
 *
 * Latitude/longitude follow WGS-84 as reported by the platform location
 * provider. A null [altitudeMeters] omits every altitude tag rather than
 * fabricating sea level. [timeMillis] is the UTC epoch of the fix itself,
 * not the shutter time.
 */
data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timeMillis: Long = System.currentTimeMillis(),
    val processingMethod: String = METHOD_GPS,
    val accuracyMeters: Float? = null
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must lie within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must lie within [-180, 180]"
        }
        altitudeMeters?.let { require(it.isFinite()) { "Altitude must be finite" } }
        require(processingMethod.isNotBlank()) { "GPS processing method must be recorded" }
    }

    val latitudeRef: String get() = if (latitude < 0) "S" else "N"
    val longitudeRef: String get() = if (longitude < 0) "W" else "E"

    /** Platform [android.location.Location] for APIs that take one (DngCreator). */
    fun toAndroidLocation(provider: String = "RawLens"): android.location.Location =
        android.location.Location(provider).apply {
            latitude = this@GpsLocation.latitude
            longitude = this@GpsLocation.longitude
            altitudeMeters?.let { altitude = it }
            time = timeMillis
            accuracyMeters?.let { accuracy = it }
        }

    /** Degrees/minutes/whole-milli-seconds with EXIF carry (60" rolls into minutes). */
    fun latitudeDms(): Dms = Dms.from(abs(latitude))
    fun longitudeDms(): Dms = Dms.from(abs(longitude))

    /**
     * Writes the complete GPS tag set into a JPEG [ExifInterface]. Every tag
     * the EXIF 2.32 GPS IFD requires for a positioned fix is written; nothing
     * is written when this fix carries no value for it (altitude).
     */
    fun writeTo(exif: ExifInterface) {
        exif.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, GPS_VERSION)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latitudeRef)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latitudeDms().toExifString())
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, longitudeRef)
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, longitudeDms().toExifString())
        altitudeMeters?.let {
            exif.setAttribute(
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                if (it < 0) ALTITUDE_BELOW_SEA else ALTITUDE_ABOVE_SEA
            )
            exif.setAttribute(
                ExifInterface.TAG_GPS_ALTITUDE,
                rationalString((abs(it) * ALTITUDE_DENOMINATOR).toLong(), ALTITUDE_DENOMINATOR)
            )
        }
        val utc = utcCalendar(timeMillis)
        exif.setAttribute(
            ExifInterface.TAG_GPS_TIMESTAMP,
            "${utc.get(Calendar.HOUR_OF_DAY)}/1,${utc.get(Calendar.MINUTE)}/1," +
                "${utc.get(Calendar.SECOND)}/1"
        )
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateStamp(timeMillis))
        exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, processingMethod)
    }

    companion object {
        const val METHOD_GPS = "GPS"
        const val METHOD_NETWORK = "CELLID"
        /** EXIF GPSVersionID unfolds to BYTE 2.3.0.0; kept as text for ExifInterface. */
        const val GPS_VERSION = "2.3.0.0"
        const val ALTITUDE_ABOVE_SEA = "0"
        const val ALTITUDE_BELOW_SEA = "1"
        const val ALTITUDE_DENOMINATOR = 1000L

        fun rationalString(numerator: Long, denominator: Long): String {
            require(denominator > 0) { "EXIF rational denominator must be positive" }
            return "$numerator/$denominator"
        }

        /** UTC "YYYY:MM:DD" stamp shared by the JPEG datestamp and the DNG GPSDateStamp. */
        fun dateStamp(timeMillis: Long): String {
            val utc = utcCalendar(timeMillis)
            return String.format(
                java.util.Locale.US, "%04d:%02d:%02d",
                utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1,
                utc.get(Calendar.DAY_OF_MONTH)
            )
        }

        fun utcCalendar(timeMillis: Long): Calendar =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { this.timeInMillis = timeMillis }
    }
}

/** Degrees/minutes/milli-arc-seconds-second triple with EXIF carry applied. */
data class Dms(val degrees: Int, val minutes: Int, val secondsNum: Long) {
    init {
        require(degrees >= 0 && minutes in 0..59 && secondsNum in 0L..59_999L)
    }

    fun toExifString(): String = "$degrees/1,$minutes/1,$secondsNum/$SECOND_DENOMINATOR"

    /** Little-endian RATIONAL[3] payload for a TIFF GPS coordinate. */
    fun toRationalBytes(): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(degrees).putInt(1)
            putInt(minutes).putInt(1)
            putInt(secondsNum.toInt()).putInt(SECOND_DENOMINATOR)
        }.array()

    companion object {
        const val SECOND_DENOMINATOR = 1000

        fun from(decimalDegrees: Double): Dms {
            require(decimalDegrees.isFinite() && decimalDegrees >= 0.0)
            var degrees = floor(decimalDegrees).toInt()
            var minutes = floor((decimalDegrees - degrees) * 60.0).toInt()
            var secondsNum = (((decimalDegrees - degrees) * 3600.0 - minutes * 60.0) *
                SECOND_DENOMINATOR + 0.5).toLong()
            if (secondsNum >= 60L * SECOND_DENOMINATOR) {
                secondsNum = 0
                minutes += 1
            }
            if (minutes >= 60) {
                minutes = 0
                degrees += 1
            }
            return Dms(degrees, minutes, secondsNum)
        }
    }
}

/**
 * Standalone little-endian GPS sub-IFD (tag 34853 target) shared by the
 * hand-rolled Kotlin DNG writers. [baseOffset] is the absolute file offset
 * where the returned block will be placed; embedded value offsets are
 * resolved against it so the block can be spliced verbatim.
 */
object GpsTiffDirectory {
    const val TAG_GPS_IFD_POINTER = 34853

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    fun build(location: GpsLocation, baseOffset: Int): ByteArray {
        require(baseOffset >= 8) { "GPS IFD base offset must sit past the TIFF header" }
        val latBytes = location.latitudeDms().toRationalBytes()
        val lonBytes = location.longitudeDms().toRationalBytes()
        val utc = GpsLocation.utcCalendar(location.timeMillis)
        val stampBytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(utc.get(Calendar.HOUR_OF_DAY)).putInt(1)
            putInt(utc.get(Calendar.MINUTE)).putInt(1)
            putInt(utc.get(Calendar.SECOND)).putInt(1)
        }.array()
        val entries = arrayListOf(
            GpsEntry(0, BYTE, byteArrayOf(2, 3, 0, 0)),
            GpsEntry(1, ASCII, ascii(location.latitudeRef)),
            GpsEntry(2, RATIONAL, latBytes),
            GpsEntry(3, ASCII, ascii(location.longitudeRef)),
            GpsEntry(4, RATIONAL, lonBytes),
            GpsEntry(7, RATIONAL, stampBytes),
            GpsEntry(27, ASCII, ascii(location.processingMethod)),
            GpsEntry(29, ASCII, ascii(GpsLocation.dateStamp(location.timeMillis)))
        )
        location.altitudeMeters?.let {
            entries += GpsEntry(5, BYTE, byteArrayOf(if (it < 0) 1 else 0))
            val altNum = (abs(it) * GpsLocation.ALTITUDE_DENOMINATOR + 0.5).toLong()
                .coerceAtMost(0xFFFFFFFFL)
            entries += GpsEntry(
                6, RATIONAL,
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(altNum.toInt()).putInt(GpsLocation.ALTITUDE_DENOMINATOR.toInt())
                }.array()
            )
        }
        entries.sortBy { it.tag }

        val headerBytes = 2 + entries.size * 12 + 4
        var dataOffset = baseOffset + headerBytes
        val external = HashMap<Int, Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.payload.size > 4) {
                external[index] = dataOffset
                dataOffset += entry.payload.size + (entry.payload.size and 1)
            }
        }
        return ByteBuffer.allocate(dataOffset - baseOffset).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(entries.size.toShort())
            entries.forEachIndexed { index, entry ->
                putShort(entry.tag.toShort()).putShort(entry.type.toShort())
                putInt(entry.payload.size / unitSize(entry.type))
                if (entry.payload.size <= 4) {
                    put(entry.payload)
                    repeat(4 - entry.payload.size) { put(0) }
                } else {
                    putInt(requireNotNull(external[index]))
                }
            }
            putInt(0)
            entries.forEachIndexed { index, entry ->
                external[index]?.let { offset ->
                    position(offset - baseOffset)
                    put(entry.payload)
                    if (entry.payload.size and 1 == 1) put(0)
                }
            }
        }.array()
    }

    private fun unitSize(type: Int): Int = when (type) {
        SHORT -> 2
        LONG -> 4
        RATIONAL -> 8
        else -> 1
    }

    private fun ascii(value: String) = (value + '\u0000').toByteArray(Charsets.US_ASCII)

    private data class GpsEntry(val tag: Int, val type: Int, val payload: ByteArray)
}
