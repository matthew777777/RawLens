// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.Locale

/** One frozen record shared by the DNG and JPEG save paths, including JPEG-only captures. */
internal fun sharpMergeProvenance(
    merged: MfsrMerge.MfsrResult,
    settings: MfsrSettings,
    selectedCount: Int,
    frames: List<RawFrameMetadata>,
    referenceSelection: String
): MergeProvenance {
    val lkr = settings.engine == MfsrEngine.LKR
    val ref = merged.refMetadata
    val details = buildString {
        append("- Output: ${merged.width}x${merged.height}\n")
        if (lkr) {
            append("- Merge: LK-SR (LK-Reloaded reconstruction)\n")
            append("- Zoom: ${settings.lkrZoom.factor}x\n")
            append("- Reconstruction scale: ${if (settings.lkrZoom == LkrZoom.X1) 1 else 2}x\n")
            append("- LK prior: ${if (settings.lkrDctPrior) "Sliding DCT" else "None"}\n")
        } else {
            append("- Merge: MFSR (robust multi-frame reconstruction)\n")
            append("- Requested kernel: ${settings.kernel}\n")
            append("- High-frequency rejection: ${settings.s3}\n")
            append("- Learned noise requested: ${settings.learnedNoise}\n")
            append("- Colour-difference zipper fix: ${settings.zipperFix}\n")
        }
        append("\nBase Frame Selection:\n- Mode: $referenceSelection\n")
        val index = frames.indexOfFirst { it.timestampNanos == ref.timestampNanos }
        append("- Reference: ${if (index >= 0) "frame ${index + 1} of ${frames.size}" else "unknown"}\n")
        append("\nFrames (merge input order):\n")
        frames.forEachIndexed { i, frame ->
            val exposure = frame.exposureTimeNanos?.takeIf { it > 0 }
                ?.let { String.format(Locale.US, "%.3f ms", it / 1e6) } ?: "? ms"
            append("- F${i + 1}: $exposure, ISO ${frame.sensitivityIso ?: "?"}")
            if (i == index) append(" (reference)")
            append('\n')
        }
        append("\nTIMINGS\n- Merge: ${merged.mergeMs} ms\n")
        append(merged.mergeDetails)
        append("- Source dimensions: ${ref.imageWidth}x${ref.imageHeight}\n")
        append("- Reference frame: ${ref.frameNumber}\n")
    }
    return MergeProvenance(
        algorithmVersion = if (lkr) "RawLens-LK-SR/lkr-pipeline" else "RawLens-Mfsr/scale1-sr-pipeline",
        selectedFrames = selectedCount, acceptedFrames = merged.keptCount,
        rejectedFrames = selectedCount - merged.keptCount,
        referenceTimestampNs = ref.timestampNanos, outputScale = 1,
        sourceCameraId = ref.cameraId,
        lensShadingApplied = RawPreDemosaicPipeline.lensShadingModel(ref) != null,
        effectiveFrames = merged.keptCount.toDouble(), engineLabel = settings.label, details = details
    )
}

internal fun MfsrSettings.mergeFileSuffix(): String =
    if (engine == MfsrEngine.LKR) CaptureFileNames.TYPE_LKSR else CaptureFileNames.TYPE_MFSR
