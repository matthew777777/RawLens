// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Writes RAW_SENSOR as DNG while keeping DNG serialization separate from RawLens JPEG development.
 *
 * Android's official Camera2 DngCreator is the default because it owns the Camera2 -> DNG metadata
 * translation (NoiseProfile, ActiveArea, CFA, OpcodeList2/GainMap, hot pixels, etc). The patched
 * TinyDNG path is retained as an explicit diagnostic/fallback backend.
 */
class DngSaver(private val context: Context) {
    /**
     * Writes an AI-denoised CFA as a float DNG next to its capture stem
     * (`IMG_<ts>_AI.dng`, or `IMG_<ts>_F00AI.dng` for burst frames).
     * Same float-DNG flavor as [saveMerged] (normalized values, zero black
     * level, preserved CFA pattern); the CFA must already be denoised.
     */
    fun saveAiDenoised(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        captureId: Long = System.currentTimeMillis(),
        fileNameSuffix: String? = null,
        gps: GpsLocation? = null,
        subfolder: String? = null
    ): String {
        if (subfolder != null) BurstSidecar.requireSubfolder(subfolder)
        val displayName = CaptureFileNames.aiDng(captureId, fileNameSuffix)
        val resolver = context.contentResolver
        val relativePath = if (subfolder != null) "DCIM/RawLens/$subfolder" else "DCIM/RawLens"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create AI DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use {
                FloatCfaDngWriter.write(it, cfa, metadata, gps,
                    description = "Captured with RawLens\nAI-denoised CFA DNG (32-bit float, normalized, zero black level)")
            } ?: throw IOException("Could not open AI DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish AI DNG")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    fun saveGaloshDenoised(
        cfa: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        captureId: Long = System.currentTimeMillis(),
        fileNameSuffix: String? = null,
        gps: GpsLocation? = null,
        subfolder: String? = null
    ): String {
        if (subfolder != null) BurstSidecar.requireSubfolder(subfolder)
        val displayName = CaptureFileNames.galoshDng(captureId, fileNameSuffix)
        val resolver = context.contentResolver
        val relativePath = if (subfolder != null) "DCIM/RawLens/$subfolder" else "DCIM/RawLens"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create GALOSH DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use {
                FloatCfaDngWriter.write(it, cfa, metadata, gps,
                    description = "Captured with RawLens\nGALOSH-denoised CFA DNG (32-bit float, normalized, zero black level)")
            } ?: throw IOException("Could not open GALOSH DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish GALOSH DNG")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /**
     * @param sixteenBit selects the 16-bit normalized writer
     *   ([HdrPlusDngWriter]) instead of the 32-bit float writer
     *   ([FloatCfaDngWriter]). HDR+ merges use 16-bit; fusion/SR keep
     *   32-bit float.
     * @param hdrPlusProvenance merge facts for the 16-bit path's
     *   ImageDescription block; ignored by the 32-bit path.
     * @param description ImageDescription for the 32-bit path; null omits
     *   it. Ignored by the 16-bit path (provenance owns that block).
     */
    fun saveMerged(cfa: UnpackedRawCfa, metadata: RawFrameMetadata,
                   captureId: Long = System.currentTimeMillis(),
                   gps: GpsLocation? = null,
                   displayName: String? = null,
                   sixteenBit: Boolean = false,
                   hdrPlusProvenance: HdrPlusProvenance? = null,
                   baselineExposureEv: Double? = null,
                   description: String? = null,
                   dngCompression: DngCompression = DngCompression.UNCOMPRESSED): String {
        val resolvedName = displayName ?: CaptureFileNames.hdrDng(captureId)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, resolvedName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create HDR DNG media entry")
        try {
            // JXL only covers the 16-bit CFA path (the JNI encode is
            // uint16-grayscale): float merges stay uncompressed, and unlike
            // the single-frame fallback there is no platform writer for
            // merges, so merged JXL files carry GPS in their own GPS IFD.
            if (!sixteenBit && dngCompression == DngCompression.JXL) {
                Log.w(LOG_TAG, "JXL DNG compression needs the 16-bit merged path; " +
                    "$resolvedName stays uncompressed")
            }
            val mergedCompression =
                if (sixteenBit) dngCompression else DngCompression.UNCOMPRESSED
            resolver.openOutputStream(uri, "w")?.use {
                if (sixteenBit) HdrPlusDngWriter.write(it, cfa, metadata, gps,
                    captureTimeMillis = captureId, provenance = hdrPlusProvenance,
                    compression = mergedCompression)
                else FloatCfaDngWriter.write(it, cfa, metadata, gps,
                    baselineExposureEv = baselineExposureEv, description = description)
            } ?: throw IOException("Could not open HDR DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish HDR DNG")
            return resolvedName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /**
     * @param dngCompression JXL stores the single-frame mosaic losslessly as
     * a JPEG XL codestream (DNG 1.7, Compression 52546 — the iPhone ProRAW
     * flavor). Applies to the single-frame path only: packed frames and
     * merged DNGs stay uncompressed. JXL forces the TinyDNG backend (the
     * platform writer cannot emit 52546); when a GPS fix is present the
     * save stays uncompressed instead, because TinyDNG cannot carry the
     * GPS sub-IFD and silently dropping location is worse than a bigger file.
     */
    @Throws(IOException::class)
    fun save(
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientation: Int,
        overrides: DngMetadataOverrides = DngMetadataOverrides(),
        metadata: RawFrameMetadata,
        backend: DngWriterBackend = DngWriterBackend.ANDROID,
        fileNameSuffix: String? = null,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        // Burst folder grouping: null keeps the legacy flat DCIM/RawLens
        // layout; non-null must pass BurstSidecar.requireSubfolder and
        // places this frame under DCIM/RawLens/<subfolder>/.
        subfolder: String? = null,
        dngCompression: DngCompression = DngCompression.UNCOMPRESSED
    ): String {
        check(orientation == metadata.exifOrientation) { "DNG orientation snapshot mismatch" }
        overrides.validate()
        if (subfolder != null) BurstSidecar.requireSubfolder(subfolder)

        val displayName = if (fileNameSuffix.isNullOrBlank()) {
            CaptureFileNames.singleDng(captureId)
        } else {
            CaptureFileNames.fileName(captureId, fileNameSuffix, "dng")
        }
        val resolver = context.contentResolver
        val relativePath = if (subfolder != null) "DCIM/RawLens/$subfolder" else "DCIM/RawLens"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create media entry")

        // Sampled levels mean the HAL's reported pair was unusable: the
        // platform writer would bake that garbage into the tags (or throw),
        // while TinyDNG consumes the repaired metadata. Reroute unless the
        // user pinned both level tags deliberately (their patch fixes the
        // platform file afterwards).
        val levelsSampled = metadata.blackLevelSource == BlackLevelSource.SAMPLED ||
            metadata.whiteLevelSource == WhiteLevelSource.SAMPLED
        val userPinnedLevels = overrides.blackLevels != null && overrides.whiteLevel != null
        val effectiveBackend = if (levelsSampled && !userPinnedLevels && backend != DngWriterBackend.TINY_DNG) {
            Log.w(LOG_TAG, "HAL black/white levels unusable (sampled black=" +
                "${metadata.blackLevels?.toList()?.joinToString(",")} white=${metadata.whiteLevel}); " +
                "forcing TinyDNG backend for $displayName")
            DngWriterBackend.TINY_DNG
        } else backend

        // Packed frames cannot enter either Image-direct writer (DngCreator
        // and the native writer assume 16-bit planes): they unpack to CFA
        // first and save through the 16-bit HDR+ writer, which keeps GPS.
        // White/black levels stay format-independent Camera2 sensor
        // properties, so the same metadata normalizes both containers.
        val packedFormat = PackedRawFormat.ofInt(image.format)
        if (dngCompression == DngCompression.JXL && packedFormat != null) {
            Log.w(LOG_TAG, "JXL DNG compression needs the single-frame path; " +
                "packed frame $displayName stays uncompressed")
        }
        // JXL rides TinyDNG (the platform writer has no 52546); a present
        // GPS fix keeps the uncompressed platform file instead of losing
        // location through TinyDNG's flat IFD.
        val wantJxl = dngCompression == DngCompression.JXL && packedFormat == null
        if (wantJxl && gps != null) {
            Log.w(LOG_TAG, "JXL DNG compression skipped for $displayName: " +
                "GPS fix preserved via uncompressed DNG (TinyDNG has no GPS sub-IFD)")
        }
        val useJxl = wantJxl && gps == null
        val compressionBackend = if (useJxl && effectiveBackend != DngWriterBackend.TINY_DNG) {
            Log.i(LOG_TAG, "JXL DNG compression forces TinyDNG backend for $displayName")
            DngWriterBackend.TINY_DNG
        } else effectiveBackend
        try {
            val actualBackend = if (packedFormat != null) {
                savePackedCfa(uri, image, metadata, gps, overrides, displayName)
                DngWriterBackend.TINY_DNG
            } else when (compressionBackend) {
                DngWriterBackend.ANDROID -> {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        writeAndroid(output, image, characteristics, result, orientation, gps)
                    } ?: throw IOException("Could not open DNG output stream")
                    DngWriterBackend.ANDROID
                }

                DngWriterBackend.TINY_DNG -> {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        NativeDngWriter.write(output, image, metadata, result, overrides, jxl = useJxl)
                    } ?: throw IOException("Could not open DNG output stream")
                    DngWriterBackend.TINY_DNG
                }

                DngWriterBackend.AUTO -> writeAuto(
                    uri = uri,
                    image = image,
                    characteristics = characteristics,
                    result = result,
                    orientation = orientation,
                    overrides = overrides,
                    metadata = metadata,
                    gps = gps
                )
            }
            // Packed saves report TINY_DNG below only as the closest surviving
            // label; their HDR+ file keeps GPS, so the discard warning must
            // not fire for them.
            if (gps != null && actualBackend == DngWriterBackend.TINY_DNG && packedFormat == null) {
                // The pinned TinyDNG writer emits a single flat IFD and cannot
                // carry the GPS sub-IFD; the fix is dropped rather than written
                // half-correctly. The platform backend remains the GPS path.
                Log.w(LOG_TAG, "TinyDNG backend discards the GPS fix for $displayName")
            }

            // Android DngCreator deliberately follows device Camera2 metadata. Preserve RawLens'
            // optional calibration editor by patching only already-declared tags after writing.
            // TinyDNG already consumed these overrides while constructing its tags, so do not patch
            // it a second time.
            if (actualBackend == DngWriterBackend.ANDROID && !overrides.isEmpty()) {
                DngMetadataPatcher.apply(context, uri, overrides)
            }

            if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) == null &&
                characteristics.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) != true
            ) {
                Log.w(LOG_TAG, "DNG saved without a lens-shading map; HAL did not return one")
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish pending DNG media entry")
            }
            Log.i(LOG_TAG, "DNG requestedBackend=$backend actualBackend=$actualBackend" +
                (packedFormat?.let { " packed=${it.name}" } ?: "") +
                (if (useJxl) " compression=jxl" else "") + " saved=$displayName")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    /**
     * Packed single-frame save: unpacks to CFA through the bridge, then the
     * 16-bit HDR+ writer (GPS-capable). User calibration overrides patch the
     * file afterwards; a patch failure warns but never fails the save.
     */
    private fun savePackedCfa(
        uri: android.net.Uri,
        image: Image,
        metadata: RawFrameMetadata,
        gps: GpsLocation?,
        overrides: DngMetadataOverrides,
        displayName: String
    ) {
        val normalization = metadata.normalizationOrNull()
            ?: throw IOException("Packed DNG normalization metadata is missing")
        val plane = image.planes.single()
        val cfa = RawPackedUnpacker.unpackFrameNormalized(
            plane.buffer, image.width, image.height,
            plane.rowStride, plane.pixelStride, image.format, normalization,
            byteOrder = ByteOrder.LITTLE_ENDIAN
        )
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            HdrPlusDngWriter.write(output, cfa, metadata, gps)
        } ?: throw IOException("Could not open DNG output stream")
        if (!overrides.isEmpty()) {
            runCatching { DngMetadataPatcher.apply(context, uri, overrides) }
                .onFailure { Log.w(LOG_TAG, "Packed DNG calibration patch skipped for $displayName", it) }
        }
    }

