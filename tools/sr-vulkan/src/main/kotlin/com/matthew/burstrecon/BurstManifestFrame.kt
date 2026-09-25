// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// VERBATIM LIFT from burstrecon/BundleIo.kt:294-321. Manifest entry type
// referenced by DngAdmission.resolveExposure.
// tools/parity_sr_vulkan.py diffs the LIFT region on every check.
package com.matthew.burstrecon

// LIFT-BEGIN (verbatim, parity-checked)
/** One admitted frame entry in the manifest. */
data class BurstManifestFrame(
    /** File name under frames/ (e.g. 000.dng). */
    val file: String,
    val timestampNanos: Long,
    val exposureNanos: Long,
    val iso: Int,
    val skewNanos: Long,
    /** Expected SHA-256 of the frame file (empty = skip hash check). */
    val sha256: String = "",
    val cameraId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val cfaPattern: String = "",
    val sensorLeft: Int = 0,
    val sensorTop: Int = 0,
    /**
     * Color calibration as seen at import (9 ColorMatrix1 + 3
     * AsShotNeutral doubles). Empty = undeclared: manifests written before
     * calibration was recorded skip the admission cross-check.
     */
    val colorMatrix1: List<Double> = emptyList(),
    val asShotNeutral: List<Double> = emptyList(),
    /** Nullable capture states for reference scoring; null = unknown. */
    val afState: Int? = null,
    val aeState: Int? = null,
    val lensState: Int? = null
)
// LIFT-END
