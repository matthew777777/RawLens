// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream

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
    val lensShadingApplied: Boolean,
    /**
     * Measured effective merged frame count (mean support, see
     * RawSrMergedNoise). 1.0 is the unscaled reference profile.
     */
    val effectiveFrames: Double = 1.0
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
        require(effectiveFrames.isFinite() && effectiveFrames >= 1.0) {
            "Effective frames must be a finite count of at least 1"
        }
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
 * TIFF serialization is owned by the pinned TinyDNG writer
 * ([TinyDngImageWriter], header-first streaming): this object only assembles
 * the tag field lists and quantizes the pixels. Geometry, compression,
 * photometric, strip, and sample-format tags come from TinyDNG itself and
 * must never be passed as custom fields.
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

    /**
     * @param captureTimeMillis wall-clock save time for the EXIF date tags
     *   (the savers' captureId); null omits all date tags.
     */
    fun write(
        output: OutputStream,
        image: MosaicSrCfa,
        metadata: RawFrameMetadata,
        provenance: MosaicSrProvenance,
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null,
        captureTimeMillis: Long? = null
    ) {
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
            ascii(270, provenanceBlock(provenance, image))
            ascii(271, oem(Build.MANUFACTURER))
            ascii(272, oem(Build.MODEL))
            shorts(274, metadata.exifOrientation)
            shorts(284, 1)
            ascii(305, "RawLens")
            shorts(33421, 2, 2)
            bytes(33422, cfaPattern(image.pattern))
            bytes(50706, byteArrayOf(1, 4, 0, 0))
            bytes(50707, byteArrayOf(1, 4, 0, 0))
            ascii(50708, cameraName)
            shorts(50713, 2, 2)
            // BlackLevel count must cover the 2x2 repeat grid (one level per
            // Bayer phase): count 1 is rejected by rawspeed/Darktable
            // ("BLACKLEVEL entry is too small"). Same shape as
            // FloatCfaDngWriter (count 4); all phases are zero here.
            shorts(50714, 0, 0, 0, 0)
            longs(50717, 65535)
            srationals(50721, matrix)
            rationals(50728, neutral)
            shorts(50778, metadata.referenceIlluminant1 ?: 21)
            shorts(50829, 0, 0, image.height, image.width)
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
        // Sensor noise model for profiled denoise downstream; same RGB S/O
        // pairs as the source-Bayer path and the Linear RGB prime writer.
        // Omitted when the reference metadata carries no model (cfaPattern is
        // nullable, so no requireNotNull). A merged-burst override
        // (post-merge S/O from RawSrMergedNoise) takes precedence when it is
        // a well-formed RGB profile.
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
        TinyDngImageWriter.write(output, image.width, image.height,
            samplesPerPixel = 1, bitsPerSample = 16, sampleFormat = 1,
            photometric = 32803, rowsPerStrip = image.height,
            fields = fields, exif = exif,
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = 1) {
            val bytes = ByteArray(Math.multiplyExact(image.width * image.height, 2))
            // Sharded over samples: each sample quantizes independently into
            // disjoint byte pairs, so any worker count emits identical bytes.
            val samples = image.samples
            RawSrWorkers.forEachShard(samples.size) { p0, p1 ->
                var o = p0 * 2
                for (p in p0 until p1) {
                    val q = LinearRgbDngWriter.quantize(samples[p])
                    bytes[o] = q.toByte()
                    bytes[o + 1] = (q shr 8).toByte()
                    o += 2
                }
            }
            bytes
        }
    }

    /**
     * Human-readable multi-line provenance record for ImageDescription, in
     * the sectioned style of RAWR's multiframe descriptions. Deterministic
     * for a fixed input; unknown reference timestamps serialize as `?`,
     * never fabricated.
     */
    fun provenanceBlock(provenance: MosaicSrProvenance, image: MosaicSrCfa): String {
        val timestamp = if (provenance.referenceTimestampNs == Long.MIN_VALUE) "?"
            else provenance.referenceTimestampNs.toString()
        return "Captured with RawLens\n" +
            "Mosaic SR DNG - derived Bayer reconstruction\n" +
            "\nPARAMETERS\n" +
            "- Algorithm: ${MosaicSrReconstructor.ALGORITHM_VERSION}\n" +
            "- Selected frames: ${provenance.selectedFrames}\n" +
            "- Accepted frames: ${provenance.acceptedFrames}\n" +
            "- Rejected frames: ${provenance.rejectedFrames}\n" +
            "- Output scale: ${MosaicSrReconstructor.LINEAR_SCALE}x\n" +
            "- Lens shading applied: ${provenance.lensShadingApplied}\n" +
            "- Effective frames: " +
            "${LinearRgbDngWriter.formatEffectiveFrames(provenance.effectiveFrames)}\n" +
            "\nSource:\n" +
            "- Camera: ${provenance.sourceCameraId}\n" +
            "- Source dimensions: ${provenance.sourceWidth}x${provenance.sourceHeight}\n" +
            "- Target dimensions: ${image.width}x${image.height}\n" +
            "- Target pattern: ${image.pattern}\n" +
            "- Reference timestamp: $timestamp ns\n" +
            "- Derivation: $DERIVATION\n" +
            "- Quantization: clamp[0,1]*65535 round-half-up\n"
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

    private fun cfaPattern(pattern: BayerPattern) = ByteArray(4) { index ->
        when (pattern.colorAt(index and 1, index shr 1)) {
            CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
        }
    }

    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
