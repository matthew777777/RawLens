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
        // Quiet small-caps label above a card; the accent is kept for values and switches.
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        isAllCaps = true
        letterSpacing = 0.12f
        tag = SettingsCardColumn.SECTION_TAG
        setTextColor(activity.getColor(R.color.text_secondary))
        setPadding(dp(16), dp(22), dp(16), dp(8))
    }

    fun sectionDesc(text: String): TextView = TextView(activity).apply {
        this.text = text
        tag = "settings-description"
        setTextColor(activity.getColor(R.color.text_secondary))
        textSize = 13f
        setPadding(dp(16), dp(6), dp(16), dp(12))
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

    private fun themeResource(attr: Int): Int = android.util.TypedValue().also {
        activity.theme.resolveAttribute(attr, it, true)
    }.resourceId

    /**
     * Navigation row, the one look for "opens another page": title, optional summary
     * beneath, chevron at the end. Used by the page list and by sub-page links.
     * [icon] adds a tinted badge in front, for the page list.
     */
    fun navRow(title: String, summary: String?, icon: Int? = null, onClick: () -> Unit): LinearLayout {
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                this.text = title
                textSize = ROW_TITLE_SP
                setTextColor(activity.getColor(R.color.text_primary))
            })
            if (summary != null) addView(TextView(activity).apply {
                this.text = summary
                textSize = ROW_VALUE_SP
                setTextColor(activity.getColor(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
            minimumHeight = dp(if (summary != null) 72 else 56)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = if (summary != null) "$title. $summary" else "$title, opens a page"
            setOnClickListener { onClick() }
            if (icon != null) addView(android.widget.ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(activity.getColor(R.color.accent_soft))
                }
                setPadding(dp(9), dp(9), dp(9), dp(9))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(text, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                this.text = "›"
                textSize = 20f
                setTextColor(activity.getColor(R.color.text_muted))
                setPadding(dp(12), 0, dp(4), 0)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    /**
     * The one way to choose a value: a list with the current choice marked. Settings
     * rows never cycle to "the next" value on tap. [onPick] runs only for a new choice.
     */
    fun pick(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        RawLensDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) onPick(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** [pick] over typed [values]; [label] may return "Title: value", only the value is listed. */
    fun <T> choose(title: String, values: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) =
        pick(title, values.map { label(it).substringAfter(": ").removeSuffix(" • tap to switch") },
            values.indexOf(current)) { onPick(values[it]) }

    fun <T : Enum<T>> chooseEnum(title: String, current: T, label: (T) -> String, onPick: (T) -> Unit) =
        choose(title, current.declaringJavaClass.enumConstants!!.toList(), current, label, onPick)

    /**
     * [pick] for a value that only has a step-to-next function (shared with the quick
     * tiles): steps until [currentLabel] matches the chosen label, at most once around.
     */
    fun pickByStepping(
        title: String, labels: List<String>, currentLabel: () -> String,
        step: () -> Unit, onDone: () -> Unit
    ) = pick(title, labels, labels.indexOf(currentLabel())) { which ->
        repeat(labels.size) { if (currentLabel() != labels[which]) step() }
        onDone()
    }

    /** Row text as last shown and described, so restyling is idempotent. */
    private val shownRowText = java.util.WeakHashMap<Button, String>()
    private val shownRowDescription = java.util.WeakHashMap<Button, String>()
    private val watchedRows = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Button, Boolean>())
    private var formattingRow = false

    /** "Title: value" becomes the title with the value beneath it, like a system settings row. */
    private fun formatRowText(button: Button) {
        val raw = button.text ?: return
        if (raw.toString() == shownRowText[button]) return
        val plain = raw.toString().removeSuffix(" • tap to switch").removeSuffix("  ›")
        val split = plain.indexOf(": ")
        val title = if (split > 0) plain.substring(0, split) else plain
        val value = if (split > 0) plain.substring(split + 2).trim() else ""
        val shown = android.text.SpannableString(if (value.isEmpty()) title else "$title\n$value")
        if (value.isNotEmpty()) {
            val from = title.length + 1
            shown.setSpan(android.text.style.ForegroundColorSpan(activity.getColor(R.color.accent)),
                from, shown.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            shown.setSpan(android.text.style.RelativeSizeSpan(ROW_VALUE_SP / ROW_TITLE_SP),
                from, shown.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        formattingRow = true
        try {
            shownRowText[button] = shown.toString()
            button.text = shown
            // A page's own description wins; ours only follows the value we display.
            val described = button.contentDescription?.toString()
            if (described == null || described == shownRowDescription[button]) {
                val description = if (value.isEmpty()) title else "$title, $value"
                shownRowDescription[button] = description
                button.contentDescription = description
            }
        } finally {
            formattingRow = false
        }
    }

    /** One flat row look for every tappable setting: actions, value choosers and links. */
    private fun styleRow(button: Button) {
        styleButton(button, false)
        button.setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
        button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        button.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
        button.textSize = ROW_TITLE_SP
        button.typeface = android.graphics.Typeface.DEFAULT
        button.letterSpacing = 0f
        button.minHeight = dp(60)
        button.minimumHeight = dp(60)
        button.minWidth = 0
        button.minimumWidth = 0
        button.maxLines = 4
        button.setLineSpacing(dp(3).toFloat(), 1f)
        button.setPadding(dp(16), dp(10), dp(16), dp(10))
        button.stateListAnimator = null
        button.alpha = if (button.isEnabled) 1f else 0.45f
        formatRowText(button)
        if (watchedRows.add(button)) {
            // Pages rewrite "Title: value" whenever the value changes; keep the row look.
            button.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!formattingRow) formatRowText(button)
                }
            })
        }
    }

    /** Sub-pages of the current page by title; the Settings shell shows one at a time. */
    val subPages = mutableMapOf<String, android.view.View>()
    /** Set by the Settings shell: navigates to a sub-page, like opening a page from the list. */
    var openSubPage: ((title: String, body: android.view.View) -> Unit)? = null
    /** Set by the Settings shell: title of the sub-page on screen, or null. */
    var openSubPageTitle: () -> String? = { null }

    /**
     * Moves the rows [start, end) onto their own sub-page behind one "title ›" row, so
     * expert controls stay available without filling the everyday page. Sections never
     * expand in place: every submenu navigates and back returns, as in the page list.
     */
    fun collapseRange(start: Int, end: Int, title: String) {
        if (end <= start) return
        val views = (start until end).map { content.getChildAt(it) }
        val body = SettingsCardColumn(activity)
        views.forEach { content.removeView(it); body.addView(it) }
        val header = navRow(title, null) { openSubPage?.invoke(title, body) }
        // The top margin sets the link apart as a card of its own.
        content.addView(header, start, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        subPages[title] = body
        // The page was rebuilt while this sub-page was on screen: show the fresh rows.
        if (openSubPageTitle() == title) openSubPage?.invoke(title, body)
    }

    fun collapseSection(title: String, label: String) {
        val start = (0 until content.childCount).firstOrNull {
            val child = content.getChildAt(it)
            child is TextView && child.tag == SettingsCardColumn.SECTION_TAG && child.text.toString().removeSuffix("  ⓘ") == title
        } ?: return
        val end = (start + 1 until content.childCount).firstOrNull {
            content.getChildAt(it).tag == SettingsCardColumn.SECTION_TAG
        } ?: content.childCount
        collapseRange(start, end, label)
    }

    /** Normalize legacy pages into readable sections without changing their listeners. */
    fun polish() {
        val children = (0 until content.childCount).map { content.getChildAt(it) }
        var heading: TextView? = null
        val help = linkedMapOf<TextView, MutableList<CharSequence>>()
        children.forEach { child ->
            if (child is TextView && child.tag == SettingsCardColumn.SECTION_TAG) heading = child
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
            // A drawn lowercase "i" in a ring: the font's ⓘ reads as a capital I.
            title.text = android.text.SpannableString("$label  ⓘ").apply {
                val icon = activity.getDrawable(R.drawable.ic_info_small)!!.apply {
                    val size = (title.textSize * 1.35f).toInt()
                    setBounds(0, 0, size, size)
                }
                setSpan(android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                    length - 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            title.minHeight = dp(48)
            title.gravity = android.view.Gravity.BOTTOM
            title.contentDescription = "$label. Show help"
            title.setOnClickListener {
                RawLensDialogBuilder(activity)
                    .setTitle(label)
                    .setMessage(explanations.joinToString("\n\n"))
                    .setPositiveButton("Got it", null)
                    .show()
            }
        }
        children.forEach { child -> polishControl(content, child) }
    }

    private fun polishControl(parent: LinearLayout, child: android.view.View) {
        when (child) {
            is SettingsSlider -> {
                val index = parent.indexOfChild(child)
                val label = (if (index > 0) parent.getChildAt(index - 1) else null) as? TextView
                if (label != null) {
                    child.contentDescription = label.text.toString().substringBefore(":")
                    child.setLabelFormatter {
                        val firstLine = label.text.toString().substringBefore('\n')
                        firstLine.substringAfter(": ", firstLine)
                    }
                }
                (child.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = dp(8)
                    it.marginEnd = dp(8)
                }
            }
            is android.widget.CompoundButton -> {
                child.textSize = ROW_TITLE_SP
                child.minHeight = dp(60)
                child.setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(activity.getColor(R.color.text_muted), activity.getColor(R.color.text_primary))))
                child.setPadding(dp(16), dp(4), dp(14), dp(4))
            }
            is Button -> {
                styleRow(child)
                child.layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            is TextView -> if (child.tag != SettingsCardColumn.SECTION_TAG) {
                child.textSize = 13f
                child.setLineSpacing(dp(2).toFloat(), 1f)
                child.setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            // Stacked groups (dependent controls under a switch) follow the same rules;
            // side-by-side option chips keep their own segmented look.
            is LinearLayout -> if (child.orientation == LinearLayout.VERTICAL && child.isClickable.not()) {
                (0 until child.childCount).map { child.getChildAt(it) }.forEach { polishControl(child, it) }
            } else if (!child.isClickable && child.paddingLeft == 0 && child.paddingRight == 0) {
                // Chip rows keep their look but sit inside the card's edge.
                child.setPadding(dp(12), child.paddingTop + dp(4), dp(12), child.paddingBottom + dp(8))
            }
        }
    }

    private companion object {
        const val ROW_TITLE_SP = 15f
        const val ROW_VALUE_SP = 13f
    }
}
