// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Merged linear camera RGB ready for prime-DNG serialization: three float
 * samples per pixel, already black-subtracted, normalized, and reference-
 * fallback-resolved by the burst merge. Alpha is not carried: use
 * [toTriplets] to drop it at the caller boundary.
 */
data class MergedLinearRgb(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(width > 0 && height > 0) { "Linear RGB frame must have positive dimensions" }
        require(rgb.size == Math.multiplyExact(Math.multiplyExact(width, height), 3)) {
            "Linear RGB must hold exactly three samples per pixel"
        }
        require(rgb.all(Float::isFinite)) { "Linear RGB DNG cannot contain NaN or infinity" }
    }

    companion object {
        fun toTriplets(rgba: FloatArray): FloatArray {
            require(rgba.size % 4 == 0) { "RGBA input must contain four floats per pixel" }
            // Sharded over the shared worker pool: shards cover disjoint
            // pixels with the serial index math untouched, so the repack is
            // bitwise-identical at any worker count (200MB pass at full res).
            val out = FloatArray(rgba.size / 4 * 3)
            RawSrWorkers.forEachShard(rgba.size / 4) { p0, p1 ->
                for (p in p0 until p1) {
                    val o = p * 3
                    val r = p * 4
                    out[o] = rgba[r]
                    out[o + 1] = rgba[r + 1]
                    out[o + 2] = rgba[r + 2]
                }
            }
            return out
        }
    }
}

/**
 * Truthful merge provenance for the prime DNG ImageDescription block.
 * [rejectedFrames] must equal selected − accepted: silently dropped frames
 * are a lie about the burst, so the constructor enforces the arithmetic.
 */
data class MergeProvenance(
    val algorithmVersion: String,
    val selectedFrames: Int,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    /** Sensor-nanos timestamp of the reference frame; Long.MIN_VALUE when unknown (never fabricated). */
    val referenceTimestampNs: Long,
    /** Output scale relative to the sensor crop (1 = scale-1 native merge). */
    val outputScale: Int,
    val sourceCameraId: String,
    val lensShadingApplied: Boolean
) {
    init {
        require(algorithmVersion.isNotBlank()) { "Merge algorithm version must be recorded" }
        require(selectedFrames >= 1) { "Selected frame count must be at least 1" }
        require(acceptedFrames in 1..selectedFrames) {
            "Accepted frames must lie within 1..selectedFrames"
        }
        require(rejectedFrames == selectedFrames - acceptedFrames) {
            "Rejected frames must equal selected − accepted"
        }
        require(outputScale >= 1) { "Output scale must be at least 1" }
        require(sourceCameraId.isNotBlank()) { "Source camera ID must be recorded" }
    }
}

/**
 * Separate uncompressed 16-bit Linear RGB (LinearRaw, PhotometricInterpretation
 * 34892) prime-DNG writer. Deliberately independent from [DngSaver] and the
 * source-Bayer writers: no CFA tags are emitted, and float camera RGB is
 * quantized only here, at the writer boundary.
 *
 * Quantization policy (documented, parser-tested): each sample is clamped to
 * [0, 1], multiplied by [QUANTIZATION_SCALE], and rounded half up to an
 * unsigned 16-bit code. merged values above 1.0 (unclipped highlight energy)
 * saturate at white; WhiteLevel 65535 and BlackLevel 0 therefore describe the
 * file exactly. Merged data is already black-subtracted, so BlackLevel 0 is
 * truthful, not a default.
 *
 * Metadata policy: only reference-frame facts are copied (capture, exposure,
 * ISO, camera identity, white point, calibration, colour transforms,
 * orientation, sensor noise model). There is no lens-geometry source in
 * [RawFrameMetadata], so no lens tags are written; lens-shading state travels
 * in the provenance block instead of a fabricated tag. The NoiseProfile tag
 * carries the same DNG RGB S/O pairs as the source-Bayer path (via
 * [DngNoiseProfile]) so converters can apply profiled chroma denoise; it is
 * omitted — never fabricated — when the reference metadata carries no model.
 * BaselineNoise is deliberately unwritten: absent means 1.0, which is the
 * truthful value for un-denoised merge output.
 */
