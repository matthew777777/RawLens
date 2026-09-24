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

    fun setContentInsets(top: Int, end: Int, bottom: Int) {
        contentTop = top.toFloat()
        contentEnd = end.toFloat()
        contentBottom = bottom.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridEnabled) drawThirds(canvas)
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
}
