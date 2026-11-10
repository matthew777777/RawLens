// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared building blocks for the settings dialog's tab pages: section
 * headings, descriptions, chip buttons and check boxes, plus the final
 * restyling pass. Moved verbatim from MainActivity.showSettings so each tab
 * can live in its own class; [content] is the page the active tab fills.
 */
internal class SettingsKit(
    val activity: Activity,
    val content: LinearLayout,
    private val styleButton: (Button, Boolean) -> Unit,
) {
    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.text_primary))
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.02f
        tag = "settings-section"
        setTextColor(activity.getColor(R.color.accent))
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    fun sectionDesc(text: String): TextView = TextView(activity).apply {
        this.text = text
        tag = "settings-description"
        setTextColor(activity.getColor(R.color.text_secondary))
        textSize = 13f
        setPadding(dp(4), dp(6), dp(4), dp(12))
    }

    fun settingsButton(label: String, active: Boolean = false): Button =
        Button(activity).apply {
            text = label
            styleButton(this, active)
        }

    fun settingsCheck(label: String, checked: Boolean): SettingsSwitch =
        SettingsSwitch(activity).apply {
            text = label
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = checked
            setPadding(0, dp(2), 0, dp(2))
        }

    /** Keep expert controls available without filling the everyday settings page. */
    fun collapseRange(start: Int, end: Int, title: String) {
        if (end <= start) return
        val views = (start until end).map { content.getChildAt(it) }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        views.forEach { content.removeView(it); body.addView(it) }
        val header = settingsButton("$title  ＋").apply {
            gravity = android.view.Gravity.CENTER
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            setOnClickListener {
                val expand = body.visibility != android.view.View.VISIBLE
                body.visibility = if (expand) android.view.View.VISIBLE else android.view.View.GONE
                text = "$title  ${if (expand) "−" else "＋"}"
                contentDescription = "$title, ${if (expand) "expanded" else "collapsed"}"
            }
            contentDescription = "$title, collapsed"
        }
        content.addView(header, start)
        content.addView(body, start + 1)
    }

    fun collapseSection(title: String, label: String) {
        val start = (0 until content.childCount).firstOrNull {
            val child = content.getChildAt(it)
            child is TextView && child.tag == "settings-section" && child.text.toString().removeSuffix("  ⓘ") == title
        } ?: return
        val end = (start + 1 until content.childCount).firstOrNull {
            content.getChildAt(it).tag == "settings-section"
        } ?: content.childCount
        collapseRange(start, end, label)
    }

    /** Normalize legacy pages into readable sections without changing their listeners. */
    fun polish() {
        val children = (0 until content.childCount).map { content.getChildAt(it) }
        var heading: TextView? = null
        val help = linkedMapOf<TextView, MutableList<CharSequence>>()
        children.forEach { child ->
            if (child is TextView && child.tag == "settings-section") heading = child
            if (child is TextView && child.tag == "settings-description") {
                val title = heading
                if (title != null) {
                    help.getOrPut(title) { mutableListOf() }.add(child.text)
                    child.visibility = android.view.View.GONE
                }
            }
        }
        help.forEach { (title, explanations) ->
            val label = title.text.toString().removeSuffix("  ⓘ")
            title.text = "$label  ⓘ"
            title.minHeight = dp(48)
            title.contentDescription = "$label. Show help"
            title.setOnClickListener {
                RawLensDialogBuilder(activity)
                    .setTitle(label)
                    .setMessage(explanations.joinToString("\n\n"))
                    .setPositiveButton("Got it", null)
                    .show()
            }
        }
        children.forEach { child ->
            when (child) {
                is SettingsSlider -> {
                    val index = content.indexOfChild(child)
                    val label = (if (index > 0) content.getChildAt(index - 1) else null) as? TextView
                    if (label != null) {
                        child.contentDescription = label.text.toString().substringBefore(":")
                        child.setLabelFormatter {
                            val firstLine = label.text.toString().substringBefore('\n')
                            firstLine.substringAfter(": ", firstLine)
                        }
                    }
                }
                is android.widget.CompoundButton -> {
                    child.textSize = 13f
                    child.minHeight = dp(48)
                    child.setTextColor(android.content.res.ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(activity.getColor(R.color.text_muted), activity.getColor(R.color.text_primary))))
                    child.setPadding(dp(4), dp(4), dp(8), dp(4))
                }
                is Button -> {
                    styleButton(child, false)
                    child.gravity = android.view.Gravity.CENTER
                    child.minHeight = dp(48)
                    child.minimumHeight = dp(48)
                    child.minWidth = 0
                    child.minimumWidth = 0
                    child.maxLines = 2
                    child.setPadding(dp(12), dp(6), dp(12), dp(6))
                    child.stateListAnimator = null
                    child.layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(4)
                    }
                }
                is TextView -> if (child.tag != "settings-section") {
                    child.textSize = 13f
                    child.setLineSpacing(dp(2).toFloat(), 1f)
                    child.setPadding(dp(4), dp(4), dp(4), dp(6))

                }
            }
        }
    }
}
