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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Pending-MediaStore JPEG writer. Every failure deletes its unpublished entry. */
class JpegSaver(private val context: Context) {
    @Throws(IOException::class)
    fun save(developed: DevelopedJpeg, metadata: RawFrameMetadata, result: CaptureResult): String {
        val startedAt = SystemClock.elapsedRealtime()
        val bitmap = developed.bitmap
        val now = System.currentTimeMillis()
        val displayName = "RAW_${now}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.ORIENTATION, exifOrientationDegrees(metadata.exifOrientation))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create pending JPEG media entry")
        val insertedAt = SystemClock.elapsedRealtime()
        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IOException("Android JPEG encoder rejected the developed bitmap")
                }
            } ?: throw IOException("Could not open JPEG output stream")
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
                    result.get(CaptureResult.LENS_APERTURE)?.let {
                        setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
                    }
                    result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                        setAttribute(
                            ExifInterface.TAG_FOCAL_LENGTH,
                            "${(it * 1000f).roundToInt()}/1000"
                        )
                    }
                    result.get(CaptureResult.CONTROL_AWB_MODE)?.let {
                        setAttribute(
                            ExifInterface.TAG_WHITE_BALANCE,
                            if (it == CaptureResult.CONTROL_AWB_MODE_AUTO) "0" else "1"
                        )
                    }
                    val date = EXIF_DATE_FORMAT.format(Date(now))
                    setAttribute(ExifInterface.TAG_DATETIME, date)
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
                    setAttribute(
                        ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "RAW_SENSOR -> AMaZE -> ACEScg -> AgX Base -> " +
                            (if (developed.settings.displayP3) "Display P3" else "sRGB") +
                            if (developed.settings.ultraHdr) " -> Android Ultra HDR gainmap" else ""
                    )
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
                "JPEG save ${bitmap.width}x${bitmap.height}: insert=${insertedAt - startedAt}ms " +
                    "encode=${encodedAt - insertedAt}ms exif=${exifAt - encodedAt}ms " +
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
        const val JPEG_QUALITY = 95
        const val LOG_TAG = "RawLensDevelop"
        val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}
