// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Test-build-only activity: no camera, storage or recorder initialization. */
class PreviewSurfaceProbeActivity : Activity() {
    lateinit var preview: RawViewfinder
    var encodedPreview: EncodedVideoPreviewView? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = RawViewfinder(this)
        setContentView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            if (intent.getBooleanExtra("encodedPreview", false)) {
                // A default-layer ISP surface, created after the RAW VF,
                // reproduces the stack that used to cover the take monitor.
                addView(android.view.SurfaceView(this@PreviewSurfaceProbeActivity).apply {
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) = paint(holder)
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) = paint(holder)
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                        private fun paint(holder: android.view.SurfaceHolder) {
                            val canvas = holder.lockCanvas() ?: return
                            try { canvas.drawColor(android.graphics.Color.BLUE) }
                            finally { holder.unlockCanvasAndPost(canvas) }
                        }
                    })
                }, FrameLayout.LayoutParams(-1, -1))
                encodedPreview = EncodedVideoPreviewView(this@PreviewSurfaceProbeActivity).also {
                    addView(it, FrameLayout.LayoutParams(-1, -1))
                }
            }
        })
    }
    override fun onDestroy() {
        preview.dispose()
        encodedPreview?.dispose()
        super.onDestroy()
    }
}
