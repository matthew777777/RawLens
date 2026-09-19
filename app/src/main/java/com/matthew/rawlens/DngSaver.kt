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
            resolver.openOutputStream(uri, "w")?.use { FloatCfaDngWriter.write(it, cfa, metadata, gps) }
                ?: throw IOException("Could not open AI DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish AI DNG")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    fun saveMerged(cfa: UnpackedRawCfa, metadata: RawFrameMetadata,
                   captureId: Long = System.currentTimeMillis(),
                   gps: GpsLocation? = null): String {
        val displayName = CaptureFileNames.hdrDng(captureId)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create HDR DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { FloatCfaDngWriter.write(it, cfa, metadata, gps) }
                ?: throw IOException("Could not open HDR DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish HDR DNG")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

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
        subfolder: String? = null
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

        try {
            val actualBackend = when (backend) {
                DngWriterBackend.ANDROID -> {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        writeAndroid(output, image, characteristics, result, orientation, gps)
                    } ?: throw IOException("Could not open DNG output stream")
                    DngWriterBackend.ANDROID
                }

                DngWriterBackend.TINY_DNG -> {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        NativeDngWriter.write(output, image, metadata, result, overrides)
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
            if (gps != null && actualBackend == DngWriterBackend.TINY_DNG) {
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
            Log.i(LOG_TAG, "DNG requestedBackend=$backend actualBackend=$actualBackend saved=$displayName")
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
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
