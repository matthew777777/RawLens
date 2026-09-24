// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

/**
 * Prompt 5D publication for the Mosaic SR CFA DNG. Mirrors the pending
 * MediaStore pattern: insert pending, stream the writer output, publish on
 * success, delete the incomplete entry on every failure.
 */
class MosaicSrDngSaver(private val context: Context) {
    fun saveMosaicSr(
        image: MosaicSrCfa,
        metadata: RawFrameMetadata,
        provenance: MosaicSrProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null
    ): String {
        val displayName = CaptureFileNames.mosaicSrDng(captureId)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create Mosaic SR DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { MosaicSrDngWriter.write(it, image, metadata, provenance, gps, noiseProfileOverride) }
                ?: throw IOException("Could not open Mosaic SR DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish Mosaic SR DNG")
            Log.i(LOG_TAG, "Mosaic SR DNG saved=$displayName")
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
