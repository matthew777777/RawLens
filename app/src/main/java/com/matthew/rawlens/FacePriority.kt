// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * HAL face-detection (Camera2 `STATISTICS_FACES`) selection and mapping.
 * Pure Kotlin with no Android framework types so the policy stays unit-testable;
 * the controller converts [HalFace] to [android.hardware.camera2.params.MeteringRectangle]
 * and [FaceViewBox] to overlay pixels at the boundary.
 *
 * Face bounds arrive in sensor active-array coordinates. The tracked face drives
 * the AF/AE metering regions while tap targets override it; every visible face
 * is drawn on the viewfinder overlay.
 */
data class HalFace(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Sensor active-array bounds in pixels, for normalizing face coordinates. */
data class SensorActiveArray(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Face box in normalized view coordinates (0..1); the caller scales to pixels. */
data class FaceViewBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val tracked: Boolean
)

/** Face box in viewfinder pixels for the overlay; [tracked] marks the AF/AE face. */
data class FaceOverlayBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val tracked: Boolean
)

object FacePriority {
    /**
     * Two detections are the same face while their centers stay within this
     * fraction of the previous box size on both axes. Keeps the tracked face
     * (and its metering region) stable across HAL box jitter instead of
     * flip-flopping between nearby faces every frame.
     */
    const val TRACK_MATCH_FRACTION = 0.25f

    /** Same face, possibly jittered by the HAL: track-lock comparison. */
    fun matches(a: HalFace, b: HalFace): Boolean {
        val tolX = a.width * TRACK_MATCH_FRACTION
        val tolY = a.height * TRACK_MATCH_FRACTION
        return kotlin.math.abs(a.centerX - b.centerX) <= tolX &&
            kotlin.math.abs(a.centerY - b.centerY) <= tolY
    }

    /**
     * Picks the tracked face: the previous one while it is still visible,
     * otherwise the largest box (closest subject), breaking area ties by
     * HAL score. Null when no face is visible.
     */
    fun select(faces: List<HalFace>, previous: HalFace?): HalFace? {
        if (faces.isEmpty()) return null
        if (previous != null) faces.firstOrNull { matches(previous, it) }?.let { return it }
        return faces.maxWithOrNull(compareBy<HalFace> { it.area }.thenBy { it.score })
    }

    /**
     * True when both edges sit within [tolerancePx] on every side: the caller
     * skips the repeating-request rebuild while the HAL box only jitters.
     */
    fun nearlyEqual(a: HalFace, b: HalFace, tolerancePx: Int): Boolean =
        kotlin.math.abs(a.left - b.left) <= tolerancePx &&
            kotlin.math.abs(a.top - b.top) <= tolerancePx &&
            kotlin.math.abs(a.right - b.right) <= tolerancePx &&
            kotlin.math.abs(a.bottom - b.bottom) <= tolerancePx

    /**
     * Maps a sensor-space face box into normalized view coordinates, mirroring
     * the tap path ([RawPreviewGeometry.sensorPoint]) in reverse: same rotation
     * convention, same un-offset normalization by the viewfinder size.
     */
    fun mapToView(
        face: HalFace,
        active: SensorActiveArray,
        rotation: Int,
        mirrored: Boolean
    ): FaceViewBox {
        fun u(px: Int): Float =
            if (active.width <= 0) 0f else ((px - active.left).toFloat() / active.width).coerceIn(0f, 1f)
        fun v(px: Int): Float =
            if (active.height <= 0) 0f else ((px - active.top).toFloat() / active.height).coerceIn(0f, 1f)
        val p0 = RawPreviewGeometry.viewPoint(u(face.left), v(face.top), rotation, mirrored)
        val p1 = RawPreviewGeometry.viewPoint(u(face.right), v(face.bottom), rotation, mirrored)
        return FaceViewBox(
            left = minOf(p0.first, p1.first),
            top = minOf(p0.second, p1.second),
            right = maxOf(p0.first, p1.first),
            bottom = maxOf(p0.second, p1.second),
            tracked = false
        )
    }
}
