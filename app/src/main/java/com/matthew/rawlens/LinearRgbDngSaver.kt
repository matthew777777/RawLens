// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException

/**
 * Prompt 5B publication for the Linear RGB prime DNG. Separate from the
 * source-Bayer [DngSaver], which is frozen: pending MediaStore insert, stream
 * the writer output, publish on success, and delete the incomplete entry on
 * every failure so no half-written DNG is ever visible.
 */
class LinearRgbDngSaver(private val context: Context) {
    fun saveLinearRgb(
        image: MergedLinearRgb,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null
    ): String = publish(captureId) {
        LinearRgbDngWriter.write(it, image, metadata, provenance, gps, noiseProfileOverride, captureId)
    }

    /**
     * Same publication through [LinearRgbDngWriter.writeStriped] (pixel-
     * identical to [saveLinearRgb]): samples are read band by band from
     * [rgb], interleaved RGB in a native buffer, so no frame-sized heap copy
     * is made (C11: ~150 MB at 12 MP for MFSR).
     */
    fun saveLinearRgbStriped(
        width: Int,
        height: Int,
        rgb: java.nio.FloatBuffer,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null
    ): String {
        require(rgb.capacity() >= Math.multiplyExact(Math.multiplyExact(width, height), 3)) { "RGB buffer too small" }
        return publish(captureId) { out ->
            LinearRgbDngWriter.writeStriped(out, width, height, metadata, provenance, gps,
                noiseProfileOverride = noiseProfileOverride, captureTimeMillis = captureId) { startY, rows, band ->
                val src = rgb.duplicate()
                src.position(startY * width * 3)
                src.get(band, 0, rows * width * 3)
            }
        }
    }

    private fun publish(captureId: Long, writeTo: (java.io.OutputStream) -> Unit): String {
        val displayName = CaptureFileNames.linearRgbDng(captureId)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create Linear RGB DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { writeTo(it) }
                ?: throw IOException("Could not open Linear RGB DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish Linear RGB DNG")
            Log.i(LOG_TAG, "Linear RGB prime DNG saved=$displayName")
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
