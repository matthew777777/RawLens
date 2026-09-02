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
import kotlin.math.hypot

/** Independent draggable autofocus and auto-exposure targets. */
class FocusMeteringOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onAfPointChanged: ((Float, Float) -> Unit)? = null
    var onAePointChanged: ((Float, Float) -> Unit)? = null
    var onOverlayTouched: (() -> Unit)? = null

    private val afPoint = PointF()
    private val aePoint = PointF()
    private var targetsVisible = false
    private var dragging = Target.NONE
    private val radius = 34f * resources.displayMetrics.density
    private val hitRadius = 48f * resources.displayMetrics.density
    private val afPaint = targetPaint(Color.rgb(76, 220, 120))
    private val aePaint = targetPaint(Color.rgb(255, 177, 66))

    fun clearTargets() {
        targetsVisible = false
        dragging = Target.NONE
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
                dragging = when {
                    targetsVisible && distance(event.x, event.y, afPoint) <= hitRadius -> Target.AF
                    targetsVisible && distance(event.x, event.y, aePoint) <= hitRadius -> Target.AE
                    else -> {
                        afPoint.set(event.x - radius * 0.55f, event.y)
                        aePoint.set(event.x + radius * 0.55f, event.y)
                        clamp(afPoint); clamp(aePoint)
                        targetsVisible = true
                        onAePointChanged?.invoke(aePoint.x, aePoint.y)
                        // Install AE first so the following AF callback can start both triggers
                        // with their final regions in the same touch-focus sequence.
                        onAfPointChanged?.invoke(afPoint.x, afPoint.y)
                        invalidate()
                        Target.NONE
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val point = when (dragging) {
                    Target.AF -> afPoint
                    Target.AE -> aePoint
                    Target.NONE -> return false
                }
                point.set(event.x, event.y)
                clamp(point)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging == Target.AF) onAfPointChanged?.invoke(afPoint.x, afPoint.y)
                else if (dragging == Target.AE) onAePointChanged?.invoke(aePoint.x, aePoint.y)
                dragging = Target.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = Target.NONE
                return true
            }
        }
        return false
    }

    private fun clamp(point: PointF) {
        point.x = point.x.coerceIn(radius, (width - radius).coerceAtLeast(radius))
        point.y = point.y.coerceIn(radius, (height - radius).coerceAtLeast(radius))
    }

    private fun distance(x: Float, y: Float, point: PointF): Float = hypot(x - point.x, y - point.y)

    private fun targetPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private enum class Target { NONE, AF, AE }
}
