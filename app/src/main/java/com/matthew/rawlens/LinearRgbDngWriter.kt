// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
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
    val lensShadingApplied: Boolean,
    /**
     * Measured effective merged frame count (mean support, see
     * RawSrMergedNoise). 1.0 is the unscaled reference profile. Recorded
     * for audit; the NoiseProfile tag itself carries the scaled pairs.
     */
    val effectiveFrames: Double = 1.0
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
        require(effectiveFrames.isFinite() && effectiveFrames >= 1.0) {
            "Effective frames must be a finite count of at least 1"
        }
    }
}

/**
 * Separate uncompressed 16-bit Linear RGB (LinearRaw, PhotometricInterpretation
 * 34892) prime-DNG writer. Deliberately independent from [DngSaver] and the
 * source-Bayer writers: no CFA tags are emitted, and float camera RGB is
 * quantized only here, at the writer boundary.
 *
 * TIFF serialization is owned by the pinned TinyDNG writer
 * ([TinyDngImageWriter], header-first streaming): this object only assembles
 * the tag field lists (IFD layout, sorting, offsets, strip tables, and the
 * EXIF/GPS sub-IFDs are the native writer's job) and quantizes the pixels.
 * Geometry, compression, photometric, strip, and sample-format tags come from
 * TinyDNG itself and must never be passed as custom fields.
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
 *
 * Merged bursts may pass [noiseProfileOverride]: the post-merge S/O pairs
 * from [RawSrMergedNoise] (reference profile scaled by the measured
 * effective frame count). Null keeps the reference profile exactly.
 */
object LinearRgbDngWriter {
    const val ALGORITHM_VERSION = "RawLens-RawSr/4M-scale1-neutral-highlights"
    const val QUANTIZATION_SCALE = 65535.0
    const val DERIVATION = "DerivedFromRawBurst"
    /**
     * Default readback band height for [writeStriped]: 256 rows cost ~12MB
     * of float band plus ~17MB of GL scratch at 12MP, versus ~523MB of
     * transient direct + RGBA + triplet buffers for a whole-frame readback.
     */
    const val DEFAULT_STRIP_ROWS = 256

    /**
     * Whole-frame prime-DNG serialization as ONE strip (RowsPerStrip ==
     * height): pixel data quantizes straight into the single strip buffer.
     * Full-resolution callers that cannot hold the frame plus its strip
     * bytes must use [writeStriped] instead.
     */
    /**
     * @param captureTimeMillis wall-clock save time for the EXIF date tags
     *   (the savers' captureId); null omits all date tags.
     */
    fun write(
        output: OutputStream,
        image: MergedLinearRgb,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null,
        captureTimeMillis: Long? = null
    ) {
        val fields = headerFields(image.width, image.height, metadata, provenance,
            noiseProfileOverride, captureTimeMillis)
        val stripBytes = Math.multiplyExact(Math.multiplyExact(image.width * image.height, 3), 2)
        TinyDngImageWriter.write(output, image.width, image.height,
            samplesPerPixel = 3, bitsPerSample = 16, sampleFormat = 1,
            photometric = 34892, rowsPerStrip = image.height,
            fields = fields.fields, exif = fields.exif,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = 1) {
            quantizeStrip(image.rgb, 0, image.width * image.height, stripBytes)
        }
    }

    /**
     * Memory-bounded prime-DNG serialization for full-resolution merges.
     * Pixel-identical to [write]: the same tags and the same per-sample
     * quantization, but samples arrive band by band (one TIFF strip per
     * band of at most [stripRows] rows) instead of as one retained
     * [MergedLinearRgb] (~143MB at 12MP on top of the ~380MB a
     * whole-frame RGBA readback already holds — the combination OOMs a
     * 512MB-heap save). The strip tables are the only bytes that differ
     * from [write]; readers consume multi-strip DNGs identically.
     *
     * [fillRgbStrip] must synchronously fill `rgb[0, width*rows*3)` with the
     * finite RGB triplets for rows `[startY, startY+rows)` in [MergedLinearRgb]
     * row order; bands run from `0` upward in steps of at most [stripRows].
     * Non-finite samples fail via [quantize], exactly as in [write].
     */
    fun writeStriped(
        output: OutputStream,
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        gps: GpsLocation? = null,
        stripRows: Int = DEFAULT_STRIP_ROWS,
        noiseProfileOverride: DoubleArray? = null,
        captureTimeMillis: Long? = null,
        fillRgbStrip: (startY: Int, rows: Int, rgb: FloatArray) -> Unit
    ) {
        require(stripRows > 0) { "Strip height must be positive" }
        val fields = headerFields(width, height, metadata, provenance,
            noiseProfileOverride, captureTimeMillis)
        val stripCount = (height + stripRows - 1) / stripRows
        val band = FloatArray(Math.multiplyExact(Math.multiplyExact(width, stripRows), 3))
        TinyDngImageWriter.write(output, width, height,
            samplesPerPixel = 3, bitsPerSample = 16, sampleFormat = 1,
            photometric = 34892, rowsPerStrip = stripRows,
            fields = fields.fields, exif = fields.exif,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = stripCount) { strip ->
            val startY = strip * stripRows
            val rows = minOf(stripRows, height - startY)
            fillRgbStrip(startY, rows, band)
            quantizeStrip(band, 0, width * rows,
                Math.multiplyExact(Math.multiplyExact(width * rows, 3), 2))
        }
    }

    /** Tag field lists shared by [write] and [writeStriped]. */
    private class HeaderFields(val fields: TiffFields, val exif: TiffFields?)