object LinearRgbDngWriter {
    const val ALGORITHM_VERSION = "RawLens-RawSr/4D-scale1"
    const val QUANTIZATION_SCALE = 65535.0
    const val DERIVATION = "DerivedFromRawBurst"

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val SRATIONAL = 10
    private const val DOUBLE = 12

    fun write(
        output: OutputStream,
        image: MergedLinearRgb,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
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
        val byteCount = Math.multiplyExact(
            Math.multiplyExact(image.width * image.height, 3), Short.SIZE_BYTES
        )
        val entries = arrayListOf(
            Entry(254, LONG, longs(0)),
            Entry(256, LONG, longs(image.width)), Entry(257, LONG, longs(image.height)),
            Entry(258, SHORT, shorts(16, 16, 16)), Entry(259, SHORT, shorts(1)),
            Entry(262, SHORT, shorts(34892)),
            Entry(270, ASCII, ascii(provenanceBlock(provenance))),
            Entry(271, ASCII, ascii(Build.MANUFACTURER)),
            Entry(272, ASCII, ascii(Build.MODEL)),
            Entry(273, LONG, ByteArray(4), patchStripOffset = true),
            Entry(274, SHORT, shorts(metadata.exifOrientation)),
            Entry(277, SHORT, shorts(3)), Entry(278, LONG, longs(image.height)),
            Entry(279, LONG, longs(byteCount)),
            Entry(284, SHORT, shorts(1)), Entry(305, ASCII, ascii("RawLens")),
            Entry(339, SHORT, shorts(1)),
            Entry(50706, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50707, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50708, ASCII, ascii(cameraName)),
            Entry(50713, SHORT, shorts(2, 2)),
            Entry(50714, RATIONAL, rationals(doubleArrayOf(0.0, 0.0, 0.0), signed = false)),
            Entry(50717, LONG, longs(65535)),
            Entry(50721, SRATIONAL, rationals(matrix, signed = true)),
            Entry(50728, RATIONAL, rationals(neutral, signed = false)),
            Entry(50778, SHORT, shorts(metadata.referenceIlluminant1 ?: 21))
        )
        // A non-positive exposure carries no information; omit rather than
        // write a zero-second tag. Same truthfulness rule as the ISO range.
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
        // pairs as the source-Bayer path. Omitted when the reference metadata
        // carries no model (cfaPattern is nullable, so no requireNotNull).
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

        val row = ByteBuffer.allocate(image.width * 3 * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until image.height) {
            row.clear()
            val start = y * image.width * 3
            for (x in 0 until image.width * 3) {
                row.putShort(quantize(image.rgb[start + x]).toShort())
            }
            output.write(row.array())
        }
    }

    /**
     * Documented quantization: clamp to [0, 1], scale, round half up.
     * Visible for parser tests; the writer boundary is its only caller.
     */
    fun quantize(sample: Float): Int {
        require(sample.isFinite()) { "Linear RGB DNG cannot contain NaN or infinity" }
        return (sample.coerceIn(0f, 1f).toDouble() * QUANTIZATION_SCALE).roundToInt()
            .coerceIn(0, 65535)
    }

    /**
     * Deterministic `key=value;` provenance block for ImageDescription.
     * Unknown reference timestamps serialize as `unknown`, never as a
     * fabricated zero. Visible for parser tests.
     */
    fun provenanceBlock(provenance: MergeProvenance): String {
        val timestamp = if (provenance.referenceTimestampNs == Long.MIN_VALUE) "unknown"
            else provenance.referenceTimestampNs.toString()
        return "RawLens LinearRGB prime DNG from RAW burst merge. " +
            "algorithm=${provenance.algorithmVersion};" +
            "selectedFrames=${provenance.selectedFrames};" +
            "acceptedFrames=${provenance.acceptedFrames};" +
            "rejectedFrames=${provenance.rejectedFrames};" +
            "referenceTimestampNs=$timestamp;" +
            "outputScale=${provenance.outputScale};" +
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
    // OEM strings are never null on device, but a JVM-stubbed Build field is:
    // record "unknown" rather than crashing the save or fabricating identity.
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
