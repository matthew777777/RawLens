// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Application

/**
 * Process entry point. Installs the global crash handler before any
 * activity, service, or background executor runs, so fatal traces from
 * lens discovery, the viewfinder, or the launcher itself always land in
 * the session log. Continuous logcat streaming stays opt-out via the
 * existing `logcat_file_enabled` preference and is started by MainActivity.
 */
class RawLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            LogcatFileWriter.installCrashHandler(this)
        } catch (_: Throwable) {
            // Never crash startup because crash logging is unavailable.
        }
    }
}
