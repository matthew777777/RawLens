// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gyro-to-warp planner: turns a [StabSidecar.Take] into one 3x3
 * output-pixel → source-pixel homography per encoded frame.
 *
 * Pipeline: integrate camera-frame gyro per frame → absolute orientation
 * (quaternion accumulation, exact for large pans) → yaw/pitch/roll Euler
 * (rotation about stored Y/X/Z) → moving-average smoothing (~0.5 s) →
 * shake = raw − smooth → content-motion law (see [GyroCameraFrameMapper]:
 * shift ∝ (+fx·ry, −fy·rx), +rz screen-clockwise) → per-frame affine
 * correction folded with the crop-and-scale-to-fill into one matrix.
 *
 * Conventions: encode axes ARE stored axes (the record path encodes a
 * center sensor crop scaled uniformly to 16:9, no transpose), so no axis
 * remap runs here. Rolling shutter is ignored (per-frame center sampling);
 * per-row correction is later work.
 *
 * Fail-closed: null when gyro coverage is too thin (avg < 1 sample/frame),
 * intrinsics are unavailable, or geometry is implausible — the caller then
 * keeps the original MP4. Per-frame, an empty gyro window holds the last
 * delta (fail-soft); corrections clamp to the crop margin so the sample
 * window never leaves the frame (no black borders, ever).
 *
 * Pure JVM, no Android dependencies — unit-testable on the host.
 */
internal object StabTrajectory {
    /** Crop margin per side (fraction of encode dims); the shake headroom. */
    const val CROP_MARGIN = 0.05f

    /** Smoothing window in seconds (steady-cam lag ≈ half of this). */
    const val SMOOTH_SECONDS = 0.5

    /** Correction clamps: 90% of the margin + max roll (rad). */
    const val SHIFT_CLAMP_FRACTION = 0.9f
    const val ROLL_CLAMP_RAD = 0.15f

    /** Minimum average gyro samples per frame, else the plan is refused. */
    const val MIN_SAMPLES_PER_FRAME = 1.0

    data class Plan(
        /** Row-major 3x3 per frame, `frameCount * 9` floats. */
        val matrices: FloatArray,
        val frameCount: Int,
    ) {
        init {
            require(matrices.size == frameCount * 9) { "matrix array size drift" }
        }

        /** Row-major 3x3 for [frame] (copy). */
        fun matrixFor(frame: Int): FloatArray {
            require(frame in 0 until frameCount) { "frame $frame out of range" }
            return matrices.copyOfRange(frame * 9, frame * 9 + 9)
        }
    }

