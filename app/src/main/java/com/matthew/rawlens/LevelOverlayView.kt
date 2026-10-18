// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Dual-axis virtual level: a Gcam-style horizon for roll plus a pitch bubble
 * for forward/back tilt. The moving horizon segments rotate by the live roll;
 * a short fixed witness at the center sits on the nearest 90° quadrant. Roll
 * level snaps the moving line onto the witness and turns it accent.
 *
 * The pitch bubble slides along the world-vertical track through the center
 * (toward the sky end when the camera points up, toward the ground end when
 * it points down) and turns accent only at the center ring. That second axis
 * is what prevents falling/converging verticals: a level horizon alone still
 * keystones when the camera tilts to sky or ground. True level (both axes)
 * turns both indicators accent.
 *
 * Portrait-locked canvas: in landscape the horizon draws vertical, which the
 * user holding the rotated phone sees as horizontal. While the device points
 * steeply up/down roll is noise ([VirtualHorizon.State.isFlat]): the horizon
 * hides and only the pitch bubble remains, pinned at its track end, so the
 * user can still tilt back toward level.
 */
class LevelOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var state: VirtualHorizon.State? = null
        private set

    private val density = resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 3f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1.5f * density
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Virtual horizon off"
    }

    fun setState(next: VirtualHorizon.State) {
        val previous = state
        state = next
        if (previous == null || previous.isLevel != next.isLevel ||
            previous.isRollLevel != next.isRollLevel ||
            previous.isPitchLevel != next.isPitchLevel ||
            previous.isFlat != next.isFlat ||
            abs(next.tiltDegrees - previous.tiltDegrees) >= 0.05f ||
            abs(next.pitchDegrees - previous.pitchDegrees) >= 0.05f
        ) {
            contentDescription = describe(next)
            invalidate()
        }
    }

    fun setFlat() {
        state = null
        contentDescription = "Virtual horizon flat, tilt unavailable"
        invalidate()
    }

    private fun describe(state: VirtualHorizon.State): String {
        val pitch = state.pitchDegrees
        val pitchText = when {
            state.isPitchLevel -> "pitch level"
            pitch > 0f -> String.format(
                java.util.Locale.US, "tilted %.1f degrees up toward sky", pitch
            )
            else -> String.format(
                java.util.Locale.US, "tilted %.1f degrees down toward ground", abs(pitch)
            )
        }
        if (state.isFlat) return "Virtual level, $pitchText, roll unavailable"
        if (state.isLevel) return "Virtual level, level"
        val roll = abs(state.errorDegrees)
        return String.format(
            java.util.Locale.US,
            "Virtual level, %.1f degrees off level, %s", roll, pitchText
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val lineLength = min(width, height) * 0.30f
        val gapHalf = 14f * density
        val witnessHalf = 6f * density
        val half = lineLength / 2f
        if (half <= gapHalf) return

        val drawDegrees = current.drawDegrees()
        if (!current.isFlat) {
            val snapped = VirtualHorizon.wrap180(
                kotlin.math.round(current.tiltDegrees / 90f) * 90f
            )
            linePaint.color = if (current.isRollLevel) LEVEL_COLOR else TILT_COLOR

            // Moving horizon: two segments with a center gap, rotated by roll.
            canvas.save()
            canvas.rotate(drawDegrees, cx, cy)
            drawSegment(canvas, cx - half, cy, cx - gapHalf, cy)
            drawSegment(canvas, cx + gapHalf, cy, cx + half, cy)
            canvas.restore()

            // Fixed witness: short center tick on the quadrant axis. Aligned
            // with the moving line exactly when roll-level.
            canvas.save()
            canvas.rotate(snapped, cx, cy)
            drawSegment(canvas, cx - witnessHalf, cy, cx + witnessHalf, cy)
            canvas.restore()
        }

        drawPitchBubble(canvas, cx, cy, if (current.isFlat) 0f else drawDegrees, current)
    }

    /**
     * Pitch track along world-vertical in the roll-rotated frame (up in that
     * frame is the sky): the bubble leaves the center ring toward the sky end
     * when the camera points up and toward the ground end when it points
     * down, pinning at the track end beyond the display range.
     */
    private fun drawPitchBubble(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        drawDegrees: Float,
        current: VirtualHorizon.State
    ) {
        val trackHalf = 36f * density
        val tickHalf = 4f * density
        val ringRadius = 6f * density
        val bubbleRadius = 4.5f * density
        val offset = VirtualHorizon.pitchOffsetFraction(current.pitchDegrees) * trackHalf
        bubblePaint.color = if (current.isPitchLevel) LEVEL_COLOR else TILT_COLOR

        canvas.save()
        canvas.rotate(drawDegrees, cx, cy)
        canvas.drawLine(cx, cy - trackHalf, cx, cy + trackHalf, outlinePaint)
        canvas.drawLine(cx, cy - trackHalf, cx, cy + trackHalf, trackPaint)
        canvas.drawLine(cx - tickHalf, cy - trackHalf, cx + tickHalf, cy - trackHalf, trackPaint)
        canvas.drawLine(cx - tickHalf, cy + trackHalf, cx + tickHalf, cy + trackHalf, trackPaint)
        canvas.drawCircle(cx, cy, ringRadius, outlinePaint)
        canvas.drawCircle(cx, cy, ringRadius, trackPaint)
        val bubbleY = cy - offset
        canvas.drawCircle(cx, bubbleY, bubbleRadius + 1f * density, outlinePaint)
        canvas.drawCircle(cx, bubbleY, bubbleRadius, bubblePaint)
        canvas.restore()
    }

    private fun drawSegment(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawLine(x0, y0, x1, y1, outlinePaint)
        canvas.drawLine(x0, y0, x1, y1, linePaint)
    }

    private companion object {
        const val LEVEL_COLOR = 0xFFDDC08D.toInt()
        const val TILT_COLOR = 0xE6FFFFFF.toInt()
    }
}
