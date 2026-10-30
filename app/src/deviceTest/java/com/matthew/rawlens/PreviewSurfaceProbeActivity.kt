// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Test-build-only activity: no camera, storage or recorder initialization. */
class PreviewSurfaceProbeActivity : Activity() {
    lateinit var preview: RawViewfinder
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = RawViewfinder(this)
        setContentView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
        })
    }
    override fun onDestroy() {
        preview.dispose()
        super.onDestroy()
    }
}