    /**
     * Pinhole intrinsics in encode pixels for the take, or null when the
     * calibration is missing/implausible. The encode is the center sensor
     * crop scaled uniformly, so focal_px = focalMm * encodePx / cropMm.
     */
    fun intrinsicsForEncode(h: StabSidecar.Header): BurstSidecar.IntrinsicsSnapshot? {
        if (h.sensorW <= 0 || h.sensorH <= 0) return null
        if (h.cropW <= 0 || h.cropH <= 0 || h.cropW > h.sensorW || h.cropH > h.sensorH) return null
        // Physical crop window (uniform pixel pitch assumed — standard).
        val cropWMm = h.sensorWMm.toDouble() * h.cropW / h.sensorW
        val cropHMm = h.sensorHMm.toDouble() * h.cropH / h.sensorH
        if (!cropWMm.isFinite() || cropWMm <= 0.0) return null
        if (!cropHMm.isFinite() || cropHMm <= 0.0) return null
        val fx = h.focalMm.toDouble() * h.encodeW / cropWMm
        val fy = h.focalMm.toDouble() * h.encodeH / cropHMm
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0.0 || fy <= 0.0) return null
        // Sanity: focal length in pixels should exceed half the frame
        // (wider than ~90° FOV smells like a unit mixup).
        if (fx < h.encodeW / 2.0 || fy < h.encodeH / 2.0) return null
        return BurstSidecar.IntrinsicsSnapshot(fx, fy, h.encodeW / 2.0, h.encodeH / 2.0)
    }

    /**
     * Plans the warp, or null when the take cannot stabilize (see class
     * docs). [cropMargin] overrides [CROP_MARGIN] (tests, tuning).
     */
    fun plan(take: StabSidecar.Take, cropMargin: Float = CROP_MARGIN): Plan? {
        val h = take.header
        val frames = take.frameTimestampsNs.size
        if (frames == 0) return null
        if (take.gyro.size < frames * MIN_SAMPLES_PER_FRAME) return null
        if (cropMargin <= 0f || cropMargin >= 0.25f) return null
        val intrinsics = intrinsicsForEncode(h) ?: return null
        if (h.encodeW <= 0 || h.encodeH <= 0) return null

        // 1. Per-frame rotation deltas (camera-frame radians).
        val deltas = integrateDeltas(take.frameTimestampsNs, take.gyro)

        // 2. Absolute orientation (exact quaternion accumulation).
        var q = Quat.IDENTITY
        val ax = DoubleArray(frames)
        val ay = DoubleArray(frames)
        val az = DoubleArray(frames)
        for (i in 0 until frames) {
            q = q.then(Quat.fromRotationVector(deltas[i * 3], deltas[i * 3 + 1], deltas[i * 3 + 2]))
            val e = q.toEulerYxz()
            ax[i] = e[0]
            ay[i] = e[1]
            az[i] = e[2]
        }
        unwrapInPlace(ax)
        unwrapInPlace(ay)
        unwrapInPlace(az)

        // 3. Smooth each channel; shake = raw − smooth.
        val window = ((SMOOTH_SECONDS * h.fps).toInt().coerceIn(3, 121) / 2 * 2) + 1
        val sx = smooth(ax, window)
        val sy = smooth(ay, window)
        val sz = smooth(az, window)

        // 4. Fold content motion + crop/scale into one matrix per frame.
        val w = h.encodeW.toDouble()
        val ht = h.encodeH.toDouble()
        val s = 1.0 - 2.0 * cropMargin
        val ix = cropMargin * w
        val iy = cropMargin * ht
        val maxTx = (cropMargin * w * SHIFT_CLAMP_FRACTION).toFloat()
        val maxTy = (cropMargin * ht * SHIFT_CLAMP_FRACTION).toFloat()
        val cx = w / 2.0
        val cy = ht / 2.0
        val out = FloatArray(frames * 9)
        for (i in 0 until frames) {
            // Shake (raw − smooth) through the content-motion law.
            var tx = (intrinsics.fxPixels * (ay[i] - sy[i])).toFloat()
            var ty = (-intrinsics.fyPixels * (ax[i] - sx[i])).toFloat()
            var roll = (az[i] - sz[i]).toFloat()
            tx = tx.coerceIn(-maxTx, maxTx)
            ty = ty.coerceIn(-maxTy, maxTy)
            roll = roll.coerceIn(-ROLL_CLAMP_RAD, ROLL_CLAMP_RAD)
            // M = [R | C − R·C + t]: rotation about the frame center
            // plus the content shift; H = M · cropScale.
            val c = cos(roll.toDouble())
            val sn = sin(roll.toDouble())
            val m00 = c
            val m01 = -sn
            val m10 = sn
            val m11 = c
            val m02 = cx - (c * cx - sn * cy) + tx
            val m12 = cy - (sn * cx + c * cy) + ty
            // Times cropScale [s 0 ix; 0 s iy; 0 0 1].
            val o = i * 9
            out[o] = (m00 * s).toFloat()
            out[o + 1] = (m01 * s).toFloat()
            out[o + 2] = (m00 * ix + m01 * iy + m02).toFloat()
            out[o + 3] = (m10 * s).toFloat()
            out[o + 4] = (m11 * s).toFloat()
            out[o + 5] = (m10 * ix + m11 * iy + m12).toFloat()
            out[o + 6] = 0f
            out[o + 7] = 0f
            out[o + 8] = 1f
        }
        return Plan(out, frames)
    }

    /**
     * Per-frame rotation deltas in radians (flat `frames * 3`). Frame i
     * integrates gyro over [ts[i], ts[i+1]); the last frame reuses the
     * median interval. An empty window holds the previous delta (zeros for
     * frame 0) — fail-soft, covered by the whole-take coverage gate.
     */
    internal fun integrateDeltas(frameTs: LongArray, gyro: List<GyroSample>): DoubleArray {
        val frames = frameTs.size
        val out = DoubleArray(frames * 3)
        if (frames == 0 || gyro.isEmpty()) return out
        val lastDt = if (frames > 1) {
            val gaps = LongArray(frames - 1) { frameTs[it + 1] - frameTs[it] }
            gaps.sorted()[gaps.size / 2].coerceAtLeast(1L)
        } else {
            33_333_333L
        }
        var gi = 0
        // Skip pre-roll samples (gyro starts before the first frame).
        while (gi < gyro.size && gyro[gi].timestampNanos < frameTs[0]) gi++
        for (f in 0 until frames) {
            val start = frameTs[f]
            val end = if (f + 1 < frames) frameTs[f + 1] else start + lastDt
            var dx = 0.0
            var dy = 0.0
            var dz = 0.0
            var count = 0
            // Advance past samples at/before the window start, keeping the
            // nearest one as the integration seed (nearest-sample hold).
            var seed = gi
            while (seed + 1 < gyro.size && gyro[seed + 1].timestampNanos <= start) seed++
            var prev = start
            var k = seed
            // Seed sample covers [start, next sample): hold its rate.
            if (k < gyro.size && gyro[k].timestampNanos <= start) {
                val next = if (k + 1 < gyro.size) gyro[k + 1].timestampNanos.coerceAtMost(end) else end
                if (next > prev) {
                    val dt = (next - prev) / 1e9
                    dx += gyro[k].xRadiansPerSecond * dt
                    dy += gyro[k].yRadiansPerSecond * dt
                    dz += gyro[k].zRadiansPerSecond * dt
                    count++
                    prev = next
                }
                k++
            }
            while (k < gyro.size && gyro[k].timestampNanos < end) {
                val next = if (k + 1 < gyro.size) gyro[k + 1].timestampNanos.coerceAtMost(end) else end
                if (next > prev) {
                    val dt = (next - prev) / 1e9
                    dx += gyro[k].xRadiansPerSecond * dt
                    dy += gyro[k].yRadiansPerSecond * dt
                    dz += gyro[k].zRadiansPerSecond * dt
                    count++
                    prev = next
                }
                k++
            }
            if (count == 0 && f > 0) {
                // Empty window: hold the last delta (fail-soft).
                out[f * 3] = out[(f - 1) * 3]
                out[f * 3 + 1] = out[(f - 1) * 3 + 1]
                out[f * 3 + 2] = out[(f - 1) * 3 + 2]
            } else {
                out[f * 3] = dx
                out[f * 3 + 1] = dy
                out[f * 3 + 2] = dz
            }
            gi = seed
        }
        return out
    }

    /** Centered moving average (edge indices clamp); [window] must be odd. */
    internal fun smooth(channel: DoubleArray, window: Int): DoubleArray {
        require(window % 2 == 1) { "window must be odd" }
        val half = window / 2
        val n = channel.size
        if (n == 0) return DoubleArray(0)
        // O(n·w) with w ≤ 121 (≈13M ops for a 10-min take) — acceptable
        // for a once-per-take background pass; edges clamp (replicate).
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            for (k in -half..half) {
                sum += channel[(i + k).coerceIn(0, n - 1)]
            }
            out[i] = sum / window
        }
        return out
    }

    /** Unwraps radian angles in place (consecutive jumps land in [-π, π]). */
    internal fun unwrapInPlace(channel: DoubleArray) {
        if (channel.isEmpty()) return
        var offset = 0.0
        var prev = channel[0]
        for (i in 1 until channel.size) {
            val d = (channel[i] + offset) - prev
            if (d > Math.PI) offset -= 2.0 * Math.PI
            else if (d < -Math.PI) offset += 2.0 * Math.PI
            channel[i] += offset
            prev = channel[i]
        }
    }

    /** Minimal unit quaternion: compose + Euler(Y-X-Z intrinsic) extract. */
    internal data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
        /** this ⊗ [other] (apply [other] after this). */
        fun then(other: Quat): Quat = Quat(
            w * other.w - x * other.x - y * other.y - z * other.z,
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w
        )

        /**
         * Euler angles (ax about X, ay about Y, az about Z) for
         * R = Ry(ay)·Rx(ax)·Rz(az). Middle angle is pitch: gimbal lock
         * only pointing straight up/down (a yaw pan never locks).
         */
        fun toEulerYxz(): DoubleArray {
            // Rotation matrix rows (q stays unit by construction;
            // renormalize defensively against float drift).
            val n = w * w + x * x + y * y + z * z
            val s = if (n > 0.0) 1.0 / kotlin.math.sqrt(n) else 0.0
            val qw = w * s
            val qx = x * s
            val qy = y * s
            val qz = z * s
            val r00 = 1.0 - 2.0 * (qy * qy + qz * qz)
            val r01 = 2.0 * (qx * qy - qz * qw)
            val r02 = 2.0 * (qx * qz + qy * qw)
            val r10 = 2.0 * (qx * qy + qz * qw)
            val r11 = 1.0 - 2.0 * (qx * qx + qz * qz)
            val r12 = 2.0 * (qy * qz - qx * qw)
            val r22 = 1.0 - 2.0 * (qx * qx + qy * qy)
            val ax = asin((-r12).coerceIn(-1.0, 1.0))
            val ay = atan2(r02, r22)
            val az = atan2(r10, r11)
            return doubleArrayOf(ax, ay, az)
        }

        companion object {
            val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

            /** Quaternion for the rotation vector ([rx], [ry], [rz]) rad. */
            fun fromRotationVector(rx: Double, ry: Double, rz: Double): Quat {
                val angle = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
                if (angle < 1e-12) return IDENTITY
                val half = angle / 2.0
                val k = sin(half) / angle
                return Quat(cos(half), rx * k, ry * k, rz * k)
            }
        }
    }
}
