// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Minimal viewfinder guides which stay independent from camera metering gestures. */
class CameraGuideOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var gridEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var contentTop = 0f
    private var contentEnd = 0f
    private var contentBottom = 0f
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        strokeWidth = density
    }
    private val letterboxPaint = Paint().apply {
        color = Color.argb(204, 0, 0, 0)
    }
    private val letterboxEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = density
    }

    /**
     * RAW Video framing guides. When [videoActive] with a non-open [videoCrop],
     * centered letterbox bars are drawn over the full-frame preview (the
     * recorder crops in-encoder, so the session never reconfigures).
     */
    var videoCrop: VideoCrop? = null
        set(value) {
            field = value
            invalidate()
        }
    var videoActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun setContentInsets(top: Int, end: Int, bottom: Int) {
        contentTop = top.toFloat()
        contentEnd = end.toFloat()
        contentBottom = bottom.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridEnabled) drawThirds(canvas)
        if (videoActive) drawVideoLetterbox(canvas)
    }

    private fun drawThirds(canvas: Canvas) {
        val right = (width - contentEnd).coerceAtLeast(0f)
        val bottom = (height - contentBottom).coerceAtLeast(contentTop)
        val thirdX = right / 3f
        val thirdY = (bottom - contentTop) / 3f
        canvas.drawLine(thirdX, contentTop, thirdX, bottom, gridPaint)
        canvas.drawLine(thirdX * 2f, contentTop, thirdX * 2f, bottom, gridPaint)
        canvas.drawLine(0f, contentTop + thirdY, right, contentTop + thirdY, gridPaint)
        canvas.drawLine(0f, contentTop + thirdY * 2f, right, contentTop + thirdY * 2f, gridPaint)
    }

    /**
     * Centered cinematic letterbox for the active [videoCrop]. The preview
     * shows the full sensor frame, so the visible height fraction is
     * `sensorW / ratio / sensorH` approximated from the view aspect: bars =
     * `(H - W / ratio) / 2`. Open Gate (ratio null) draws nothing.
     */
    private fun drawVideoLetterbox(canvas: Canvas) {
        val ratio = (videoCrop?.ratio ?: return).toFloat()
        val right = (width - contentEnd).coerceAtLeast(0f)
        val bottom = (height - contentBottom).coerceAtLeast(contentTop)
        val viewH = bottom - contentTop
        if (right <= 0f || viewH <= 0f) return
        val visibleH = right / ratio
        val bar = (viewH - visibleH) / 2f
        if (bar <= 0f) return
        canvas.drawRect(0f, contentTop, right, contentTop + bar, letterboxPaint)
        canvas.drawRect(0f, bottom - bar, right, bottom, letterboxPaint)
        canvas.drawLine(0f, contentTop + bar, right, contentTop + bar, letterboxEdgePaint)
        canvas.drawLine(0f, bottom - bar, right, bottom - bar, letterboxEdgePaint)
    }
}
