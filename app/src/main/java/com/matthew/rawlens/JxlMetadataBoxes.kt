// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Capture-metadata boxes for JPEG XL files, mirroring what [JpegSaver]
 * writes through ExifInterface: Make/Model/Software, orientation,
 * dimensions, exposure trio, white balance, timestamps, and the full GPS
 * tag set when a fix is available.
 *
 * androidx.exifinterface cannot parse or write JXL, so this builds the
 * little-endian TIFF blob directly (same tag values as the JPEG path) and
 * [NativeJxlEncoder] stores it as an `Exif` BMFF box (with darktable's
 * 4-byte zero offset prefix) plus a minimal XMP packet as an `xml ` box.
 * Kept free of Android dependencies so the blobs are JVM-testable.
 */
object JxlMetadataBoxes {
    data class Boxes(val exif: ByteArray, val xmp: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Boxes) return false
            return exif.contentEquals(other.exif) && xmp.contentEquals(other.xmp)
        }

        override fun hashCode(): Int = 31 * exif.contentHashCode() + xmp.contentHashCode()
    }

    fun build(
        metadata: RawFrameMetadata,
        /** CaptureResult.CONTROL_AWB_MODE_* or null when no live result exists (offline merges). */
        awbAuto: Boolean?,
        captureTimeMillis: Long,
        displayP3: Boolean,
        gps: GpsLocation?,
        make: String,
        model: String,
        /** PQ stills are never sRGB: mark ColorSpace uncalibrated like P3. */
        hdrPq: Boolean = false
    ): Boxes {
        val dateTime = EXIF_DATE_FORMAT.format(captureTimeMillis)
        val software = "RawLens scene-referred developer"
        val exifFields = ArrayList<TiffField>()
        val exifSubFields = ArrayList<TiffField>()
        exifFields += TiffField(0x0100, LONG, 1, intBytes(metadata.imageWidth))
        exifFields += TiffField(0x0101, LONG, 1, intBytes(metadata.imageHeight))
        exifFields += TiffField(0x010F, ASCII, 0, asciiBytes(make))
        exifFields += TiffField(0x0110, ASCII, 0, asciiBytes(model))
        exifFields += TiffField(0x0131, ASCII, 0, asciiBytes(software))
        exifFields += TiffField(0x0132, ASCII, 0, asciiBytes(dateTime))
        exifFields += TiffField(0x0112, SHORT, 1, shortBytes(metadata.exifOrientation))
        exifFields += TiffField(0x011A, RATIONAL, 1, rationalBytes(72, 1))
        exifFields += TiffField(0x011B, RATIONAL, 1, rationalBytes(72, 1))
        exifFields += TiffField(0x0128, SHORT, 1, shortBytes(2))
        // EXIF's ColorSpace tag has no Display-P3 or PQ code; mark those as
        // uncalibrated rather than falsely sRGB (same rule as JpegSaver).
        exifFields += TiffField(0xA001, SHORT, 1, shortBytes(if (displayP3 || hdrPq) 0xFFFF else 1))

        exifSubFields += TiffField(0x9000, UNDEFINED, 4, "0232".toByteArray(Charsets.US_ASCII))
        exifSubFields += TiffField(0xA000, UNDEFINED, 4, "0100".toByteArray(Charsets.US_ASCII))
        exifSubFields += TiffField(0x9101, UNDEFINED, 4, byteArrayOf(1, 2, 3, 0))
        metadata.exposureTimeNanos?.let { nanos ->
            exposureRational(nanos)?.let { (num, den) ->
                exifSubFields += TiffField(0x829A, RATIONAL, 1, rationalBytes(num, den))
            }
        }
        metadata.aperture?.takeIf { it.isFinite() && it > 0f }?.let { aperture ->
            val num = (aperture * 1000f).roundToInt()
            exifSubFields += TiffField(0x829D, RATIONAL, 1, rationalBytes(num, 1000))
        }
        metadata.sensitivityIso?.let { iso ->
            exifSubFields += TiffField(0x8827, SHORT, 1, shortBytes(iso.coerceIn(0, 65535)))
        }
        metadata.focalLengthMm?.takeIf { it.isFinite() && it > 0f }?.let { focal ->
            val num = (focal * 1000f).roundToInt()
            exifSubFields += TiffField(0x920A, RATIONAL, 1, rationalBytes(num, 1000))
        }
        metadata.flashFired?.let { fired ->
            exifSubFields += TiffField(0x9209, SHORT, 1, shortBytes(if (fired) 1 else 0))
        }
        awbAuto?.let { auto ->
            exifSubFields += TiffField(0xA403, SHORT, 1, shortBytes(if (auto) 0 else 1))
        }

        val gpsFields = gps?.let(::gpsFields)
        // Layout: header(8) IFD0 exifSubIfd gpsSubIfd, each followed by its data.
        val ifd0Size = ifdSize(exifFields.size + 2)
        val exifSubSize = ifdSize(exifSubFields.size)
        val gpsSubSize = ifdSize(gpsFields?.size ?: 0)
        val exifDataSize = exifFields.sumOf { dataSize(it) }
        val exifSubDataSize = exifSubFields.sumOf { dataSize(it) }
        val exifSubOffset = 8 + ifd0Size + exifDataSize
        val gpsSubOffset = exifSubOffset + exifSubSize + exifSubDataSize
        val out = ByteBuffer.allocate(
            gpsSubOffset + gpsSubSize + (gpsFields?.sumOf { dataSize(it) } ?: 0)
        ).order(ByteOrder.LITTLE_ENDIAN)
        out.put("II".toByteArray(Charsets.US_ASCII))
        out.putShort(42)
        out.putInt(8)
        val allIfd0 = exifFields + listOf(
            TiffField(0x8769, LONG, 1, intBytes(exifSubOffset))
        ) + (gpsFields?.let { listOf(TiffField(0x8825, LONG, 1, intBytes(gpsSubOffset))) } ?: emptyList())
        writeIfd(out, 8, allIfd0)
        writeIfd(out, exifSubOffset, exifSubFields)
        if (gpsFields != null) writeIfd(out, gpsSubOffset, gpsFields)
        val exif = out.array()

        // P3-ness lives in the EXIF ColorSpace tag; XMP carries no color tag.
        val xmp = buildXmp(
            metadata = metadata,
            awbAuto = awbAuto,
            dateTime = dateTime,
            make = make,
            model = model,
            software = software
        )
        return Boxes(exif, xmp)
    }

    private fun gpsFields(gps: GpsLocation): List<TiffField> {
        val fields = ArrayList<TiffField>()
        fields += TiffField(0x0000, BYTE, 4, byteArrayOf(2, 3, 0, 0))
        fields += TiffField(0x0001, ASCII, 0, asciiBytes(gps.latitudeRef))
        fields += TiffField(0x0002, RATIONAL, 3, gps.latitudeDms().toRationalBytes())
        fields += TiffField(0x0003, ASCII, 0, asciiBytes(gps.longitudeRef))
        fields += TiffField(0x0004, RATIONAL, 3, gps.longitudeDms().toRationalBytes())
        gps.altitudeMeters?.takeIf { it.isFinite() }?.let { alt ->
            fields += TiffField(0x0005, BYTE, 1, byteArrayOf(if (alt < 0) 1 else 0))
            val num = (kotlin.math.abs(alt) * 1000.0).roundToInt()
            fields += TiffField(0x0006, RATIONAL, 1, rationalBytes(num, 1000))
        }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = gps.timeMillis
        }
        val stamp = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        stamp.putInt(utc.get(Calendar.HOUR_OF_DAY)).putInt(1)
        stamp.putInt(utc.get(Calendar.MINUTE)).putInt(1)
        stamp.putInt(utc.get(Calendar.SECOND)).putInt(1)
        fields += TiffField(0x0007, RATIONAL, 3, stamp.array())
        fields += TiffField(0x001D, ASCII, 0, asciiBytes(GpsLocation.dateStamp(gps.timeMillis)))
        fields += TiffField(0x0010, ASCII, 0, asciiBytes("GPS"))
        return fields
    }

    private fun buildXmp(
        metadata: RawFrameMetadata,
        awbAuto: Boolean?,
        dateTime: String,
        make: String,
        model: String,
        software: String
    ): ByteArray {
        // Minimal but valid XMP: identifying + capture trio only. History
        // and develop parameters stay in the sidecar/DNG domain.
        fun attr(name: String, value: String?) =
            if (value == null) "" else " $name=\"${escapeXml(value)}\""
        val exposure = metadata.exposureTimeNanos?.let { nanos ->
            exposureRational(nanos)?.let { (num, den) -> "$num/$den" }
        }
        val packet = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\"" +
            " xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\"" +
            " xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"" +
            attr("tiff:Make", make) +
            attr("tiff:Model", model) +
            " tiff:ImageWidth=\"${metadata.imageWidth}\"" +
            " tiff:ImageLength=\"${metadata.imageHeight}\"" +
            " tiff:Orientation=\"${metadata.exifOrientation}\"" +
            attr("tiff:DateTime", dateTime) +
            attr("exif:ExposureTime", exposure) +
            attr(
                "exif:FNumber",
                metadata.aperture?.takeIf { it.isFinite() && it > 0f }?.let {
                    "${(it * 1000f).roundToInt()}/1000"
                }
            ) +
            attr("exif:PhotographicSensitivity", metadata.sensitivityIso?.toString()) +
            attr(
                "exif:FocalLength",
                metadata.focalLengthMm?.takeIf { it.isFinite() && it > 0f }?.let {
                    "${(it * 1000f).roundToInt()}/1000"
                }
            ) +
            attr("exif:WhiteBalance", awbAuto?.let { if (it) "0" else "1" }) +
            " dc:format=\"image/jxl\"" +
            attr("xmp:CreatorTool", software) +
            "/>\n" +
            " </rdf:RDF>\n" +
            "</x:xmpmeta>\n" +
            "<?xpacket end=\"w\"?>"
        return packet.toByteArray(Charsets.UTF_8)
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private data class TiffField(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

    private fun ifdSize(fieldCount: Int): Int = 2 + fieldCount * 12 + 4

    private fun dataSize(field: TiffField): Int =
        if (field.payload.size <= 4) 0 else field.payload.size

    private fun writeIfd(out: ByteBuffer, base: Int, fields: List<TiffField>) {
        out.position(base)
        out.putShort(fields.size.toShort())
        var cursor = base + ifdSize(fields.size)
        for (field in fields) {
            out.putShort(field.tag.toShort())
            out.putShort(field.type.toShort())
            val count = if (field.type == ASCII) field.payload.size else field.count
            out.putInt(count)
            if (field.payload.size <= 4) {
                out.put(field.payload)
                repeat(4 - field.payload.size) { out.put(0) }
            } else {
                out.putInt(cursor)
                val at = out.position()
                out.position(cursor)
                out.put(field.payload)
                cursor += field.payload.size
                out.position(at)
            }
        }
        out.putInt(0)
        out.position(cursor)
    }

    private fun asciiBytes(value: String): ByteArray =
        (value + "\u0000").toByteArray(Charsets.US_ASCII)

    private fun shortBytes(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun rationalBytes(num: Int, den: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(num).putInt(den).array()

    private fun rationalBytes(num: Long, den: Long): ByteArray =
        rationalBytes(num.coerceIn(0, 0xFFFFFFFFL).toInt(), den.coerceIn(1, 0xFFFFFFFFL).toInt())

    private fun exposureRational(nanos: Long): Pair<Long, Long>? {
        if (nanos <= 0) return null
        var num = nanos
        var den = 1_000_000_000L
        val g = gcd(num, den)
        num /= g
        den /= g
        while (num > 0xFFFFFFFFL || den > 0xFFFFFFFFL) {
            num = (num + 1) / 2
            den = (den + 1) / 2
        }
        return num to den
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7

    private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
}
