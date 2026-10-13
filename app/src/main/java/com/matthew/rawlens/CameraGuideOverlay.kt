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
    /**
     * Device orientation in degrees (0/90/180/270 from the orientation
     * listener). The letterbox stays world-horizontal like the level line:
     * landscape draws side bars on the portrait-locked canvas, which the
     * user holding the rotated phone sees as top/bottom. Portrait rendering
     * is unchanged.
     */
    var videoOrientationDegrees: Int = 0
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
     * Centered cinematic letterbox for the active [videoCrop], kept
     * world-horizontal like the level line: the canvas is portrait-locked,
     * so in landscape the bars move to the view's left/right edges. The
     * preview shows the full sensor frame, so the visible fraction is
     * `sensorW / ratio / sensorH` approximated from the view aspect:
     * portrait bars = `(H - W / ratio) / 2`, landscape bars =
     * `(W - H / ratio) / 2`. Open Gate (ratio null) draws nothing.
     */
    private fun drawVideoLetterbox(canvas: Canvas) {
        val ratio = (videoCrop?.ratio ?: return).toFloat()
        val right = (width - contentEnd).coerceAtLeast(0f)
        val bottom = (height - contentBottom).coerceAtLeast(contentTop)
        val bars = VideoLetterbox.compute(
            viewW = right,
            viewH = bottom - contentTop,
            ratio = ratio,
            orientationDegrees = videoOrientationDegrees
        ) ?: return
        if (bars.topBottom) {
            canvas.drawRect(0f, contentTop, right, contentTop + bars.bar, letterboxPaint)
            canvas.drawRect(0f, bottom - bars.bar, right, bottom, letterboxPaint)
            canvas.drawLine(0f, contentTop + bars.bar, right, contentTop + bars.bar, letterboxEdgePaint)
            canvas.drawLine(0f, bottom - bars.bar, right, bottom - bars.bar, letterboxEdgePaint)
        } else {
            canvas.drawRect(0f, contentTop, bars.bar, bottom, letterboxPaint)
            canvas.drawRect(right - bars.bar, contentTop, right, bottom, letterboxPaint)
            canvas.drawLine(bars.bar, contentTop, bars.bar, bottom, letterboxEdgePaint)
            canvas.drawLine(right - bars.bar, contentTop, right - bars.bar, bottom, letterboxEdgePaint)
        }
    }
}

/**
 * Orientation-aware RAW Video letterbox geometry (pure math; the overlay
 * only draws it). The activity is portrait-locked while the phone rotates
 * freely, so fixed top/bottom bars would appear as side bars in landscape.
 * Like the level line, the letterbox stays world-horizontal: a portrait
 * device (0/180 deg) draws top/bottom bars, a landscape device (90/270 deg)
 * draws left/right bars.
 */
object VideoLetterbox {
    /**
     * @param topBottom true for top/bottom bars (portrait device), false for
     * left/right bars (landscape device).
     * @param bar bar thickness in px.
     */
    data class Bars(val topBottom: Boolean, val bar: Float)

    /**
     * Centered letterbox over a [viewW]x[viewH] full-frame preview for a crop
     * with width/height [ratio] at [orientationDegrees] device orientation
     * (any integer; normalized to quadrants). Null when the view is empty or
     * the crop covers the frame (no bars).
     */
    fun compute(viewW: Float, viewH: Float, ratio: Float, orientationDegrees: Int): Bars? {
        if (viewW <= 0f || viewH <= 0f || ratio <= 0f) return null
        val landscape = when ((orientationDegrees % 360 + 360) % 360) {
            90, 270 -> true
            else -> false
        }
        return if (!landscape) {
            val bar = (viewH - viewW / ratio) / 2f
            if (bar <= 0f) null else Bars(topBottom = true, bar = bar)
        } else {
            val bar = (viewW - viewH / ratio) / 2f
            if (bar <= 0f) null else Bars(topBottom = false, bar = bar)
        }
    }
}
