// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Custom virtual lenses in the Lenses page: a named switcher entry on a real route with
// its own vendor keys, RAW size/format, DNG overrides and PROGRAM profile.

/** Section appended to the lens list. [rebuild] redraws it after an edit. */
internal fun MainActivity.virtualLensSection(
    lenses: List<DiscoveredLens>,
    zoomOf: (DiscoveredLens) -> String,
    isChecked: (String) -> Boolean
): View {
    val store = VirtualLensStore(this)
    val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val bases = lenses.filter { it.kind != LensRouteKind.UNAVAILABLE }

    fun rebuild() {
        section.removeAllViews()
        section.addView(TextView(this).apply {
            text = "Custom lenses\nYour own switcher entries on a real lens, each with its own name and settings"
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(6))
        })
        store.all().forEach { virtual ->
            val base = bases.firstOrNull { it.id == virtual.baseCameraId }
            section.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.control_chip)
                setPadding(dp(16), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent())
                    .apply { bottomMargin = dp(8) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        text = virtual.switcherLabel
                        setTextColor(getColor(R.color.text_primary))
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                    }, LinearLayout.LayoutParams(0, wrapContent(), 1f))
                    addView(TextView(context).apply {
                        text = "on ID ${virtual.baseCameraId}"
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                    })
                })
                addView(TextView(context).apply {
                    text = virtual.description.ifBlank { base?.let { "${zoomOf(it)} · ${it.title}" } ?: "Lens not found on this phone" }
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                })
                val warning = when {
                    base == null -> "Base lens missing: hidden from the switcher"
                    !isChecked(virtual.baseCameraId) -> "Check ID ${virtual.baseCameraId} above and Save to show it in the switcher"
                    else -> null
                }
                warning?.let {
                    addView(TextView(context).apply {
                        text = it
                        setTextColor(getColor(R.color.accent))
                        textSize = 11f
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                isClickable = true
                setOnClickListener { showVirtualLensEditor(virtual, bases, zoomOf) { rebuild() } }
            })
        }
        section.addView(TextView(this).apply {
            text = "+ New custom lens"
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.control_chip)
            setPadding(0, dp(12), 0, dp(12))
            isEnabled = bases.isNotEmpty()
            alpha = if (bases.isEmpty()) 0.5f else 1f
            setOnClickListener { showVirtualLensEditor(null, bases, zoomOf) { rebuild() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrapContent()))
    }
    rebuild()
    return section
}

