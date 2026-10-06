// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
import java.util.Locale

/** Per-frame capture facts for the HDR+ frames listing (null = unreported). */
data class HdrPlusFrameInfo(
    val exposureTimeNanos: Long?,
    val sensitivityIso: Int?
)

/**
 * Provenance for the HDR+ DNG ImageDescription block: an explicit
 * "RawLens HDR+ DNG — merged burst" record. Like [MosaicSrProvenance],
 * every field is a measured save fact, never inferred.
 */
data class HdrPlusProvenance(
    val mergedFrames: Int,
    val highQuality: Boolean,
    val strength: Float,
    val tileSize: Int,
    val searchDistance: Int,
    val referenceIndex: Int,
    val referenceTimestampNs: Long,
    val referenceFrameNumber: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceCameraId: String,
    val mergeMs: Long,
    val packMs: Long,
    val gpuMs: Double,
    val unpackMs: Long,
    val frames: List<HdrPlusFrameInfo>
) {
    init {
        require(mergedFrames >= 2) { "Merged frame count must be at least 2" }
        require(strength.isFinite()) { "Strength must be finite" }
        require(tileSize in 16..64) { "Tile size must lie within 16..64" }
        require(searchDistance in 32..128) { "Search distance must lie within 32..128" }
        require(referenceIndex in 0 until mergedFrames) { "Reference index out of range" }
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(sourceCameraId.isNotBlank()) { "Source camera ID must be recorded" }
        require(mergeMs >= 0 && packMs >= 0 && unpackMs >= 0) { "Timings must be non-negative" }
        require(gpuMs.isFinite() && gpuMs >= 0.0) { "GPU time must be finite and non-negative" }
        require(frames.size == mergedFrames) { "Frame notes must cover every merged frame" }
    }
}

/**
 * 16-bit normalized CFA DNG writer for HDR+ merges. Same tag set as the
 * 32-bit float merged writer ([FloatCfaDngWriter]) and the same uint16
 * packing policy as the other derived-Bayer writers ([MosaicSrDngWriter]):
 * samples are black-subtracted and normalized, so clamp [0, 1], ×65535,
 * round half up ([LinearRgbDngWriter.quantize]) describes the file
 * exactly, with BlackLevel 0 and WhiteLevel 65535. Capture facts live in
 * the EXIF sub-IFD ([DngExifDirectory], like RAWR's DNG EXIF block) plus
 * IFD0 exposure/ISO, with the merge provenance in ImageDescription.
 *
 * TIFF serialization is owned by the pinned TinyDNG writer
 * ([TinyDngImageWriter], header-first streaming): this object only assembles
 * the tag field lists and quantizes the pixels. Geometry, compression,
 * photometric, strip, and sample-format tags come from TinyDNG itself and
 * must never be passed as custom fields.
 */
