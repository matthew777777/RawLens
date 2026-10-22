// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/** Sensor-coordinate rectangle the tracked face meters (AF and AE alike). */
internal data class FaceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Face-priority AF/AE (HAL `STATISTICS_FACES`): tracks one face across
 * preview results and steers the AF/AE region at it. Regions apply only
 * while no tap target overrides them (the controller's applyCameraControls).
 * Camera thread only, except [maxFaceCount]/[rejected], which the UI reads
 * for the N/A note. Throttles both the repeating pushes (jittery boxes must
 * not rebuild the stream every frame) and the overlay publication.
 *
 * Extracted from RawCameraController (A8, docs/refactor/controller-remaining-plan.md):
 * same decisions; `Rect.intersect` is replaced by the identical integer test
 * and the two always-equal AF/AE MeteringRectangles by one [region].
 */
internal class FaceTracking(private val host: Host, initialEnabled: Boolean) {
    interface Host {
        fun nowMs(): Long
        /** Rebuild the repeating request so the new [region] applies. */
        fun pushRegions()
        /** Viewfinder size in px; non-positive skips the overlay. */
        fun viewSize(): Pair<Int, Int>
        fun sensorOrientation(): Int
        fun frontFacing(): Boolean
        fun publish(boxes: List<FaceOverlayBox>)
    }

    var enabled: Boolean = initialEnabled
        private set
    @Volatile var maxFaceCount: Int = 0
        private set
    @Volatile var rejected: Boolean = false
        private set
    /** Region of the tracked face, null when none is metered. */
    var region: FaceRegion? = null
        private set
    private var tracked: HalFace? = null
    private var regionRect: FaceRegion? = null
    private var missCount = 0
    private var lastRegionPushMs = 0L
    private var lastPublishMs = 0L

    /** New session: face capability of the opened lens, rejection latch reset. */
    fun resetForSession(maxFaces: Int) {
        maxFaceCount = maxFaces
        rejected = false
        clear()
    }

    /** Settings toggle; false when unchanged. Drops the tracked face either way. */
    fun setEnabled(value: Boolean): Boolean {
        if (enabled == value) return false
        enabled = value
        clear()
        return true
    }

    /** False before the first open, on lenses without face stats, or after a HAL rejection. */
    fun isSupported(): Boolean = maxFaceCount > 0 && !rejected

    /** Face-detect mode to request: null after a rejection (leave the key unset). */
    fun requestedDetectFull(): Boolean? = if (rejected) null else (enabled && maxFaceCount > 0)

    /** A HAL that advertises faces but rejects the key: run faceless this session. */
    fun markRejected() {
        rejected = true
    }

    fun clear() {
        tracked = null
        regionRect = null
        region = null
        missCount = 0
        host.publish(emptyList())
    }

    /** One preview result's faces (already clamped to [active]); [crop] = SCALER_CROP_REGION. */
    fun onPreviewFaces(faces: List<HalFace>, active: SensorActiveArray, crop: SensorActiveArray) {
        if (!enabled || maxFaceCount <= 0 || rejected) return
        val selected = FacePriority.select(faces, tracked)
        if (selected == null) {
            // Debounce: a single faceless result is usually a blink, not a departure.
            if (++missCount >= MISS_TOLERANCE && tracked != null) {
                tracked = null
                regionRect = null
                region = null
                host.pushRegions()
            }
        } else {
            missCount = 0
            tracked = selected
            // Metering rectangles must sit inside the crop, like tap targets.
            val rect = intersect(selected, crop)
            if (rect == null) {
                if (region != null) {
                    regionRect = null
                    region = null
                    host.pushRegions()
                }
            } else {
                val tolerance = (active.width * REGION_MOVE_FRACTION).toInt().coerceAtLeast(1)
                val previous = regionRect
                val moved = previous == null ||
                    Math.abs(previous.left - rect.left) > tolerance ||
                    Math.abs(previous.top - rect.top) > tolerance ||
                    Math.abs(previous.right - rect.right) > tolerance ||
                    Math.abs(previous.bottom - rect.bottom) > tolerance
                val now = host.nowMs()
                if (moved && now - lastRegionPushMs >= REGION_PUSH_MIN_INTERVAL_MS) {
                    lastRegionPushMs = now
                    regionRect = rect
                    region = rect
                    host.pushRegions()
                }
            }
        }
        publish(faces, selected, active)
    }

    private fun publish(faces: List<HalFace>, selected: HalFace?, active: SensorActiveArray) {
        val now = host.nowMs()
        if (now - lastPublishMs < OVERLAY_INTERVAL_MS) return
        lastPublishMs = now
        val (width, height) = host.viewSize()
        if (width <= 0 || height <= 0) return
        // Same un-offset convention as taps: overlay pixels are fractions of the
        // viewfinder size, so boxes land where a tap at the same spot meters.
        val rotation = host.sensorOrientation()
        val mirrored = host.frontFacing()
        host.publish(faces.map { face ->
            val box = FacePriority.mapToView(face, active, rotation, mirrored)
            FaceOverlayBox(
                box.left * width, box.top * height,
                box.right * width, box.bottom * height,
                face == selected
            )
        })
    }

    internal companion object {
        /** Consecutive faceless results before the tracked face (and its regions) drops. */
        const val MISS_TOLERANCE = 5
        /** Minimum gap between face-region repeating pushes; HAL boxes jitter every frame. */
        const val REGION_PUSH_MIN_INTERVAL_MS = 150L
        /** Face-box overlay cadence; full preview rate would just churn invalidates. */
        const val OVERLAY_INTERVAL_MS = 80L
        /** Region rebuild threshold as a fraction of the active-array width. */
        const val REGION_MOVE_FRACTION = 0.02f

        /** `android.graphics.Rect.intersect`: null when the rectangles do not overlap. */
        fun intersect(face: HalFace, crop: SensorActiveArray): FaceRegion? {
            if (!(face.left < crop.right && crop.left < face.right &&
                    face.top < crop.bottom && crop.top < face.bottom)) return null
            return FaceRegion(
                maxOf(face.left, crop.left), maxOf(face.top, crop.top),
                minOf(face.right, crop.right), minOf(face.bottom, crop.bottom)
            )
        }
    }
}
