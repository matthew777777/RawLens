// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2

/**
 * TinyDNG writer for staggered dual-exposure RAW merges. This is the TinyDNG
 * half of the dual-RAW unification: the merged 16-bit Bayer mosaic is
 * streamed through [TinyDngImageWriter] with the same field conventions as
 * [HdrPlusDngWriter], instead of round-tripping through the platform
 * `DngCreator` plus a post-hoc metadata patch.
 *
 * Tag contract (matches what the old write-then-patch flow produced, so
 * existing dual-RAW files and the new ones describe the same pixels):
 * - DNG 1.4 version tags (uncompressed mosaic; no 1.7 reader needed).
 * - `BlackLevel` RATIONAL 1024 x4: the merge normalizes to
 *   `(sample - black) / headroom * 64511 + 1024`, so every Bayer phase sits
 *   on a 1024 pedestal.
 * - `WhiteLevel` 65535.
 * - `BaselineExposure` SRATIONAL `log2(headroom)`: the merge divides the
 *   mosaic by the reserved HDR headroom, and this tag tells developers to
 *   multiply it back.
 * - Exposure/ISO come from the low (short) frame's metadata, as before.
 * - GPS rides the standard GPS IFD when a fix is present (the old
 *   `DngCreator.setLocation` equivalent).
 */
object DualRawDngWriter {
    const val DESCRIPTION =
        "RawLens experimental dual-exposure RAW; simple Vulkan Bayer merge; " +
            "unclipped long samples preserved; short replaces clipped samples only"

    /**
     * @param mosaic little-endian u16 full-frame mosaic, exactly
     * `width * height` samples; read from position 0.
     * @param headroom linear HDR headroom the merge divided by (> 0);
     * written as `BaselineExposure = log2(headroom)`.
     * @param orientation EXIF orientation value (1..8).
     */
    fun write(
        output: OutputStream,
        mosaic: ByteBuffer,
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        headroom: Double,
        orientation: Int,
        gps: GpsLocation?,
        captureTimeMillis: Long,
        description: String = DESCRIPTION
    ) {
        require(width > 0 && height > 0) { "Dual RAW DNG needs positive dimensions" }
        val count = Math.multiplyExact(width, height)
        require(mosaic.capacity() >= Math.multiplyExact(count, 2)) {
            "Dual RAW mosaic holds ${mosaic.capacity()} bytes, needs ${count * 2}"
        }
        val cfa = checkNotNull(metadata.cfaPattern) {
            "Dual RAW merge needs a Bayer CFA pattern"
        }
        require(orientation in 1..8) { "Dual RAW DNG orientation must be 1..8" }
        require(headroom.isFinite() && headroom > 0.0) {
            "Dual RAW headroom must be positive and finite"
        }

        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.let(::dngMatrix)
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${oem(Build.MANUFACTURER)} ${oem(Build.MODEL)} (${metadata.cameraId})"
        val fields = TiffFields().apply {
            ascii(270, description)
            ascii(271, oem(Build.MANUFACTURER))
            ascii(272, oem(Build.MODEL))
            shorts(274, orientation)
            shorts(284, 1)
            ascii(305, "RawLens")
            shorts(33421, 2, 2)
            bytes(33422, cfaPattern(cfa))
            bytes(50706, byteArrayOf(1, 4, 0, 0))
            bytes(50707, byteArrayOf(1, 4, 0, 0))
            ascii(50708, cameraName)
            ascii(50936, "RawLens Camera2 calibration")
            shorts(50713, 2, 2)
            // One level per Bayer phase of the 2x2 repeat grid (count 1 is
            // rejected by rawspeed/Darktable). RATIONAL to match the files
            // this writer replaces; every phase sits on the 1024 pedestal.
            rationals(50714, doubleArrayOf(1024.0, 1024.0, 1024.0, 1024.0))
            longs(50717, 65535)
            srationals(50730, doubleArrayOf(log2(headroom)))
            srationals(50721, matrix)
            rationals(50728, neutral)
            shorts(50778, metadata.referenceIlluminant1 ?: 21)
        }
        metadata.exposureTimeNanos?.takeIf { it > 0 }?.let {
            fields.rationals(33434, doubleArrayOf(it / 1e9))
        }
        metadata.sensitivityIso?.takeIf { it in 1..65535 }?.let {
            fields.shorts(34855, it)
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
        val exif = DngExifDirectory.fields(metadata, captureTimeMillis)
        TinyDngImageWriter.write(output, width, height,
            samplesPerPixel = 1, bitsPerSample = 16, sampleFormat = 1,
            photometric = 32803, rowsPerStrip = height,
            fields = fields, exif = exif,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = 1) {
            val bytes = ByteArray(Math.multiplyExact(count, 2))
            val src = mosaic.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            src.position(0)
            src.get(bytes)
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
