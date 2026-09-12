// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.io.OutputStream

/**
 * GCam-payload-style merge debugging: every frame that went into a burst
 * merge, saved as an individual DNG next to the merged output, plus a
 * payload.txt describing the burst. The caller owns directory storage and
 * writes the merged file itself with the matching *-writer; this helper
 * owns frame naming, frame serialization and the manifest text so all of
 * that stays unit-tested. Dumping is strictly opt-in (see
 * [RawSuperResolutionSettings.saveMergeDebugFrames]) and must never fail
 * the real save — callers wrap it in try/catch and log.
 */
object MergeDebugPayload {
    /** One merge input: unpacked CFA plus the metadata its DNG needs. */
    data class DebugFrame(
        val fileIndex: Int,
        val cfa: UnpackedRawCfa,
        val metadata: RawFrameMetadata
    )

    fun frameFileName(index: Int): String = "frame-" + index.toString().padStart(2, '0') + ".dng"

    /** Creates (if needed) and returns root/tag. Throws on failure. */
    fun payloadDir(root: File, tag: String): File =
        File(root, tag).also {
            require(it.isDirectory || it.mkdirs()) { "Cannot create merge-debug dir $it" }
        }

    fun writeFrame(output: OutputStream, frame: DebugFrame, gps: GpsLocation? = null) {
        FloatCfaDngWriter.write(output, frame.cfa, frame.metadata, gps)
    }

    private fun describeFrame(frame: DebugFrame): String {
        val m = frame.metadata
        return "frame.${frame.fileIndex}.file=${frameFileName(frame.fileIndex)}\n" +
            "frame.${frame.fileIndex}.size=${m.imageWidth}x${m.imageHeight}\n" +
            "frame.${frame.fileIndex}.timestampNs=${m.timestampNanos}\n" +
            "frame.${frame.fileIndex}.frameNumber=${m.frameNumber}\n" +
            "frame.${frame.fileIndex}.iso=${m.sensitivityIso?.toString() ?: "?"}\n" +
            "frame.${frame.fileIndex}.exposureNs=${m.exposureTimeNanos?.toString() ?: "?"}"
    }

    /** Manifest text: caller info lines first, then one stanza per frame. */
    fun payloadText(info: Map<String, String>, frames: List<DebugFrame>): String {
        val out = StringBuilder()
        for ((key, value) in info) out.append(key).append('=').append(value).append('\n')
        for (frame in frames.sortedBy { it.fileIndex }) {
            out.append(describeFrame(frame)).append('\n')
        }
        return out.toString()
    }
}
