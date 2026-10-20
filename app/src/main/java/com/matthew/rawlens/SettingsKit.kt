// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.widget.Button
import android.widget.CheckBox
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
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(16), 0, dp(4))
    }

    fun sectionDesc(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, 0, 0, dp(4))
    }

    fun settingsButton(label: String, active: Boolean = false): Button =
        Button(activity).apply {
            text = label
            styleButton(this, active)
        }

    fun settingsCheck(label: String, checked: Boolean): CheckBox =
        CheckBox(activity).apply {
            text = label
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = checked
            setPadding(0, dp(2), 0, dp(2))
        }

    /** Re-applies the chip style to every top-level button on the page. */
    fun polish() {
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is Button) styleButton(child, false)
        }
    }
}
