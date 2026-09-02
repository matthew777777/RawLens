// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

/** Writes a standards-compliant DNG directly into the shared media collection. */
class DngSaver(private val context: Context) {
    @Throws(IOException::class)
    fun save(
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientation: Int,
        overrides: DngMetadataOverrides = DngMetadataOverrides(),
        metadata: RawFrameMetadata
    ): String {
        val displayName = "RAW_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { outputStream ->
                check(orientation == metadata.exifOrientation) { "DNG orientation snapshot mismatch" }
                NativeDngWriter.write(
                    outputStream, image, metadata, result, overrides
                )
            } ?: throw IOException("Could not open DNG output stream")
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
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensDng"
    }
}
