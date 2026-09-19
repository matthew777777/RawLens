// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
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
    /** Press-and-hold: focus at the point and lock indefinitely (until double-tap). */
    var onAfLockHold: ((Float, Float) -> Unit)? = null

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
    private val lockPaint = targetPaint(Color.rgb(76, 220, 120)).apply {
        style = Paint.Style.FILL_AND_STROKE
    }
    private val lockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 220, 120)
        textSize = 11f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val lockRect = RectF()

    /** Sharp AF lock badge state, driven by the camera controller. */
    private var focusLocked = false
    private var lockIndefinite = false
    private var lockDeadlineMs = 0L
    private var longPressFired = false
    private var longPressPending: Runnable? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!focusLocked || lockIndefinite) return
            if (SystemClock.elapsedRealtime() >= lockDeadlineMs) return
            invalidate()
            postDelayed(this, LOCK_TICK_MS)
        }
    }

    fun clearTargets() {
        targetsVisible = false
        focusAreaTime = -1L
        setFocusLock(locked = false, indefinite = false, deadlineMs = 0L)
        invalidate()
    }

    /**
     * Shows or hides the small lock badge next to the AF reticle.
     * @param deadlineMs elapsed-realtime expiry for timed locks (ignored when indefinite).
     */
    fun setFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long) {
        removeCallbacks(ticker)
        focusLocked = locked
        lockIndefinite = indefinite
        lockDeadlineMs = deadlineMs
        if (locked && !indefinite && deadlineMs > 0L) postDelayed(ticker, LOCK_TICK_MS)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!targetsVisible) return
        canvas.drawCircle(afPoint.x, afPoint.y, radius, afPaint)
        canvas.drawLine(afPoint.x - radius * 0.45f, afPoint.y, afPoint.x + radius * 0.45f, afPoint.y, afPaint)
        canvas.drawLine(afPoint.x, afPoint.y - radius * 0.45f, afPoint.x, afPoint.y + radius * 0.45f, afPaint)

        canvas.drawCircle(aePoint.x, aePoint.y, radius * 0.82f, aePaint)
        canvas.drawCircle(aePoint.x, aePoint.y, radius * 0.18f, aePaint)

        if (focusLocked) drawLockBadge(canvas)
    }

    /** Small padlock above the AF reticle; timed locks also show seconds remaining. */
    private fun drawLockBadge(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val w = 9f * density
        val h = 7f * density
        val cx = afPoint.x
        val top = (afPoint.y - radius - 16f * density).coerceAtLeast(h + 8f * density)
        // Shackle.
        canvas.drawArc(cx - w * 0.55f, top - h * 1.1f, cx + w * 0.55f, top + h * 0.1f,
            180f, 180f, false, lockPaint.apply { style = Paint.Style.STROKE })
        // Body.
        lockRect.set(cx - w / 2f, top - h / 2f, cx + w / 2f, top + h / 2f)
        canvas.drawRoundRect(lockRect, 2f * density, 2f * density,
            lockPaint.apply { style = Paint.Style.FILL_AND_STROKE })
        if (!lockIndefinite && lockDeadlineMs > 0L) {
            val remaining = ((lockDeadlineMs - SystemClock.elapsedRealtime()) / 1000L)
                .coerceIn(0L, 99L)
            canvas.drawText("${remaining}s", cx, top + h / 2f + 12f * density, lockTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onOverlayTouched?.invoke()
                touchDownX = event.x
                touchDownY = event.y
                longPressFired = false
                longPressPending?.let(::removeCallbacks)
                val downX = event.x
                val downY = event.y
                longPressPending = Runnable {
                    longPressPending = null
                    longPressFired = true
                    afPoint.set(downX, downY)
                    aePoint.set(downX, downY)
                    clamp(afPoint)
                    clamp(aePoint)
                    targetsVisible = true
                    focusAreaTime = System.currentTimeMillis()
                    onAfLockHold?.invoke(afPoint.x, afPoint.y)
                    invalidate()
                }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - touchDownX, event.y - touchDownY) > touchSlop) {
                    longPressPending?.let(::removeCallbacks)
                    longPressPending = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressPending?.let(::removeCallbacks)
                longPressPending = null
                // A fired long-press already locked; the lift is not a tap.
                // Restart the double-tap clock so a fast double-tap after the
                // hold is read as a fresh pair, not as a continuation.
                if (longPressFired) {
                    longPressFired = false
                    focusAreaTime = -1L
                    return true
                }
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
            MotionEvent.ACTION_CANCEL -> {
                longPressPending?.let(::removeCallbacks)
                longPressPending = null
                longPressFired = false
                return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        longPressPending?.let(::removeCallbacks)
        longPressPending = null
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
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

    companion object {
        private const val LOCK_TICK_MS = 500L
    }
}
