// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import java.io.IOException

/**
 * Pending-MediaStore JPEG XL writer. Every failure deletes its unpublished entry.
 *
 * Metadata: `androidx.exifinterface` cannot parse or write JXL, so — unlike
 * [JpegSaver] — capture metadata is embedded at encode time as `Exif` / `xml `
 * BMFF boxes (darktable parity, see `src/imageio/format/jxl.c`), built by
 * [JxlMetadataBoxes] with the same tag values the JPEG path writes.
 */
class JxlSaver(private val context: Context) {
    @Throws(IOException::class)
    /**
     * @param result live capture result for the AWB EXIF tag;
     * null for offline merges, which carry no Camera2 result (the tag is omitted).
     */
    fun save(
        developed: DevelopedJxl,
        metadata: RawFrameMetadata,
        result: CaptureResult?,
        captureTimeMillis: Long = System.currentTimeMillis(),
        typeSuffix: String? = null,
        gps: GpsLocation? = null,
        // Burst folder grouping, mirrors JpegSaver.save: null keeps flat.
        subfolder: String? = null
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        val settings = developed.settings.resolved()
        val displayName = CaptureFileNames.fileName(captureTimeMillis, typeSuffix, EXTENSION)
        if (subfolder != null) BurstSidecar.requireSubfolder(subfolder)
        val relativePath = if (subfolder != null) "DCIM/RawLens/$subfolder" else "DCIM/RawLens"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, captureTimeMillis)
            // Galleries cannot EXIF-parse JXL (like ExifInterface), so they
            // depend on this column; omitting it shows portrait shots sideways.
            put(MediaStore.Images.Media.ORIENTATION, exifOrientationDegrees(metadata.exifOrientation))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create pending JXL media entry")
        val insertedAt = SystemClock.elapsedRealtime()
        try {
            val rgb = JxlPixelPack.packRgb(
                developed.halfRgba, developed.width, developed.height, settings
            )
            val packedAt = SystemClock.elapsedRealtime()
            val boxes = JxlMetadataBoxes.build(
                metadata = metadata,
                awbAuto = result?.get(CaptureResult.CONTROL_AWB_MODE)?.let {
                    it == CaptureResult.CONTROL_AWB_MODE_AUTO
                },
                captureTimeMillis = captureTimeMillis,
                displayP3 = settings.displayP3,
                gps = gps,
                make = Build.MANUFACTURER,
                model = Build.MODEL,
                hdrPq = settings.hdrTransfer == JxlHdrTransfer.PQ,
                outputWidth = developed.width,
                outputHeight = developed.height
            )
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                NativeJxlEncoder.encode(
                    rgb, developed.width, developed.height,
                    descriptor, settings,
                    iccProfile = ByteArray(0),
                    exif = boxes.exif,
                    xmp = boxes.xmp,
                    orientation = metadata.exifOrientation
                )
            } ?: throw IOException("Could not open JXL output descriptor")
            val encodedAt = SystemClock.elapsedRealtime()

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish pending JXL media entry")
            }
            val publishedAt = SystemClock.elapsedRealtime()
            Log.i(
                LOG_TAG,
                "JXL save ${developed.width}x${developed.height} " +
                    "${settings.bitDepth.label} ${settings.pixelType.label} " +
                    "${settings.hdrTransfer.label} q=${settings.quality} " +
                    "effort=${settings.effort} tier=${settings.decodingSpeed}: " +
                    "insert=${insertedAt - startedAt}ms pack=${packedAt - insertedAt}ms " +
                    "encode=${encodedAt - packedAt}ms publish=${publishedAt - encodedAt}ms " +
                    "total=${publishedAt - startedAt}ms"
            )
            return displayName
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    companion object {
        const val EXTENSION = "jxl"
        const val MIME_TYPE = "image/jxl"
        private const val LOG_TAG = "RawLensDevelop"

        /** MediaStore wants degrees; EXIF/JXL carry the 1..8 orientation code. */
        internal fun exifOrientationDegrees(orientation: Int): Int = when (orientation) {
            3 -> 180
            6 -> 90
            8 -> 270
            else -> 0
        }
    }
}
