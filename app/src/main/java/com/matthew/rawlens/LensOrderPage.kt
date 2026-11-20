// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings → Lenses → Viewfinder order & names (after rawr's Lens list):
 * move switcher discs up/down and give any lens a short name. Names show at
 * once; a new order applies when the page closes (the camera reopens).
 */
internal fun MainActivity.showLensOrderPage(
    lenses: List<DiscoveredLens>,
    autoLabel: (DiscoveredLens) -> String,
) {
    val store = LensListStore(this)
    val byId = lenses.associateBy { it.id }
    val enabled = selectedLensIds().filter { it in byId }
    // Same order the controller applies: widest first, then the saved order.
    val widest = enabled.sortedWith(compareBy<String> { byId.getValue(it).role == LensRole.FRONT }
        .thenBy { byId.getValue(it).opticalMetric })
    var order = LensListStore.applyOrder(widest, store.order())
    val startOrder = order

    val dialog = android.app.Dialog(this, R.style.Theme_RawLens)
    val selectable = TypedValue().also {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId
    val column = SettingsCardColumn(this)

    fun arrow(label: String, description: String, enabled: Boolean, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = description
            setTextColor(getColor(if (enabled) R.color.text_primary else R.color.text_muted))
            alpha = if (enabled) 1f else 0.35f
            isEnabled = enabled
            setBackgroundResource(selectable)
            setOnClickListener { onTap() }
        }

    lateinit var rebuild: () -> Unit
    fun rename(lens: DiscoveredLens) {
        val auto = autoLabel(lens)
        val input = EditText(this).apply {
            setText(store.name(lens.id).orEmpty())
            hint = auto
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(LensListStore.NAME_MAX_LENGTH))
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()))
        }
        RawLensDialogBuilder(this)
            .setTitle("Name for ${lens.title}")
            .setMessage("Up to ${LensListStore.NAME_MAX_LENGTH} characters, shown on the viewfinder button. " +
                "Leave empty for the automatic “$auto”.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                store.setName(lens.id, input.text.toString())
                rebuild()
                refreshLensSwitcher()
            }
            .setNeutralButton("Automatic") { _, _ ->
                store.setName(lens.id, "")
                rebuild()
                refreshLensSwitcher()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    rebuild = {
        column.removeAllViews()
        column.addView(TextView(this).apply {
            text = "Viewfinder buttons"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.12f
            tag = SettingsCardColumn.SECTION_TAG
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(22), dp(16), dp(8))
        })
        order.forEachIndexed { index, id ->
            val lens = byId.getValue(id)
            val custom = store.name(id)
            val shown = custom ?: autoLabel(lens)
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(16), dp(10), dp(4), dp(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(selectable)
                    isClickable = true
                    contentDescription = "$shown, ${lens.title}. Tap to rename"
                    setOnClickListener { rename(lens) }
                    addView(TextView(context).apply {
                        text = shown
                        gravity = Gravity.CENTER
                        textSize = if (shown.length > 3) 11f else 13f
                        maxLines = 1
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(getColor(R.color.accent))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(getColor(R.color.accent_soft))
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(14) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply {
                            text = lens.title
                            textSize = 15f
                            setTextColor(getColor(R.color.text_primary))
                        })
                        addView(TextView(context).apply {
                            text = "Camera ${lens.id} · " + if (custom != null) "named · tap to rename" else "tap to name"
                            textSize = 13f
                            setTextColor(getColor(R.color.text_secondary))
                            setPadding(0, dp(3), 0, 0)
                        })
                    })
                }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
                addView(arrow("↑", "Move ${lens.title} up", index > 0) {
                    order = LensListStore.move(order, id, -1); rebuild()
                }, LinearLayout.LayoutParams(dp(44), dp(48)))
                addView(arrow("↓", "Move ${lens.title} down", index < order.lastIndex) {
                    order = LensListStore.move(order, id, +1); rebuild()
                }, LinearLayout.LayoutParams(dp(44), dp(48)))
            })
        }
        if (order.isEmpty()) column.addView(TextView(this).apply {
            text = "No lenses enabled yet. Tick lenses on the Lenses page first."
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }
    rebuild()

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(4))
        addView(TextView(context).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(selectable)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = "Order & names"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAccessibilityHeading = true
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(12), 0, dp(4), 0)
        })
    }
    val reset = Button(this).apply {
        text = "Reset to widest first, automatic names"
        RawLensUi.styleButton(this, false)
        setOnClickListener {
            store.reset()
            order = widest
            rebuild()
            refreshLensSwitcher()
        }
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.surface))
        addView(header)
        addView(TextView(context).apply {
            text = "Order of the lens buttons above the shutter, left to right. Tap a lens to give it a short name."
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(28), dp(4), dp(28), 0)
        })
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(column.apply { setPadding(dp(16), 0, dp(16), dp(16)) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()).apply {
            setMargins(dp(20), dp(8), dp(20), dp(20))
        })
    }
    dialog.setOnDismissListener {
        if (order != startOrder) {
            store.setOrder(order)
            controller.reloadLenses()
            setStatus("LENS ORDER SAVED")
        }
    }
    RawLensUi.showFullscreen(dialog, page)
}
