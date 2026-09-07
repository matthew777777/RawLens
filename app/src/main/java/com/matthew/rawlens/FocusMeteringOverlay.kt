// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Open Camera-style touch focus overlay with separately reported AF and AE areas. */
class FocusMeteringOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onAfPointChanged: ((Float, Float) -> Unit)? = null
    var onAePointChanged: ((Float, Float) -> Unit)? = null
    var onTargetsCleared: (() -> Unit)? = null
    var onOverlayTouched: (() -> Unit)? = null

    private val afPoint = PointF()
    private val aePoint = PointF()
    private var targetsVisible = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var focusAreaTime = -1L
    private val radius = 34f * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val afPaint = targetPaint(Color.rgb(76, 220, 120))
    private val aePaint = targetPaint(Color.rgb(255, 177, 66))

    fun clearTargets() {
        targetsVisible = false
        focusAreaTime = -1L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!targetsVisible) return
        canvas.drawCircle(afPoint.x, afPoint.y, radius, afPaint)
        canvas.drawLine(afPoint.x - radius * 0.45f, afPoint.y, afPoint.x + radius * 0.45f, afPoint.y, afPaint)
        canvas.drawLine(afPoint.x, afPoint.y - radius * 0.45f, afPoint.x, afPoint.y + radius * 0.45f, afPaint)

        canvas.drawCircle(aePoint.x, aePoint.y, radius * 0.82f, aePaint)
        canvas.drawCircle(aePoint.x, aePoint.y, radius * 0.18f, aePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onOverlayTouched?.invoke()
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                if (hypot(event.x - touchDownX, event.y - touchDownY) > touchSlop) return true
                val now = System.currentTimeMillis()
                val clearFocusAreas = targetsVisible && focusAreaTime != -1L &&
                    now - focusAreaTime < ViewConfiguration.getDoubleTapTimeout()
                if (clearFocusAreas) {
                    clearTargets()
                    onTargetsCleared?.invoke()
                } else {
                    // Open Camera installs one touch rectangle for both subsystems. Keep separate
                    // coordinates/callbacks so AF-only and AE-only camera capabilities remain valid.
                    afPoint.set(event.x, event.y)
                    aePoint.set(event.x, event.y)
                    clamp(afPoint)
                    clamp(aePoint)
                    targetsVisible = true
                    focusAreaTime = now
                    onAePointChanged?.invoke(aePoint.x, aePoint.y)
                    onAfPointChanged?.invoke(afPoint.x, afPoint.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun clamp(point: PointF) {
        point.x = point.x.coerceIn(radius, (width - radius).coerceAtLeast(radius))
        point.y = point.y.coerceIn(radius, (height - radius).coerceAtLeast(radius))
    }
    private fun targetPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
}
