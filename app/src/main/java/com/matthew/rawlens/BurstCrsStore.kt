// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.FileUtils
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline-merge burst takes: one shutter tap dumps the whole ZSL ring to
 * `IMG_<ts>_BURST.crs` (container `recordingType=BURST`), stored apart
 * from RAW video and excluded from video lists.
 *
 * Phase 1 covers capture-side storage + listing. Pixel decode for the
 * offline MFSR/HDR+ runs arrives with the vendored ContainerReader JNI;
 * listings until then rely on the container footer (frame count) plus
 * file size, never a full decode.
 */
internal object BurstCrsStore {
    const val RECORDING_TYPE_BURST = "BURST"
    const val STAGE_SUBDIR = "raw-bursts"

    data class BurstDumpStats(
        val framesWritten: Int,
        val fileBytes: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long
    )

    data class BurstTake(
        val file: File,
        val frameCount: Int,
        val fileBytes: Long,
        val lastModified: Long
    )

    fun isBurstFile(file: File): Boolean {
        if (!file.isFile) return false
        if (file.extension != "crs") return false
        val stem = file.nameWithoutExtension.uppercase(java.util.Locale.US)
        return stem.endsWith("_${CaptureFileNames.TYPE_BURST}")
    }

    fun burstFileName(captureTimeMillis: Long): String =
        CaptureFileNames.burstCrs(captureTimeMillis)

    /**
     * Closed burst takes eligible for the Merges list, oldest first.
     * Same footer gate as video takes; the actively-dumping [active] file
     * is excluded. Pure filesystem scan, host-testable.
     */
    fun stagedBursts(stageDir: File, active: File? = null): List<File> {
        val files = stageDir.listFiles() ?: return emptyList()
        val activePath = active?.absolutePath
        return files.filter { file ->
            file.isFile && file.extension == "crs" && isBurstFile(file) &&
                file.length() > 0 && file.absolutePath != activePath &&
                hasValidFooter(file)
        }.sortedBy { it.lastModified() }
    }

    /**
     * Listing rows with footer frame counts. Files that fail the footer
     * gate are omitted (unfinalized dumps are never offered for merge).
     */
    fun listTakes(stageDir: File, active: File? = null): List<BurstTake> =
        stagedBursts(stageDir, active).mapNotNull { file ->
            val count = footerFrameCount(file) ?: return@mapNotNull null
            BurstTake(file, count, file.length(), file.lastModified())
        }

    fun hasValidFooter(file: File): Boolean = footerFrameCount(file) != null

