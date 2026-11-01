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
    private val gridPaint = OverlayStyle.stroke(OverlayStyle.GRID, OverlayStyle.HAIRLINE_DP, density, shadow = false)
    private val letterboxPaint = Paint().apply { color = OverlayStyle.MASK }
    private val letterboxEdgePaint =
        OverlayStyle.stroke(OverlayStyle.STROKE_SECONDARY, OverlayStyle.HAIRLINE_DP, density, shadow = false)

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
    private var previewFrameWidth = 0
    private var previewFrameHeight = 0
    private var previewRotationDegrees = 90

    /** Recorder crops sensor rows; their displayed axis follows the sensor mount, not the phone. */
    fun setPreviewFrame(frameWidth: Int, frameHeight: Int, rotationDegrees: Int) {
        if (previewFrameWidth == frameWidth && previewFrameHeight == frameHeight &&
            previewRotationDegrees == rotationDegrees) return
        previewFrameWidth = frameWidth
        previewFrameHeight = frameHeight
        previewRotationDegrees = rotationDegrees
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

    /** Map the recorder's crop into the same aspect-fill frame used by the preview. */
    private fun drawVideoLetterbox(canvas: Canvas) {
        val ratio = (videoCrop?.ratio ?: return).toFloat()
        val right = (width - contentEnd).coerceAtLeast(0f)
        val bottom = (height - contentBottom).coerceAtLeast(contentTop)
        if (right <= 0f || bottom <= contentTop) return
        val frame = if (previewFrameWidth > 0 && previewFrameHeight > 0) {
            VfAspectFit.fill(right.toInt().coerceAtLeast(1), (bottom - contentTop).toInt().coerceAtLeast(1),
                previewFrameWidth, previewFrameHeight, previewRotationDegrees)
        } else intArrayOf(0, 0, right.toInt(), (bottom - contentTop).toInt())
        val bars = VideoLetterbox.compute(frame[2].toFloat(), frame[3].toFloat(), ratio, previewRotationDegrees) ?: return
        if (bars.topBottom) {
            val first = contentTop + frame[1] + bars.bar
            val second = contentTop + frame[1] + frame[3] - bars.bar
            canvas.drawRect(0f, contentTop, right, first.coerceIn(contentTop, bottom), letterboxPaint)
            canvas.drawRect(0f, second.coerceIn(contentTop, bottom), right, bottom, letterboxPaint)
            if (first > contentTop && first < bottom) canvas.drawLine(0f, first, right, first, letterboxEdgePaint)
            if (second > contentTop && second < bottom) canvas.drawLine(0f, second, right, second, letterboxEdgePaint)
        } else {
            val first = frame[0] + bars.bar
            val second = frame[0] + frame[2] - bars.bar
            canvas.drawRect(0f, contentTop, first.coerceIn(0f, right), bottom, letterboxPaint)
            canvas.drawRect(second.coerceIn(0f, right), contentTop, right, bottom, letterboxPaint)
            if (first > 0f && first < right) canvas.drawLine(first, contentTop, first, bottom, letterboxEdgePaint)
            if (second > 0f && second < right) canvas.drawLine(second, contentTop, second, bottom, letterboxEdgePaint)
        }
    }
}

/** Sensor-to-preview crop geometry shared by RAW video and Encoded Video framing guides. */
object VideoLetterbox {
    /**
     * @param topBottom true for sensor mounts of 0/180 degrees; false for
     * 90/270 mounts, which map the sensor's cropped rows to the preview's sides.
     * @param bar bar thickness in px.
     */
    data class Bars(val topBottom: Boolean, val bar: Float)

    /**
     * Centered letterbox over a [viewW]x[viewH] full-frame preview for a crop
     * with sensor width/height [ratio] at [rotationDegrees] preview rotation.
     * This is sensor orientation, independent of how the phone is held. Null when the view is empty or
     * the crop covers the frame (no bars).
     */
    fun compute(viewW: Float, viewH: Float, ratio: Float, rotationDegrees: Int): Bars? {
        if (viewW <= 0f || viewH <= 0f || ratio <= 0f) return null
        val swappedAxes = when ((rotationDegrees % 360 + 360) % 360) {
            90, 270 -> true
            else -> false
        }
        return if (!swappedAxes) {
            val bar = (viewH - viewW / ratio) / 2f
            if (bar <= 0f) null else Bars(topBottom = true, bar = bar)
        } else {
            val bar = (viewW - viewH / ratio) / 2f
            if (bar <= 0f) null else Bars(topBottom = false, bar = bar)
        }
    }
}
