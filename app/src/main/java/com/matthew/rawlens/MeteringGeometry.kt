// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Camera2 crop in the coordinate system selected by distortion correction. */
internal object MeteringGeometry {
    fun activeArray(active: SensorActiveArray, preCorrection: SensorActiveArray?,
                    supportsDistortion: Boolean, correctionOff: Boolean): SensorActiveArray =
        if (supportsDistortion && correctionOff) preCorrection ?: active else active

    /** Additional center crop used by a processed output with a different aspect ratio. */
    fun streamCrop(crop: SensorActiveArray, width: Int, height: Int): SensorActiveArray {
        if (width <= 0 || height <= 0 || crop.width <= 0 || crop.height <= 0) return crop
        return if (crop.width.toLong() * height > crop.height.toLong() * width) {
            val w = (crop.height.toLong() * width / height).toInt().coerceAtLeast(1)
            val left = crop.left + (crop.width - w) / 2
            SensorActiveArray(left, crop.top, left + w, crop.bottom)
        } else {
            val h = (crop.width.toLong() * height / width).toInt().coerceAtLeast(1)
            val top = crop.top + (crop.height - h) / 2
            SensorActiveArray(crop.left, top, crop.right, top + h)
        }
    }

    fun tap(x: Float, y: Float, crop: SensorActiveArray, rotation: Int, mirrored: Boolean): FaceRegion {
        val point = RawPreviewGeometry.sensorPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), rotation, mirrored)
        val cx = crop.left + (point.first * crop.width).toInt()
        val cy = crop.top + (point.second * crop.height).toInt()
        val hw = (crop.width * 0.05f).toInt().coerceAtLeast(1)
        val hh = (crop.height * 0.05f).toInt().coerceAtLeast(1)
        return FaceRegion(
            (cx - hw).coerceIn(crop.left, crop.right - 1),
            (cy - hh).coerceIn(crop.top, crop.bottom - 1),
            (cx + hw).coerceIn(crop.left + 1, crop.right),
            (cy + hh).coerceIn(crop.top + 1, crop.bottom))
    }
}
