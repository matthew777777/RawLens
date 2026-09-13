// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Mosaic SR reconstruction output: one float sample per target CFA site on the
 * [MosaicSrReconstructor] grid. Single-sample by construction — this type
 * cannot carry demosaiced RGB, which is what makes re-mosaicing unrepresentable.
 */
data class MosaicSrCfa(
    val width: Int,
    val height: Int,
    val pattern: BayerPattern,
    val samples: FloatArray
) {
    init {
        require(width > 0 && height > 0) { "Mosaic CFA must have positive dimensions" }
        require(samples.size == width * height) { "Mosaic CFA must hold one sample per site" }
        require(samples.all(Float::isFinite)) { "Mosaic CFA DNG cannot contain NaN or infinity" }
    }
}

/**
 * Provenance for the Mosaic SR DNG ImageDescription block: an explicit
 * "RawLens Mosaic SR DNG — derived Bayer reconstruction" record. Like
 * [MergeProvenance], rejected must equal selected − accepted.
 */
data class MosaicSrProvenance(
    val selectedFrames: Int,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    val referenceTimestampNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceCameraId: String,
    val lensShadingApplied: Boolean
) {
    init {
        require(selectedFrames >= 1) { "Selected frame count must be at least 1" }
        require(acceptedFrames in 1..selectedFrames) {
            "Accepted frames must lie within 1..selectedFrames"
        }
        require(rejectedFrames == selectedFrames - acceptedFrames) {
            "Rejected frames must equal selected − accepted"
        }
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(sourceCameraId.isNotBlank()) { "Source camera ID must be recorded" }
    }
}

/**
 * One-sample 16-bit CFA DNG writer for Mosaic SR reconstructions. Separate
 * from the source-Bayer writers ([DngSaver], [FloatCfaDngWriter]) and the
 * Linear RGB prime writer ([LinearRgbDngWriter]): PhotometricInterpretation
 * 32803 with the TARGET grid's CFA tags, target active area, BlackLevel 0,
 * WhiteLevel 65535, the reference colour metadata, the output scale, and the
 * derived-Bayer-reconstruction provenance record.
 *
 * Quantization reuses the documented prime-DNG policy (clamp [0, 1], ×65535,
 * round half up) via [LinearRgbDngWriter.quantize]: reconstructed samples are
 * already black-subtracted and normalized, so the scale describes the file
 * exactly.
 *
 * The NoiseProfile tag carries the same DNG RGB S/O pairs as the source-Bayer
 * path and the Linear RGB prime writer (via [DngNoiseProfile]) so converters
 * can apply profiled denoise; it is omitted — never fabricated — when the
 * reference metadata carries no model.
 */
object MosaicSrDngWriter {
    const val DERIVATION = "MosaicSrDerivedBayer"

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val SRATIONAL = 10
    private const val DOUBLE = 12