object HdrPlusDngWriter {
    /**
     * @param captureTimeMillis wall-clock save time for the EXIF date tags
     *   (the savers' captureId); null omits all date tags.
     * @param provenance merge facts for the ImageDescription block; null
     *   omits it.
     */
    fun write(
        output: OutputStream,
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        gps: GpsLocation? = null,
        captureTimeMillis: Long? = null,
        provenance: HdrPlusProvenance? = null
    ) {
        cfa.requireAmazeCompatible()
        require(cfa.values.size == cfa.width * cfa.height)
        require(cfa.values.all(Float::isFinite)) { "HDR+ DNG cannot contain NaN or infinity" }

        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.let(::dngMatrix)
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${oem(Build.MANUFACTURER)} ${oem(Build.MODEL)} (${metadata.cameraId})"
        require(metadata.exifOrientation in 1..8)
        if (provenance != null) {
            require(metadata.cameraId == provenance.sourceCameraId) {
                "Provenance source camera must be the reference metadata camera"
            }
        }
        val fields = TiffFields().apply {
            if (provenance != null) ascii(270, provenanceBlock(provenance))
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
            // BlackLevel count must cover the 2x2 repeat grid (one level per
            // Bayer phase): count 1 is rejected by rawspeed/Darktable
            // ("BLACKLEVEL entry is too small"). All phases are zero here:
            // merged samples are black-subtracted and normalized.
            shorts(50714, 0, 0, 0, 0)
            longs(50717, 65535)
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
        TinyDngImageWriter.write(output, cfa.width, cfa.height,
            samplesPerPixel = 1, bitsPerSample = 16, sampleFormat = 1,
            photometric = 32803, rowsPerStrip = cfa.height,
            fields = fields, exif = exif,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = 1) {
            val bytes = ByteArray(Math.multiplyExact(cfa.width * cfa.height, 2))
            var o = 0
            for (v in cfa.values) {
                val q = LinearRgbDngWriter.quantize(v)
                bytes[o] = q.toByte()
                bytes[o + 1] = (q shr 8).toByte()
                o += 2
            }
            bytes
        }
    }

    /**
     * Human-readable multi-line provenance record for ImageDescription, in
     * the sectioned style of RAWR's multiframe descriptions (header,
     * PARAMETERS, base-frame selection, per-frame notes, TIMINGS, source).
     * Deterministic for a fixed input; unknown values serialize as `?`,
     * never fabricated.
     */
    fun provenanceBlock(provenance: HdrPlusProvenance): String {
        val out = StringBuilder("Captured with RawLens\nHDR+ GPU merge\n")
        val mp = kotlin.math.round(
            provenance.sourceWidth.toDouble() * provenance.sourceHeight / 1e5) / 10.0
        out.append("\nPARAMETERS\n")
            .append("- Frames: ${provenance.mergedFrames}\n")
            .append("- Output: ${provenance.sourceWidth}x${provenance.sourceHeight} " +
                "(1.00x, ~${"%.1f".format(Locale.US, mp)} MP)\n")
            .append(if (provenance.highQuality)
                "- Merge: HDR+ frequency (tile alignment + per-frequency Wiener merge)\n"
            else "- Merge: HDR+ spatial (tile alignment + robust average)\n")
            .append("- HDR+ strength: ${"%.3f".format(Locale.US, provenance.strength)}\n")
            .append("- HDR+ tile size: ${provenance.tileSize}\n")
            .append("- HDR+ search distance: ${provenance.searchDistance}\n")
        out.append("\nBase Frame Selection:\n")
            .append("- Mode: middle\n")
            .append("- Reference: chronological middle " +
                "(frame ${provenance.referenceIndex + 1} of ${provenance.mergedFrames})\n")
        out.append("\nFrames:\n")
        provenance.frames.forEachIndexed { index, frame ->
            val exp = frame.exposureTimeNanos?.takeIf { it > 0 }
                ?.let { "%.1f".format(Locale.US, it / 1e6) + " ms" } ?: "? ms"
            val iso = frame.sensitivityIso?.takeIf { it > 0 }?.toString() ?: "?"
            out.append("- F${index + 1}: $exp, ISO $iso")
            if (index == provenance.referenceIndex) out.append(" (reference)")
            out.append('\n')
        }
        out.append("\nTIMINGS\n")
            .append("- Merge: ${provenance.mergeMs} ms " +
                "(pack ${provenance.packMs} ms, " +
                "GPU ${"%.1f".format(Locale.US, provenance.gpuMs)} ms, " +
                "unpack ${provenance.unpackMs} ms)\n")
        val timestamp = if (provenance.referenceTimestampNs == Long.MIN_VALUE) "?"
            else provenance.referenceTimestampNs.toString()
        val frameNumber = if (provenance.referenceFrameNumber < 0) "?"
            else provenance.referenceFrameNumber.toString()
        out.append("\nSource:\n")
            .append("- Camera: ${provenance.sourceCameraId}\n")
            .append("- Dimensions: ${provenance.sourceWidth}x${provenance.sourceHeight}\n")
            .append("- Reference timestamp: $timestamp ns\n")
            .append("- Reference frame: $frameNumber\n")
            .append("- Quantization: clamp[0,1]*65535 round-half-up\n")
        return out.toString()
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
