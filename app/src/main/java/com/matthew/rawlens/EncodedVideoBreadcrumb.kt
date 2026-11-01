// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Last-stage marker for Encoded Video recording, persisted outside logcat.
 *
 * Why: a native crash while recording kills the process and its logcat
 * stream with it — the 2026-10-14 Motorola session file ends mid-startup
 * with no record attempt and no stack, so the failure stage is unknowable
 * from the export. Java crashes already land in LogcatFileWriter's crash
 * handler; this covers the native hole: every record stage overwrites a
 * tiny file in filesDir, a clean stop deletes it, and the next launch
 * logs whatever stage the previous session died at.
 */
internal object EncodedVideoBreadcrumb {
    private const val TAG = "EncodedVideoRecorder"
    private const val FILE_NAME = "encoded_video_breadcrumb.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Overwrite the stage marker. Best-effort; never fails the recording. */
    fun mark(context: Context, stage: String) {
        runCatching {
            file(context).writeText("${SystemClock.elapsedRealtime()} $stage")
        }
        Log.i(TAG, "stage: $stage")
    }

    /** Clean stop: no marker survives, so the next launch stays silent. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * Called once per launch. A surviving marker means the previous
     * process died (or was killed) mid-recording; log its last stage so
     * the next exported session names where the crash happened.
     */
    fun reportPrevious(context: Context) {
        runCatching {
            val f = file(context)
            if (f.exists()) {
                val text = f.readText().trim()
                Log.w(TAG, "previous EncodedVideo session ended unclean; last stage: $text")
                f.delete()
            }
        }
    }
}
