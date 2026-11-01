// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.Canvas
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

    private data class Key(val text: String, val width: Int, val height: Int, val color: Int, val size: Float, val typeface: Typeface?)
    private var lastKey: Key? = null
    private var textLayout: StaticLayout? = null

    override fun onDraw(canvas: Canvas) {
        val quarterTurn = ((contentRotation.roundToInt() % 180) + 180) % 180 == 90
        val inset = 2f * resources.displayMetrics.density
        val availableWidth = ((if (quarterTurn) height else width) - inset * 2).toInt().coerceAtLeast(1)
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
            val label = SpannableString(lines.joinToString("\n"))
            val split = label.indexOf('\n')
            if (split >= 0) {
                label.setSpan(RelativeSizeSpan(.9f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val muted = (currentTextColor and 0x00ffffff) or (((currentTextColor ushr 24) * .78f).toInt() shl 24)
                label.setSpan(ForegroundColorSpan(muted), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(StyleSpan(Typeface.BOLD), split + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        canvas.translate(-availableWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
