// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JPEG uses the same capture facts and rational encoding as the DNG EXIF IFD. */
internal object CaptureExifAttributes {
    private val names = mapOf(
        33434 to "ExposureTime", 33437 to "FNumber", 34850 to "ExposureProgram",
        34855 to "PhotographicSensitivity", 34864 to "SensitivityType", 34867 to "ISOSpeed",
        36867 to "DateTimeOriginal", 36868 to "DateTimeDigitized",
        36880 to "OffsetTime", 36881 to "OffsetTimeOriginal", 36882 to "OffsetTimeDigitized",
        37377 to "ShutterSpeedValue", 37378 to "ApertureValue", 37380 to "ExposureBiasValue",
        37382 to "SubjectDistance", 37385 to "Flash", 37386 to "FocalLength",
        37520 to "SubSecTime", 37521 to "SubSecTimeOriginal", 37522 to "SubSecTimeDigitized",
        41495 to "SensingMethod", 41986 to "ExposureMode", 41987 to "WhiteBalance",
        41988 to "DigitalZoomRatio", 42034 to "LensSpecification", 42036 to "LensModel"
    )

    fun writeTo(exif: ExifInterface, metadata: RawFrameMetadata, timeMillis: Long) {
        val fields = DngExifDirectory.fields(metadata, timeMillis) ?: return
        fields.payloads.forEachIndexed { index, bytes ->
            val tag = fields.descriptors[index * 3]
            val name = names[tag] ?: return@forEachIndexed
            val type = fields.descriptors[index * 3 + 1]
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val text = when (type) {
                TiffFields.ASCII -> bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
                TiffFields.SHORT -> (0 until bytes.size / 2).joinToString(",") {
                    (b.short.toInt() and 65535).toString()
                }
                TiffFields.LONG -> (b.int.toLong() and 0xffffffffL).toString()
                TiffFields.RATIONAL, TiffFields.SRATIONAL -> {
                    (0 until bytes.size / 8).joinToString(",") {
                        val n = b.int
                        val d = b.int
                        "${if (type == TiffFields.SRATIONAL) n.toLong() else n.toLong() and 0xffffffffL}/${d.toLong() and 0xffffffffL}"
                    }
                }
                else -> return@forEachIndexed
            }
            exif.setAttribute(name, text)
        }
        exif.setAttribute(ExifInterface.TAG_DATETIME, DngExifDirectory.dateTime(timeMillis))
    }
}
