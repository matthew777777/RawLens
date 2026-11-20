// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.util.Locale

/**
 * Gyroflow IMU log (`<take>.gcsv`, Gyroflow 1.7+) written by the app next to
 * a RAW Video `.craw`, so Gyroflow can stabilise without first running
 * craw2dng. Byte-for-byte the same layout as the decoder's
 * `Clip::gcsv` (CinematicRAWStudio-Decoder tools/common/Clip.cpp): raw
 * Android sensor axes for gyro (rad/s) and accel (g), t = 0 at the first
 * frame, accel linearly resampled onto gyro timestamps.
 *
 * Samples arrive already rebased to the recording origin (first frame = 0
 * ns), in ascending order per sensor, from the committer thread. Pure JVM,
 * host-testable; not thread-safe (one writer thread, read after stop).
 */
internal class GcsvLog {
    private var gT = LongArray(4096); private var gV = FloatArray(4096 * 3); private var gN = 0
    private var aT = LongArray(1024); private var aV = FloatArray(1024 * 3); private var aN = 0

    /** Rolling-shutter skew of the first frame (ns), 0 = unknown. */
    var rollingShutterSkewNs: Long = 0L

    val gyroCount: Int get() = gN

    fun addGyro(ts: LongArray, axes: FloatArray, count: Int) {
        if (gN + count > gT.size) {
            val cap = maxOf(gT.size * 2, gN + count)
            gT = gT.copyOf(cap); gV = gV.copyOf(cap * 3)
        }
        System.arraycopy(ts, 0, gT, gN, count)
        System.arraycopy(axes, 0, gV, gN * 3, count * 3)
        gN += count
    }

    fun addAccel(ts: LongArray, axes: FloatArray, count: Int) {
        if (aN + count > aT.size) {
            val cap = maxOf(aT.size * 2, aN + count)
            aT = aT.copyOf(cap); aV = aV.copyOf(cap * 3)
        }
        System.arraycopy(ts, 0, aT, aN, count)
        System.arraycopy(axes, 0, aV, aN * 3, count * 3)
        aN += count
    }

    /** The full log, or null when no gyro samples were recorded. */
    fun render(
        model: String,
        vendor: String,
        orientation: String = DEFAULT_ORIENTATION,
    ): String? {
        if (gN == 0) return null
        val sb = StringBuilder(64 + gN * 64)
        sb.append("GYROFLOW IMU LOG\nversion,1.3\n")
        sb.append("id,craw_").append(model).append('\n')
        sb.append("orientation,").append(orientation).append('\n')
        sb.append("note,Cinematic RAW Studio: raw Android sensor axes; t = 0 at the first frame\n")
        sb.append("vendor,").append(vendor).append('\n')
        if (rollingShutterSkewNs > 0) {
            sb.append(String.format(Locale.ROOT, "frame_readout_time,%.3f\n", rollingShutterSkewNs / 1e6))
            sb.append("frame_readout_direction,0\n")
        }
        sb.append("tscale,0.000001\ngscale,1\nascale,1\n")
        sb.append(if (aN == 0) "t,gx,gy,gz\n" else "t,gx,gy,gz,ax,ay,az\n")
        var a = 0
        for (i in 0 until gN) {
            val t = gT[i]
            if (t < 0) continue
            val us = t / 1000
            val gx = gV[i * 3]; val gy = gV[i * 3 + 1]; val gz = gV[i * 3 + 2]
            if (aN == 0) {
                sb.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f\n", us, gx, gy, gz))
            } else {
                while (a + 1 < aN && aT[a + 1] <= t) a++
                val b = if (a + 1 < aN) a + 1 else a
                val t0 = aT[a]; val t1 = aT[b]
                val w = if (t1 > t0 && t > t0) minOf(1.0, (t - t0).toDouble() / (t1 - t0)) else 0.0
                fun acc(k: Int) = (aV[a * 3 + k] + (aV[b * 3 + k] - aV[a * 3 + k]) * w) / G
                sb.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f\n",
                    us, gx, gy, gz, acc(0), acc(1), acc(2)))
            }
        }
        return sb.toString()
    }

    companion object {
        /** Same default as the decoder's Clip::kDefaultGcsvOrientation. */
        const val DEFAULT_ORIENTATION = "XYZ"
        private const val G = 9.80665
    }
}
