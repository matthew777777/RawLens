// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-FileCopyrightText: 2010-2026 darktable developers
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream

/**
 * Streaming 32-bit IEEE-float CFA DNG writer, adapted from darktable's HDR
 * DNG writer. TIFF serialization is owned by the pinned TinyDNG writer
 * ([TinyDngImageWriter], header-first streaming): this object only assembles
 * the tag field lists and packs the samples. Geometry, compression,
 * photometric, strip, and sample-format tags come from TinyDNG itself and
 * must never be passed as custom fields.
 */
object FloatCfaDngWriter {
    /**
     * @param baselineExposureEv display compensation for exposure-bracket merges
     *   (reference/shortest ratio in EV): merged samples live in the shortest
     *   frame's normalization, so external developers need this BaselineExposure
     *   (plus the reference ExposureTime/ISO below) to place brightness
     *   correctly. Null (default) writes no exposure tags — legacy behavior
     *   for uniform merges, which are already at native brightness.
     */
    fun write(
        output: OutputStream,
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        gps: GpsLocation? = null,
        baselineExposureEv: Double? = null
    ) {
        cfa.requireAmazeCompatible()
        require(cfa.values.size == cfa.width * cfa.height)
        require(cfa.values.all(Float::isFinite)) { "Float DNG cannot contain NaN or infinity" }

        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.let(::dngMatrix)
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${oem(Build.MANUFACTURER)} ${oem(Build.MODEL)} (${metadata.cameraId})"
        require(metadata.exifOrientation in 1..8)
        val fields = TiffFields().apply {
            ascii(271, oem(Build.MANUFACTURER))
            ascii(272, oem(Build.MODEL))
            shorts(274, metadata.exifOrientation)
            shorts(284, 1)
            ascii(305, "RawLens")
            shorts(33421, 2, 2)
            bytes(33422, cfaPattern(cfa.pattern))
            bytes(50706, byteArrayOf(1, 4, 0, 0))
            bytes(50707, byteArrayOf(1, 4, 0, 0))
            ascii(50708, cameraName)
            shorts(50713, 2, 2)
            rationals(50714, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
            longs(50717, 1)
            srationals(50721, matrix)
            rationals(50728, neutral)
            shorts(50778, metadata.referenceIlluminant1 ?: 21)
        }
        if (baselineExposureEv != null) {
            require(baselineExposureEv.isFinite() && kotlin.math.abs(baselineExposureEv) < 2000.0) {
                "BaselineExposure must be finite and sane"
            }
            fields.srationals(50730, doubleArrayOf(baselineExposureEv))
            metadata.exposureTimeNanos?.takeIf { it > 0 }?.let {
                fields.rationals(33434, doubleArrayOf(it / 1e9))
            }
            metadata.sensitivityIso?.takeIf { it in 1..65535 }?.let {
                fields.shorts(34855, it)
            }
        }
        // Preserve the full camera colour calibration. Omitting calibration/forward matrices
        // while retaining AsShotNeutral can change the rendering in external RAW developers.
        fun matrixTag(tag: Int, values: ImmutableDoubleValues?) {
            values?.let(::dngMatrix)?.let {
                require(it.size == 9 && it.all(Double::isFinite))
                fields.srationals(tag, it)
            }
        }
        matrixTag(50723, metadata.cameraCalibration1)
        matrixTag(50964, metadata.forwardMatrix1)
        if (metadata.colorMatrix2 != null && metadata.referenceIlluminant2 != null) {
            matrixTag(50722, metadata.colorMatrix2)
            matrixTag(50724, metadata.cameraCalibration2)
            matrixTag(50965, metadata.forwardMatrix2)
            fields.shorts(50779, metadata.referenceIlluminant2)
        }
        TinyDngImageWriter.write(output, cfa.width, cfa.height,
            samplesPerPixel = 1, bitsPerSample = 32, sampleFormat = 3,
            photometric = 32803, rowsPerStrip = cfa.height,
            fields = fields, exif = null,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = 1) {
            val bytes = ByteArray(Math.multiplyExact(cfa.width * cfa.height, 4))
            var o = 0
            for (v in cfa.values) {
                val bits = java.lang.Float.floatToRawIntBits(v.coerceAtLeast(0f))
                bytes[o] = bits.toByte()
                bytes[o + 1] = (bits shr 8).toByte()
                bytes[o + 2] = (bits shr 16).toByte()
                bytes[o + 3] = (bits shr 24).toByte()
                o += 4
            }
            bytes
        }
    }

    // Match TinyDngMetadata's RAW_SENSOR serialization. Camera2 snapshots are column-major
    // because getElement takes (column, row); TIFF/DNG matrix tags must be row-major.
    private fun dngMatrix(values: ImmutableDoubleValues): DoubleArray {
        require(values.size == 9)
        return DoubleArray(9) { values[(it % 3) * 3 + it / 3] }
    }

    // OEM strings are never null on device, but a JVM-stubbed Build field is:
    // record "unknown" rather than crashing the save or fabricating identity.
    private fun oem(value: String?) = value ?: "unknown"

    private fun cfaPattern(pattern: BayerPattern) = ByteArray(4) { index -> when (
        pattern.colorAt(index and 1, index shr 1)) {
        CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
    } }
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
