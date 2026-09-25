// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * DESKTOP COUNTERPART of app/.../LinearRgbDngSaver.kt (sr-vulkan only).
 * MediaStore has no desktop equivalent, so the pending/publish discipline is
 * kept with files: stream to `<name>.tmp`, atomically rename on success, and
 * delete the incomplete temp file on every failure. API shapes, writer calls,
 * and log lines mirror the phone saver.
 */
class LinearRgbDngSaver(private val outputDir: File) {
    fun saveLinearRgb(
        image: MergedLinearRgb,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null
    ): String {
        val displayName = CaptureFileNames.linearRgbDng(captureId)
        outputDir.mkdirs()
        val tmp = File(outputDir, "$displayName.tmp")
        val dest = File(outputDir, displayName)
        try {
            tmp.outputStream().buffered().use {
                LinearRgbDngWriter.write(it, image, metadata, provenance, gps, noiseProfileOverride)
            }
            publish(tmp, dest)
            Log.i(LOG_TAG, "Linear RGB prime DNG saved=$displayName")
            return displayName
        } catch (failure: Exception) {
            tmp.delete()
            throw failure
        }
    }

    /**
     * Memory-bounded publication for full-resolution merges: identical file
     * discipline to [saveLinearRgb], but pixels stream band by band through
     * [LinearRgbDngWriter.writeStriped] instead of arriving as one retained
     * [MergedLinearRgb].
     */
    fun saveLinearRgbStriped(
        width: Int,
        height: Int,
        metadata: RawFrameMetadata,
        provenance: MergeProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        stripRows: Int = LinearRgbDngWriter.DEFAULT_STRIP_ROWS,
        noiseProfileOverride: DoubleArray? = null,
        fillRgbStrip: (startY: Int, rows: Int, rgb: FloatArray) -> Unit
    ): String {
        val displayName = CaptureFileNames.linearRgbDng(captureId)
        outputDir.mkdirs()
        val tmp = File(outputDir, "$displayName.tmp")
        val dest = File(outputDir, displayName)
        try {
            tmp.outputStream().buffered().use {
                LinearRgbDngWriter.writeStriped(
                    it, width, height, metadata, provenance, gps, stripRows,
                    noiseProfileOverride, fillRgbStrip
                )
            }
            publish(tmp, dest)
            Log.i(LOG_TAG, "Linear RGB prime DNG saved=$displayName")
            return displayName
        } catch (failure: Exception) {
            tmp.delete()
            throw failure
        } catch (oom: OutOfMemoryError) {
            tmp.delete()
            throw oom
        }
    }

    private fun publish(tmp: File, dest: File) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!dest.isFile) throw IOException("Could not publish ${dest.name}")
    }

    private companion object {
        const val LOG_TAG = "RawLensDng"
    }
}
