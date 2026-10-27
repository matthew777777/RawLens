// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/** Pending-MediaStore JPEG writer. Every failure deletes its unpublished entry. */
class JpegSaver(private val context: Context) {
    @Throws(IOException::class)
    /**
     * @param result retained for source compatibility; capture facts come from the frozen snapshot.
     */
    fun save(developed: DevelopedJpeg, metadata: RawFrameMetadata, result: CaptureResult?,
             captureTimeMillis: Long = System.currentTimeMillis(),
             typeSuffix: String? = null,
             gps: GpsLocation? = null,
             // Burst folder grouping, mirrors DngSaver.save: null keeps flat.
             subfolder: String? = null): String {
        val startedAt = SystemClock.elapsedRealtime()
        val bitmap = developed.bitmap
        val displayName = CaptureFileNames.fileName(captureTimeMillis, typeSuffix, "jpg")
        if (subfolder != null) BurstSidecar.requireSubfolder(subfolder)
        val relativePath = if (subfolder != null) "DCIM/RawLens/$subfolder" else "DCIM/RawLens"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, captureTimeMillis)
            put(MediaStore.Images.Media.ORIENTATION, exifOrientationDegrees(metadata.exifOrientation))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create pending JPEG media entry")
        val insertedAt = SystemClock.elapsedRealtime()
        try {
            if (developed.settings.ultraHdr) {
                // RAWR-parity Full Ultra HDR: native dual-encode (SDR base +
                // fitted gain map) + JPEG_R mux. A null map or any native
                // failure falls back to the SDR encode so a shot is never lost
                // to the HDR path (RAWR writeUltraHdrFallback parity).
                val gainmap = developed.gainmap
                if (gainmap == null) {
                    Log.w(LOG_TAG, "Ultra HDR develop produced no gain map; writing SDR fallback")
                    resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                        NativeJpegEncoder.encode(bitmap, descriptor, developed.settings)
                    } ?: throw IOException("Could not open native JPEG output descriptor")
                } else {
                    try {
                        resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                            NativeUltraHdrWriter.encode(bitmap, gainmap, descriptor, developed.settings)
                        } ?: throw IOException("Could not open Ultra HDR JPEG output descriptor")
                    } catch (failure: IOException) {
                        Log.w(LOG_TAG, "Native Ultra HDR encode failed; writing SDR fallback", failure)
                        resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                            NativeJpegEncoder.encode(bitmap, descriptor, developed.settings)
                        } ?: throw IOException("Could not open native JPEG output descriptor")
                    }
                }
            } else {
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    NativeJpegEncoder.encode(bitmap, descriptor, developed.settings)
                } ?: throw IOException("Could not open native JPEG output descriptor")
            }
            val encodedAt = SystemClock.elapsedRealtime()

            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                    setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                    setAttribute(ExifInterface.TAG_SOFTWARE, "RawLens scene-referred developer")
                    setAttribute(ExifInterface.TAG_ORIENTATION, metadata.exifOrientation.toString())
                    // EXIF's ColorSpace tag has no Display-P3 code; Android's JPEG encoder writes
                    // the Bitmap's ICC profile. Mark P3 as uncalibrated rather than falsely sRGB.
                    setAttribute(
                        ExifInterface.TAG_COLOR_SPACE,
                        (if (developed.settings.displayP3) ExifInterface.COLOR_SPACE_UNCALIBRATED
                        else ExifInterface.COLOR_SPACE_S_RGB).toString()
                    )
                    setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
                    setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
                    setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
                    setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, metadata.sensitivityIso?.toString())
                    setAttribute(
                        ExifInterface.TAG_EXPOSURE_TIME,
                        metadata.exposureTimeNanos?.let { it / 1_000_000_000.0 }?.toString()
                    )
                    CaptureExifAttributes.writeTo(this, metadata, captureTimeMillis)
                    setAttribute(
                        ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "RAW_SENSOR -> AMaZE -> ACEScg -> AgX Base -> " +
                            (if (developed.settings.displayP3) "Display P3" else "sRGB") +
                            if (developed.settings.ultraHdr) " -> Ultra HDR gainmap (JPEG_R)" else ""
                    )
                    // A missing fix writes no GPS tags at all: an absent geotag
                    // is truthful, a stale or zeroed one is not.
                    gps?.writeTo(this)
                    saveAttributes()
                }
            } ?: throw IOException("Could not reopen JPEG for EXIF metadata")
            val exifAt = SystemClock.elapsedRealtime()

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish pending JPEG media entry")
            }
            val publishedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JPEG save ${bitmap.width}x${bitmap.height} q=${developed.settings.jpegQuality} " +
                    "subsampling=${developed.settings.chromaSubsampling.label} " +
                    "encoder=${if (developed.settings.ultraHdr) "Android JPEG/R" else "libjpeg-turbo"}: " +
                    "insert=${insertedAt - startedAt}ms encode=${encodedAt - insertedAt}ms exif=${exifAt - encodedAt}ms " +
                    "publish=${publishedAt - exifAt}ms total=${publishedAt - startedAt}ms"
            )
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun exifOrientationDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private companion object {
        const val LOG_TAG = "RawLensDevelop"
    }
}
