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

/**
 * Transparent ruler-style slider matching the reference camera app.
 *
 * Tick-only: no numeric value is drawn here on purpose. Values live in the
 * exposure chips (ISO/S/WB/AF/EV) so the strip stays compact in landscape.
 * The lime center needle is the RawLens touch; ticks/label stay reference-white.
 */
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

    /** Short code only (SS / ISO / WB / MF / EV) — never a value. */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    override var contentRotation: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var onProgressChanged: ((progress: Int, fromUser: Boolean) -> Unit)? = null
    var onStartTracking: (() -> Unit)? = null
    var onStopTracking: ((progress: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val backgroundRect = RectF()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(102, 16, 18, 20)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 255, 51)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    private var tracking = false

    fun setProgressFromUser(progress: Int, fromUser: Boolean) {
        this.progress = progress
        onProgressChanged?.invoke(this.progress, fromUser)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (64f * density).roundToInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 14f * density
        backgroundRect.set(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        canvas.drawRoundRect(backgroundRect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(backgroundRect, radius, radius, borderPaint)

        val centerX = backgroundRect.centerX()
        // Label stays readable in landscape: rotate only the glyph, never the ruler.
        canvas.save()
        canvas.rotate(contentRotation, centerX, backgroundRect.top + 16f * density)
        canvas.drawText(label, centerX, backgroundRect.top + 16f * density, labelPaint)
        canvas.restore()

        val tickTop = backgroundRect.top + 24f * density
        val tickBottom = backgroundRect.bottom - 10f * density
        val tickCenterY = (tickTop + tickBottom) / 2f
        val minorHalf = 4f * density
        val majorHalf = 7f * density
        val tickCount = 21
        val span = backgroundRect.width() - 24f * density
        val startX = backgroundRect.left + 12f * density
        for (index in 0 until tickCount) {
            val x = startX + span * index / (tickCount - 1).toFloat()
            val isMajor = index % 5 == 0 || index == tickCount / 2
            val half = if (isMajor) majorHalf else minorHalf
            canvas.drawLine(x, tickCenterY - half, x, tickCenterY + half,
                if (isMajor) majorTickPaint else minorTickPaint)
        }

        // Lime needle marks the current position; the fill track is intentionally omitted
        // so the strip reads as a ruler rather than a progress bar.
        val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        val needleX = startX + span * fraction.coerceIn(0f, 1f)
        canvas.drawLine(needleX, tickTop - 2f * density, needleX, tickBottom + 2f * density, needlePaint)
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
