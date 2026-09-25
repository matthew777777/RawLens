// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import com.matthew.burstrecon.AdmittedFrame
import com.matthew.burstrecon.DngAdmission
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DESKTOP-OWNED DNG burst import (sr-vulkan only). The phone builds
 * [RawSrPackedFrame]/[RawFrameMetadata] from Camera2; the desktop builds the
 * identical values from DNG files: admitted codes become the little-endian
 * RAW16 plane and DNG tags become the metadata (see
 * RawFrameMetadataFactory.fromDng). No ported line is involved.
 */
object DngFrameLoader {
    data class LoadedFrame(
        val plane: ByteBuffer,
        val metadata: RawFrameMetadata,
        val admitted: AdmittedFrame,
        val file: File
    )

    /**
     * Loads `*.dng` (sorted by name) from [dngDir]: at least two frames,
     * cross-checked by [DngAdmission.admit]. [crop] is an even
     * `x,y,w,h` stored-crop applied before admission (fast iteration).
     */
    fun load(dngDir: File, limit: Int = 30, crop: IntArray? = null): List<LoadedFrame> {
        require(dngDir.isDirectory) { "DNG input is not a directory: $dngDir" }
        val files = dngDir.listFiles { file ->
            file.isFile && file.name.endsWith(".dng", ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
        return loadFiles(files.take(limit), crop)
    }

    /** Explicit file list (order kept): for brackets and hand-picked bursts. */
    fun loadFiles(files: List<File>, crop: IntArray? = null): List<LoadedFrame> {
        require(files.size >= 2) { "Need at least two DNGs, found ${files.size}" }
        if (crop != null) {
            require(crop.size == 4) { "Crop must be x,y,w,h" }
        }
        var frames = files.map { DngAdmission.read(it) }
        if (crop != null) {
            frames = frames.map { DngAdmission.crop(it, crop[0], crop[1], crop[2], crop[3]) }
        }
        DngAdmission.admit(frames)
        return frames.mapIndexed { index, admitted ->
            val codes = requireNotNull(admitted.codes) { "${admitted.file.name}: codes missing" }
            require(codes.size == admitted.width * admitted.height) {
                "${admitted.file.name}: codes ${codes.size} != ${admitted.width}x${admitted.height}"
            }
            val plane = ByteBuffer.allocateDirect(codes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (code in codes) plane.putShort(code)
            plane.flip()
            val metadata = RawFrameMetadataFactory.fromDng(
                "0", admitted, index.toLong(), admitted.file.lastModified() * 1_000_000L
            )
            LoadedFrame(plane, metadata, admitted, admitted.file)
        }
    }
}
