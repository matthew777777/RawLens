// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CaptureResult

/**
 * Transition-only AF trace for repeating capture results. The video paths
 * have no [TouchFocus] trace, so without this a "focus never moves" report
 * from a take is undecidable: the log must show which AF mode the HAL ran,
 * whether it ever scanned, and where the lens sat. One instance per
 * session; called from that session's capture-callback thread only.
 */
internal class AfTrace(private val tag: String, private val label: String) {
    private var logged = false
    private var lastMode: Int? = null
    private var lastState: Int? = null

    fun onResult(result: CaptureResult) {
        val mode = result.get(CaptureResult.CONTROL_AF_MODE)
        val state = result.get(CaptureResult.CONTROL_AF_STATE)
        if (logged && mode == lastMode && state == lastState) return
        logged = true
        lastMode = mode
        lastState = state
        Log.i(tag, line(label, mode, state, result.get(CaptureResult.LENS_FOCUS_DISTANCE)))
    }

    /** Next result logs unconditionally (e.g. idle resuming after a tap scan). */
    fun reset() {
        logged = false
    }

    companion object {
        fun line(label: String, mode: Int?, state: Int?, focusDiopters: Float?): String =
            "$label AF ${Camera2Names.afStateName(state)} mode=${Camera2Names.afModeName(mode)}" +
                (focusDiopters?.let { " fd=${String.format(java.util.Locale.US, "%.2f", it)}D" } ?: "")
    }
}
