// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        hdrPq: Boolean = false,
        outputWidth: Int = metadata.imageWidth,
        outputHeight: Int = metadata.imageHeight
    ): Boxes {
        val dateTime = DngExifDirectory.dateTime(captureTimeMillis)
        val effectiveAwbAuto = metadata.awbMode?.let { it == 1 } ?: awbAuto
        require(outputWidth > 0 && outputHeight > 0)
        val software = "RawLens scene-referred developer"
        val exifFields = ArrayList<TiffField>()
        val exifSubFields = ArrayList<TiffField>()
        exifFields += TiffField(0x0100, LONG, 1, intBytes(outputWidth))
        exifFields += TiffField(0x0101, LONG, 1, intBytes(outputHeight))
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
        exifSubFields += fields(DngExifDirectory.fields(metadata, captureTimeMillis))
        exifSubFields += TiffField(0xA001, SHORT, 1, shortBytes(if (displayP3 || hdrPq) 0xFFFF else 1))
        exifSubFields += TiffField(0xA002, LONG, 1, intBytes(outputWidth))
        exifSubFields += TiffField(0xA003, LONG, 1, intBytes(outputHeight))
        // Legacy callers may only have the AWB argument. Frozen frame modes take precedence.
        if (metadata.awbMode == null && effectiveAwbAuto != null) {
            exifSubFields += TiffField(0xA403, SHORT, 1, shortBytes(if (effectiveAwbAuto) 0 else 1))
        }
        exifSubFields += TiffField(0xA000, UNDEFINED, 4, "0100".toByteArray(Charsets.US_ASCII))
        exifSubFields += TiffField(0x9101, UNDEFINED, 4, byteArrayOf(1, 2, 3, 0))
        val gpsFields = gps?.let { fields(GpsTiffDirectory.fields(it)) }
        // Layout: header(8) IFD0 exifSubIfd gpsSubIfd, each followed by its data.
        val ifd0Size = ifdSize(exifFields.size + 1 + if (gpsFields != null) 1 else 0)
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
            metadata = metadata.copy(imageWidth = outputWidth, imageHeight = outputHeight),
            awbAuto = effectiveAwbAuto,
            dateTime = Instant.ofEpochMilli(captureTimeMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            make = make,
            model = model,
            software = software
        )
        return Boxes(exif, xmp)
    }

    private fun fields(source: TiffFields?): List<TiffField> = source?.payloads?.mapIndexed { i, bytes ->
        TiffField(source.descriptors[i * 3], source.descriptors[i * 3 + 1],
            source.descriptors[i * 3 + 2], bytes)
    }.orEmpty()

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
            attr("xmp:CreateDate", dateTime) +
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
        if (field.payload.size <= 4) 0 else (field.payload.size + 1) and -2

    private fun writeIfd(out: ByteBuffer, base: Int, fields: List<TiffField>) {
        out.position(base)
        out.putShort(fields.size.toShort())
        var cursor = base + ifdSize(fields.size)
        for (field in fields.sortedBy { it.tag }) {
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
                cursor += dataSize(field)
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

    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7

}