    /** Frame count from the type-0 container footer, or null when invalid. */
    internal fun footerFrameCount(file: File): Int? {
        val length = file.length()
        if (length < 32) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - 24)
                val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                var read = 0
                while (read < 24) {
                    val n = raf.channel.read(tail)
                    if (n < 0) return null
                    read += n
                }
                tail.flip()
                if (tail.int != 0 || tail.int != 16) return null
                if (tail.int != CrsFormat.FOOTER_MAGIC) return null
                val frameCount = tail.int
                if (frameCount <= 0) return null
                val indexOff = tail.long
                if (indexOff <= 0 || indexOff >= length - 24) return null
                frameCount
            }
        } catch (_: Exception) {
            null
        }
    }

    fun payloadBound(width: Int, height: Int): Int {
        val ew = (width + 63) / 64 * 64
        return (ew.toLong() * height * 2 + ew * height / 4 + 65536)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Encodes [frames] (timestamp order) into [output] as a BURST container.
     * Transfers image ownership: every frame image is released exactly once,
     * on success or failure. Timestamps must be unique (the ZSL ring dedups);
     * a regression aborts the dump rather than writing an unreadable file.
     */
    fun writeZslRingToCrs(
        frames: List<BufferedRawFrame>,
        characteristics: CameraCharacteristics,
        realtimeTimestamps: Boolean,
        output: File
    ): BurstDumpStats {
        require(frames.isNotEmpty()) { "Empty ZSL ring" }
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        require(cfa != null && cfa in 0..3) { "CRS requires a Bayer sensor" }
        val ordered = frames.sortedBy { it.timestampNanos }
        val originNs = ordered.first().timestampNanos
        val first = ordered.first()
        val width = first.image.width
        val height = first.image.height
        require(width % 2 == 0 && height % 4 == 0) {
            "Encoder requires even width and height % 4 == 0, got ${width}x$height"
        }
        val orientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val baseJson = "{\"UniqueCameraModel\":\"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\"," +
            "\"width\":$width,\"height\":$height," +
            "\"crop\":\"FULL\",\"sensorOrientation\":$orientation,\"encoder\":\"RawLens-Burst\"}"
        val containerJson = CrsMetadata.container(
            baseJson, characteristics, realtimeTimestamps,
            audioRate = 0, audioChannels = 0, motion = false,
            orientation = orientation, recordingType = RECORDING_TYPE_BURST
        )
        val startedMs = System.currentTimeMillis()
        val handle = CrsWriter.open(output.absolutePath, containerJson)
        var lastRel = Long.MIN_VALUE
        var written = 0
        try {
            val dst = ByteBuffer.allocateDirect(payloadBound(width, height))
                .order(ByteOrder.nativeOrder())
            for (frame in ordered) {
                try {
                    val image = frame.image
                    require(image.width == width && image.height == height) {
                        "Mixed burst geometry ${image.width}x${image.height} != ${width}x$height"
                    }
                    val plane = image.planes[0]
                    require(plane.pixelStride == 2) {
                        "Packed RAW10 ZSL plane unsupported for BURST dump"
                    }
                    val absTs: Long = frame.result.get(CaptureResult.SENSOR_TIMESTAMP)
                        ?: frame.timestampNanos
                    val relTs = maxOf(0L, absTs - originNs)
                    require(relTs > lastRel) { "Non-increasing burst timestamp" }
                    val baseJson = "{\"width\":$width,\"height\":$height," +
                        "\"compressionType\":7,\"timestamp\":$absTs,\"crop\":\"FULL\"}"
                    val frameJson = CrsMetadata.frame(baseJson, frame.result, originNs)
                    dst.clear()
                    val src = plane.buffer
                    val bytes = CrsWriter.encodeFrame(
                        src, src.remaining(), width, height, plane.rowStride,
                        0, height, dst, false, cfa
                    )
                    dst.flip()
                    CrsWriter.writeFrame(handle, dst, bytes, relTs, frameJson)
                    lastRel = relTs
                    written++
                } finally {
                    RawImageOwnership.release(frame.image)
                }
            }
        } catch (failure: Exception) {
            try { CrsWriter.close(handle) } catch (_: Exception) { }
            throw failure
        }
        CrsWriter.close(handle)
        return BurstDumpStats(
            framesWritten = written,
            fileBytes = output.length(),
            width = width,
            height = height,
            durationMs = System.currentTimeMillis() - startedMs
        )
    }

    /**
     * Publishes one closed burst take to `DCIM/RawLens/Bursts` (never the
     * video folder). Stage source is deleted on success and always
     * retained on failure, mirroring [RawVideoSaver.save].
     */
    fun publishToBursts(resolver: ContentResolver, source: File): RawVideoSaver.Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty burst take" }
        require(isBurstFile(source)) { "Not a burst take: ${source.name}" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-crs")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/RawLens/Bursts")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create burst take in DCIM/RawLens/Bursts")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open burst destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete burst copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("Burst size mismatch")
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish burst take")
            }
        } catch (failure: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {
            }
            throw failure
        }
        return RawVideoSaver.Saved(uri, source.delete())
    }

    /**
     * SAF fallback for the burst publish above, reusing the photo-folder
     * tree grant. Lands under `RawLens/Bursts/` next to the DNGs.
     */
    fun publishViaTree(context: Context, treeUri: Uri, source: File): RawVideoSaver.Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty burst take" }
        require(isBurstFile(source)) { "Not a burst take: ${source.name}" }
        if (!SidecarTreeAccess.hasWriteAccess(context, treeUri)) {
            throw IOException("No write access to granted folder")
        }
        val resolver = context.contentResolver
        val treePath = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (failure: Exception) {
            throw IOException("Unreadable burst folder grant", failure)
        }
        val suffix = treePath.substringAfter(':', "")
        var dirId = treePath
        if (suffix == "DCIM" || suffix == "DCIM/" || suffix.endsWith("/DCIM") ||
            suffix.endsWith("/DCIM/")
        ) {
            dirId = findOrCreateDir(resolver, treeUri, dirId, "RawLens")
        }
        // Mirror saveViaTree: DCIM -> RawLens/, else the grant root; bursts
        // then land in a Bursts/ subfolder unless the grant already is one.
        if (!suffix.trimEnd('/').endsWith("Bursts")) {
            dirId = findOrCreateDir(resolver, treeUri, dirId, "Bursts")
        }
        val fileUri = try {
            DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId),
                "video/x-crs", source.name
            ) ?: throw IOException("Could not create burst take in granted folder")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not create burst take in granted folder", failure)
        }
        try {
            val descriptor = resolver.openFileDescriptor(fileUri, "w")
                ?: throw IOException("Could not open granted-folder destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete burst copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("Burst size mismatch")
            }
        } catch (failure: Exception) {
            try { DocumentsContract.deleteDocument(resolver, fileUri) } catch (_: Exception) {
            }
            if (failure is IOException) throw failure
            throw IOException("Granted-folder burst copy failed", failure)
        }
        return RawVideoSaver.Saved(fileUri, source.delete())
    }

    private fun findOrCreateDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        name: String,
    ): String {
        findChildId(resolver, treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create burst dir $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun findChildId(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        displayName: String,
    ): String? {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == displayName) return cursor.getString(idCol)
            }
        }
        return null
    }
}
