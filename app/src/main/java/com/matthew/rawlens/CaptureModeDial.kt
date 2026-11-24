// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CaptureModeDial(context: Context, attrs: AttributeSet) : TextView(context, attrs), RotatingContent {
    private val density = resources.displayMetrics.density
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val face = context.getColor(R.color.surface_container)
    private val rim = context.getColor(R.color.outline_strong)
    private val ivory = context.getColor(R.color.text_primary)
    private val muted = context.getColor(R.color.text_secondary)
    private val gold = context.getColor(R.color.accent)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var lastAngle = 0f
    private var dragAngle = 0f
    private var wheelAngle = 0f
    private var dragOrigin = 0f
    private var dragging = false
    private var rimGesture = false
    private var animation: ValueAnimator? = null
    private var videoMode = false
    var onStep: ((Int) -> Unit)? = null

    override var contentRotation = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var mode = CaptureExposureMode.AUTO

    fun showMode(value: CaptureExposureMode, video: Boolean) {
        val changed = mode != value
        mode = value
        videoMode = video
        if (changed && !dragging) settleWheel()
        val name = value.name
        text = "${MARKS[value.ordinal]}\n$name"
        contentDescription = if (video) "Return to photo mode" else "Exposure mode: $name. Tap to cycle modes."
        invalidate()
    }

    private fun settleWheel() {
        animation?.cancel()
        val target = wheelAngle + shortestTurn(-mode.ordinal * 90f - wheelAngle)
        if (!isShown || abs(target - wheelAngle) < 0.1f) {
            wheelAngle = target
            invalidate()
            return
        }
        animation = ValueAnimator.ofFloat(wheelAngle, target).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                wheelAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun shortestTurn(degrees: Float): Float = ((degrees % 360f + 540f) % 360f) - 180f

    private fun touchAngle(event: MotionEvent): Float =
        Math.toDegrees(atan2((event.y - height / 2f).toDouble(), (event.x - width / 2f).toDouble())).toFloat()

    override fun onDetachedFromWindow() {
        animation?.cancel()
        wheelAngle = -mode.ordinal * 90f
        dragging = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 6f * density
        if (radius <= 0f) return
        val checkpoint = canvas.save()
        canvas.rotate(contentRotation, cx, cy)

        dialPaint.style = Paint.Style.FILL
        dialPaint.color = face
        canvas.drawCircle(cx, cy, radius, dialPaint)
        dialPaint.style = Paint.Style.STROKE
        dialPaint.strokeWidth = density
        dialPaint.color = rim
        canvas.drawCircle(cx, cy, radius, dialPaint)
        val rimCheckpoint = canvas.save()
        canvas.rotate(wheelAngle, cx, cy)
        repeat(36) {
            canvas.drawLine(cx, cy - radius, cx, cy - radius + 2f * density, dialPaint)
            canvas.rotate(360f / 36, cx, cy)
        }

        canvas.restoreToCount(rimCheckpoint)

        dialPaint.color = gold
        dialPaint.strokeWidth = 2f * density
        canvas.drawLine(cx - 3f * density, cy - radius - 3f * density,
            cx + 3f * density, cy - radius - 3f * density, dialPaint)

        dialPaint.style = Paint.Style.FILL
        dialPaint.textSize = 10f * density
        val markRadius = radius * 0.76f
        val baseline = -(dialPaint.ascent() + dialPaint.descent()) / 2
        MARKS.indices.forEach { position ->
            val degrees = position * 90f + wheelAngle
            val angle = Math.toRadians(degrees.toDouble())
            dialPaint.color = if (abs(shortestTurn(degrees)) < 45f) gold else muted
            canvas.drawText(MARKS[position],
                cx + markRadius * sin(angle).toFloat(),
                cy - markRadius * cos(angle).toFloat() + baseline, dialPaint)
        }
        dialPaint.color = ivory
        dialPaint.textSize = 8f * density
        val nameWidth = dialPaint.measureText(mode.name)
        dialPaint.textScaleX = (radius * 1.08f / nameWidth).coerceAtMost(1f)
        canvas.drawText(mode.name, cx, cy - (dialPaint.ascent() + dialPaint.descent()) / 2, dialPaint)
        dialPaint.textScaleX = 1f
        canvas.restoreToCount(checkpoint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || videoMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastAngle = touchAngle(event)
                rimGesture = hypot(startX - width / 2f, startY - height / 2f) >= 14f * density
                dragAngle = 0f
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val angle = touchAngle(event)
                if (rimGesture) dragAngle += shortestTurn(angle - lastAngle)
                lastAngle = angle
                if (!dragging && hypot(dx, dy) > slop) {
                    animation?.cancel()
                    dragOrigin = wheelAngle
                    dragging = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    if (!rimGesture) {
                        val rotation = Math.toRadians(contentRotation.toDouble())
                        dragAngle = ((dx * cos(rotation) + dy * sin(rotation)) / density * 3).toFloat()
                    }
                    wheelAngle = dragOrigin + dragAngle
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val steps = (-dragAngle / 90f).roundToInt()
                if (event.actionMasked == MotionEvent.ACTION_UP && steps % MARKS.size != 0) {
                    onStep?.invoke(steps % MARKS.size)
                }
                settleWheel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        val MARKS = listOf("A", "P", "Z", "M")
    }
}
