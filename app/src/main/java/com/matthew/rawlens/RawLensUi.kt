// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Shared chrome for settings, discovery, editors, confirmations and standalone tools. */
internal object RawLensUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun styleButton(button: Button, active: Boolean = false) {
        val context = button.context
        button.isSelected = active
        button.setBackgroundResource(if (active) R.drawable.quick_control_active else R.drawable.quick_control)
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.text_muted), context.getColor(if (active) R.color.accent_dark else R.color.text_primary))))
        button.gravity = Gravity.CENTER
        button.textSize = 13f
        button.isAllCaps = false
        button.minHeight = dp(context, 48)
        button.minimumHeight = dp(context, 48)
        button.minWidth = 0
        button.minimumWidth = 0
        button.stateListAnimator = null
        button.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
    }

    fun styleTab(button: Button, active: Boolean = button.isSelected) {
        styleButton(button, active)
        val context = button.context
        button.minWidth = dp(context, 88)
        button.minimumWidth = dp(context, 88)
        button.textSize = 14f
        button.maxLines = 1
        button.includeFontPadding = false
        button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        button.setPadding(dp(context, 20), 0, dp(context, 20), 0)
    }

    fun styleDialog(dialog: AlertDialog) {
        val context = dialog.context
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(R.drawable.quick_panel)
        // Dialogs rely on platform resize behavior when the keyboard opens.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val metrics = context.resources.displayMetrics
        val width = minOf(metrics.widthPixels - dp(context, 24), dp(context, 560))
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        fun polish(view: View) {
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.scrollIndicators = 0
            when (view) {
                is CompoundButton -> {
                    view.minHeight = dp(context, 48)
                    view.textSize = 13f
                    view.setTextColor(ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(context.getColor(R.color.text_muted), context.getColor(R.color.text_primary))))
                }
                is Button -> {
                    styleButton(view, view.isSelected)
                    val parent = view.parent as? LinearLayout
                    if (parent?.parent is android.widget.HorizontalScrollView) {
                        styleTab(view)
                    }
                    if (parent?.orientation == LinearLayout.VERTICAL) {
                        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                            params.width = ViewGroup.LayoutParams.MATCH_PARENT
                            params.bottomMargin = dp(context, 4)
                            view.layoutParams = params
                        }
                    }
                }
                is EditText -> {
                    view.setBackgroundResource(R.drawable.settings_input)
                    view.setTextColor(ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(context.getColor(R.color.text_muted), context.getColor(R.color.text_primary))))
                    view.setHintTextColor(context.getColor(R.color.text_secondary))
                    view.textSize = 13f
                    view.minHeight = dp(context, 48)
                    view.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                }
                is TextView -> if (view.id == android.R.id.message) {
                    view.textSize = 13f
                    view.setTextColor(context.getColor(R.color.text_secondary))
                    view.setLineSpacing(dp(context, 2).toFloat(), 1f)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) polish(view.getChildAt(index))
        }
        val custom = window.decorView.findViewById<ViewGroup>(android.R.id.custom)
        val customRoot = custom?.let { if (it.childCount == 1) it.getChildAt(0) else null }
        val column = if (customRoot is android.widget.ScrollView) customRoot.getChildAt(0) else customRoot
        if (column is LinearLayout && column.orientation == LinearLayout.VERTICAL) {
            column.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
        }
        polish(window.decorView)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.let { button ->
                styleButton(button, which == AlertDialog.BUTTON_POSITIVE)
                button.minWidth = dp(context, 88)
                button.minimumWidth = dp(context, 88)
                button.textSize = 14f
                button.setPadding(dp(context, 20), dp(context, 6), dp(context, 20), dp(context, 6))
                (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.marginStart = if (which == AlertDialog.BUTTON_POSITIVE) dp(context, 8) else 0
                    button.layoutParams = params
                }
            }
        }
        // Measure the final styled content before attaching a visible window.
        val maxHeight = (metrics.heightPixels * .85f).toInt()
        window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        window.setLayout(width, window.decorView.measuredHeight.coerceAtMost(maxHeight))
    }
}

/** All app-owned alerts share styling while callers retain their existing callbacks. */
internal class RawLensDialogBuilder(context: Context) : AlertDialog.Builder(context, R.style.ThemeOverlay_RawLens_Dialog) {
    override fun setTitle(title: CharSequence?): RawLensDialogBuilder {
        super.setTitle(title)
        super.setCustomTitle(TextView(context).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(RawLensUi.dp(context, 12), RawLensUi.dp(context, 20), RawLensUi.dp(context, 12), RawLensUi.dp(context, 8))
            isAccessibilityHeading = true
        })
        return this
    }

    override fun setTitle(titleId: Int): RawLensDialogBuilder = setTitle(context.getText(titleId))

    override fun create(): AlertDialog = super.create().also { dialog ->
        dialog.create()
        RawLensUi.styleDialog(dialog)
    }
}
