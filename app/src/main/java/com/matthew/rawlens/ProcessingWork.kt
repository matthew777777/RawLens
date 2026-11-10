// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context

/** Process-wide ownership: overlapping camera saves and offline merges share one service. */
internal object ProcessingWork {
    private var jobs = 0

    @Synchronized fun begin(context: Context) {
        if (jobs++ == 0) {
            try {
                JpegProcessingService.start(context.applicationContext)
            } catch (failure: RuntimeException) {
                Log.e("ProcessingWork", "Could not start foreground processing", failure)
            }
        }
    }

    @Synchronized fun end(context: Context) {
        check(jobs > 0) { "Unbalanced processing work" }
        if (--jobs == 0) JpegProcessingService.stop(context.applicationContext)
    }
}
