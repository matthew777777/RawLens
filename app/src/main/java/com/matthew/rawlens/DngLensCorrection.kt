// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Converts Camera2's focal-normalized Brown model to DNG's corner-normalized model.
 * No pixel resampling: correction remains an optional, post-demosaic OpcodeList3.
 * See docs/dng-lens-correction.md for coordinate conventions and compatibility limits.
 */
internal object DngLensCorrection {
    /** [active] is the retained rectangle in the original RAW buffer, before any writer crop. */
    fun opcodeList(metadata: RawFrameMetadata, active: RawCrop): ByteArray? {
        val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported ?: return null
        // Vendor readout overrides rewrite geometry without remapping optical calibration.
        if (metadata.sensorReadoutProfile != null || (metadata.sensorPixelMode ?: 0) != 0 ||
            metadata.rawBinningFactorUsed == true) return null
        val pre = metadata.preCorrectionActiveArray ?: return null
        val intrinsics = metadata.lensIntrinsics?.toFloatArray() ?: return null
        val distortion = metadata.lensDistortion?.toFloatArray() ?: return null
        if (active.left < 0 || active.top < 0 || active.width <= 0 || active.height <= 0 ||
            active.left.toLong() + active.width > metadata.imageWidth ||
            active.top.toLong() + active.height > metadata.imageHeight) return null
        val x = geometry.sensorOriginX.toDouble() + active.left - pre.left
        val y = geometry.sensorOriginY.toDouble() + active.top - pre.top
        if (x < 0 || y < 0 || x + active.width > pre.width || y + active.height > pre.height) return null
        return encode(intrinsics, distortion, active.width, active.height, x, y)
    }

    internal fun encode(intrinsics: FloatArray, distortion: FloatArray,
                        width: Int, height: Int, cropX: Double = 0.0, cropY: Double = 0.0): ByteArray? {
        if (intrinsics.size != 5 || distortion.size != 5 ||
            !intrinsics.all(Float::isFinite) || !distortion.all(Float::isFinite) ||
            width <= 0 || height <= 0 || !cropX.isFinite() || !cropY.isFinite()) return null
        if (distortion.all { it == 0f }) return null
        val f = intrinsics[0].toDouble()
        // A single isotropic WarpRectilinear cannot exactly represent skew or fx != fy.
        // Permit only floating-point rounding, rather than silently approximating optics.
        if (f <= 0 || abs(intrinsics[1] - f) > f * 1e-6 || abs(intrinsics[4]) > f * 1e-6) return null
        val cx = intrinsics[2] - cropX
        val cy = intrinsics[3] - cropY
        if (cx <= 0 || cx >= width || cy <= 0 || cy >= height) return null
        val radius = hypot(max(cx, width - cx), max(cy, height - cy))
        val scale = radius / f
        val s2 = scale * scale
        val coefficients = doubleArrayOf(1.0, distortion[0] * s2,
            distortion[1] * s2 * s2, distortion[2] * s2 * s2 * s2,
            distortion[3] * scale, distortion[4] * scale)
        if (!coefficients.all(Double::isFinite)) return null
        // Reject folding/degenerate HAL models across the complete retained radius.
        for (i in 0..256) {
            val r2 = i / 256.0
            val radial = 1 + coefficients[1] * r2 + coefficients[2] * r2*r2 + coefficients[3] * r2*r2*r2
            val derivative = 1 + 3*coefficients[1]*r2 + 5*coefficients[2]*r2*r2 + 7*coefficients[3]*r2*r2*r2
            if (radial <= 0 || derivative <= 0) return null
        }
        for (iy in 0..16) for (ix in 0..16) {
            val x = (ix * width / 16.0 - cx) / radius
            val y = (iy * height / 16.0 - cy) / radius
            val t = x*x + y*y
            val p = 1 + coefficients[1]*t + coefficients[2]*t*t + coefficients[3]*t*t*t
            val d = coefficients[1] + 2*coefficients[2]*t + 3*coefficients[3]*t*t
            val p1 = coefficients[4]; val p2 = coefficients[5]
            val a = p + 2*x*x*d + 2*p1*y + 6*p2*x
            val b = 2*x*y*d + 2*p1*x + 2*p2*y
            val c = p + 2*y*y*d + 6*p1*y + 2*p2*x
            val determinant = a*c - b*b
            if (!determinant.isFinite() || a <= 0 || c <= 0 || determinant <= 0) return null
        }
        return ByteBuffer.allocate(88).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1) // opcode count
            putInt(1).putInt(0x01030000).putInt(1).putInt(68) // WarpRectilinear, optional
            putInt(1) // same geometric calibration for all color planes; no invented TCA
            coefficients.forEach { putDouble(it) }
            putDouble(cx / width).putDouble(cy / height)
        }.array()
    }
}