private fun MainActivity.showVirtualLensEditor(
    existing: VirtualLens?,
    bases: List<DiscoveredLens>,
    zoomOf: (DiscoveredLens) -> String,
    onChanged: () -> Unit
) {
    val store = VirtualLensStore(this)
    var base = bases.firstOrNull { it.id == existing?.baseCameraId } ?: bases.first()
    val id = existing?.id ?: store.newId()
    val key = VirtualLens.settingsKeyOf(id)

    fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_muted))
        textSize = 12f
        setPadding(0, dp(12), 0, dp(4))
    }
    fun field(value: String, hint: String, max: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        filters = arrayOf(InputFilter.LengthFilter(max))
        setTextColor(getColor(R.color.text_primary))
        setHintTextColor(getColor(R.color.text_muted))
    }
    fun link(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.accent))
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(4))
    }

    val baseRow = link("")
    fun refreshBase() { baseRow.text = "${zoomOf(base)} · ${base.title} · ID ${base.id} ▾" }
    refreshBase()
    val zoomField = field(existing?.zoom ?: zoomOf(base), "1×", VirtualLens.MAX_ZOOM)
    val tagField = field(existing?.tag ?: "", "NIGHT, 50 MP, PORTRAIT…", VirtualLens.MAX_TAG)
    val descField = field(existing?.description ?: "", "What this lens is for", VirtualLens.MAX_DESCRIPTION).apply {
        setSingleLine(false)
        maxLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    val sizeRow = link("")
    val formatRow = link("")
    fun refreshStreams() {
        sizeRow.text = "RAW size: ${rawSizePreferenceStore.get(key) ?: "Auto"} ▾"
        sizeRow.visibility = if (base.rawSizes.size > 1) View.VISIBLE else View.GONE
        formatRow.text = "RAW format: ${rawFormatPreferenceStore.get(key) ?: "Auto"} ▾"
        formatRow.visibility = if (base.packedFormats.isNotEmpty()) View.VISIBLE else View.GONE
    }
    refreshStreams()

    fun current() = VirtualLens(
        id, base.id,
        zoomField.text.toString().trim().ifBlank { zoomOf(base) },
        tagField.text.toString().trim().uppercase(),
        descField.text.toString().trim()
    )
    fun persist() {
        store.save(current())
        refreshLensSwitcher()
        onChanged()
    }

    baseRow.setOnClickListener {
        val items = bases.map { "${zoomOf(it)} · ${it.title} · ID ${it.id}" }.toTypedArray()
        RawLensDialogBuilder(this)
            .setTitle("Base lens")
            .setSingleChoiceItems(items, bases.indexOf(base)) { dialog, which ->
                val previous = base
                base = bases[which]
                if (zoomField.text.toString().trim() == zoomOf(previous)) zoomField.setText(zoomOf(base))
                // Sizes and formats belong to the old sensor; fall back to Auto.
                if (previous.id != base.id) {
                    rawSizePreferenceStore.set(key, null)
                    rawFormatPreferenceStore.set(key, null)
                }
                refreshBase()
                refreshStreams()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    sizeRow.setOnClickListener {
        val sizes = base.rawSizes
        val stored = rawSizePreferenceStore.get(key)
        val checked = stored?.let { wanted ->
            sizes.indexOfFirst { RawSizePreferenceStore.normalizeSize(it) == wanted } + 1
        }?.takeIf { it > 0 } ?: 0
        RawLensDialogBuilder(this)
            .setTitle("RAW size")
            .setSingleChoiceItems((listOf("Auto (largest)") + sizes).toTypedArray(), checked) { dialog, which ->
                rawSizePreferenceStore.set(key, if (which == 0) null else sizes[which - 1])
                refreshStreams()
                if (controller.activeSettingsKey() == key) controller.reopenCamera()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    formatRow.setOnClickListener {
        val formats = base.packedFormats
        val stored = rawFormatPreferenceStore.get(key)
        val checked = stored?.let { formats.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        RawLensDialogBuilder(this)
            .setTitle("RAW format")
            .setSingleChoiceItems((listOf("Auto (RAW_SENSOR)") + formats).toTypedArray(), checked) { dialog, which ->
                rawFormatPreferenceStore.set(key, if (which == 0) null else formats[which - 1])
                refreshStreams()
                if (controller.activeSettingsKey() == key) controller.reopenCamera()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    val vendorRow = link("Vendor keys & sensor mode ▸")
    val vendorNote = TextView(this).apply {
        text = "Keys are stored for this custom lens only. Advertised-key lookup reads the lens that is open now."
        setTextColor(getColor(R.color.text_muted))
        textSize = 11f
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(8))
        addView(label("Base lens"))
        addView(baseRow)
        addView(label("Switcher text (big)"))
        addView(zoomField)
        addView(label("Switcher tag (small line)"))
        addView(tagField)
        addView(label("Description"))
        addView(descField)
        addView(label("Per-lens settings"))
        addView(sizeRow)
        addView(formatRow)
        addView(vendorRow)
        addView(vendorNote)
    }
    val builder = RawLensDialogBuilder(this)
        .setTitle(if (existing == null) "New custom lens" else "Edit ${existing.switcherLabel}")
        .setView(android.widget.ScrollView(this).apply { addView(content) })
        .setPositiveButton("Save") { _, _ -> persist() }
        .setNegativeButton("Cancel") { _, _ ->
            // A brand-new lens that was never saved leaves no stream choices behind.
            if (existing == null && store.get(id) == null) {
                rawSizePreferenceStore.set(key, null)
                rawFormatPreferenceStore.set(key, null)
            }
        }
    if (existing != null) {
        builder.setNeutralButton("Delete") { _, _ ->
            RawLensDialogBuilder(this)
                .setTitle("Delete ${existing.switcherLabel}?")
                .setMessage("Its vendor keys, RAW size/format and DNG overrides are removed too.")
                .setPositiveButton("Delete") { _, _ ->
                    val wasActive = controller.activeSettingsKey() == key
                    store.delete(id)
                    rawSizePreferenceStore.set(key, null)
                    rawFormatPreferenceStore.set(key, null)
                    vendorKeyStore.clear(key)
                    dngMetadataOverrideStore.clear(key)
                    if (wasActive) controller.reopenCamera()
                    refreshLensSwitcher()
                    onChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    val dialog = builder.show()
    vendorRow.setOnClickListener {
        // Save first so the editor can name the lens, then edit keys under its own key.
        // Open the keys page on top first, then drop this dialog with no exit animation
        // underneath it: dismissing first made the Lenses page flash and jump between them.
        store.save(current())
        showVendorKeysEditor(key)
        dialog.window?.setWindowAnimations(0)
        dialog.window?.decorView?.post {
            dialog.dismiss()
            refreshLensSwitcher()
            onChanged()
        }
    }
}
