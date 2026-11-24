// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
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

    var referenceLabels: Pair<String, String>? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var valueLabel: String = ""
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

    var onProgressChanged: (() -> Unit)? = null
    var onStartTracking: (() -> Unit)? = null
    var onStopTracking: ((progress: Int, changed: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val accentColor = context.getColor(R.color.accent)
    private val labelColor = context.getColor(R.color.text_primary)
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
        color = accentColor
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        typeface = android.graphics.Typeface.create("sans-serif-condensed-medium", 0)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    var isTracking = false
        private set
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var previousX = 0f
    private var dragProgress = 0f
    private var dragging = false
    private var changed = false
    private var lastTickTime = 0L

    init {
        isFocusable = true
    }

    private val divisions get() = max.coerceAtMost(20)
    private val scaleStart get() = paddingLeft + 12f * density
    private val scaleSpan get() = width - paddingRight - 12f * density - scaleStart

    private fun changeProgress(candidate: Int, explicitTap: Boolean = false) {
        val previous = progress
        progress = candidate
        if (previous == progress && !explicitTap) return
        changed = true
        fun tick(value: Int) = (value.toFloat() * divisions / max).roundToInt()
        val now = SystemClock.uptimeMillis()
        if (tick(previous) != tick(progress) && now - lastTickTime >= 30L) {
            val feedback = when {
                Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CLOCK_TICK
                max <= 20 -> HapticFeedbackConstants.SEGMENT_TICK
                else -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            }
            performHapticFeedback(feedback)
            lastTickTime = now
        }
        onProgressChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundRect.set(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        val radius = backgroundRect.height() / 2f
        canvas.drawRoundRect(backgroundRect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(backgroundRect, radius, radius, borderPaint)

        val sideways = abs(contentRotation % 180f) > 45f
        val tickCenterY = if (sideways) backgroundRect.top + 10f * density
            else backgroundRect.centerY() - 7f * density
        val needleHalf = (if (sideways) 7f else 9f) * density
        // At a quarter turn, measured text width runs across the ruler's height.
        // Reserve clearance for the gold index as well as the shorter white ticks.
        val labelTop = tickCenterY + needleHalf + needlePaint.strokeWidth / 2f + 3f * density
        val labelBottom = backgroundRect.bottom - 3f * density
        val minorHalf = 3f * density
        val majorHalf = 5.5f * density
        val span = scaleSpan
        if (span <= 0f) return
        val startX = scaleStart
        for (index in 0..divisions) {
            val x = startX + span * index / divisions
            val isMajor = max <= 20 || index % 5 == 0
            val half = if (isMajor) majorHalf else minorHalf
            canvas.drawLine(x, tickCenterY - half, x, tickCenterY + half,
                if (isMajor) majorTickPaint else minorTickPaint)
        }

        val checkpoint = canvas.save()
        canvas.clipRect(backgroundRect)
        for (index in 1..3) {
            val label = when (index) {
                1 -> referenceLabels?.first
                2 -> valueLabel
                else -> referenceLabels?.second
            } ?: continue
            labelPaint.color = if (index == 2) accentColor else labelColor
            val x = startX + span * index / 4f
            val y = if (sideways) (labelTop + labelBottom) / 2f
                else backgroundRect.centerY() + 9f * density
            val saved = canvas.save()
            canvas.rotate(contentRotation, x, y)
            val labelWidth = if (sideways) (labelBottom - labelTop).coerceAtLeast(1f)
                else span / 4f
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
        if (!isEnabled || scaleSpan <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointerId = event.getPointerId(0)
                previousX = event.x
                dragProgress = progress.toFloat()
                dragging = false
                changed = false
                isTracking = true
                lastTickTime = 0L
                onStartTracking?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (isTracking && index >= 0) dragTo(event.getX(index))
            }
            MotionEvent.ACTION_UP -> {
                if (isTracking) {
                    dragTo(event.x)
                    if (!dragging) {
                        val target = ((event.x - scaleStart) / scaleSpan * max).roundToInt()
                        changeProgress(target, explicitTap = true)
                        performClick()
                    }
                    finishTracking()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) finishTracking()
            }
            MotionEvent.ACTION_CANCEL -> finishTracking()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dragTo(x: Float) {
        var dx = x - previousX
        if (!dragging) {
            if (abs(dx) <= touchSlop) return
            dragging = true
            dx += if (dx > 0f) -touchSlop else touchSlop
        }
        previousX = x
        dragProgress = (dragProgress + dx * max / scaleSpan).coerceIn(0f, max.toFloat())
        changeProgress(dragProgress.roundToInt())
    }

    private fun finishTracking() {
        if (!isTracking) return
        isTracking = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        onStopTracking?.invoke(progress, changed)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityNodeInfo.RangeInfo(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, max.toFloat(), progress.toFloat())
        } else {
            // Android 10 has no public RangeInfo constructor.
            @Suppress("DEPRECATION")
            AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, max.toFloat(), progress.toFloat())
        }
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            if (progress < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (progress > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        val step = (max / divisions).coerceAtLeast(1)
        val target = when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
                arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: return false
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> progress + step
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> progress - step
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (target.coerceIn(0, max) == progress) return false
        onStartTracking?.invoke()
        changeProgress(target)
        onStopTracking?.invoke(progress, true)
        return true
    }
}
