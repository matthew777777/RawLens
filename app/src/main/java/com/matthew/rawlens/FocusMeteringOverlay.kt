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
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Reference-style tap-to-focus: thin white rounded squares, separate for AF and AE.
 * AF is the larger square, AE the smaller one with a center dot.
 * A tap focuses with a timed lock (dot + countdown); press-and-hold freezes
 * AE + AF indefinitely (padlock + ∞ badge) until a same-spot double-tap or the
 * AE/AF reset button releases it. A single tap elsewhere always refocuses.
 *
 * Default together, draggable apart: a tap on empty area joins AF + AE at
 * that point; dragging (or tapping) a visible square moves only that
 * subsystem. Grab the center/small square to peel AE out, the outer ring
 * to peel AF out.
 */
class FocusMeteringOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /**
     * Single tap (or joint drag) carrying AF + AE together at one point.
     * Coalesced so a tap costs one repeating update + one AF START.
     */
    var onTapBoth: ((Float, Float) -> Unit)? = null
    /** AF square dragged on its own; AE stays where it was. */
    var onAfDragged: ((Float, Float) -> Unit)? = null
    /** AE square dragged on its own; AF scan/lock is untouched. */
    var onAeDragged: ((Float, Float) -> Unit)? = null
    /** Joint drag of both squares when they overlap. Defaults to [onTapBoth]. */
    var onBothDragged: ((Float, Float) -> Unit)? = null
    var onTargetsCleared: (() -> Unit)? = null
    var onOverlayTouched: (() -> Unit)? = null
    /** Press-and-hold: freeze AE + AF at the point indefinitely (until double-tap/reset). */
    var onAfLockHold: ((Float, Float) -> Unit)? = null

    private val afPoint = PointF()
    private val aePoint = PointF()
    private var targetsVisible = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var focusAreaTime = -1L
    /** Last tap lift, so a double-tap clears an indefinite lock of any age. */
    private var lastTapUpMs = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private val density = resources.displayMetrics.density
    private val afHalfSide = 32f * density
    private val aeHalfSide = 24f * density
    private val afCornerRadius = 12f * density
    private val aeCornerRadius = 10f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** Finger wobble a hold tolerates before it becomes a drag (thumbs drift). */
    private val holdSlop = touchSlop * 2f
    private val afPaint = OverlayStyle.stroke(OverlayStyle.STROKE, OverlayStyle.STROKE_DP, density)
    private val aePaint = OverlayStyle.stroke(OverlayStyle.STROKE_SECONDARY, OverlayStyle.STROKE_THIN_DP, density)
    private val aeDotPaint = OverlayStyle.fill(OverlayStyle.STROKE_SECONDARY, density, shadow = true)
    private val lockDotPaint = OverlayStyle.fill(OverlayStyle.ACCENT, density, shadow = true)
    private val lockShacklePaint = OverlayStyle.stroke(OverlayStyle.ACCENT, 2f, density, shadow = false)
    private val lockKeyholePaint = OverlayStyle.fill(OverlayStyle.ACCENT_INK, density)
    private val lockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayStyle.ACCENT
        setShadowLayer(OverlayStyle.SHADOW_RADIUS_DP * density, 0f, 0.5f * density, OverlayStyle.SHADOW)
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val facePaint = OverlayStyle.stroke(OverlayStyle.STROKE_SECONDARY, OverlayStyle.STROKE_THIN_DP, density)
    private val faceTrackedPaint = OverlayStyle.stroke(OverlayStyle.ACCENT, OverlayStyle.STROKE_DP, density)
    private val focusRect = RectF()
    /** HAL face boxes in overlay pixels; the sand one drives AF/AE. Not touch handles. */
    private var faceBoxes: List<RectF> = emptyList()
    private var trackedFace = -1

    /** Sharp AF lock badge state, driven by the camera controller. */
    private var focusLocked = false
    private var lockIndefinite = false
    private var lockDeadlineMs = 0L
    private var longPressFired = false
    private var longPressPending: Runnable? = null
    private var fadePending: Runnable? = null
    private var dragMode = DragNone
    private var dragging = false
    /** Second finger down (pinch-zoom): swallow the gesture, never a tap. */
    private var multiTouch = false
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
        lastTapUpMs = 0L
        fadePending?.let(::removeCallbacks)
        fadePending = null
        setFocusLock(locked = false, indefinite = false, deadlineMs = 0L)
        invalidate()
    }

    /**
     * Shows or hides the small sand lock dot under the reticle.
     * @param deadlineMs elapsed-realtime expiry for timed locks (ignored when indefinite).
     */
    fun setFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long) {
        removeCallbacks(ticker)
        focusLocked = locked
        lockIndefinite = indefinite
        lockDeadlineMs = deadlineMs
        if (locked && !indefinite && deadlineMs > 0L) postDelayed(ticker, LOCK_TICK_MS)
        fadePending?.let(::removeCallbacks)
        fadePending = null
        if (targetsVisible && !locked) scheduleFade()
        invalidate()
    }

    private fun scheduleFade() {
        fadePending?.let(::removeCallbacks)
        fadePending = Runnable {
            fadePending = null
            // A fresh lock or retarget cancels the fade by replacing this runnable.
            if (!focusLocked && targetsVisible) {
                targetsVisible = false
                invalidate()
            }
        }.also { postDelayed(it, FADE_DELAY_MS) }
    }

    private fun showReticleAt(x: Float, y: Float) {
        afPoint.set(x, y)
        aePoint.set(x, y)
        clamp(afPoint, afHalfSide)
        clamp(aePoint, aeHalfSide)
        targetsVisible = true
        focusAreaTime = System.currentTimeMillis()
        scheduleFade()
        invalidate()
    }

    /** Moves only the AF square, keeping a separated AE where it was. */
    private fun showAfAt(x: Float, y: Float) {
        afPoint.set(x, y)
        clamp(afPoint, afHalfSide)
        targetsVisible = true
        focusAreaTime = System.currentTimeMillis()
        scheduleFade()
        invalidate()
    }

    /** Moves only the AE square, keeping AF scan/lock where it was. */
    private fun showAeAt(x: Float, y: Float) {
        aePoint.set(x, y)
        clamp(aePoint, aeHalfSide)
        targetsVisible = true
        focusAreaTime = System.currentTimeMillis()
        scheduleFade()
        invalidate()
    }

    /** Face boxes from the controller; [tracked] is the index driving AF/AE (-1 none). */
    fun setFaces(boxes: List<RectF>, tracked: Int) {
        faceBoxes = boxes
        trackedFace = tracked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for ((index, box) in faceBoxes.withIndex()) {
            val paint = if (index == trackedFace) faceTrackedPaint else facePaint
            canvas.drawRoundRect(box, 6f * density, 6f * density, paint)
        }
        if (!targetsVisible) return
        focusRect.set(
            afPoint.x - afHalfSide, afPoint.y - afHalfSide,
            afPoint.x + afHalfSide, afPoint.y + afHalfSide
        )
        canvas.drawRoundRect(focusRect, afCornerRadius, afCornerRadius, afPaint)
        focusRect.set(
            aePoint.x - aeHalfSide, aePoint.y - aeHalfSide,
            aePoint.x + aeHalfSide, aePoint.y + aeHalfSide
        )
        canvas.drawRoundRect(focusRect, aeCornerRadius, aeCornerRadius, aePaint)
        canvas.drawCircle(aePoint.x, aePoint.y, 2.5f * density, aeDotPaint)
        if (focusLocked) drawLockBadge(canvas)
    }

    /** Small sand dot below the AF reticle; timed locks also show seconds remaining. */
    private fun drawLockBadge(canvas: Canvas) {
        val cx = afPoint.x
        val cy = afPoint.y + afHalfSide + 10f * density
        if (lockIndefinite) {
            drawPadlock(canvas, cx, cy)
            return
        }
        canvas.drawCircle(cx, cy, 4f * density, lockDotPaint)
        if (lockDeadlineMs > 0L) {
            val remaining = ((lockDeadlineMs - SystemClock.elapsedRealtime()) / 1000L)
                .coerceIn(0L, 99L)
            canvas.drawText("${remaining}s", cx, cy + 14f * density, lockTextPaint)
        }
    }

    /**
     * Padlock badge for the indefinite AE/AF hold: shackle arc plus a filled
     * body with a keyhole dot, all in RawLens sand. Drawn with canvas
     * primitives so no drawable asset is needed.
     */
    private fun drawPadlock(canvas: Canvas, cx: Float, cy: Float) {
        val s = density
        val bodyHalfW = 6f * s
        val bodyHalfH = 5f * s
        val shackleRadius = 4.5f * s
        // Shackle: U-shape above the body.
        canvas.drawArc(
            cx - shackleRadius, cy - bodyHalfH - shackleRadius * 1.6f,
            cx + shackleRadius, cy - bodyHalfH + shackleRadius * 0.4f,
            180f, 180f, false, lockShacklePaint
        )
        // Body.
        focusRect.set(cx - bodyHalfW, cy - bodyHalfH, cx + bodyHalfW, cy + bodyHalfH)
        canvas.drawRoundRect(focusRect, 1.5f * s, 1.5f * s, lockDotPaint)
        // Keyhole.
        canvas.drawCircle(cx, cy - 1f * s, 1.4f * s, lockKeyholePaint)
        canvas.drawRect(cx - 0.8f * s, cy - 1f * s, cx + 0.8f * s, cy + 3f * s, lockKeyholePaint)
        // Infinity marker so a held lock reads differently from a timed dot.
        canvas.drawText("∞", cx, cy + bodyHalfH + 11f * s, lockTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onOverlayTouched?.invoke()
                touchDownX = event.x
                touchDownY = event.y
                longPressFired = false
                dragging = false
                multiTouch = false
                dragMode = hitTest(event.x, event.y)
                longPressPending?.let(::removeCallbacks)
                // Hold-to-lock is armed everywhere, also on the reticle the
                // last tap left behind (where people naturally press again).
                // Starting a drag cancels it, so a lock never fires mid-drag.
                val downX = event.x
                val downY = event.y
                longPressPending = Runnable {
                    longPressPending = null
                    longPressFired = true
                    dragMode = DragNone
                    dragging = false
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showReticleAt(downX, downY)
                    onAfLockHold?.invoke(afPoint.x, afPoint.y)
                }.also { postDelayed(it, HOLD_LOCK_MS) }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger (pinch-zoom): this gesture is not a tap. Cancel
                // the hold and swallow everything until the last finger lifts.
                multiTouch = true
                longPressPending?.let(::removeCallbacks)
                longPressPending = null
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Stay swallowed until ACTION_UP/CANCEL ends the gesture.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) return true
                if (longPressFired) return true
                if (dragMode != DragNone) {
                    // Drag starts past the hold slop so a still finger on the
                    // reticle reaches the lock instead of nudging the square.
                    if (!dragging &&
                        hypot(event.x - touchDownX, event.y - touchDownY) > holdSlop
                    ) {
                        dragging = true
                        longPressPending?.let(::removeCallbacks)
                        longPressPending = null
                    }
                    if (dragging) {
                        moveDraggedPoints(event.x, event.y)
                        invalidate()
                    }
                    return true
                }
                if (hypot(event.x - touchDownX, event.y - touchDownY) > holdSlop) {
                    longPressPending?.let(::removeCallbacks)
                    longPressPending = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressPending?.let(::removeCallbacks)
                longPressPending = null
                if (multiTouch) {
                    multiTouch = false
                    longPressFired = false
                    dragMode = DragNone
                    dragging = false
                    Log.i(LOG_TAG, "Touch gesture ignored (multi-touch)")
                    return true
                }
                // A fired long-press already locked; the lift is not a tap.
                // Reset the tap-pair clock so the next two quick taps form the
                // double-tap that releases the indefinite lock.
                if (longPressFired) {
                    longPressFired = false
                    dragMode = DragNone
                    dragging = false
                    lastTapUpMs = 0L
                    return true
                }
                // A drag commits the moved square(s) on lift. This is the only
                // path that moves AF without AE (or vice versa).
                if (dragging && dragMode != DragNone) {
                    val mode = dragMode
                    dragMode = DragNone
                    dragging = false
                    focusAreaTime = System.currentTimeMillis()
                    scheduleFade()
                    invalidate()
                    when (mode) {
                        DragAf -> onAfDragged?.invoke(afPoint.x, afPoint.y)
                        DragAe -> onAeDragged?.invoke(aePoint.x, aePoint.y)
                        else -> (onBothDragged ?: onTapBoth)?.invoke(afPoint.x, afPoint.y)
                            ?: run {
                                onAeDragged?.invoke(aePoint.x, aePoint.y)
                                onAfDragged?.invoke(afPoint.x, afPoint.y)
                            }
                    }
                    return true
                }
                val downMode = dragMode
                dragMode = DragNone
                dragging = false
                if (hypot(event.x - touchDownX, event.y - touchDownY) > touchSlop) return true
                val now = System.currentTimeMillis()
                // Same-spot double-tap releases the lock. A single tap anywhere
                // else always refocuses — even while locked, even inside the
                // double-tap window — so one tap switches place, never clears.
                // Indefinite holds outlive focusAreaTime by design, so the pair
                // clock (lastTapUpMs) — not the reticle age — decides the double.
                val nearAf =
                    hypot(event.x - afPoint.x, event.y - afPoint.y) < afHalfSide + touchSlop
                val nearAe =
                    hypot(event.x - aePoint.x, event.y - aePoint.y) < aeHalfSide + touchSlop
                val nearLastTap =
                    hypot(event.x - lastTapUpX, event.y - lastTapUpY) < touchSlop * 2f
                val isDoubleTap = lastTapUpMs != 0L &&
                    now - lastTapUpMs < ViewConfiguration.getDoubleTapTimeout() && nearLastTap
                val clearFocusAreas = targetsVisible && (nearAf || nearAe) &&
                    ((focusAreaTime != -1L &&
                        now - focusAreaTime < ViewConfiguration.getDoubleTapTimeout()) ||
                        isDoubleTap)
                // Bounce gate: the release above always wins, but a refocus
                // faster than the bounce window is an accidental tap — swallow
                // it without moving the reticle. The scan-in-flight half of the
                // gate lives in the controller, which knows the AF state.
                if (!clearFocusAreas && !AutoFocusPolicy.shouldAcceptTap(
                        now, lastTapUpMs, lastTapUpX, lastTapUpY,
                        event.x, event.y,
                        minOf(width, height).toFloat(),
                        scanInFlight = false
                    )
                ) {
                    lastTapUpMs = now
                    lastTapUpX = event.x
                    lastTapUpY = event.y
                    Log.i(LOG_TAG, "Touch tap ignored (bounce)")
                    return true
                }
                if (clearFocusAreas) {
                    lastTapUpMs = 0L
                    clearTargets()
                    onTargetsCleared?.invoke()
                } else if (downMode == DragAf && nearAf) {
                    // Tap on the AF square (no drag): refocus AF only, keep a
                    // separated AE. Fires the same callback as an AF drag.
                    showAfAt(event.x, event.y)
                    onAfDragged?.invoke(afPoint.x, afPoint.y)
                } else if (downMode == DragAe && nearAe) {
                    // Tap on the AE square (no drag): re-meter AE only, keep AF.
                    showAeAt(event.x, event.y)
                    onAeDragged?.invoke(aePoint.x, aePoint.y)
                } else {
                    // Single tap joins AF + AE at the new point through one
                    // coalesced callback (one repeating update + one AF START).
                    showReticleAt(event.x, event.y)
                    (onTapBoth ?: onBothDragged)?.invoke(afPoint.x, afPoint.y)
                        ?: run {
                            onAeDragged?.invoke(aePoint.x, aePoint.y)
                            onAfDragged?.invoke(afPoint.x, afPoint.y)
                        }
                }
                lastTapUpMs = now
                lastTapUpX = event.x
                lastTapUpY = event.y
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressPending?.let(::removeCallbacks)
                longPressPending = null
                longPressFired = false
                dragMode = DragNone
                dragging = false
                multiTouch = false
                return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        longPressPending?.let(::removeCallbacks)
        longPressPending = null
        fadePending?.let(::removeCallbacks)
        fadePending = null
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun clamp(point: PointF, half: Float = afHalfSide) {
        point.x = point.x.coerceIn(half, (width - half).coerceAtLeast(half))
        point.y = point.y.coerceIn(half, (height - half).coerceAtLeast(half))
    }

    /**
     * Finger-friendly hit test against the two drawn squares. When the squares
     * overlap (the default joined state), the center/small square peels AE out
     * and the outer AF ring peels AF out — a drag from joined state always
     * separates one square instead of moving both together (taps, not drags,
     * are what moves both). Once separated, an overlapping touch grabs the
     * nearer square.
     */
    private fun hitTest(x: Float, y: Float): Int {
        if (!targetsVisible) return DragNone
        val afHitRadius = afHalfSide + 16f * density
        val aeHitRadius = aeHalfSide + 16f * density
        val distAf = hypot(x - afPoint.x, y - afPoint.y)
        val distAe = hypot(x - aePoint.x, y - aePoint.y)
        val afHit = distAf <= afHitRadius
        val aeHit = distAe <= aeHitRadius
        if (afHit && aeHit) {
            return if (distAe < distAf) DragAe else DragAf
        }
        if (afHit) return DragAf
        if (aeHit) return DragAe
        return DragNone
    }

    private fun moveDraggedPoints(x: Float, y: Float) {
        when (dragMode) {
            DragAf -> {
                afPoint.set(x, y)
                clamp(afPoint, afHalfSide)
            }
            DragAe -> {
                aePoint.set(x, y)
                clamp(aePoint, aeHalfSide)
            }
            DragBoth -> {
                afPoint.set(x, y)
                aePoint.set(x, y)
                clamp(afPoint, afHalfSide)
                clamp(aePoint, aeHalfSide)
            }
        }
    }

    companion object {
        // Shared camera tag so one grep follows a tap from overlay to HAL verdict.
        private const val LOG_TAG = "RawLensCamera"
        private const val LOCK_TICK_MS = 500L
        /** Press-and-hold time to lock: a bit faster than the system long-press. */
        private const val HOLD_LOCK_MS = 450L
        // Long enough to peel a square out after the tap that revealed it:
        // covers the AF scan timeout, while locks hold indefinitely anyway.
        private const val FADE_DELAY_MS = 4_000L
        private const val DragNone = 0
        private const val DragAf = 1
        private const val DragAe = 2
        private const val DragBoth = 3
    }
}
