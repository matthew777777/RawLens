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
        // Narrow windows get tighter chips so a fourth tab stays in view.
        val compact = compactTabs(context)
        val minWidth = dp(context, if (compact) 64 else 88)
        val sidePadding = dp(context, if (compact) 12 else 20)
        button.minWidth = minWidth
        button.minimumWidth = minWidth
        button.textSize = if (compact) 13f else 14f
        button.maxLines = 1
        button.includeFontPadding = false
        button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        button.setPadding(sidePadding, 0, sidePadding, 0)
    }

    /** True when the screen is too narrow for full-size tab chips. */
    fun compactTabs(context: Context): Boolean =
        context.resources.configuration.screenWidthDp in 1 until 380

    /**
     * Shows [dialog] as a full-screen page like the camera itself: it covers the cutout
     * and status-bar strip, while [root] is padded clear of the camera hole, any visible
     * system bar and the keyboard. The dialog must use the activity theme.
     */
    /** System bars + cutout + IME, via the compat layer so Android 10 (API 29) works too. */
    private fun safeInsets(insets: android.view.WindowInsets, view: View): androidx.core.graphics.Insets =
        androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, view).getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                androidx.core.view.WindowInsetsCompat.Type.ime())

    /** MODE_ALWAYS is API 30; SHORT_EDGES is the closest Android 10 mode (portrait camera UI). */
    private fun cutoutModeAlways(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    fun showFullscreen(dialog: android.app.Dialog, root: View) {
        val context = dialog.context
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = safeInsets(insets, view)
            view.setPadding(base[0] + safe.left, base[1] + safe.top, base[2] + safe.right, base[3] + safe.bottom)
            insets
        }
        // Size and place the window before it is shown, so it never appears at one
        // size and snaps to another.
        dialog.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(context.getColor(R.color.surface)))
            window.setWindowAnimations(R.style.Animation_RawLens_Dialog)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = cutoutModeAlways()
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            ImmersiveMode.apply(window)
        }
        dialog.show()
        dialog.window?.let { ImmersiveMode.apply(it) }
        dialog.window?.decorView?.let { StableUiScale.followSystemText(it) }
    }

    /**
     * Turns a shown alert dialog into a full-screen page (see [RawLensDialogBuilder.asPage]):
     * header on top, the content filling the height, the buttons pinned to the bottom.
     */
    fun pageDialog(dialog: AlertDialog) {
        val context = dialog.context
        val window = dialog.window ?: return
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(context.getColor(R.color.surface)))
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = cutoutModeAlways()
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ImmersiveMode.apply(window)
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val panel = content.getChildAt(0) as? ViewGroup ?: return
        panel.layoutParams = panel.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        // Stretch whichever section holds the body (custom view or message) so the
        // button bar sits at the bottom of the screen, not under short content.
        val body: View? = window.decorView.findViewById(android.R.id.custom)
            ?: window.decorView.findViewById(android.R.id.message)
        var section = body
        while (section != null && section.parent !== panel) section = section.parent as? View
        (section?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = 0
            params.weight = 1f
            section!!.layoutParams = params
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val safe = safeInsets(insets, view)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        content.requestApplyInsets()
    }

    fun styleDialog(dialog: AlertDialog) {
        val context = dialog.context
        val window = dialog.window ?: return
        window.setBackgroundDrawableResource(R.drawable.quick_panel)
        // Dialog text follows the system font and display size (the camera chrome does not).
        StableUiScale.followSystemText(window.decorView)
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
    private var page = false
    private var titleText: CharSequence? = null

    /**
     * Presents this dialog as a full-screen page with a back arrow, like the Settings
     * pages. For screens people browse or edit at length; short forms, confirmations and
     * value pickers stay floating dialogs.
     */
    fun asPage(): RawLensDialogBuilder {
        page = true
        return this
    }

    override fun setTitle(title: CharSequence?): RawLensDialogBuilder {
        super.setTitle(title)
        titleText = title
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

    override fun create(): AlertDialog {
        val back = if (!page) null else TextView(context).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text_primary))
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
        }
        if (back != null) super.setCustomTitle(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(RawLensUi.dp(context, 12), RawLensUi.dp(context, 12), RawLensUi.dp(context, 12), RawLensUi.dp(context, 4))
            addView(back, LinearLayout.LayoutParams(RawLensUi.dp(context, 48), RawLensUi.dp(context, 48)))
            addView(TextView(context).apply {
                text = titleText
                textSize = 22f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(context.getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isAccessibilityHeading = true
                setPadding(RawLensUi.dp(context, 12), 0, RawLensUi.dp(context, 4), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        return super.create().also { dialog ->
            dialog.create()
            RawLensUi.styleDialog(dialog)
            if (back != null) {
                back.setOnClickListener { dialog.dismiss() }
                // Full-screen from the first frame: stretching it once shown made the
                // page appear as a small dialog and then jump.
                RawLensUi.pageDialog(dialog)
                dialog.setOnShowListener { dialog.window?.let { ImmersiveMode.apply(it) } }
            }
        }
    }
}
