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
 * DESKTOP COUNTERPART of app/.../MosaicSrDngSaver.kt (sr-vulkan only).
 * MediaStore has no desktop equivalent, so the pending/publish discipline is
 * kept with files: stream to `<name>.tmp`, atomically rename on success, and
 * delete the incomplete temp file on every failure. API shape, writer call,
 * and log line mirror the phone saver.
 */
class MosaicSrDngSaver(private val outputDir: File) {
    fun saveMosaicSr(
        image: MosaicSrCfa,
        metadata: RawFrameMetadata,
        provenance: MosaicSrProvenance,
        captureId: Long = System.currentTimeMillis(),
        gps: GpsLocation? = null,
        noiseProfileOverride: DoubleArray? = null
    ): String {
        val displayName = CaptureFileNames.mosaicSrDng(captureId)
        outputDir.mkdirs()
        val tmp = File(outputDir, "$displayName.tmp")
        val dest = File(outputDir, displayName)
        try {
            tmp.outputStream().buffered().use {
                MosaicSrDngWriter.write(it, image, metadata, provenance, gps, noiseProfileOverride)
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Log.i(LOG_TAG, "Mosaic SR DNG saved=$displayName")
            return displayName
        } catch (failure: Exception) {
            tmp.delete()
            throw failure
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensDng"
    }
}