    /**
     * Shared tags for [write] and [writeStriped]: identical fields for
     * identical dimensions and metadata, so the striped path cannot drift
     * from the parser-tested set. Structural tags (geometry, strips,
     * photometric, compression, sample format) are TinyDNG's and are never
     * listed here — passing one fails the native writer loudly.
     */
    private fun headerFields(
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        noiseProfileOverride: DoubleArray?,
        captureTimeMillis: Long?
    ): HeaderFields {
        require(width > 0 && height > 0) { "Linear RGB frame must have positive dimensions" }
        require(metadata.cameraId == provenance.sourceCameraId) {
            "Provenance source camera must be the reference metadata camera"
        }
        require(metadata.exifOrientation in 1..8)
        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.let(::dngMatrix)
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${oem(Build.MANUFACTURER)} ${oem(Build.MODEL)} (${metadata.cameraId})"
        val fields = TiffFields().apply {
            ascii(270, provenanceBlock(provenance))
            ascii(271, oem(Build.MANUFACTURER))
            ascii(272, oem(Build.MODEL))
            shorts(274, metadata.exifOrientation)
            shorts(284, 1)
            ascii(305, "RawLens")
            bytes(50706, byteArrayOf(1, 4, 0, 0))
            bytes(50707, byteArrayOf(1, 4, 0, 0))
            ascii(50708, cameraName)
            shorts(50713, 1, 1)
            rationals(50714, doubleArrayOf(0.0, 0.0, 0.0))
            longs(50717, 65535)
            srationals(50721, matrix)
            rationals(50728, neutral)
            shorts(50778, metadata.referenceIlluminant1 ?: 21)
        }
        // A non-positive exposure carries no information; omit rather than
        // write a zero-second tag. Same truthfulness rule as the ISO range.
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
        // Sensor noise model for profiled denoise downstream; same RGB S/O
        // pairs as the source-Bayer path. Omitted when the reference metadata
        // carries no model (cfaPattern is nullable, so no requireNotNull).
        // A merged-burst override (post-merge S/O from RawSrMergedNoise)
        // takes precedence when it is a well-formed RGB profile; anything
        // else falls back to the reference model, never to fabrication.
        val mergedProfile = noiseProfileOverride
            ?.takeIf { it.size == 6 && it.all(Double::isFinite) }
        if (mergedProfile != null) {
            fields.doubles(51041, mergedProfile)
        } else metadata.cfaPattern?.let { pattern ->
            DngNoiseProfile.toRgb(metadata.noiseProfile?.toDoubleArray(), pattern,
                metadata.blackLevels?.toFloatArray(), metadata.whiteLevel)
                ?.let { fields.doubles(51041, it) }
        }
        // The provenance timestamp is sensor-nanos (monotonic), not wall
        // clock: EXIF dates come only from the saver's wall-clock
        // captureTimeMillis, never from the sensor timebase.
        val exif = DngExifDirectory.fields(metadata, captureTimeMillis)
        return HeaderFields(fields, exif)
    }

    /**
     * Quantizes [pixels] triplets at [offset] into one little-endian strip
     * buffer of exactly [stripBytes]: the shared pixel loop for [write] and
     * [writeStriped], so both paths emit identical sample bytes.
     */
    private fun quantizeStrip(
        triplets: FloatArray, offset: Int, pixels: Int, stripBytes: Int
    ): ByteArray {
        val bytes = ByteArray(stripBytes)
        // Sharded over samples: each sample quantizes independently into
        // disjoint byte pairs, so any worker count emits identical bytes.
        val samples = pixels * 3
        RawSrWorkers.forEachShard(samples) { p0, p1 ->
            var o = p0 * 2
            for (p in p0 until p1) {
                val q = quantize(triplets[offset + p])
                bytes[o] = q.toByte()
                bytes[o + 1] = (q shr 8).toByte()
                o += 2
            }
        }
        return bytes
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
     * Human-readable multi-line provenance record for ImageDescription, in
     * the sectioned style of RAWR's multiframe descriptions. Deterministic
     * for a fixed input; unknown reference timestamps serialize as `?`,
     * never as a fabricated zero. Visible for parser tests.
     */
    fun provenanceBlock(provenance: MergeProvenance): String {
        val timestamp = if (provenance.referenceTimestampNs == Long.MIN_VALUE) "?"
            else provenance.referenceTimestampNs.toString()
        return "Captured with RawLens\n" +
            "Linear RGB prime DNG from RAW burst merge\n" +
            "\nPARAMETERS\n" +
            "- Algorithm: ${provenance.algorithmVersion}\n" +
            "- Selected frames: ${provenance.selectedFrames}\n" +
            "- Accepted frames: ${provenance.acceptedFrames}\n" +
            "- Rejected frames: ${provenance.rejectedFrames}\n" +
            "- Output scale: ${provenance.outputScale}x\n" +
            "- Lens shading applied: ${provenance.lensShadingApplied}\n" +
            "- Effective frames: ${formatEffectiveFrames(provenance.effectiveFrames)}\n" +
            "\nSource:\n" +
            "- Camera: ${provenance.sourceCameraId}\n" +
            "- Reference timestamp: $timestamp ns\n" +
            "- Derivation: $DERIVATION\n" +
            "- Quantization: clamp[0,1]*65535 round-half-up\n"
    }

    /**
     * Locale-free 4-decimal rendering of the effective frame count for
     * provenance records. Visible so parser tests pin the exact bytes.
     */
    fun formatEffectiveFrames(n: Double): String {
        require(n.isFinite() && n >= 1.0) { "Effective frames must be a finite count of at least 1" }
        return (kotlin.math.round(n * 1e4) / 1e4).toString()
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

    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
