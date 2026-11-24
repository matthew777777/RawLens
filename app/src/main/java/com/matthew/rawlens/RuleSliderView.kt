// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Engraved scale with reference marks in the active camera setting’s units. */
class RuleSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), RotatingContent {

    var max: Int = 10_000
        set(value) {
            field = value.coerceAtLeast(1)
            if (progress > field) progress = field
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            val coerced = value.coerceIn(0, max)
            if (field != coerced) {
                field = coerced
                invalidate()
            }
        }

    var referenceLabels: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override var contentRotation: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onProgressChanged: ((progress: Int, fromUser: Boolean) -> Unit)? = null
    var onStartTracking: (() -> Unit)? = null
    var onStopTracking: ((progress: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val backgroundRect = RectF()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_container)
        alpha = 168
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        typeface = android.graphics.Typeface.create("sans-serif-condensed-medium", 0)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private var tracking = false

    fun setProgressFromUser(progress: Int, fromUser: Boolean) {
        this.progress = progress
        onProgressChanged?.invoke(this.progress, fromUser)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundRect.set(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        val radius = backgroundRect.height() / 2f
        canvas.drawRoundRect(backgroundRect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(backgroundRect, radius, radius, borderPaint)

        val sideways = kotlin.math.abs(contentRotation % 180f) > 45f
        val tickCenterY = if (sideways) backgroundRect.top + 10f * density
            else backgroundRect.centerY() - 7f * density
        val needleHalf = (if (sideways) 7f else 9f) * density
        // At a quarter turn, measured text width runs across the ruler's height.
        // Reserve clearance for the gold index as well as the shorter white ticks.
        val labelTop = tickCenterY + needleHalf + needlePaint.strokeWidth / 2f + 3f * density
        val labelBottom = backgroundRect.bottom - 3f * density
        val minorHalf = 3f * density
        val majorHalf = 5.5f * density
        val tickCount = if (max < 21) max + 1 else 21
        val span = backgroundRect.width() - 24f * density
        if (span <= 0f) return
        val startX = backgroundRect.left + 12f * density
        for (index in 0 until tickCount) {
            val x = startX + span * index / (tickCount - 1).toFloat()
            val isMajor = max < 21 || index % 5 == 0 || index == tickCount / 2
            val half = if (isMajor) majorHalf else minorHalf
            canvas.drawLine(x, tickCenterY - half, x, tickCenterY + half,
                if (isMajor) majorTickPaint else minorTickPaint)
        }

        val checkpoint = canvas.save()
        canvas.clipRect(backgroundRect)
        referenceLabels.forEachIndexed { index, label ->
            val x = startX + span * (index + 1) / (referenceLabels.size + 1)
            val y = if (sideways) (labelTop + labelBottom) / 2f
                else backgroundRect.centerY() + 9f * density
            val saved = canvas.save()
            canvas.rotate(contentRotation, x, y)
            val labelWidth = if (sideways) (labelBottom - labelTop).coerceAtLeast(1f)
                else span / (referenceLabels.size + 1)
            val naturalWidth = labelPaint.measureText(label)
            labelPaint.textScaleX = if (naturalWidth > labelWidth) labelWidth / naturalWidth else 1f
            canvas.drawText(label, x, y - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            labelPaint.textScaleX = 1f
            canvas.restoreToCount(saved)
        }
        canvas.restoreToCount(checkpoint)

        val needleX = startX + span * progress / max
        canvas.drawLine(needleX, tickCenterY - needleHalf, needleX, tickCenterY + needleHalf, needlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                onStartTracking?.invoke()
                updateProgressForX(event.x, fromUser = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                updateProgressForX(event.x, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                updateProgressForX(event.x, fromUser = true)
                parent?.requestDisallowInterceptTouchEvent(false)
                onStopTracking?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateProgressForX(x: Float, fromUser: Boolean) {
        val startX = paddingLeft + 12f * density
        val endX = width - paddingRight - 12f * density
        val fraction = if (endX > startX) ((x - startX) / (endX - startX)).coerceIn(0f, 1f) else 0f
        setProgressFromUser((fraction * max).roundToInt(), fromUser)
    }
}
