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
 * Minimal floating ruler slider, after the reference camera apps:
 * a translucent dark strip with the axis code + live value stacked on top,
 * a slim tick band, edge min/max hints, and a lime needle. Values are fed in
 * via [valueText]/[minText]/[maxText] so the strip stays self-sufficient and
 * the exposure chips are free for status.
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

    /** Short code (SS / ISO / WB / MF / EV). */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Live formatted value drawn bold under the code (e.g. "1/60", "+0.2"). */
    var valueText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Edge hints for the tick band (range min / max). Empty hides them. */
    var minText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var maxText: String = ""
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
        color = Color.argb(105, 10, 12, 14)
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
        color = Color.rgb(214, 255, 51)
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 214, 255, 51)
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 8.5f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.14f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 255, 255)
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        textAlign = Paint.Align.CENTER
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textSize = 8f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL
        )
    }

    private var tracking = false

    fun setProgressFromUser(progress: Int, fromUser: Boolean) {
        this.progress = progress
        onProgressChanged?.invoke(this.progress, fromUser)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (68f * density).roundToInt()
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
        // Code + live value stacked on top, reference style. Only the glyphs
        // rotate in landscape; the strip itself never does.
        canvas.save()
        canvas.rotate(contentRotation, centerX, backgroundRect.top + 20f * density)
        canvas.drawText(label, centerX, backgroundRect.top + 12f * density, codePaint)
        if (valueText.isNotEmpty()) {
            canvas.drawText(valueText, centerX, backgroundRect.top + 28f * density, valuePaint)
        }
        canvas.restore()

        val tickTop = backgroundRect.top + 36f * density
        val tickBottom = backgroundRect.bottom - 12f * density
        val tickCenterY = (tickTop + tickBottom) / 2f
        val minorHalf = 3f * density
        val majorHalf = 5.5f * density
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

        // Edge min/max hints along the bottom of the strip. Each glyph rotates
        // around its own anchor so it stays in place in landscape, exactly
        // like the rotating button values.
        edgePaint.textAlign = Paint.Align.LEFT
        if (minText.isNotEmpty()) {
            canvas.save()
            canvas.rotate(contentRotation, startX, backgroundRect.bottom - 3f * density)
            canvas.drawText(minText, startX, backgroundRect.bottom - 3f * density, edgePaint)
            canvas.restore()
        }
        edgePaint.textAlign = Paint.Align.RIGHT
        if (maxText.isNotEmpty()) {
            canvas.save()
            canvas.rotate(contentRotation, startX + span, backgroundRect.bottom - 3f * density)
            canvas.drawText(maxText, startX + span, backgroundRect.bottom - 3f * density, edgePaint)
            canvas.restore()
        }

        // Lime needle marks the current position; the fill track is intentionally omitted
        // so the strip reads as a ruler rather than a progress bar.
        val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        val needleX = startX + span * fraction.coerceIn(0f, 1f)
        canvas.drawLine(needleX, tickTop - 2f * density, needleX, tickBottom + 2f * density, needleGlowPaint)
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