    private fun writeAndroid(
        output: java.io.OutputStream,
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientation: Int,
        gps: GpsLocation?
    ) {
        DngCreator(characteristics, result).use { creator ->
            creator.setOrientation(orientation)
            creator.setDescription("RawLens • Android Camera2 DngCreator")
            gps?.let { creator.setLocation(it.toAndroidLocation()) }
            creator.writeImage(output, image)
        }
    }

    /**
     * AUTO must not fall back into a stream containing a partially-written Android DNG. Write the
     * platform attempt to a temporary file first, then copy it atomically into the pending media
     * item. If platform metadata is rejected, TinyDNG writes directly into the still-empty item.
     */
    private fun writeAuto(
        uri: android.net.Uri,
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientation: Int,
        overrides: DngMetadataOverrides,
        metadata: RawFrameMetadata,
        gps: GpsLocation?
    ): DngWriterBackend {
        val resolver = context.contentResolver
        val temp = File.createTempFile("rawlens_android_dng_", ".dng", context.cacheDir)
        try {
            try {
                FileOutputStream(temp).use { output ->
                    writeAndroid(output, image, characteristics, result, orientation, gps)
                }
                resolver.openOutputStream(uri, "w")?.use { destination ->
                    FileInputStream(temp).use { source -> source.copyTo(destination, COPY_BUFFER_BYTES) }
                } ?: throw IOException("Could not open DNG output stream")
                Log.i(LOG_TAG, "AUTO selected Android Camera2 DngCreator")
                return DngWriterBackend.ANDROID
            } catch (platformFailure: Exception) {
                Log.w(
                    LOG_TAG,
                    "Android DngCreator failed in AUTO; retrying patched TinyDNG: " +
                        (platformFailure.message ?: platformFailure.javaClass.simpleName),
                    platformFailure
                )
                resolver.openOutputStream(uri, "w")?.use { output ->
                    NativeDngWriter.write(output, image, metadata, result, overrides)
                } ?: throw IOException("Could not open DNG output stream for TinyDNG fallback")
                return DngWriterBackend.TINY_DNG
            }
        } finally {
            if (!temp.delete()) Log.w(LOG_TAG, "Could not delete AUTO DNG temp file: ${temp.name}")
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensDng"
        const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}
