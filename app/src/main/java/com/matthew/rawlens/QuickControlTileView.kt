// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Fixed touch target and background; text is laid out inside its rotated available bounds. */
class QuickControlTileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs), RotatingContent {
    override var contentRotation = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    var showsChoice = false
    var valueFirst = false
        set(value) {
            if (field == value) return
            field = value
            lastKey = null
            invalidate()
        }
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val activeColor = context.getColor(R.color.accent)
    private val choiceColor = context.getColor(R.color.text_secondary)

    private data class Key(val text: String, val width: Int, val height: Int, val color: Int, val size: Float, val typeface: Typeface?)
    private var lastKey: Key? = null
    private var textLayout: StaticLayout? = null

    override fun onDraw(canvas: Canvas) {
        val quarterTurn = ((contentRotation.roundToInt() % 180) + 180) % 180 == 90
        val density = resources.displayMetrics.density
        val inset = 2f * density
        val sideInset = if (showsChoice && isEnabled) 6f * density else inset
        val availableWidth = ((if (quarterTurn) height else width) - sideInset * 2).toInt().coerceAtLeast(1)
        val availableHeight = ((if (quarterTurn) width else height) - inset * 2).coerceAtLeast(1f)
        val key = Key(text.toString(), availableWidth, availableHeight.toInt(), currentTextColor, textSize, typeface)
        if (lastKey != key) {
            val lines = key.text.lines().toMutableList()
            if (lines.isNotEmpty()) lines[0] = when (lines[0].uppercase()) {
                "D-LIGHTING" -> "D-LIGHT"
                "AE METER" -> "METER"
                "FRAME RATE" -> "FPS"
                else -> lines[0]
            }
            if (lines.size > 1) lines[1] = when (lines[1]) {
                "UNAVAILABLE" -> "N/A"
                "WARMING" -> "WAIT"
                "RAW HDR TEST" -> "HDR TEST"
                else -> lines[1]
            }
            // Keep the original TextView text for accessibility and automation.
            val hasValue = lines.size == 2
            if (hasValue && valueFirst) {
                val name = lines[0]
                lines[0] = lines[1]
                lines[1] = name
            }
            val label = SpannableString(lines.joinToString("\n"))
            val split = label.indexOf('\n')
            if (split >= 0) {
                val titleStart = if (hasValue && valueFirst) split + 1 else 0
                val titleEnd = if (hasValue && valueFirst) label.length else split
                label.setSpan(RelativeSizeSpan(if (valueFirst) .72f else .9f),
                    titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val muted = (currentTextColor and 0x00ffffff) or (((currentTextColor ushr 24) * .78f).toInt() shl 24)
                label.setSpan(ForegroundColorSpan(muted), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val valueStart = if (hasValue && valueFirst) 0 else split + 1
                val valueEnd = if (hasValue && valueFirst) split else label.length
                label.setSpan(StyleSpan(Typeface.BOLD), valueStart, valueEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val textPaint = TextPaint(paint).apply { color = currentTextColor }
            var longest = 1f
            var start = 0
            for (i in 0..label.length) if (i == label.length || label[i] == '\n') {
                longest = maxOf(longest, Layout.getDesiredWidth(label, start, i, textPaint))
                start = i + 1
            }
            fun layout(width: Int) = StaticLayout.Builder.obtain(label, 0, label.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setLineSpacing(resources.displayMetrics.density * 2, 1f).build()
            val natural = layout(ceil(longest).toInt().coerceAtLeast(1))
            textPaint.textSize *= QuickTileTextFit.scale(longest, natural.height.toFloat(), (availableWidth - 1).coerceAtLeast(1).toFloat(), availableHeight)
            textLayout = layout(availableWidth)
            lastKey = key
        }
        val layout = textLayout ?: return
        val checkpoint = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(contentRotation)
        if (isSelected && text.indexOf('\n') >= 0 && currentTextColor != context.getColor(R.color.accent_dark)) {
            indicator.color = activeColor
            canvas.drawCircle(0f, -availableHeight / 2f + 3f * density, 1.5f * density, indicator)
        }
        if (showsChoice && isEnabled) {
            indicator.color = choiceColor
            indicator.strokeWidth = density
            val x = availableWidth / 2f + 2f * density
            val half = 2f * density
            canvas.drawLine(x - half, -half, x + half, 0f, indicator)
            canvas.drawLine(x + half, 0f, x - half, half, indicator)
        }
        canvas.translate(-availableWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
