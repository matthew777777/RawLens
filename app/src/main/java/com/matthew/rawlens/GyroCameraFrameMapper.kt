// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.cos
import kotlin.math.sin

/**
 * Device-frame gyroscope rates into the desktop pipeline's camera frame.
 *
 * The desktop (`tools/burst-reconstruction-desktop`) merges in STORED sensor
 * orientation and assumes gyro xyz is already expressed in the camera frame
 * (its words), i.e. in stored-image axes: X right along columns, Y down
 * along rows. Its seed convention is pinned operationally
 * (`GlobalAlign.seedFromRotation`, `GyroSyncTest.seedRotatesAboutPrincipalPoint`):
 * content shift ∝ (+fx·ry, −fy·rx), and +rz rotates content clockwise on
 * screen. Feeding it device-frame rates unmapped (or wrong-signed) yields
 * backwards seeds, which are worse than no gyro at all — hence this mapper
 * is explicit, pure, and pinned by motion probes, not by assumption.
 *
 * Derivation (back camera, portrait attitude, sensor landscape mount with
 * SENSOR_ORIENTATION=90 — the overwhelmingly common case; verified four
 * ways below). Stored axes in device coordinates (x right, y up,
 * z toward viewer, portrait natural orientation):
 * - stored +x (increasing column) = device −Y (down),
 * - stored +y (increasing row) = device −X (left),
 * - sensor normal (back lens) = device −Z (into scene),
 * which is right-handed (x̂×ŷ=−ẑ... checked: (0,−1,0)×(−1,0,0)=(0,0,−1) ✓).
 * The desktop seed additionally needs a 180° rotation about the sensor
 * normal (pinned, not derived — see probes); the composition stays a proper
 * rotation (det +1), i.e. no mirror is smuggled in for the back camera.
 *
 * Motion probes (each verified against `seedFromRotation` signs):
 * - pan right (device ωy<0, content shifts display-left = stored +y):
 *   needs ty>0, i.e. rx<0. Mapper gives rx=−0−(−1·ωy)... concretely
 *   M·(0,ωy,0) = (ωy,0,0) with ωy<0 → rx<0 ✓ (then ty=−fy·rx>0 ✓).
 * - tilt down (device ωx<0, content shifts display-up = stored −x):
 *   needs tx<0, i.e. ry<0. M·(ωx,0,0) = (0,ωx,0) → ry=ωx<0 ✓.
 * - clockwise roll as seen by the user (device ωz<0): desktop +rz is
 *   screen-clockwise. M·(0,0,ωz) = (0,0,−ωz) → rz>0 ✓.
 *
 * Generalization to other mounts: R_mount(θ) = Rz(90°−θ) · R_mount(90°),
 * verified cyclically (θ=0 reproduces portrait-native axes; det stays +1;
 * M(90°) is exactly the anchored matrix). Front camera mirrors the image
 * horizontally, so its x-rate is negated — PROVISIONAL (no hardware to
 * verify against; flagged for the controlled-pan validation below).
 *
 * HARDWARE VALIDATION GATE (required before trusting new-device data):
 * record a burst with one slow deliberate pan, run desktop `inspect` +
 * global-align, and confirm the gyro-seeded displacement sign matches the
 * image-estimated direction. The desktop gates on coverage and refines
 * image-only afterwards, so a sign error degrades to a wasted seed rather
 * than corruption — but validate anyway. burst.json records
 * [sensorOrientationDeg], [frontFacing] and [MAPPER_VERSION] so any capture
 * can be re-audited.
 */
object GyroCameraFrameMapper {
    /** Mapping revision, recorded in burst.json provenance. */
    const val MAPPER_VERSION = 1

    /**
     * Maps one device-frame rate sample to desktop camera-frame rates.
     *
     * @param sensorOrientationDeg CameraCharacteristics.SENSOR_ORIENTATION
     *   (0/90/180/270); anything else throws (fail fast, never silently
     *   misalign axes).
     * @param frontFacing true for LENS_FACING_FRONT (mirrored mount,
     *   provisional — see class docs).
     * @return Triple(x, y, z) rad/s in stored-frame camera coordinates.
     */
    fun map(
        wx: Float,
        wy: Float,
        wz: Float,
        sensorOrientationDeg: Int,
        frontFacing: Boolean
    ): Triple<Float, Float, Float> {
        val m = matrix(sensorOrientationDeg, frontFacing)
        return Triple(
            (m[0] * wx + m[1] * wy + m[2] * wz).toFloat(),
            (m[3] * wx + m[4] * wy + m[5] * wz).toFloat(),
            (m[6] * wx + m[7] * wy + m[8] * wz).toFloat()
        )
    }

    /**
     * Row-major 3x3 mapping matrix (Double precision build, Float use).
     * Pure rotation for the back camera (det +1, orthogonal); front camera
     * additionally mirrors x (provisional, documented above).
     */
    fun matrix(sensorOrientationDeg: Int, frontFacing: Boolean): DoubleArray {
        val theta = ((sensorOrientationDeg % 360) + 360) % 360
        require(theta % 90 == 0) { "sensor orientation must be a quarter turn, got $sensorOrientationDeg" }
        // Anchor: back-camera landscape mount at θ=90° (verified probes).
        // Stored +x = device −Y, stored +y = device −X, normal −Z, then the
        // pinned 180° about the normal: M = Rz(180°) · R_mount(90°).
        val anchor = doubleArrayOf(
            0.0, 1.0, 0.0,
            1.0, 0.0, 0.0,
            0.0, 0.0, -1.0
        )
        // Other mounts rotate rigidly with the sensor: relative mount angle
        // (90°−θ) about the device Z axis, composed on the LEFT (rotating
        // the assembly rotates its basis vectors: new rows = Q·old rows).
        // Verified: θ=0 yields rows X_s=(−1,0,0) Y_s=(0,+1,0) N=(0,0,−1),
        // i.e. portrait-native dump (columns right, rows down), and all
        // three motion probes pass there too.
        val rel = Math.toRadians((90 - theta).toDouble())
        // Quarter-turn trig snaps to exact integers (cos 90° is 6.1e-17 in
        // floating point); without this every non-anchor mount carries dust
        // that breaks exact-equality audits of the matrix.
        fun snap(v: Double): Double = when {
            kotlin.math.abs(v) < 1e-12 -> 0.0
            kotlin.math.abs(v - 1.0) < 1e-12 -> 1.0
            kotlin.math.abs(v + 1.0) < 1e-12 -> -1.0
            else -> v
        }
        val c = snap(cos(rel))
        val s = snap(sin(rel))
        // Rz(rel) in device coords (x right, y up: standard math sense).
        val r = doubleArrayOf(
            c, -s, 0.0,
            s, c, 0.0,
            0.0, 0.0, 1.0
        )
        var m = mul(r, anchor)
        if (frontFacing) {
            // Provisional selfie mirror: negate the stored-x rate. Front
            // captures must pass the controlled-pan validation before use.
            m = mul(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), m)
        }
        return m
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (cc in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[r * 3 + k] * b[k * 3 + cc]
            out[r * 3 + cc] = s
        }
        return out
    }
}
