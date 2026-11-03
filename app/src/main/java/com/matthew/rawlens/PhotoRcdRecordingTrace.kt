// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in evidence for real sensor cadence. Never used to generate or adjust PTS. */
internal class PhotoRcdRecordingTrace {
    private val events = ArrayList<LongArray>()
    @Synchronized fun record(kind: Int, sensorNs: Long = 0, identity: Long = -1, value: Long = 0) {
        check(events.size < 300_000) { "RCD evidence capacity exceeded" }
        events.add(longArrayOf(kind.toLong(), SystemClock.elapsedRealtimeNanos(), sensorNs, identity, value))
    }
    @Synchronized fun json(): JSONObject = JSONObject()
        .put("columns", JSONArray(listOf("kind", "wall_ns", "sensor_ns", "identity_or_pts", "value")))
        .put("kinds", JSONObject().put("1", "capture-result").put("2", "acquired-image")
            .put("3", "submitted-RCD").put("4", "queued-codec").put("5", "muxed-video")
            .put("6", "RCD-render-wall-ns").put("10","RAW-input-validation-or-normalize-ns").put("11","RCD-engine-ns")
            .put("12","product-corrections-ns").put("13","half-output-upload-ns")
            .put("14","GPU-guides-ns").put("15","GPU-green-ns").put("16","GPU-opposite-ns")
            .put("19","GPU-response-cache-ns").put("17","GPU-final-ns").put("18","GPU-border-ns").put("20","GPU-product-ns").put("21","GPU-responses-or-RAW-pack-ns"))
        .put("events", JSONArray(events.map { JSONArray(it.toList()) }))
}
