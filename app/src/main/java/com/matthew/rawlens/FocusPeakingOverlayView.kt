// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Focus-peaking sparkle over the RAW viewfinder: one inset block per sharp
 * sensor cell. The view is sized to the viewfinder rect like the guide overlay;
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
    private val rect = RectF()

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
                // Inset so peaking reads as sparkle over the image, not solid blocks.
                val insetX = (cell[2] - cell[0]) * CELL_INSET_FRACTION
                val insetY = (cell[3] - cell[1]) * CELL_INSET_FRACTION
                rect.set(
                    (cell[0] + insetX) * viewWidth,
                    (cell[1] + insetY) * viewHeight,
                    (cell[2] - insetX) * viewWidth,
                    (cell[3] - insetY) * viewHeight
                )
                if (rect.right > rect.left && rect.bottom > rect.top) canvas.drawRect(rect, paint)
            }
        }
    }

    private companion object {
        const val PEAKING_ALPHA = 220
        const val CELL_INSET_FRACTION = 0.18f
    }
}
