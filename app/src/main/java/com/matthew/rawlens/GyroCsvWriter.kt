// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Serializes per-frame gyro windows to the exact sidecar format consumed by
 * `tools/burst-reconstruction-desktop` (`GyroCsv.HEADER`, parsed tolerantly
 * but written canonically here):
 *
 * ```text
 * timestamp_ns,x_rad_s,y_rad_s,z_rad_s
 * 123456789,0.0101,-0.0020,0.0003
 * ```
 *
 * Timestamps are boot-time nanos (Camera2 SENSOR_TIMESTAMP domain); rates
 * must already be in the desktop camera frame (see [GyroCameraFrameMapper]).
 * Pure JVM, no Android dependencies — unit-testable on the host.
 */
internal object GyroCsvWriter {
    const val HEADER = "timestamp_ns,x_rad_s,y_rad_s,z_rad_s"

    /** Renders [samples] (ascending timestamps) to CSV text with header. */
    fun format(samples: List<GyroSample>): String {
        val out = StringBuilder(HEADER.length + 1 + samples.size * 32)
        out.append(HEADER).append('\n')
        for (s in samples) {
            out.append(s.timestampNanos).append(',')
                .append(s.xRadiansPerSecond).append(',')
                .append(s.yRadiansPerSecond).append(',')
                .append(s.zRadiansPerSecond).append('\n')
        }
        return out.toString()
    }
}
