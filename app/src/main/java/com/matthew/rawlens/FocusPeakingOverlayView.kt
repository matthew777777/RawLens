// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Focus-peaking dots over the RAW viewfinder: one small dot per sharp sensor
 * cell. The view is sized to the viewfinder rect like the guide overlay;
 * cells map through [FocusPeakingGeometry] so rotation/mirror match the
 * displayed frame. Never clickable: touches fall through to the metering
 * overlay above. Visibility is owned by the tap/manual-focus auto-show policy;
 * [update] only refreshes the mask.
 */
class FocusPeakingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var peakingColor: FocusPeakingColor = FocusPeakingColor.GREEN
        set(value) {
            field = value
            paint.color = value.argb
            contentDescription = "Focus peaking ${value.label.lowercase()}"
            invalidate()
        }

    private var frame: FocusPeakingFrame? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = peakingColor.argb
        alpha = PEAKING_ALPHA
    }

    init {
        contentDescription = "Focus peaking ${peakingColor.label.lowercase()}"
    }

    fun update(frame: FocusPeakingFrame) {
        this.frame = frame
        invalidate()
    }

    fun clear() {
        frame = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        if (width <= 0 || height <= 0) return
        if (current.cols <= 0 || current.rows <= 0) return
        if (current.mask.size != current.cols * current.rows) return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        for (row in 0 until current.rows) {
            for (col in 0 until current.cols) {
                if (!current.mask[row * current.cols + col]) continue
                val cell = FocusPeakingGeometry.viewCell(
                    col, row, current.cols, current.rows, current.rotation, current.mirrored
                )
                // Small centered dot, never a filled block: peaking must read as
                // points of sharpness over the image, not sand.
                val cx = (cell[0] + cell[2]) / 2f * viewWidth
                val cy = (cell[1] + cell[3]) / 2f * viewHeight
                val radius = minOf(cell[2] - cell[0], cell[3] - cell[1]) *
                    minOf(viewWidth, viewHeight) * DOT_RADIUS_FRACTION
                if (radius > 0f) canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }

    private companion object {
        const val PEAKING_ALPHA = 220

        /** Dot radius as a fraction of the smaller cell dimension. */
        const val DOT_RADIUS_FRACTION = 0.22f
    }
}
