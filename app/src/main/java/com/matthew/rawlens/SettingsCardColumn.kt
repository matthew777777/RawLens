// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A settings page column that draws its rows as rounded cards. Rows stay direct
 * children, so pages keep adding, hiding and indexing them as before; the cards are
 * worked out from what is visible each time the column draws. A section heading
 * ([SECTION_TAG]) or a row with a top margin starts a new card.
 */
internal class SettingsCardColumn(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val radius = 20f * density
    private val dividerInset = 16f * density
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_container)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.outline)
        alpha = 22
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val dividerPaint = Paint().apply {
        color = context.getColor(R.color.outline)
        alpha = 26
        strokeWidth = density
    }
    private val cards = mutableListOf<List<View>>()
    private val clips = HashMap<View, Path>()
    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    private fun isHeading(view: View) = view.tag == SECTION_TAG

    /** Text that belongs to the control next to it: a slider's label or a row's note. */
    private fun isNote(view: View) = view is TextView && view !is Button

    private fun collectCards() {
        cards.clear()
        clips.clear()
        var open = mutableListOf<View>()
        fun close() {
            if (open.isNotEmpty()) cards.add(open)
            open = mutableListOf()
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != VISIBLE || child.height == 0) continue
            if (isHeading(child)) {
                close()
                continue
            }
            if (((child.layoutParams as? MarginLayoutParams)?.topMargin ?: 0) > 0) close()
            open.add(child)
        }
        close()
        cards.forEach { rows ->
            val path = Path().apply {
                rect.set(0f, rows.first().top.toFloat(), width.toFloat(), rows.last().bottom.toFloat())
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            // Only the end rows can reach the rounded corners.
            clips[rows.first()] = path
            clips[rows.last()] = path
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        collectCards()
        cards.forEach { rows ->
            rect.set(0f, rows.first().top.toFloat(), width.toFloat(), rows.last().bottom.toFloat())
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
            rect.inset(density / 2, density / 2)
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
            for (index in 1 until rows.size) {
                val above = rows[index - 1]
                val row = rows[index]
                if (row is SettingsSlider || isNote(above) || isNote(row)) continue
                val y = row.top.toFloat()
                canvas.drawLine(dividerInset, y, width - dividerInset, y, dividerPaint)
            }
        }
    }

    /** Keeps a pressed row's ripple inside the card's rounded corners. */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val clip = clips[child] ?: return super.drawChild(canvas, child, drawingTime)
        val saved = canvas.save()
        canvas.clipPath(clip)
        val more = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(saved)
        return more
    }

    companion object {
        const val SECTION_TAG = "settings-section"
    }
}