    fun write(
        output: OutputStream,
        image: MosaicSrCfa,
        metadata: RawFrameMetadata,
        provenance: MosaicSrProvenance,
        gps: GpsLocation? = null
    ) {
        require(metadata.cameraId == provenance.sourceCameraId) {
            "Provenance source camera must be the reference metadata camera"
        }
        require(metadata.exifOrientation in 1..8)
        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.let(::dngMatrix)
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${Build.MANUFACTURER} ${Build.MODEL} (${metadata.cameraId})"
        val byteCount = Math.multiplyExact(image.width * image.height, Short.SIZE_BYTES)
        val entries = arrayListOf(
            Entry(254, LONG, longs(0)),
            Entry(256, LONG, longs(image.width)), Entry(257, LONG, longs(image.height)),
            Entry(258, SHORT, shorts(16)), Entry(259, SHORT, shorts(1)),
            Entry(262, SHORT, shorts(32803)),
            Entry(270, ASCII, ascii(provenanceBlock(provenance, image))),
            Entry(271, ASCII, ascii(Build.MANUFACTURER)),
            Entry(272, ASCII, ascii(Build.MODEL)),
            Entry(273, LONG, ByteArray(4), patchStripOffset = true),
            Entry(274, SHORT, shorts(metadata.exifOrientation)),
            Entry(277, SHORT, shorts(1)), Entry(278, LONG, longs(image.height)),
            Entry(279, LONG, longs(byteCount)),
            Entry(284, SHORT, shorts(1)), Entry(305, ASCII, ascii("RawLens")),
            Entry(339, SHORT, shorts(1)),
            Entry(33421, SHORT, shorts(2, 2)),
            Entry(33422, BYTE, cfaPattern(image.pattern)),
            Entry(50706, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50707, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50708, ASCII, ascii(cameraName)),
            Entry(50713, SHORT, shorts(2, 2)),
            // BlackLevel count must cover the 2x2 repeat grid (one level per
            // Bayer phase): count 1 is rejected by rawspeed/Darktable
            // ("BLACKLEVEL entry is too small"). Same shape as
            // FloatCfaDngWriter (count 4); all phases are zero here.
            Entry(50714, SHORT, shorts(0, 0, 0, 0)),
            Entry(50717, LONG, longs(65535)),
            Entry(50721, SRATIONAL, rationals(matrix, signed = true)),
            Entry(50728, RATIONAL, rationals(neutral, signed = false)),
            Entry(50778, SHORT, shorts(metadata.referenceIlluminant1 ?: 21)),
            Entry(50829, SHORT, shorts(0, 0, image.height, image.width))
        )
        metadata.exposureTimeNanos?.takeIf { it > 0 }?.let {
            entries += Entry(33434, RATIONAL, exposureRational(it))
        }
        metadata.sensitivityIso?.takeIf { it in 1..65535 }?.let {
            entries += Entry(34855, SHORT, shorts(it))
        }
        // Preserve the full camera colour calibration. Omitting calibration/forward matrices
        // while retaining AsShotNeutral can change the rendering in external RAW developers.
        fun matrixTag(tag: Int, values: ImmutableDoubleValues?) {
            values?.let(::dngMatrix)?.let {
                require(it.size == 9 && it.all(Double::isFinite))
                entries += Entry(tag, SRATIONAL, rationals(it, signed = true))
            }
        }
        matrixTag(50723, metadata.cameraCalibration1)
        matrixTag(50964, metadata.forwardMatrix1)
        if (metadata.colorMatrix2 != null && metadata.referenceIlluminant2 != null) {
            matrixTag(50722, metadata.colorMatrix2)
            matrixTag(50724, metadata.cameraCalibration2)
            matrixTag(50965, metadata.forwardMatrix2)
            entries += Entry(50779, SHORT, shorts(metadata.referenceIlluminant2))
        }
        // Sensor noise model for profiled denoise downstream; same RGB S/O
        // pairs as the source-Bayer path and the Linear RGB prime writer.
        // Omitted when the reference metadata carries no model (cfaPattern is
        // nullable, so no requireNotNull).
        metadata.cfaPattern?.let { pattern ->
            DngNoiseProfile.toRgb(metadata.noiseProfile?.toDoubleArray(), pattern)
                ?.let { entries += Entry(51041, DOUBLE, doubles(it)) }
        }
        if (gps != null) {
            // Placeholder value: the GPS sub-IFD offset is patched during
            // serialization once the external-data layout is known.
            entries += Entry(GpsTiffDirectory.TAG_GPS_IFD_POINTER, LONG, ByteArray(4))
        }
        entries.sortBy { it.tag }

        val ifdBytes = 2 + entries.size * 12 + 4
        var dataOffset = 8 + ifdBytes
        val externalOffsets = HashMap<Int, Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.payload.size > 4) {
                externalOffsets[index] = dataOffset
                dataOffset += entry.payload.size + (entry.payload.size and 1)
            }
        }
        val gpsOffset = dataOffset
        val gpsBlob = gps?.let { GpsTiffDirectory.build(it, gpsOffset) }
        val stripOffset = gpsOffset + (gpsBlob?.size ?: 0)
        val header = ByteBuffer.allocate(stripOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        header.putShort(entries.size.toShort())
        entries.forEachIndexed { index, entry ->
            header.putShort(entry.tag.toShort()).putShort(entry.type.toShort())
            header.putInt(entry.count)
            when {
                entry.patchStripOffset -> header.putInt(stripOffset)
                entry.tag == GpsTiffDirectory.TAG_GPS_IFD_POINTER -> header.putInt(gpsOffset)
                entry.payload.size <= 4 -> {
                    header.put(entry.payload)
                    repeat(4 - entry.payload.size) { header.put(0) }
                }
                else -> header.putInt(requireNotNull(externalOffsets[index]))
            }
        }
        header.putInt(0)
        entries.forEachIndexed { index, entry -> if (externalOffsets.containsKey(index)) {
            header.position(requireNotNull(externalOffsets[index]))
            header.put(entry.payload)
            if (entry.payload.size and 1 == 1) header.put(0)
        } }
        gpsBlob?.let {
            header.position(gpsOffset)
            header.put(it)
        }
        output.write(header.array())

        val row = ByteBuffer.allocate(image.width * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until image.height) {
            row.clear()
            val start = y * image.width
            for (x in 0 until image.width) {
                row.putShort(LinearRgbDngWriter.quantize(image.samples[start + x]).toShort())
            }
            output.write(row.array())
        }
    }

    /**
     * Deterministic `key=value;` provenance record for ImageDescription.
     * Unknown reference timestamps serialize as `unknown`, never fabricated.
     */
    fun provenanceBlock(provenance: MosaicSrProvenance, image: MosaicSrCfa): String {
        val timestamp = if (provenance.referenceTimestampNs == Long.MIN_VALUE) "unknown"
            else provenance.referenceTimestampNs.toString()
        return "RawLens Mosaic SR DNG - derived Bayer reconstruction. " +
            "algorithm=${MosaicSrReconstructor.ALGORITHM_VERSION};" +
            "selectedFrames=${provenance.selectedFrames};" +
            "acceptedFrames=${provenance.acceptedFrames};" +
            "rejectedFrames=${provenance.rejectedFrames};" +
            "referenceTimestampNs=$timestamp;" +
            "sourceDims=${provenance.sourceWidth}x${provenance.sourceHeight};" +
            "targetDims=${image.width}x${image.height};" +
            "targetPattern=${image.pattern};" +
            "outputScale=${MosaicSrReconstructor.LINEAR_SCALE};" +
            "sourceCameraId=${provenance.sourceCameraId};" +
            "derivation=$DERIVATION;" +
            "lensShadingApplied=${provenance.lensShadingApplied};" +
            "quantization=clamp[0,1]*65535 round-half-up"
    }

    // Match TinyDngMetadata's RAW_SENSOR serialization. Camera2 snapshots are column-major
    // because getElement takes (column, row); TIFF/DNG matrix tags must be row-major.
    private fun dngMatrix(values: ImmutableDoubleValues): DoubleArray {
        require(values.size == 9)
        return DoubleArray(9) { values[(it % 3) * 3 + it / 3] }
    }

    private fun exposureRational(exposureNanos: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(exposureNanos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).putInt(1_000_000_000)
        }.array()

    private fun cfaPattern(pattern: BayerPattern) = ByteArray(4) { index ->
        when (pattern.colorAt(index and 1, index shr 1)) {
            CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
        }
    }

    private data class Entry(
        val tag: Int, val type: Int, val payload: ByteArray, val patchStripOffset: Boolean = false
    ) {
        val count: Int = payload.size / when (type) {
            SHORT -> 2; LONG -> 4; RATIONAL, SRATIONAL, DOUBLE -> 8; else -> 1
        }
    }

    private fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    private fun ascii(value: String?) = ((value ?: "unknown") + '\u0000').toByteArray(Charsets.US_ASCII)
    private fun rationals(values: DoubleArray, signed: Boolean): ByteArray =
        ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { value ->
                val denominator = 1_000_000
                val numerator = (value * denominator).roundToInt()
                putInt(if (signed) numerator else numerator.coerceAtLeast(0)).putInt(denominator)
            }
        }.array()
    private fun doubles(values: DoubleArray) = ByteBuffer.allocate(values.size * 8)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putDouble) }.array()
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
